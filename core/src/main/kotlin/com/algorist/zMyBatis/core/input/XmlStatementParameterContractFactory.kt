package com.algorist.zMyBatis.core.input

import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlStatementId
import java.io.StringReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * Derives source-backed XML placeholder evidence for the #63 input contract.
 *
 * The XML source graph proves statement identity and source use, but it does not prove the mapper
 * method parameter-object semantics that MyBatis will apply at runtime. Therefore a placeholder use
 * is retained as provenance while caller-input authority remains blocked. A placeholder-free static
 * root statement can produce an empty non-blocking contract.
 */
object XmlStatementParameterContractFactory {
    private const val DEPENDENCY_PROVENANCE_PROBLEM = "xml-dependent-fragment-provenance-unsupported"
    private const val MALFORMED_XML_PROBLEM = "xml-parameter-contract-malformed-root-source"
    private const val ROOT_MISMATCH_PROBLEM = "xml-parameter-contract-root-mismatch"
    private const val NESTED_ELEMENT_PROBLEM = "xml-nested-element-input-discovery-unsupported"
    private const val MALFORMED_PLACEHOLDER_PROBLEM = "xml-malformed-placeholder"
    private const val COMPLEX_PLACEHOLDER_PROBLEM = "xml-complex-placeholder-expression"
    private const val RESERVED_ROOT_PROBLEM = "xml-reserved-internal-placeholder-root"
    private const val MIXED_KIND_PROBLEM = "xml-mixed-raw-bound-input"
    private const val CALLER_AUTHORITY_PROBLEM = "xml-caller-input-authority-unproven"
    private const val UNSAFE_DTD_PROBLEM = "xml-parameter-contract-unsafe-dtd"

    private val simpleRoot = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val reservedInternalRoots = setOf("_parameter", "_databaseId")

    fun build(graph: StatementSourceGraph): ParameterContract {
        val statementId = graph.rootStatement.id as? XmlStatementId
            ?: throw IllegalArgumentException("XML parameter contract requires XmlStatementId")
        val sourceRevisions = graph.sourceSnapshots.associate { it.fileId to it.revision }
        val rootRevision = sourceRevisions.getValue(statementId.sourceFileId)
        val statementSource = SourceEvidence(
            sourceFileId = statementId.sourceFileId,
            sourceRevision = rootRevision,
            sourceRange = null,
        )

        if (graph.dependencies.isNotEmpty()) {
            return blockedContract(
                statementId,
                sourceRevisions,
                InputContractProblemKind.UNSUPPORTED,
                DEPENDENCY_PROVENANCE_PROBLEM,
            )
        }

        val rootSnapshot = graph.sourceSnapshots.single { it.fileId == statementId.sourceFileId }
        val statementScan = scanRootStatement(
            snapshot = rootSnapshot,
            statementId = statementId,
            statementKind = graph.rootStatement.kind,
        )
        if (statementScan is StatementScan.Failed) {
            return blockedContract(
                statementId,
                sourceRevisions,
                statementScan.kind,
                statementScan.code,
            )
        }
        statementScan as StatementScan.Ready

        val placeholderScan = scanPlaceholders(statementScan.textSegments)
        if (placeholderScan.failures.isNotEmpty()) {
            return ParameterContract(
                statementId = statementId,
                requirements = emptyList(),
                aliases = emptyList(),
                internalBindings = emptyList(),
                blockingProblems = placeholderScan.failures.map { failure ->
                    InputContractProblem(
                        kind = failure.problemKind,
                        code = failure.code,
                        requirementId = null,
                        provenance = failure.expression?.let { expression ->
                            InputProvenance(
                                listOf(
                                    InputEvidence.Placeholder(
                                        kind = failure.kind,
                                        expression = expression,
                                        source = statementSource,
                                    ),
                                ),
                            )
                        },
                    )
                },
                sourceRevisions = sourceRevisions,
            )
        }

        val usesByRoot = linkedMapOf<String, MutableList<PlaceholderUse>>()
        placeholderScan.uses.forEach { use ->
            usesByRoot.getOrPut(use.expression) { mutableListOf() } += use
        }

        val problems = usesByRoot.map { (root, uses) ->
            val provenance = InputProvenance(
                uses.map { use ->
                    InputEvidence.Placeholder(
                        kind = use.kind,
                        expression = use.expression,
                        source = statementSource,
                    )
                },
            )
            val kinds = uses.mapTo(linkedSetOf()) { it.kind }

            when {
                root in reservedInternalRoots -> InputContractProblem(
                    kind = InputContractProblemKind.UNSUPPORTED,
                    code = RESERVED_ROOT_PROBLEM,
                    requirementId = null,
                    provenance = provenance,
                )
                kinds.size != 1 -> InputContractProblem(
                    kind = InputContractProblemKind.AMBIGUOUS,
                    code = MIXED_KIND_PROBLEM,
                    requirementId = null,
                    provenance = provenance,
                )
                else -> InputContractProblem(
                    kind = InputContractProblemKind.UNKNOWN,
                    code = CALLER_AUTHORITY_PROBLEM,
                    requirementId = null,
                    provenance = provenance,
                )
            }
        }

        return ParameterContract(
            statementId = statementId,
            requirements = emptyList(),
            aliases = emptyList(),
            internalBindings = emptyList(),
            blockingProblems = problems,
            sourceRevisions = sourceRevisions,
        )
    }

    private fun scanRootStatement(
        snapshot: SourceSnapshot,
        statementId: XmlStatementId,
        statementKind: StatementKind,
    ): StatementScan {
        val reader = try {
            secureInputFactory().createXMLStreamReader(StringReader(snapshot.content))
        } catch (_: XMLStreamException) {
            return StatementScan.Failed(InputContractProblemKind.UNKNOWN, MALFORMED_XML_PROBLEM)
        }

        var depth = 0
        var mapperSeen = false
        var targetDepth = -1
        var targetMatches = 0
        var targetClosed = false
        val textSegments = mutableListOf<String>()

        return try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.DTD -> {
                        val dtd = reader.text.orEmpty()
                        if (dtd.contains("<!ENTITY", ignoreCase = true) || dtd.contains('[')) {
                            return StatementScan.Failed(
                                InputContractProblemKind.UNSUPPORTED,
                                UNSAFE_DTD_PROBLEM,
                            )
                        }
                    }

                    XMLStreamConstants.START_ELEMENT -> {
                        depth += 1
                        val localName = reader.localName
                        if (depth == 1) {
                            if (
                                localName != "mapper" ||
                                !isUnqualifiedElement(reader) ||
                                reader.getAttributeValue(null, "namespace") != statementId.namespace
                            ) {
                                return StatementScan.Failed(
                                    InputContractProblemKind.UNKNOWN,
                                    ROOT_MISMATCH_PROBLEM,
                                )
                            }
                            mapperSeen = true
                            continue
                        }

                        if (depth == 2) {
                            val declarationId = reader.getAttributeValue(null, "id")
                            if (declarationId == statementId.statementId) {
                                targetMatches += 1
                                if (
                                    targetMatches > 1 ||
                                    localName != elementName(statementKind) ||
                                    !isUnqualifiedElement(reader)
                                ) {
                                    return StatementScan.Failed(
                                        InputContractProblemKind.UNKNOWN,
                                        ROOT_MISMATCH_PROBLEM,
                                    )
                                }
                                targetDepth = depth
                            }
                            continue
                        }

                        if (targetDepth >= 0 && depth > targetDepth) {
                            return StatementScan.Failed(
                                InputContractProblemKind.UNSUPPORTED,
                                NESTED_ELEMENT_PROBLEM,
                            )
                        }
                    }

                    XMLStreamConstants.CHARACTERS,
                    XMLStreamConstants.CDATA,
                    -> if (targetDepth >= 0) {
                        val text = reader.text
                        if (text.isNotEmpty()) {
                            textSegments += text
                        }
                    }

                    XMLStreamConstants.END_ELEMENT -> {
                        if (targetDepth == depth) {
                            targetDepth = -1
                            targetClosed = true
                        }
                        depth -= 1
                        if (depth < 0) {
                            return StatementScan.Failed(
                                InputContractProblemKind.UNKNOWN,
                                MALFORMED_XML_PROBLEM,
                            )
                        }
                    }
                }
            }

            if (!mapperSeen || targetMatches != 1 || !targetClosed || depth != 0) {
                StatementScan.Failed(InputContractProblemKind.UNKNOWN, ROOT_MISMATCH_PROBLEM)
            } else {
                StatementScan.Ready(textSegments)
            }
        } catch (_: XMLStreamException) {
            StatementScan.Failed(InputContractProblemKind.UNKNOWN, MALFORMED_XML_PROBLEM)
        } finally {
            try {
                reader.close()
            } catch (_: XMLStreamException) {
                // Parsing outcome is already fixed.
            }
        }
    }

    private fun scanPlaceholders(segments: List<String>): PlaceholderScan {
        val uses = mutableListOf<PlaceholderUse>()
        val failures = mutableListOf<PlaceholderFailure>()

        segments.forEach { segment ->
            var cursor = 0
            while (cursor < segment.length - 1) {
                val kind = when {
                    segment[cursor] == '#' && segment[cursor + 1] == '{' -> InputKind.BOUND
                    segment[cursor].code == 36 && segment[cursor + 1] == '{' -> InputKind.RAW_INTERPOLATION
                    else -> {
                        cursor += 1
                        continue
                    }
                }

                if (cursor > 0 && segment[cursor - 1] == '\\') {
                    cursor += 2
                    continue
                }

                val close = findClosingToken(segment, cursor + 2)
                if (close == null) {
                    failures += PlaceholderFailure(
                        kind = kind,
                        expression = null,
                        problemKind = InputContractProblemKind.UNKNOWN,
                        code = MALFORMED_PLACEHOLDER_PROBLEM,
                    )
                    break
                }

                val payload = close.expression.trim()
                val expression = if (kind == InputKind.BOUND) {
                    payload.substringBefore(',').trim()
                } else {
                    payload
                }
                if (expression.isEmpty() || !simpleRoot.matches(expression)) {
                    failures += PlaceholderFailure(
                        kind = kind,
                        expression = expression.takeIf(String::isNotEmpty),
                        problemKind = InputContractProblemKind.UNSUPPORTED,
                        code = COMPLEX_PLACEHOLDER_PROBLEM,
                    )
                } else {
                    uses += PlaceholderUse(kind, expression)
                }
                cursor = close.endOffset + 1
            }
        }

        return PlaceholderScan(uses, failures)
    }

    private fun findClosingToken(text: String, expressionStart: Int): ClosingToken? {
        val expression = StringBuilder()
        var offset = expressionStart
        var end = text.indexOf('}', startIndex = offset)

        while (end >= 0) {
            if (end > offset && text[end - 1] == '\\') {
                expression.append(text, offset, end - 1).append('}')
                offset = end + 1
                end = text.indexOf('}', startIndex = offset)
            } else {
                expression.append(text, offset, end)
                return ClosingToken(end, expression.toString())
            }
        }
        return null
    }

    private fun secureInputFactory(): XMLInputFactory =
        XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
            setProperty(XMLInputFactory.SUPPORT_DTD, true)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
            setProperty(XMLInputFactory.IS_COALESCING, true)
            xmlResolver = javax.xml.stream.XMLResolver { _, _, _, _ -> StringReader("") }
        }

    private fun isUnqualifiedElement(reader: XMLStreamReader): Boolean =
        reader.prefix.isNullOrEmpty() && reader.namespaceURI.isNullOrEmpty()

    private fun elementName(kind: StatementKind): String = when (kind) {
        StatementKind.SELECT -> "select"
        StatementKind.INSERT -> "insert"
        StatementKind.UPDATE -> "update"
        StatementKind.DELETE -> "delete"
    }

    private fun blockedContract(
        statementId: XmlStatementId,
        sourceRevisions: Map<SourceFileId, SourceRevision>,
        kind: InputContractProblemKind,
        code: String,
    ): ParameterContract = ParameterContract(
        statementId = statementId,
        requirements = emptyList(),
        aliases = emptyList(),
        internalBindings = emptyList(),
        blockingProblems = listOf(InputContractProblem(kind, code, null, null)),
        sourceRevisions = sourceRevisions,
    )

    private sealed interface StatementScan {
        data class Ready(val textSegments: List<String>) : StatementScan

        data class Failed(
            val kind: InputContractProblemKind,
            val code: String,
        ) : StatementScan
    }

    private data class PlaceholderUse(
        val kind: InputKind,
        val expression: String,
    )

    private data class PlaceholderFailure(
        val kind: InputKind,
        val expression: String?,
        val problemKind: InputContractProblemKind,
        val code: String,
    )

    private data class PlaceholderScan(
        val uses: List<PlaceholderUse>,
        val failures: List<PlaceholderFailure>,
    )

    private data class ClosingToken(
        val endOffset: Int,
        val expression: String,
    )
}
