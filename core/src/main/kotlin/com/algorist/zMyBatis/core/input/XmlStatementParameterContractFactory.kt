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
 * root statement can produce an empty non-blocking contract. Flat simple-name if siblings, either
 * direct or inside direct wrappers (one where, plus one set for UPDATE), carry
 * OGNL and placeholder-scope provenance. Each Boolean wrapper must contain a Boolean-if sibling;
 * bounded trim forms with literal WHERE/SET prefixes share those wrapper limits. Native where/set
 * accept no attributes; trim accepts only the exact prefix/override pairs checked below.
 * Mapper metadata must separately prove every Boolean caller alias. This scanner discovers source
 * uses, never evaluates conditions or WHERE/SET trimming semantics. One bounded foreach may instead
 * be direct or a direct child of a where-role wrapper (set-role for UPDATE). UPDATE may combine
 * both roles around exactly one foreach; its other wrapper contains only static text/caller bindings.
 * Boolean-if/foreach composition and additional loops remain unsupported.
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
    private const val FOREACH_PROVENANCE_PROBLEM = "xml-foreach-input-provenance-unsupported"
    private const val FOREACH_LOCAL_RAW_PROBLEM = "xml-foreach-raw-local-unsupported"
    private const val FOREACH_LOCAL_EXPRESSION_PROBLEM = "xml-foreach-local-expression-unsupported"
    private const val FOREACH_LOCAL_SHADOWING_PROBLEM = "xml-foreach-local-shadowing-unsupported"
    private const val IF_RAW_INPUT_PROBLEM = "xml-if-raw-input-unsupported"
    private const val UNSAFE_DTD_PROBLEM = "xml-parameter-contract-unsafe-dtd"

    private val simpleRoot = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val simpleNamedProperty = Regex("[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*")
    private val reservedInternalRoots = setOf("_parameter", "_databaseId")
    // Alphabetic token literals from the maintained MyBatis 3.5.19 OgnlParserConstants.
    // A lexically simple keyword is not an OGNL caller-variable lookup.
    private val ognlKeywords = setOf(
        "or", "and", "bor", "xor", "band", "eq", "neq", "lt", "gt", "lte", "gte",
        "in", "not", "shl", "shr", "ushr", "instanceof", "true", "false", "null", "new",
    )

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

        if (statementScan.ifConditions.isNotEmpty() && placeholderScan.uses.any { it.kind == InputKind.RAW_INTERPOLATION }) {
            return blockedContract(
                statementId,
                sourceRevisions,
                InputContractProblemKind.UNSUPPORTED,
                IF_RAW_INPUT_PROBLEM,
            )
        }

        val foreach = statementScan.foreach
        val foreachLocals = foreach
            ?.let { declaration -> listOfNotNull(declaration.item, declaration.index).toSet() }
            .orEmpty()
        val callerRoots = placeholderScan.uses
            .mapTo(linkedSetOf()) { use -> use.expression.substringBefore('.') }
        if (foreachLocals.any { it in callerRoots }) {
            return blockedContract(
                statementId,
                sourceRevisions,
                InputContractProblemKind.UNSUPPORTED,
                FOREACH_LOCAL_SHADOWING_PROBLEM,
            )
        }

        val internalBindings = buildList {
            foreach?.let { declaration ->
                add(
                    InternalBinding(
                        name = declaration.item,
                        kind = InternalBindingKind.FOREACH_ITEM,
                        provenance = InputProvenance(
                            listOf(
                                InputEvidence.ForeachLocal(
                                    name = declaration.item,
                                    role = ForeachLocalRole.ITEM,
                                    source = statementSource,
                                ),
                            ),
                        ),
                    ),
                )
                declaration.index?.let { index ->
                    add(
                        InternalBinding(
                            name = index,
                            kind = InternalBindingKind.FOREACH_INDEX,
                            provenance = InputProvenance(
                                listOf(
                                    InputEvidence.ForeachLocal(
                                        name = index,
                                        role = ForeachLocalRole.INDEX,
                                        source = statementSource,
                                    ),
                                ),
                            ),
                        ),
                    )
                }
            }
        }

        val usesByRoot = linkedMapOf<String, MutableList<CallerUse>>()
        placeholderScan.uses.forEach { use ->
            usesByRoot.getOrPut(use.expression) { mutableListOf() } += CallerUse(
                kind = use.kind,
                evidence = InputEvidence.Placeholder(
                    kind = use.kind,
                    expression = use.expression,
                    source = statementSource,
                    enclosingOgnlExpression = use.enclosingOgnlExpression,
                ),
            )
        }
        foreach?.let { declaration ->
            usesByRoot.getOrPut(declaration.collection) { mutableListOf() } += CallerUse(
                kind = InputKind.BOUND,
                evidence = InputEvidence.ForeachCollection(
                    expression = declaration.collection,
                    source = statementSource,
                ),
            )
        }

        statementScan.ifConditions.forEach { condition ->
            usesByRoot.getOrPut(condition) { mutableListOf() } += CallerUse(
                kind = InputKind.BOUND,
                evidence = InputEvidence.OgnlExpression(condition, statementSource),
            )
        }

        val problems = usesByRoot.map { (root, uses) ->
            val provenance = InputProvenance(uses.map { it.evidence })
            val kinds = uses.mapTo(linkedSetOf()) { it.kind }

            when {
                root.substringBefore('.') in reservedInternalRoots -> InputContractProblem(
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
            internalBindings = internalBindings,
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
        var foreachDepth = -1
        var wrapperDepth = -1
        var activeWrapperKind: String? = null
        val wrappersSeen = mutableSetOf<String>()
        var wrapperConditionStart = 0
        var wrapperWithoutCondition = false
        var ifDepth = -1
        var activeIfCondition: String? = null
        val ifConditions = mutableListOf<String>()
        var foreachDeclaration: ForeachDeclaration? = null
        val textSegments = mutableListOf<StatementTextSegment>()

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

                        if (targetDepth >= 0) {
                            val wrapperKind = wrapperKind(reader, statementKind)
                            if (
                                depth == targetDepth + 1 &&
                                wrapperKind != null && wrapperKind !in wrappersSeen &&
                                (ifConditions.isEmpty() ||
                                    (statementKind == StatementKind.UPDATE && wrappersSeen.isNotEmpty())) &&
                                (foreachDeclaration == null ||
                                    (statementKind == StatementKind.UPDATE && wrappersSeen.isNotEmpty()))
                            ) {
                                wrappersSeen += wrapperKind
                                wrapperDepth = depth
                                activeWrapperKind = wrapperKind
                                wrapperConditionStart = ifConditions.size
                                continue
                            }
                            if (
                                ((depth == targetDepth + 1 && wrappersSeen.isEmpty()) ||
                                    (activeWrapperKind != null && depth == wrapperDepth + 1)) &&
                                localName == "foreach" &&
                                isUnqualifiedElement(reader) &&
                                foreachDepth < 0 &&
                                foreachDeclaration == null &&
                                ifConditions.isEmpty()
                            ) {
                                val declaration = parseForeachDeclaration(reader)
                                    ?: return StatementScan.Failed(
                                        InputContractProblemKind.UNSUPPORTED,
                                        FOREACH_PROVENANCE_PROBLEM,
                                    )
                                foreachDeclaration = declaration
                                foreachDepth = depth
                                continue
                            }
                            if (
                                (
                                    (depth == targetDepth + 1 && wrappersSeen.isEmpty()) ||
                                        (wrapperDepth >= 0 && depth == wrapperDepth + 1)
                                    ) && localName == "if" &&
                                isUnqualifiedElement(reader) && foreachDeclaration == null &&
                                ifDepth < 0
                            ) {
                                val condition = reader.getAttributeValue(null, "test")?.trim()
                                if (
                                    condition != null && simpleRoot.matches(condition) &&
                                    condition !in reservedInternalRoots &&
                                    condition !in ognlKeywords &&
                                    reader.attributeCount == 1 &&
                                    reader.getAttributeNamespace(0).isNullOrEmpty()
                                ) {
                                    ifConditions += condition
                                    ifDepth = depth
                                    activeIfCondition = condition
                                    continue
                                }
                            }
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
                            val locals = if (foreachDepth >= 0) {
                                val declaration = requireNotNull(foreachDeclaration)
                                listOfNotNull(declaration.item, declaration.index).toSet()
                            } else {
                                emptySet()
                            }
                            textSegments += StatementTextSegment(text, locals, activeIfCondition)
                        }
                    }

                    XMLStreamConstants.END_ELEMENT -> {
                        if (ifDepth == depth) {
                            ifDepth = -1
                            activeIfCondition = null
                        }
                        if (wrapperDepth == depth) {
                            // A static wrapper may precede UPDATE's sole foreach in the other role.
                            // Defer refusal until the complete statement proves that loop exists.
                            if (ifConditions.size == wrapperConditionStart) wrapperWithoutCondition = true
                            wrapperDepth = -1
                            activeWrapperKind = null
                        }
                        if (foreachDepth == depth) {
                            foreachDepth = -1
                        }
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
            } else if (wrapperWithoutCondition && foreachDeclaration == null) {
                StatementScan.Failed(InputContractProblemKind.UNSUPPORTED, NESTED_ELEMENT_PROBLEM)
            } else {
                StatementScan.Ready(textSegments, foreachDeclaration, ifConditions)
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

    private fun scanPlaceholders(segments: List<StatementTextSegment>): PlaceholderScan {
        val uses = mutableListOf<PlaceholderUse>()
        val failures = mutableListOf<PlaceholderFailure>()

        segments.forEach { segment ->
            val text = segment.text
            var cursor = 0
            while (cursor < text.length - 1) {
                val kind = when {
                    text[cursor] == '#' && text[cursor + 1] == '{' -> InputKind.BOUND
                    text[cursor].code == 36 && text[cursor + 1] == '{' -> InputKind.RAW_INTERPOLATION
                    else -> {
                        cursor += 1
                        continue
                    }
                }

                if (cursor > 0 && text[cursor - 1] == '\\') {
                    cursor += 2
                    continue
                }

                val close = findClosingToken(text, cursor + 2)
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
                val supportedExpression =
                    simpleRoot.matches(expression) ||
                        (kind == InputKind.BOUND && simpleNamedProperty.matches(expression))
                val root = expression.substringBefore('.')
                when {
                    root in segment.foreachLocals && kind == InputKind.RAW_INTERPOLATION -> {
                        failures += PlaceholderFailure(
                            kind = kind,
                            expression = expression.takeIf(String::isNotEmpty),
                            problemKind = InputContractProblemKind.UNSUPPORTED,
                            code = FOREACH_LOCAL_RAW_PROBLEM,
                        )
                    }
                    root in segment.foreachLocals && expression != root -> {
                        failures += PlaceholderFailure(
                            kind = kind,
                            expression = expression.takeIf(String::isNotEmpty),
                            problemKind = InputContractProblemKind.UNSUPPORTED,
                            code = FOREACH_LOCAL_EXPRESSION_PROBLEM,
                        )
                    }
                    root in segment.foreachLocals -> Unit
                    expression.isEmpty() || !supportedExpression -> {
                        failures += PlaceholderFailure(
                            kind = kind,
                            expression = expression.takeIf(String::isNotEmpty),
                            problemKind = InputContractProblemKind.UNSUPPORTED,
                            code = COMPLEX_PLACEHOLDER_PROBLEM,
                        )
                    }
                    else -> uses += PlaceholderUse(kind, expression, segment.enclosingOgnlExpression)
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

    private fun parseForeachDeclaration(reader: XMLStreamReader): ForeachDeclaration? {
        if (reader.getAttributeValue(null, "nullable") != null) return null

        val collection = reader.getAttributeValue(null, "collection")
            ?.takeIf(String::isNotBlank)
            ?: return null
        val item = reader.getAttributeValue(null, "item")
            ?.takeIf(String::isNotBlank)
            ?: return null
        val index = reader.getAttributeValue(null, "index")?.let { raw ->
            raw.takeIf(String::isNotBlank) ?: return null
        }

        if (
            !simpleRoot.matches(collection) ||
            !simpleRoot.matches(item) ||
            index?.let(simpleRoot::matches) == false ||
            collection in reservedInternalRoots ||
            item in reservedInternalRoots ||
            (index != null && index in reservedInternalRoots)
        ) {
            return null
        }

        val names = listOfNotNull(collection, item, index)
        if (names.toSet().size != names.size) return null

        return ForeachDeclaration(collection, item, index)
    }

    /** Classifies where/set roles only; stock MyBatis still owns every trim operation. */
    private fun wrapperKind(reader: XMLStreamReader, kind: StatementKind): String? {
        if (!isUnqualifiedElement(reader)) return null
        return when (reader.localName) {
            "where" -> "where".takeIf { reader.attributeCount == 0 }
            "set" -> "set".takeIf { kind == StatementKind.UPDATE && reader.attributeCount == 0 }
            "trim" -> {
                if (
                    reader.attributeCount != 2 ||
                    (0 until reader.attributeCount).any { !reader.getAttributeNamespace(it).isNullOrEmpty() }
                ) return null
                when {
                    reader.getAttributeValue(null, "prefix") == "WHERE" &&
                        reader.getAttributeValue(null, "prefixOverrides") == "AND |OR " -> "where"
                    kind == StatementKind.UPDATE && reader.getAttributeValue(null, "prefix") == "SET" &&
                        reader.getAttributeValue(null, "suffixOverrides") == "," -> "set"
                    else -> null
                }
            }
            else -> null
        }
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
        data class Ready(
            val textSegments: List<StatementTextSegment>,
            val foreach: ForeachDeclaration?,
            val ifConditions: List<String>,
        ) : StatementScan

        data class Failed(
            val kind: InputContractProblemKind,
            val code: String,
        ) : StatementScan
    }

    private data class StatementTextSegment(
        val text: String,
        val foreachLocals: Set<String>,
        val enclosingOgnlExpression: String?,
    )

    private data class ForeachDeclaration(
        val collection: String,
        val item: String,
        val index: String?,
    )

    private data class CallerUse(
        val kind: InputKind,
        val evidence: InputEvidence,
    )

    private data class PlaceholderUse(
        val kind: InputKind,
        val expression: String,
        val enclosingOgnlExpression: String?,
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
