package com.algorist.zMyBatis.core.input

import com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlMapperMethodCapture
import com.algorist.zMyBatis.core.source.XmlStatementId

/**
 * Widens the proven XML placeholder contract only when authoritative mapper-method metadata proves
 * the corresponding MyBatis name. Explicit @Param aliases are always considered. Generic paramN
 * aliases are considered only when the complete MyBatis primary-name set is statically known.
 */
object XmlMapperMethodParameterContractFactory {
    private const val CALLER_AUTHORITY_PROBLEM = "xml-caller-input-authority-unproven"
    private const val AUTHORITY_MISMATCH_PROBLEM = "xml-mapper-method-authority-mismatch"
    private const val SOURCE_REVISION_CONFLICT_PROBLEM = "xml-mapper-method-source-revision-conflict"
    private const val DUPLICATE_ALIAS_PROBLEM = "xml-duplicate-explicit-param-alias"
    private const val MIXED_KIND_PROBLEM = "xml-mixed-raw-bound-input"
    private const val UNPROVEN_SHAPE_PROBLEM = "xml-unproven-parameter-shape"
    private const val RAW_NON_STRING_PROBLEM = "xml-raw-non-string-parameter"
    private const val GENERIC_ALIAS_RULE = "mybatis-3.5.19-param-name-resolver-generic"

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

        val problems = baseline.blockingProblems
            .filterNot { it.code == CALLER_AUTHORITY_PROBLEM }
            .toMutableList()
        val resolvedByParameter = linkedMapOf<Int, MutableList<ResolvedUse>>()

        authorityProblems.forEach { authorityProblem ->
            val placeholders = authorityProblem.provenance
                ?.evidence
                ?.filterIsInstance<InputEvidence.Placeholder>()
                .orEmpty()
            val root = placeholders.map { it.expression }.distinct().singleOrNull()
                ?: run {
                    problems += authorityProblem
                    return@forEach
                }
            val kind = placeholders.map { it.kind }.distinct().singleOrNull()
                ?: run {
                    problems += authorityProblem
                    return@forEach
                }

            val explicitCandidates = parametersByExplicitAlias[root].orEmpty()
            when {
                explicitCandidates.size > 1 -> {
                    val evidence = buildList {
                        explicitCandidates.forEach { parameter ->
                            addAll(mapperEvidence(parameter, mapperSource))
                        }
                        addAll(placeholders)
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
                    resolvedByParameter.getOrPut(parameter.index) { mutableListOf() } += ResolvedUse(
                        parameter = parameter,
                        root = root,
                        kind = kind,
                        aliasKind = InputAliasKind.EXPLICIT_PARAM,
                        placeholders = placeholders,
                        generatedAlias = null,
                    )
                }
                parametersByGenericAlias[root] != null -> {
                    val parameter = parametersByGenericAlias.getValue(root)
                    resolvedByParameter.getOrPut(parameter.index) { mutableListOf() } += ResolvedUse(
                        parameter = parameter,
                        root = root,
                        kind = kind,
                        aliasKind = InputAliasKind.GENERIC_PARAM,
                        placeholders = placeholders,
                        generatedAlias = InputEvidence.GeneratedAlias(
                            parameterIndex = parameter.index,
                            alias = root,
                            ruleId = GENERIC_ALIAS_RULE,
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
            val placeholderEvidence = uses.flatMap { it.placeholders }
            val provenance = InputProvenance(mapperEvidence + generatedEvidence + placeholderEvidence)

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

    private fun mapperEvidence(
        parameter: JavaMethodParameterMetadata,
        source: SourceEvidence,
    ): List<InputEvidence> = listOf(
        InputEvidence.MapperMethodParameter(
            index = parameter.index,
            sourceName = parameter.sourceName,
            typeIdentity = parameter.typeIdentity,
            source = source,
        ),
        InputEvidence.ExplicitParamAlias(
            parameterIndex = parameter.index,
            alias = requireNotNull(parameter.myBatisParamAlias),
            source = source,
        ),
    )

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

    private data class ResolvedUse(
        val parameter: JavaMethodParameterMetadata,
        val root: String,
        val kind: InputKind,
        val aliasKind: InputAliasKind,
        val placeholders: List<InputEvidence.Placeholder>,
        val generatedAlias: InputEvidence.GeneratedAlias?,
    )
}
