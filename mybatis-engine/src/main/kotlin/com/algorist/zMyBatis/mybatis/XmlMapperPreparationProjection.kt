package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource
import com.algorist.zMyBatis.core.source.MyBatisDatabaseId
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.XmlStatementId
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * Builds the smallest stock-MyBatis mapper view required to prepare one selected XML statement.
 *
 * Source snapshots remain the authority for revision/staleness checks. Projected documents are
 * preparation-only artifacts: unrelated mapper declarations never become parser authority merely
 * because they share the same source file.
 */
internal object XmlMapperPreparationProjection {
    private const val PARSE_FAILURE = "mybatis-xml-mapper-parse-failure"
    private const val ROOT_STATEMENT_MISSING = "xml-preparation-root-statement-missing"
    private const val ROOT_RESOURCE_MISMATCH = "xml-preparation-root-resource-mismatch"
    private const val STATEMENT_KIND_MISMATCH = "xml-preparation-statement-kind-mismatch"
    private const val CLASS_LOADING_UNSUPPORTED = "xml-preparation-runtime-class-loading-unsupported"
    private const val EXTERNAL_ENTITY_UNSUPPORTED = "xml-preparation-external-entity-unsupported"
    private const val PROJECTION_INVARIANT = "xml-preparation-projection-invariant-failure"
    private const val UNSUPPORTED_REFERENCE = "xml-preparation-projection-include-unsupported"
    private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>"
    private const val MAPPER_DOCTYPE =
        "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"https://mybatis.org/dtd/mybatis-3-mapper.dtd\">"

    private val statementElementNames = setOf("select", "insert", "update", "delete")
    private val safeMapperDoctype = Regex(
        """(?is)<!DOCTYPE\s+mapper\s+PUBLIC\s+["']-//mybatis\.org//DTD Mapper 3\.0//EN["']\s+["']https?://mybatis\.org/dtd/mybatis-3-mapper\.dtd["']\s*>""",
    )
    private val runtimeMappingOption = Regex("""(?i)\b(javaType|typeHandler)\s*=\s*([A-Za-z0-9_.$]+)""")
    private val safeParameterTypeAliases = setOf(
        "_byte", "_char", "_character", "_long", "_short", "_int", "_integer",
        "_double", "_float", "_boolean",
        "byte", "char", "character", "long", "short", "int", "integer",
        "double", "float", "boolean",
        "string", "byte[]", "char[]", "object",
        "date", "decimal", "bigdecimal", "biginteger",
        "map", "hashmap", "list", "arraylist", "collection", "iterator",
    )
    private val safeParameterTypePrefixes = listOf(
        "java.lang.",
        "java.math.",
        "java.time.",
        "java.sql.",
        "java.util.",
    )

    data class ProjectedDocument(
        val sourceFileId: SourceFileId,
        val sourceRevision: SourceRevision,
        val content: String,
        val rootStatementDocument: Boolean,
    )

    sealed interface Result {
        data class Ready(val documents: List<ProjectedDocument>) : Result
        data class Failed(val failure: PreparationFailure) : Result
    }

    fun project(
        source: XmlMapperPreparationSource,
        statementId: XmlStatementId,
        effectiveDatabaseId: MyBatisDatabaseId?,
    ): Result {
        val parsed = mutableListOf<ParsedMapper>()
        for ((fileId, revision, content) in source.sourceGraph.sourceSnapshots) {
            securityFailure(content)?.let { return Result.Failed(it) }
            val document = try {
                parse(content)
            } catch (_: Exception) {
                return failed(PreparationFailureKind.MYBATIS_PARSE, PARSE_FAILURE)
            }
            val root = document.documentElement
                ?: return failed(PreparationFailureKind.MYBATIS_PARSE, PARSE_FAILURE)
            if (root.tagName != "mapper") {
                return failed(PreparationFailureKind.MYBATIS_PARSE, PARSE_FAILURE)
            }
            val namespace = root.getAttribute("namespace").trim()
            if (namespace.isEmpty()) {
                return failed(PreparationFailureKind.MYBATIS_PARSE, PARSE_FAILURE)
            }
            parsed += ParsedMapper(
                sourceFileId = fileId,
                sourceRevision = revision,
                namespace = namespace,
                document = document,
            )
        }

        val rootMapper = parsed.singleOrNull { it.sourceFileId == statementId.sourceFileId }
            ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, PROJECTION_INVARIANT)
        if (rootMapper.namespace != statementId.namespace) {
            return failed(PreparationFailureKind.PREPARATION_INVARIANT, ROOT_RESOURCE_MISMATCH)
        }

        val rootStatements = directChildren(rootMapper.document.documentElement)
            .filter {
                it.tagName in statementElementNames &&
                    it.getAttribute("id").trim() == statementId.statementId
            }
        if (rootStatements.isEmpty()) {
            val statementExistsElsewhere = parsed
                .asSequence()
                .filter { it.sourceFileId != rootMapper.sourceFileId && it.namespace == statementId.namespace }
                .flatMap { directChildren(it.document.documentElement).asSequence() }
                .any {
                    it.tagName in statementElementNames &&
                        it.getAttribute("id").trim() == statementId.statementId
                }
            return if (statementExistsElsewhere) {
                failed(PreparationFailureKind.PREPARATION_INVARIANT, ROOT_RESOURCE_MISMATCH)
            } else {
                failed(PreparationFailureKind.MYBATIS_PARSE, ROOT_STATEMENT_MISSING)
            }
        }

        val expectedElement = elementName(source.sourceGraph.rootStatement.kind)
        val rootCandidates = rootStatements.filter { it.tagName == expectedElement }
        if (rootCandidates.isEmpty()) {
            return failed(PreparationFailureKind.PREPARATION_INVARIANT, STATEMENT_KIND_MISMATCH)
        }
        val selectedRoot = selectVariant(rootCandidates, effectiveDatabaseId?.value)
            ?: return failed(PreparationFailureKind.MYBATIS_PARSE, ROOT_STATEMENT_MISSING)

        statementRuntimeFailure(selectedRoot)?.let { return Result.Failed(it) }
        val projectedRoot = selectedRoot.cloneNode(true) as Element
        projectedRoot.removeAttribute("resultMap")
        projectedRoot.removeAttribute("resultType")

        val fragmentsByCanonicalName = linkedMapOf<String, MutableList<FragmentCandidate>>()
        parsed.forEach { mapper ->
            directChildren(mapper.document.documentElement)
                .filter { it.tagName == "sql" && it.getAttribute("id").isNotBlank() }
                .forEach { fragment ->
                    val id = fragment.getAttribute("id").trim()
                    fragmentsByCanonicalName
                        .getOrPut("${mapper.namespace}.$id") { mutableListOf() }
                        .add(FragmentCandidate(mapper, fragment))
                }
        }

        val selectedFragments = linkedMapOf<FragmentIdentity, FragmentCandidate>()
        val queue = ArrayDeque<IncludeOwner>()
        queue.add(IncludeOwner(rootMapper.namespace, projectedRoot))

        while (queue.isNotEmpty()) {
            val owner = queue.removeFirst()
            for (include in descendantIncludes(owner.element)) {
                val refid = include.getAttribute("refid").trim()
                if (refid.isEmpty() || "\${" in refid) {
                    return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, UNSUPPORTED_REFERENCE)
                }
                val canonical = if ('.' in refid) {
                    refid
                } else {
                    "${owner.namespace}.$refid"
                }
                val candidates = fragmentsByCanonicalName[canonical].orEmpty()
                val selected = selectFragmentVariant(candidates, effectiveDatabaseId?.value)
                    ?: return failed(PreparationFailureKind.MYBATIS_PARSE, ROOT_STATEMENT_MISSING)
                val id = FragmentIdentity(
                    selected.mapper.sourceFileId,
                    selected.mapper.namespace,
                    selected.element.getAttribute("id").trim(),
                    selected.element.getAttribute("databaseId").trim().ifEmpty { null },
                )
                if (selectedFragments.putIfAbsent(id, selected) == null) {
                    queue.add(IncludeOwner(selected.mapper.namespace, selected.element))
                }
            }
        }

        val fragmentDocuments = selectedFragments.values
            .groupBy { it.mapper.sourceFileId }
            .entries
            .sortedBy { it.key.value }
            .map { (_, fragments) ->
                val mapper = fragments.first().mapper
                val projected = newMapperDocument(mapper.namespace)
                val projectedRootElement = projected.documentElement
                fragments
                    .sortedWith(
                        compareBy(
                            { it.element.getAttribute("id") },
                            { it.element.getAttribute("databaseId") },
                        ),
                    )
                    .forEach { fragment ->
                        projectedRootElement.appendChild(
                            projected.importNode(fragment.element, true),
                        )
                    }
                ProjectedDocument(
                    sourceFileId = mapper.sourceFileId,
                    sourceRevision = mapper.sourceRevision,
                    content = serialize(projected),
                    rootStatementDocument = false,
                )
            }

        val rootDocument = newMapperDocument(rootMapper.namespace)
        rootDocument.documentElement.appendChild(rootDocument.importNode(projectedRoot, true))
        val projectedRootDocument = ProjectedDocument(
            sourceFileId = rootMapper.sourceFileId,
            sourceRevision = rootMapper.sourceRevision,
            content = serialize(rootDocument),
            rootStatementDocument = true,
        )

        val documents = fragmentDocuments + projectedRootDocument
        runtimeMappingFailure(documents)?.let { return Result.Failed(it) }
        return Result.Ready(documents)
    }

    private fun runtimeMappingFailure(
        documents: List<ProjectedDocument>,
    ): PreparationFailure? {
        for ((_, _, content) in documents) {
            for (match in runtimeMappingOption.findAll(content)) {
                val option = match.groupValues[1].lowercase()
                val value = match.groupValues[2]
                val safe = when (option) {
                    "javatype" -> safeParameterType(value)
                    "typehandler" -> value.startsWith("org.apache.ibatis.type.")
                    else -> false
                }
                if (!safe) {
                    return PreparationFailure(
                        PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                        CLASS_LOADING_UNSUPPORTED,
                    )
                }
            }
        }
        return null
    }

    private fun statementRuntimeFailure(statement: Element): PreparationFailure? {
        if (statement.hasAttribute("parameterMap") || statement.hasAttribute("lang")) {
            return PreparationFailure(
                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                CLASS_LOADING_UNSUPPORTED,
            )
        }
        val parameterType = statement.getAttribute("parameterType").trim()
        if (parameterType.isNotEmpty() && !safeParameterType(parameterType)) {
            return PreparationFailure(
                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                CLASS_LOADING_UNSUPPORTED,
            )
        }
        if (descendants(statement).any { it.tagName == "selectKey" }) {
            return PreparationFailure(
                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                CLASS_LOADING_UNSUPPORTED,
            )
        }
        return null
    }

    private fun safeParameterType(value: String): Boolean {
        val normalized = value.trim()
        return normalized.lowercase() in safeParameterTypeAliases ||
            safeParameterTypePrefixes.any(normalized::startsWith)
    }

    private fun securityFailure(content: String): PreparationFailure? {
        if (content.contains("<!ENTITY", ignoreCase = true)) {
            return PreparationFailure(
                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                EXTERNAL_ENTITY_UNSUPPORTED,
            )
        }
        val doctypeCount = Regex("(?i)<!DOCTYPE\\b").findAll(content).count()
        if (doctypeCount > 1 || (doctypeCount == 1 && !safeMapperDoctype.containsMatchIn(content))) {
            return PreparationFailure(
                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                EXTERNAL_ENTITY_UNSUPPORTED,
            )
        }
        return null
    }

    private fun selectVariant(elements: List<Element>, databaseId: String?): Element? {
        val exact = if (databaseId == null) {
            emptyList()
        } else {
            elements.filter { it.getAttribute("databaseId").trim() == databaseId }
        }
        if (exact.size == 1) return exact.single()
        if (exact.size > 1) return null

        val defaults = elements.filter { it.getAttribute("databaseId").isBlank() }
        return defaults.singleOrNull()
    }

    private fun selectFragmentVariant(
        candidates: List<FragmentCandidate>,
        databaseId: String?,
    ): FragmentCandidate? {
        val exact = if (databaseId == null) {
            emptyList()
        } else {
            candidates.filter { it.element.getAttribute("databaseId").trim() == databaseId }
        }
        if (exact.size == 1) return exact.single()
        if (exact.size > 1) return null

        val defaults = candidates.filter { it.element.getAttribute("databaseId").isBlank() }
        return defaults.singleOrNull()
    }

    private fun parse(content: String): Document {
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
        return ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8)).use(builder::parse)
    }

    private fun newMapperDocument(namespace: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }
        val document = factory.newDocumentBuilder().newDocument()
        val mapper = document.createElement("mapper")
        mapper.setAttribute("namespace", namespace)
        document.appendChild(mapper)
        return document
    }

    private fun serialize(document: Document): String {
        val factory = TransformerFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        }
        val transformer = factory.newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            setOutputProperty(OutputKeys.INDENT, "no")
        }
        val body = StringWriter().also { writer ->
            transformer.transform(DOMSource(document.documentElement), StreamResult(writer))
        }.toString()
        return XML_DECLARATION + "\n" + MAPPER_DOCTYPE + "\n" + body
    }

    private fun directChildren(parent: Element): List<Element> =
        buildList {
            var child: Node? = parent.firstChild
            while (child != null) {
                if (child.nodeType == Node.ELEMENT_NODE) add(child as Element)
                child = child.nextSibling
            }
        }

    private fun descendants(parent: Element): Sequence<Element> = sequence {
        val stack = ArrayDeque<Node>()
        var child: Node? = parent.firstChild
        while (child != null) {
            stack.addLast(child)
            child = child.nextSibling
        }
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            if (node.nodeType == Node.ELEMENT_NODE) {
                val element = node as Element
                yield(element)
                var nested: Node? = element.firstChild
                while (nested != null) {
                    stack.addLast(nested)
                    nested = nested.nextSibling
                }
            }
        }
    }

    private fun descendantIncludes(parent: Element): Sequence<Element> =
        descendants(parent).filter { it.tagName == "include" }

    private fun elementName(kind: StatementKind): String = when (kind) {
        StatementKind.SELECT -> "select"
        StatementKind.INSERT -> "insert"
        StatementKind.UPDATE -> "update"
        StatementKind.DELETE -> "delete"
    }

    private fun failed(kind: PreparationFailureKind, code: String): Result.Failed =
        Result.Failed(PreparationFailure(kind, code))

    private data class ParsedMapper(
        val sourceFileId: SourceFileId,
        val sourceRevision: SourceRevision,
        val namespace: String,
        val document: Document,
    )

    private data class FragmentCandidate(
        val mapper: ParsedMapper,
        val element: Element,
    )

    private data class FragmentIdentity(
        val sourceFileId: SourceFileId,
        val namespace: String,
        val id: String,
        val databaseId: String?,
    )

    private data class IncludeOwner(
        val namespace: String,
        val element: Element,
    )
}
