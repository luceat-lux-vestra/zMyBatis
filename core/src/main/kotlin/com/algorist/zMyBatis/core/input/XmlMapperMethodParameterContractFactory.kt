package com.algorist.zMyBatis.core.input

import com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlMapperMethodCapture
import com.algorist.zMyBatis.core.source.XmlStatementId

/**
 * Widens the proven XML placeholder contract only when authoritative mapper-method metadata proves
 * the corresponding MyBatis input authority. Explicit @Param aliases are always considered. Generic
 * paramN aliases are considered only when the complete MyBatis primary-name set is statically known.
 * A sole unannotated scalar/temporal parameter may additionally use the stock MyBatis whole-parameter
 * TypeHandler fallback without pretending the XML mapping property is a Java parameter name. A sole
 * unannotated Map<String, V> may use stock MyBatis parameter-object property lookup only when V is
 * one maintained scalar/temporal type.
 */
object XmlMapperMethodParameterContractFactory {
    private const val CALLER_AUTHORITY_PROBLEM = "xml-caller-input-authority-unproven"
    private const val AUTHORITY_MISMATCH_PROBLEM = "xml-mapper-method-authority-mismatch"
    private const val SOURCE_REVISION_CONFLICT_PROBLEM = "xml-mapper-method-source-revision-conflict"
    private const val DUPLICATE_ALIAS_PROBLEM = "xml-duplicate-explicit-param-alias"
    private const val MIXED_KIND_PROBLEM = "xml-mixed-raw-bound-input"
    private const val UNPROVEN_SHAPE_PROBLEM = "xml-unproven-parameter-shape"
    private const val RAW_NON_STRING_PROBLEM = "xml-raw-non-string-parameter"
    private const val FOREACH_COLLECTION_SHAPE_PROBLEM = "xml-foreach-collection-shape-unsupported"
    private const val GENERIC_ALIAS_RULE = "mybatis-3.5.19-param-name-resolver-generic"
    private const val COLLECTION_SHORTCUT_RULE =
        "mybatis-3.5.19-param-name-resolver-wrap-to-map-if-collection"
    private const val PARAMETER_OBJECT_FALLBACK_RULE =
        "mybatis-3.5.19-default-parameter-handler-type-handler-fallback"
    private const val PARAMETER_OBJECT_PROPERTY_RULE =
        "mybatis-3.5.19-default-parameter-handler-meta-object-property"
    private const val NAMED_MAP_PROPERTY_RULE =
        "mybatis-3.5.19-default-parameter-handler-named-map-property"
    private val simpleIdentifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val PARAMETER_OBJECT_TYPE_HANDLER_TYPES = setOf(
        "boolean",
        "byte",
        "short",
        "int",
        "long",
        "float",
        "double",
        "java.lang.Boolean",
        "java.lang.Byte",
        "java.lang.Short",
        "java.lang.Integer",
        "java.lang.Long",
        "java.lang.Float",
        "java.lang.Double",
        "java.lang.String",
        "java.math.BigInteger",
        "java.math.BigDecimal",
        "java.time.Instant",
        "java.time.LocalDate",
        "java.time.LocalDateTime",
        "java.time.LocalTime",
    )
    private val PARAMETER_OBJECT_MAP_VALUE_TYPES = setOf(
        "java.lang.Boolean",
        "java.lang.Byte",
        "java.lang.Short",
        "java.lang.Integer",
        "java.lang.Long",
        "java.lang.Float",
        "java.lang.Double",
        "java.lang.String",
        "java.math.BigInteger",
        "java.math.BigDecimal",
        "java.time.Instant",
        "java.time.LocalDate",
        "java.time.LocalDateTime",
        "java.time.LocalTime",
    )

    fun build(
        graph: StatementSourceGraph,
        mapperMethod: XmlMapperMethodCapture,
    ): ParameterContract {
        val baseline = XmlStatementParameterContractFactory.build(graph)
        val authorityProblems = baseline.blockingProblems.filter { it.code == CALLER_AUTHORITY_PROBLEM }
        if (authorityProblems.isEmpty()) {
            return baseline
        }

        val statementId = baseline.statementId as XmlStatementId
        val mergedRevisions = mergeSourceRevisions(
            baseline.sourceRevisions,
            mapperMethod,
        ) ?: return blockedFromBaseline(
            baseline = baseline,
            code = SOURCE_REVISION_CONFLICT_PROBLEM,
        )

        if (mapperMethod.statementId != statementId) {
            return ParameterContract(
                statementId = statementId,
                requirements = emptyList(),
                aliases = emptyList(),
                internalBindings = baseline.internalBindings,
                blockingProblems = baseline.blockingProblems
                    .filterNot { it.code == CALLER_AUTHORITY_PROBLEM } +
                    InputContractProblem(
                        kind = InputContractProblemKind.UNKNOWN,
                        code = AUTHORITY_MISMATCH_PROBLEM,
                        requirementId = null,
                        provenance = null,
                    ),
                sourceRevisions = mergedRevisions,
            )
        }

        val mapperSource = SourceEvidence(
            sourceFileId = mapperMethod.mapperSource.fileId,
            sourceRevision = mapperMethod.mapperSource.revision,
            sourceRange = mapperMethod.methodSourceRange,
        )
        val parametersByExplicitAlias = mapperMethod.parameters
            .mapNotNull { parameter -> parameter.myBatisParamAlias?.let { alias -> alias to parameter } }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        val parametersByGenericAlias = genericAliases(mapperMethod.parameters, parametersByExplicitAlias)
        val parametersByCollectionShortcutAlias = collectionShortcutAliases(mapperMethod.parameters)
        val parameterObjectFallback = singleParameterObjectFallback(mapperMethod.parameters)
        val parameterObjectProperty = singleMapParameterObjectProperty(mapperMethod.parameters)

        val problems = baseline.blockingProblems
            .filterNot { it.code == CALLER_AUTHORITY_PROBLEM }
            .toMutableList()
        val resolvedByParameter = linkedMapOf<Int, MutableList<ResolvedUse>>()

        authorityProblems.forEach { authorityProblem ->
            val authorityEvidence = authorityProblem.provenance?.evidence.orEmpty()
            val placeholders = authorityEvidence.filterIsInstance<InputEvidence.Placeholder>()
            val foreachCollections = authorityEvidence.filterIsInstance<InputEvidence.ForeachCollection>()
            val mappingProperty = (
                placeholders.map { it.expression } +
                    foreachCollections.map { it.expression }
                ).distinct().singleOrNull()
                ?: run {
                    problems += authorityProblem
                    return@forEach
                }
            val kinds = placeholders.mapTo(linkedSetOf()) { it.kind }.apply {
                if (foreachCollections.isNotEmpty()) add(InputKind.BOUND)
            }
            val kind = kinds.singleOrNull()
                ?: run {
                    problems += authorityProblem
                    return@forEach
                }
            val namedMapPath = parseNamedMapProperty(mappingProperty)
            val authorityRoot = namedMapPath?.alias ?: mappingProperty

            val explicitCandidates = parametersByExplicitAlias[authorityRoot].orEmpty()
            when {
                explicitCandidates.size > 1 -> {
                    val evidence = buildList {
                        explicitCandidates.forEach { parameter ->
                            addAll(mapperEvidence(parameter, mapperSource))
                        }
                        addAll(placeholders)
                        addAll(foreachCollections)
                    }
                    problems += InputContractProblem(
                        kind = InputContractProblemKind.AMBIGUOUS,
                        code = DUPLICATE_ALIAS_PROBLEM,
                        requirementId = null,
                        provenance = InputProvenance(evidence),
                    )
                }
                explicitCandidates.size == 1 -> {
                    val parameter = explicitCandidates.single()
                    if (namedMapPath != null) {
                        if (kind != InputKind.BOUND || !isSupportedStringScalarMap(parameter)) {
                            problems += authorityProblem
                        } else {
                            resolvedByParameter.getOrPut(parameter.index) { mutableListOf() } += ResolvedUse(
                                parameter = parameter,
                                root = authorityRoot,
                                kind = kind,
                                aliasKind = InputAliasKind.EXPLICIT_PARAM,
                                placeholders = placeholders,
                                foreachCollections = foreachCollections,
                                generatedAlias = null,
                                namedMapProperty = InputEvidence.NamedMapProperty(
                                    parameterIndex = parameter.index,
                                    alias = authorityRoot,
                                    mappingProperty = mappingProperty,
                                    key = namedMapPath.key,
                                    ruleId = NAMED_MAP_PROPERTY_RULE,
                                ),
                            )
                        }
                    } else {
                        resolvedByParameter.getOrPut(parameter.index) { mutableListOf() } += ResolvedUse(
                            parameter = parameter,
                            root = authorityRoot,
                            kind = kind,
                            aliasKind = InputAliasKind.EXPLICIT_PARAM,
                            placeholders = placeholders,
                            generatedAlias = null,
                        )
                    }
                }
                parametersByGenericAlias[authorityRoot] != null -> {
                    val parameter = parametersByGenericAlias.getValue(authorityRoot)
                    if (namedMapPath != null && (kind != InputKind.BOUND || !isSupportedStringScalarMap(parameter))) {
                        problems += authorityProblem
                    } else {
                        resolvedByParameter.getOrPut(parameter.index) { mutableListOf() } += ResolvedUse(
                            parameter = parameter,
                            root = authorityRoot,
                            kind = kind,
                            aliasKind = InputAliasKind.GENERIC_PARAM,
                            placeholders = placeholders,
                            foreachCollections = foreachCollections,
                            generatedAlias = InputEvidence.GeneratedAlias(
                                parameterIndex = parameter.index,
                                alias = authorityRoot,
                                ruleId = GENERIC_ALIAS_RULE,
                            ),
                            namedMapProperty = namedMapPath?.let { path ->
                                InputEvidence.NamedMapProperty(
                                    parameterIndex = parameter.index,
                                    alias = authorityRoot,
                                    mappingProperty = mappingProperty,
                                    key = path.key,
                                    ruleId = NAMED_MAP_PROPERTY_RULE,
                                )
                            },
                        )
                    }
                }
                parametersByCollectionShortcutAlias[mappingProperty] != null -> {
                    val shortcut = parametersByCollectionShortcutAlias.getValue(mappingProperty)
                    resolvedByParameter.getOrPut(shortcut.parameter.index) { mutableListOf() } += ResolvedUse(
                        parameter = shortcut.parameter,
                        root = mappingProperty,
                        kind = kind,
                        aliasKind = shortcut.aliasKind,
                        placeholders = placeholders,
                        generatedAlias = InputEvidence.GeneratedAlias(
                            parameterIndex = shortcut.parameter.index,
                            alias = mappingProperty,
                            ruleId = COLLECTION_SHORTCUT_RULE,
                        ),
                    )
                }
                foreachCollections.isEmpty() &&
                    parameterObjectFallback != null &&
                    kind == InputKind.BOUND &&
                    isSimpleMappingProperty(mappingProperty) -> {
                    resolvedByParameter.getOrPut(parameterObjectFallback.index) { mutableListOf() } += ResolvedUse(
                        parameter = parameterObjectFallback,
                        root = mappingProperty,
                        kind = kind,
                        aliasKind = InputAliasKind.PARAMETER_OBJECT,
                        placeholders = placeholders,
                        generatedAlias = null,
                        parameterObjectFallback = InputEvidence.ParameterObjectFallback(
                            parameterIndex = parameterObjectFallback.index,
                            mappingProperty = mappingProperty,
                            ruleId = PARAMETER_OBJECT_FALLBACK_RULE,
                        ),
                    )
                }
                foreachCollections.isEmpty() &&
                    parameterObjectProperty != null &&
                    kind == InputKind.BOUND &&
                    isSimpleMappingProperty(mappingProperty) -> {
                    resolvedByParameter.getOrPut(parameterObjectProperty.index) { mutableListOf() } += ResolvedUse(
                        parameter = parameterObjectProperty,
                        root = mappingProperty,
                        kind = kind,
                        aliasKind = InputAliasKind.PARAMETER_OBJECT,
                        placeholders = placeholders,
                        generatedAlias = null,
                        parameterObjectProperty = InputEvidence.ParameterObjectProperty(
                            parameterIndex = parameterObjectProperty.index,
                            mappingProperty = mappingProperty,
                            ruleId = PARAMETER_OBJECT_PROPERTY_RULE,
                        ),
                    )
                }
                else -> problems += authorityProblem
            }
        }

        val requirements = mutableListOf<InputRequirement>()
        val aliases = mutableListOf<InputAlias>()

        mapperMethod.parameters.forEach { parameter ->
            val uses = resolvedByParameter[parameter.index].orEmpty()
            if (uses.isEmpty()) return@forEach

            val kinds = uses.mapTo(linkedSetOf()) { it.kind }
            val mapperEvidence = mapperEvidence(parameter, mapperSource)
            val generatedEvidence = uses.mapNotNull { it.generatedAlias }.distinct()
            val parameterObjectFallbackEvidence = uses.mapNotNull { it.parameterObjectFallback }.distinct()
            val parameterObjectPropertyEvidence = uses.mapNotNull { it.parameterObjectProperty }.distinct()
            val namedMapPropertyEvidence = uses.mapNotNull { it.namedMapProperty }.distinct()
            val placeholderEvidence = uses.flatMap { it.placeholders }
            val foreachCollectionEvidence = uses.flatMap { it.foreachCollections }
            val provenance = InputProvenance(
                mapperEvidence +
                    generatedEvidence +
                    parameterObjectFallbackEvidence +
                    parameterObjectPropertyEvidence +
                    namedMapPropertyEvidence +
                    placeholderEvidence +
                    foreachCollectionEvidence,
            )

            if (kinds.size != 1) {
                problems += InputContractProblem(
                    kind = InputContractProblemKind.AMBIGUOUS,
                    code = MIXED_KIND_PROBLEM,
                    requirementId = null,
                    provenance = provenance,
                )
                return@forEach
            }

            val kind = kinds.single()
            val requirementId = InputRequirementId("xml-java-param:${parameter.index}")
            val expectedType = JavaParameterTypeContract.expectedType(parameter.typeIdentity, kind)
            requirements += InputRequirement(
                id = requirementId,
                kind = kind,
                expectedType = expectedType,
                requiredness = InputRequiredness.REQUIRED,
                provenance = provenance,
            )

            uses
                .distinctBy { it.root }
                .forEach { use ->
                    val aliasEvidence = buildList {
                        addAll(mapperEvidence)
                        use.generatedAlias?.let(::add)
                        use.parameterObjectFallback?.let(::add)
                        use.parameterObjectProperty?.let(::add)
                    }
                    aliases += InputAlias(
                        name = use.root,
                        requirementId = requirementId,
                        kind = use.aliasKind,
                        provenance = InputProvenance(aliasEvidence),
                    )
                }

            if (
                kind == InputKind.RAW_INTERPOLATION &&
                !JavaParameterTypeContract.isString(parameter.typeIdentity)
            ) {
                problems += InputContractProblem(
                    kind = InputContractProblemKind.UNSUPPORTED,
                    code = RAW_NON_STRING_PROBLEM,
                    requirementId = requirementId,
                    provenance = provenance,
                )
            }
            if (expectedType.shape == InputShape.UNKNOWN) {
                problems += InputContractProblem(
                    kind = InputContractProblemKind.UNSUPPORTED,
                    code = UNPROVEN_SHAPE_PROBLEM,
                    requirementId = requirementId,
                    provenance = provenance,
                )
            }
            if (
                foreachCollectionEvidence.isNotEmpty() &&
                expectedType.shape !in setOf(InputShape.LIST, InputShape.ARRAY, InputShape.MAP)
            ) {
                problems += InputContractProblem(
                    kind = InputContractProblemKind.UNSUPPORTED,
                    code = FOREACH_COLLECTION_SHAPE_PROBLEM,
                    requirementId = requirementId,
                    provenance = provenance,
                )
            }
        }

        return ParameterContract(
            statementId = statementId,
            requirements = requirements,
            aliases = aliases,
            internalBindings = baseline.internalBindings,
            blockingProblems = problems,
            sourceRevisions = mergedRevisions,
        )
    }

    private fun genericAliases(
        parameters: List<JavaMethodParameterMetadata>,
        parametersByExplicitAlias: Map<String, List<JavaMethodParameterMetadata>>,
    ): Map<String, JavaMethodParameterMetadata> {
        if (parameters.isEmpty()) return emptyMap()
        if (parameters.any { it.myBatisParamAlias == null }) return emptyMap()
        if (parametersByExplicitAlias.values.any { it.size != 1 }) return emptyMap()

        val primaryNames = parameters.mapTo(linkedSetOf()) { requireNotNull(it.myBatisParamAlias) }
        return buildMap {
            parameters.forEachIndexed { ordinal, parameter ->
                val genericName = "param${ordinal + 1}"
                if (genericName !in primaryNames) {
                    put(genericName, parameter)
                }
            }
        }
    }

    private fun singleParameterObjectFallback(
        parameters: List<JavaMethodParameterMetadata>,
    ): JavaMethodParameterMetadata? {
        if (parameters.size != 1) return null
        val parameter = parameters.single()
        if (parameter.myBatisParamAlias != null) return null
        val expected = JavaParameterTypeContract.expectedType(parameter.typeIdentity, InputKind.BOUND)
        val rawType = parameter.typeIdentity.value.substringBefore('<').trim()
        return parameter.takeIf {
            (expected.shape == InputShape.SCALAR || expected.shape == InputShape.TEMPORAL) &&
                rawType in PARAMETER_OBJECT_TYPE_HANDLER_TYPES
        }
    }

    private fun singleMapParameterObjectProperty(
        parameters: List<JavaMethodParameterMetadata>,
    ): JavaMethodParameterMetadata? {
        if (parameters.size != 1) return null
        val parameter = parameters.single()
        if (parameter.myBatisParamAlias != null) return null
        return parameter.takeIf(::isSupportedStringScalarMap)
    }

    private fun isSupportedStringScalarMap(parameter: JavaMethodParameterMetadata): Boolean {
        val canonical = parameter.typeIdentity.value.trim()
        if (!canonical.startsWith("java.util.Map<") || !canonical.endsWith(">")) return false
        val arguments = canonical
            .substringAfter('<')
            .dropLast(1)
            .split(',')
            .map(String::trim)
        if (arguments.size != 2 || arguments[0] != "java.lang.String") return false
        val valueType = arguments[1]
        return '<' !in valueType &&
            '>' !in valueType &&
            valueType in PARAMETER_OBJECT_MAP_VALUE_TYPES
    }

    private fun parseNamedMapProperty(property: String): NamedMapPropertyPath? {
        val parts = property.split('.')
        if (parts.size != 2 || parts.any { !simpleIdentifier.matches(it) }) return null
        return NamedMapPropertyPath(parts[0], parts[1])
    }

    private fun isSimpleMappingProperty(property: String): Boolean =
        property.isNotBlank() &&
            property.none { it == '.' || it == '[' || it == ']' }

    private fun collectionShortcutAliases(
        parameters: List<JavaMethodParameterMetadata>,
    ): Map<String, CollectionShortcutAlias> {
        if (parameters.size != 1) return emptyMap()
        val parameter = parameters.single()
        if (parameter.myBatisParamAlias != null) return emptyMap()

        val canonical = parameter.typeIdentity.value.trim()
        if (canonical.endsWith("[]")) {
            return mapOf(
                "array" to CollectionShortcutAlias(parameter, InputAliasKind.ARRAY),
            )
        }

        return when (canonical.substringBefore('<').trim()) {
            "java.util.List" -> linkedMapOf(
                "collection" to CollectionShortcutAlias(parameter, InputAliasKind.COLLECTION),
                "list" to CollectionShortcutAlias(parameter, InputAliasKind.LIST),
            )
            "java.util.Collection" -> mapOf(
                "collection" to CollectionShortcutAlias(parameter, InputAliasKind.COLLECTION),
            )
            else -> emptyMap()
        }
    }

    private fun mapperEvidence(
        parameter: JavaMethodParameterMetadata,
        source: SourceEvidence,
    ): List<InputEvidence> = buildList {
        add(
            InputEvidence.MapperMethodParameter(
                index = parameter.index,
                sourceName = parameter.sourceName,
                typeIdentity = parameter.typeIdentity,
                source = source,
            ),
        )
        parameter.myBatisParamAlias?.let { alias ->
            add(
                InputEvidence.ExplicitParamAlias(
                    parameterIndex = parameter.index,
                    alias = alias,
                    source = source,
                ),
            )
        }
    }

    private fun mergeSourceRevisions(
        baseline: Map<SourceFileId, SourceRevision>,
        mapperMethod: XmlMapperMethodCapture,
    ): Map<SourceFileId, SourceRevision>? {
        val existing = baseline[mapperMethod.mapperSource.fileId]
        if (existing != null && existing != mapperMethod.mapperSource.revision) {
            return null
        }
        return baseline + (mapperMethod.mapperSource.fileId to mapperMethod.mapperSource.revision)
    }

    private fun blockedFromBaseline(
        baseline: ParameterContract,
        code: String,
    ): ParameterContract = ParameterContract(
        statementId = baseline.statementId,
        requirements = emptyList(),
        aliases = emptyList(),
        internalBindings = baseline.internalBindings,
        blockingProblems = baseline.blockingProblems
            .filterNot { it.code == CALLER_AUTHORITY_PROBLEM } +
            InputContractProblem(
                kind = InputContractProblemKind.UNKNOWN,
                code = code,
                requirementId = null,
                provenance = null,
            ),
        sourceRevisions = baseline.sourceRevisions,
    )

    private data class CollectionShortcutAlias(
        val parameter: JavaMethodParameterMetadata,
        val aliasKind: InputAliasKind,
    )

    private data class NamedMapPropertyPath(
        val alias: String,
        val key: String,
    )

    private data class ResolvedUse(
        val parameter: JavaMethodParameterMetadata,
        val root: String,
        val kind: InputKind,
        val aliasKind: InputAliasKind,
        val placeholders: List<InputEvidence.Placeholder>,
        val foreachCollections: List<InputEvidence.ForeachCollection> = emptyList(),
        val generatedAlias: InputEvidence.GeneratedAlias?,
        val parameterObjectFallback: InputEvidence.ParameterObjectFallback? = null,
        val parameterObjectProperty: InputEvidence.ParameterObjectProperty? = null,
        val namedMapProperty: InputEvidence.NamedMapProperty? = null,
    )
}
