package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExpectedInputType
import com.algorist.zMyBatis.core.input.InputAlias
import com.algorist.zMyBatis.core.input.InputAliasKind
import com.algorist.zMyBatis.core.input.InputContractProblem
import com.algorist.zMyBatis.core.input.InputContractProblemKind
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputNullability
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequiredness
import com.algorist.zMyBatis.core.input.InputRequirement
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputShape
import com.algorist.zMyBatis.core.input.InputUsageKind
import com.algorist.zMyBatis.core.input.JavaParameterTypeContract
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementCapture
import com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.StatementId
import com.algorist.zMyBatis.core.source.XmlStatementId
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.nio.charset.StandardCharsets
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * P2 caller-authority contract.
 *
 * Mapper method metadata owns caller parameters. XML/annotation syntax contributes usage/risk
 * evidence only; it never decides whether standard MyBatis dynamic SQL is semantically admitted.
 */
object MyBatisInvocationContractFactory {
    private const val XML_PARAMETER_OBJECT_ID = "xml-parameter-object"
    private const val CONTRACT_PROJECTION_FAILED = "invocation-contract-projection-failed"
    private const val SPECIAL_ROW_BOUNDS = "org.apache.ibatis.session.RowBounds"
    private const val SPECIAL_RESULT_HANDLER = "org.apache.ibatis.session.ResultHandler"
    private const val GENERIC_ALIAS_RULE = "mybatis-param-name-resolver-generic"
    private const val COLLECTION_ALIAS_RULE = "mybatis-param-name-resolver-wrap-to-map-if-collection"
    private val JAVA_PRIMITIVE_TYPES = setOf(
        "boolean",
        "byte",
        "short",
        "int",
        "long",
        "float",
        "double",
        "char",
    )
    private val rawInterpolation = Regex("""\$\{([^}]*)}""")
    private val simpleRoot = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun build(source: PreparationSource): ParameterContract = when (source) {
        is PreparationSource.JavaAnnotation -> buildJava(source.capture)
        is XmlMapperPreparationSource -> buildXml(source)
    }

    private fun buildJava(capture: JavaAnnotationStatementCapture): ParameterContract {
        val statement = capture.sourceGraph.rootStatement
        val sourceRevisions = capture.sourceGraph.sourceSnapshots.associate { it.fileId to it.revision }
        val rootRevision = sourceRevisions.getValue(statement.id.sourceFileId)
        val statementSource = SourceEvidence(
            statement.id.sourceFileId,
            rootRevision,
            statement.sourceRange,
        )
        val rawUses = capture.sqlSegments.flatMap { segment ->
            runtimeRawUses(segment, statementSource)
        }
        return methodContract(
            statementId = statement.id,
            sourceRevisions = sourceRevisions,
            parameters = capture.parameters,
            parameterSource = statementSource,
            rawUses = rawUses,
        )
    }

    private fun buildXml(source: XmlMapperPreparationSource): ParameterContract {
        val statementId = source.sourceGraph.rootStatement.id as XmlStatementId
        val projection = when (
            val projected = XmlMapperPreparationProjection.project(
                source = source,
                statementId = statementId,
                effectiveDatabaseId = source.effectiveDatabaseId,
            )
        ) {
            is XmlMapperPreparationProjection.Result.Ready -> projected
            is XmlMapperPreparationProjection.Result.Failed ->
                return blocked(
                    statementId,
                    source.sourceRevisions,
                    projected.failure.code.ifBlank { CONTRACT_PROJECTION_FAILED },
                )
        }
        val scan = XmlInvocationUsageScanner.scan(projection)
        val mapperMethod = source.mapperMethod
        if (mapperMethod != null) {
            return methodContract(
                statementId = statementId,
                sourceRevisions = source.sourceRevisions,
                parameters = mapperMethod.parameters,
                parameterSource = SourceEvidence(
                    mapperMethod.mapperSource.fileId,
                    mapperMethod.mapperSource.revision,
                    mapperMethod.methodSourceRange,
                ),
                rawUses = scan.rawUses,
            )
        }

        if (!scan.requiresCallerObject) {
            return ParameterContract(
                statementId = statementId,
                requirements = emptyList(),
                aliases = emptyList(),
                internalBindings = emptyList(),
                blockingProblems = emptyList(),
                sourceRevisions = source.sourceRevisions,
            )
        }

        val rootSnapshot = source.sourceGraph.sourceSnapshots.single {
            it.fileId == statementId.sourceFileId
        }
        val rootEvidence = SourceEvidence(
            rootSnapshot.fileId,
            rootSnapshot.revision,
            source.sourceGraph.rootStatement.sourceRange,
        )
        val provenance = InputProvenance(
            buildList {
                add(InputEvidence.XmlParameterObject(rootEvidence))
                addAll(scan.rawUses.map { use ->
                    InputEvidence.Placeholder(
                        kind = InputKind.RAW_INTERPOLATION,
                        expression = use.expression,
                        source = use.source,
                    )
                })
            },
        )
        val usages = buildSet {
            add(InputUsageKind.BOUND_PLACEHOLDER)
            if (scan.rawUses.isNotEmpty()) add(InputUsageKind.RAW_INTERPOLATION)
        }
        return ParameterContract(
            statementId = statementId,
            requirements = listOf(
                InputRequirement(
                    id = InputRequirementId(XML_PARAMETER_OBJECT_ID),
                    kind = InputKind.BOUND,
                    expectedType = ExpectedInputType(
                        shape = InputShape.OBJECT,
                        nullability = InputNullability.UNKNOWN,
                    ),
                    requiredness = InputRequiredness.REQUIRED,
                    provenance = provenance,
                    usageKinds = usages,
                ),
            ),
            aliases = emptyList(),
            internalBindings = emptyList(),
            blockingProblems = emptyList(),
            sourceRevisions = source.sourceRevisions,
        )
    }

    private fun methodContract(
        statementId: StatementId,
        sourceRevisions: Map<SourceFileId, SourceRevision>,
        parameters: List<JavaMethodParameterMetadata>,
        parameterSource: SourceEvidence,
        rawUses: List<RawUse>,
    ): ParameterContract {
        val active = parameters.filterNot(::isSpecialParameter)
        val requirementByParameter = linkedMapOf<Int, InputRequirementId>()
        val rawUsesByParameter = rawUsesByParameter(active, rawUses)

        val requirements = active.map { parameter ->
            val id = InputRequirementId("method-param:${parameter.index}")
            requirementByParameter[parameter.index] = id
            val rawEvidence = rawUsesByParameter[parameter.index].orEmpty()
            InputRequirement(
                id = id,
                kind = InputKind.BOUND,
                expectedType = mapperMethodExpectedType(parameter),
                requiredness = InputRequiredness.REQUIRED,
                provenance = InputProvenance(
                    buildList {
                        add(mapperParameterEvidence(parameter, parameterSource))
                        parameter.myBatisParamAlias?.let { alias ->
                            add(InputEvidence.ExplicitParamAlias(parameter.index, alias, parameterSource))
                        }
                        rawEvidence.forEach { use ->
                            add(
                                InputEvidence.Placeholder(
                                    InputKind.RAW_INTERPOLATION,
                                    use.expression,
                                    use.source,
                                ),
                            )
                        }
                    },
                ),
                usageKinds = buildSet {
                    add(InputUsageKind.BOUND_PLACEHOLDER)
                    if (rawEvidence.isNotEmpty()) add(InputUsageKind.RAW_INTERPOLATION)
                },
            )
        }

        return ParameterContract(
            statementId = statementId,
            requirements = requirements,
            aliases = methodAliases(active, requirementByParameter, parameterSource),
            internalBindings = emptyList(),
            blockingProblems = emptyList(),
            sourceRevisions = sourceRevisions,
        )
    }

    private fun mapperMethodExpectedType(
        parameter: JavaMethodParameterMetadata,
    ): ExpectedInputType {
        val expected = JavaParameterTypeContract.expectedType(parameter.typeIdentity, InputKind.BOUND)
        val rawType = parameter.typeIdentity.value.substringBefore('<').trim()
        return if (rawType in JAVA_PRIMITIVE_TYPES) {
            expected
        } else {
            expected.copy(nullability = InputNullability.NULLABLE)
        }
    }

    private fun methodAliases(
        parameters: List<JavaMethodParameterMetadata>,
        requirementByParameter: Map<Int, InputRequirementId>,
        source: SourceEvidence,
    ): List<InputAlias> {
        if (parameters.isEmpty()) return emptyList()
        val hasExplicitParam = parameters.any { it.myBatisParamAlias != null }

        if (parameters.size == 1 && !hasExplicitParam) {
            val parameter = parameters.single()
            val requirementId = requirementByParameter.getValue(parameter.index)
            val rawType = parameter.typeIdentity.value.substringBefore('<').trim()
            val aliases = linkedMapOf<String, InputAliasKind>()
            when {
                parameter.typeIdentity.value.trim().endsWith("[]") ->
                    aliases["array"] = InputAliasKind.ARRAY
                rawType == "java.util.List" -> {
                    aliases["collection"] = InputAliasKind.COLLECTION
                    aliases["list"] = InputAliasKind.LIST
                }
                rawType == "java.util.Collection" ->
                    aliases["collection"] = InputAliasKind.COLLECTION
            }
            if (aliases.isNotEmpty()) {
                parameter.sourceName?.let { aliases.putIfAbsent(it, InputAliasKind.SOURCE_PARAMETER_NAME) }
            }
            return aliases.map { (name, kind) ->
                InputAlias(
                    name = name,
                    requirementId = requirementId,
                    kind = kind,
                    provenance = aliasProvenance(parameter, source, name, kind),
                )
            }
        }

        val primaryNames = parameters.associateWith { parameter ->
            parameter.myBatisParamAlias ?: parameter.sourceName ?: parameter.index.toString()
        }
        val reservedPrimary = primaryNames.values.toSet()
        return buildList {
            parameters.forEachIndexed { ordinal, parameter ->
                val requirementId = requirementByParameter.getValue(parameter.index)
                val primary = primaryNames.getValue(parameter)
                add(
                    InputAlias(
                        name = primary,
                        requirementId = requirementId,
                        kind = if (parameter.myBatisParamAlias != null) {
                            InputAliasKind.EXPLICIT_PARAM
                        } else {
                            InputAliasKind.SOURCE_PARAMETER_NAME
                        },
                        provenance = aliasProvenance(
                            parameter,
                            source,
                            primary,
                            if (parameter.myBatisParamAlias != null) {
                                InputAliasKind.EXPLICIT_PARAM
                            } else {
                                InputAliasKind.SOURCE_PARAMETER_NAME
                            },
                        ),
                    ),
                )

                val generic = "param${ordinal + 1}"
                if (generic !in reservedPrimary) {
                    add(
                        InputAlias(
                            name = generic,
                            requirementId = requirementId,
                            kind = InputAliasKind.GENERIC_PARAM,
                            provenance = InputProvenance(
                                listOf(
                                    mapperParameterEvidence(parameter, source),
                                    InputEvidence.GeneratedAlias(
                                        parameterIndex = parameter.index,
                                        alias = generic,
                                        ruleId = GENERIC_ALIAS_RULE,
                                    ),
                                ),
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun aliasProvenance(
        parameter: JavaMethodParameterMetadata,
        source: SourceEvidence,
        alias: String,
        kind: InputAliasKind,
    ): InputProvenance = InputProvenance(
        buildList {
            add(mapperParameterEvidence(parameter, source))
            when (kind) {
                InputAliasKind.EXPLICIT_PARAM ->
                    add(InputEvidence.ExplicitParamAlias(parameter.index, alias, source))
                InputAliasKind.COLLECTION,
                InputAliasKind.LIST,
                InputAliasKind.ARRAY,
                -> add(
                    InputEvidence.GeneratedAlias(
                        parameter.index,
                        alias,
                        COLLECTION_ALIAS_RULE,
                    ),
                )
                else -> Unit
            }
        },
    )

    private fun mapperParameterEvidence(
        parameter: JavaMethodParameterMetadata,
        source: SourceEvidence,
    ): InputEvidence.MapperMethodParameter = InputEvidence.MapperMethodParameter(
        index = parameter.index,
        sourceName = parameter.sourceName,
        typeIdentity = parameter.typeIdentity,
        source = source,
    )

    private fun rawUsesByParameter(
        parameters: List<JavaMethodParameterMetadata>,
        rawUses: List<RawUse>,
    ): Map<Int, List<RawUse>> {
        if (rawUses.isEmpty() || parameters.isEmpty()) return emptyMap()
        if (parameters.size == 1) return mapOf(parameters.single().index to rawUses)

        val aliases = buildMap {
            parameters.forEachIndexed { ordinal, parameter ->
                parameter.myBatisParamAlias?.let { put(it, parameter.index) }
                parameter.sourceName?.let { putIfAbsent(it, parameter.index) }
                putIfAbsent("param${ordinal + 1}", parameter.index)
            }
        }
        val result = linkedMapOf<Int, MutableList<RawUse>>()
        for (use in rawUses) {
            val root = use.expression.substringBefore('.').trim()
            val index = root.takeIf(simpleRoot::matches)?.let(aliases::get)
            if (index == null) {
                parameters.forEach { parameter ->
                    result.getOrPut(parameter.index) { mutableListOf() } += use
                }
            } else {
                result.getOrPut(index) { mutableListOf() } += use
            }
        }
        return result.mapValues { (_, uses) -> uses.toList() }
    }

    private fun runtimeRawUses(
        text: String,
        source: SourceEvidence,
    ): List<RawUse> = rawInterpolation.findAll(text)
        .mapNotNull { match ->
            val expression = match.groupValues[1].trim()
            expression.takeIf(String::isNotEmpty)?.let { RawUse(it, source) }
        }
        .toList()

    private fun isSpecialParameter(parameter: JavaMethodParameterMetadata): Boolean =
        parameter.typeIdentity.value.substringBefore('<').trim() in
            setOf(SPECIAL_ROW_BOUNDS, SPECIAL_RESULT_HANDLER)

    private fun blocked(
        statementId: StatementId,
        sourceRevisions: Map<SourceFileId, SourceRevision>,
        code: String,
    ): ParameterContract = ParameterContract(
        statementId = statementId,
        requirements = emptyList(),
        aliases = emptyList(),
        internalBindings = emptyList(),
        blockingProblems = listOf(
            InputContractProblem(
                kind = InputContractProblemKind.UNSUPPORTED,
                code = code,
                requirementId = null,
                provenance = null,
            ),
        ),
        sourceRevisions = sourceRevisions,
    )

    internal data class RawUse(
        val expression: String,
        val source: SourceEvidence,
    )

    internal object XmlInvocationUsageScanner {
        data class Result(
            val requiresCallerObject: Boolean,
            val rawUses: List<RawUse>,
        )

        fun scan(projection: XmlMapperPreparationProjection.Result.Ready): Result {
            val documents = projection.documents.map(::parseDocument)
            val fragments = buildMap {
                documents.forEach { document ->
                    directChildren(document.document.documentElement)
                        .filter { it.tagName == "sql" }
                        .forEach { fragment ->
                            val id = fragment.getAttribute("id").trim()
                            if (id.isNotEmpty()) {
                                put("${document.namespace}.$id", FragmentRef(document, fragment))
                            }
                        }
                }
            }
            val rootDocument = documents.single { it.projected.rootStatementDocument }
            val rootStatement = directChildren(rootDocument.document.documentElement)
                .single { it.tagName in setOf("select", "insert", "update", "delete") }
            val scan = ScanState()
            inspectNode(
                node = rootStatement,
                variables = emptyMap(),
                fragments = fragments,
                fragmentStack = linkedSetOf(),
                source = rootDocument.sourceEvidence(),
                scan = scan,
            )
            return Result(scan.requiresCallerObject, scan.rawUses.toList())
        }

        private fun inspectNode(
            node: Node,
            variables: Map<String, String>,
            fragments: Map<String, FragmentRef>,
            fragmentStack: MutableSet<String>,
            source: SourceEvidence,
            scan: ScanState,
        ) {
            when (node) {
                is Element -> {
                    if (node.tagName == "property") return
                    if (node.tagName == "include") {
                        val refid = substitute(node.getAttribute("refid"), variables)
                        val fragment = fragments[qualifyRefid(node, refid, fragments)]
                            ?: return
                        if (!fragmentStack.add(refid)) return

                        val childVariables = LinkedHashMap(variables)
                        directChildren(node)
                            .filter { it.tagName == "property" }
                            .forEach { property ->
                                val name = property.getAttribute("name").trim()
                                val value = substitute(property.getAttribute("value"), variables)
                                if (name.isNotEmpty()) childVariables[name] = value
                            }
                        val fragmentSource = fragment.document.sourceEvidence()
                        fragment.element.childNodes.forEachNode { child ->
                            inspectNode(
                                child,
                                childVariables,
                                fragments,
                                fragmentStack,
                                fragmentSource,
                                scan,
                            )
                        }
                        fragmentStack.remove(refid)
                        return
                    }

                    when (node.tagName) {
                        "if", "when" -> if (node.hasAttribute("test")) scan.requiresCallerObject = true
                        "foreach" -> if (node.hasAttribute("collection")) scan.requiresCallerObject = true
                        "bind" -> if (node.hasAttribute("value")) scan.requiresCallerObject = true
                    }

                    val attributes = node.attributes
                    for (index in 0 until attributes.length) {
                        val attribute = attributes.item(index)
                        inspectText(attribute.nodeValue.orEmpty(), variables, scan, source)
                    }
                    node.childNodes.forEachNode { child ->
                        inspectNode(child, variables, fragments, fragmentStack, source, scan)
                    }
                }
                else -> inspectText(node.nodeValue.orEmpty(), variables, scan, source)
            }
        }

        private fun inspectText(
            text: String,
            variables: Map<String, String>,
            scan: ScanState,
            source: SourceEvidence,
        ) {
            if ("#{" in text) scan.requiresCallerObject = true
            rawInterpolation.findAll(text).forEach { match ->
                val expression = match.groupValues[1].trim()
                if (expression.isEmpty() || expression in variables) return@forEach
                scan.requiresCallerObject = true
                scan.rawUses += RawUse(expression, source)
            }
        }

        private fun parseDocument(
            projected: XmlMapperPreparationProjection.ProjectedDocument,
        ): ParsedDocument {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
                isXIncludeAware = false
                isExpandEntityReferences = false
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }
            val document = ByteArrayInputStream(
                projected.content.toByteArray(StandardCharsets.UTF_8),
            ).use(builder::parse)
            val namespace = document.documentElement.getAttribute("namespace").trim()
            return ParsedDocument(projected, namespace, document)
        }

        private fun qualifyRefid(
            include: Element,
            refid: String,
            fragments: Map<String, FragmentRef>,
        ): String {
            if ('.' in refid) return refid
            val mapper = generateSequence(include.parentNode) { it.parentNode }
                .filterIsInstance<Element>()
                .firstOrNull { it.tagName == "mapper" }
            val namespace = mapper?.getAttribute("namespace")?.trim().orEmpty()
            val qualified = "$namespace.$refid"
            return if (qualified in fragments) qualified else refid
        }

        private fun substitute(
            value: String,
            variables: Map<String, String>,
        ): String = rawInterpolation.replace(value) { match ->
            variables[match.groupValues[1].trim()] ?: match.value
        }

        private fun directChildren(parent: Element): List<Element> = buildList {
            val nodes = parent.childNodes
            for (index in 0 until nodes.length) {
                val child = nodes.item(index)
                if (child is Element) add(child)
            }
        }

        private fun org.w3c.dom.NodeList.forEachNode(block: (Node) -> Unit) {
            for (index in 0 until length) block(item(index))
        }

        private data class ParsedDocument(
            val projected: XmlMapperPreparationProjection.ProjectedDocument,
            val namespace: String,
            val document: Document,
        ) {
            fun sourceEvidence(): SourceEvidence = SourceEvidence(
                projected.sourceFileId,
                projected.sourceRevision,
                null,
            )
        }

        private data class FragmentRef(
            val document: ParsedDocument,
            val element: Element,
        )

        private class ScanState {
            var requiresCallerObject: Boolean = false
            val rawUses = mutableListOf<RawUse>()
        }
    }
}
