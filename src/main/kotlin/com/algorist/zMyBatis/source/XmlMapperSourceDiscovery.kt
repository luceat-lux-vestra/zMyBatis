package com.algorist.zMyBatis.source

import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import java.io.StringReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

sealed interface XmlMapperDiscoveryResult {
    data class Discovered(val mapper: XmlMapperDocumentDiscovery) : XmlMapperDiscoveryResult
    data class Failed(val reason: XmlMapperDiscoveryFailure) : XmlMapperDiscoveryResult
}

enum class XmlMapperDiscoveryFailure {
    MALFORMED_XML,
    UNSAFE_DTD,
    INVALID_ROOT,
    MISSING_NAMESPACE,
    INVALID_DECLARATION,
    DUPLICATE_STATEMENT_ID,
    DUPLICATE_FRAGMENT_ID,
    INVALID_INCLUDE,
    UNMAPPABLE_SOURCE_RANGE,
}

sealed interface XmlMapperDeclarationRef {
    val id: String

    data class Statement(
        override val id: String,
        val kind: StatementKind,
    ) : XmlMapperDeclarationRef

    data class Fragment(
        override val id: String,
    ) : XmlMapperDeclarationRef
}

data class XmlMapperStatementDeclaration(
    val id: String,
    val kind: StatementKind,
    val sourceRange: SourceRange,
)

data class XmlMapperFragmentDeclaration(
    val id: String,
    val sourceRange: SourceRange,
)

data class XmlMapperIncludeReference(
    val refid: String,
    val owner: XmlMapperDeclarationRef,
    val sourceRange: SourceRange,
)

enum class XmlUnsupportedSemanticsKind {
    DATABASE_ID,
    LANGUAGE_DRIVER,
}

data class XmlUnsupportedSemanticsEvidence(
    val kind: XmlUnsupportedSemanticsKind,
    val elementName: String,
    val value: String,
    val sourceRange: SourceRange,
)

class XmlMapperDocumentDiscovery(
    val sourceFileId: SourceFileId,
    val sourceRevision: SourceRevision,
    val namespace: String,
    statements: List<XmlMapperStatementDeclaration>,
    fragments: List<XmlMapperFragmentDeclaration>,
    includes: List<XmlMapperIncludeReference>,
    unsupportedSemantics: List<XmlUnsupportedSemanticsEvidence>,
) {
    private val statementSnapshot = statements.toList()
    private val fragmentSnapshot = fragments.toList()
    private val includeSnapshot = includes.toList()
    private val unsupportedSemanticsSnapshot = unsupportedSemantics.toList()

    val statements: List<XmlMapperStatementDeclaration>
        get() = statementSnapshot.toList()

    val fragments: List<XmlMapperFragmentDeclaration>
        get() = fragmentSnapshot.toList()

    val includes: List<XmlMapperIncludeReference>
        get() = includeSnapshot.toList()

    val unsupportedSemantics: List<XmlUnsupportedSemanticsEvidence>
        get() = unsupportedSemanticsSnapshot.toList()
}

object XmlMapperSourceDiscovery {
    fun discover(snapshot: SourceSnapshot): XmlMapperDiscoveryResult {
        val startTags = scanStartTags(snapshot.content)
            ?: return XmlMapperDiscoveryResult.Failed(XmlMapperDiscoveryFailure.MALFORMED_XML)
        val reader = try {
            secureInputFactory().createXMLStreamReader(StringReader(snapshot.content))
        } catch (_: XMLStreamException) {
            return XmlMapperDiscoveryResult.Failed(XmlMapperDiscoveryFailure.MALFORMED_XML)
        }

        return try {
            discover(snapshot, reader, startTags)
        } catch (_: XMLStreamException) {
            XmlMapperDiscoveryResult.Failed(XmlMapperDiscoveryFailure.MALFORMED_XML)
        } finally {
            try {
                reader.close()
            } catch (_: XMLStreamException) {
                // Parsing has already completed or failed; closing must not replace the source outcome.
            }
        }
    }

    private fun secureInputFactory(): XMLInputFactory =
        XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
            setProperty(XMLInputFactory.IS_VALIDATING, false)
            setProperty(XMLInputFactory.SUPPORT_DTD, true)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            xmlResolver = javax.xml.stream.XMLResolver { _, _, _, _ -> StringReader("") }
        }

    private fun discover(
        snapshot: SourceSnapshot,
        reader: XMLStreamReader,
        startTags: List<LexicalStartTag>,
    ): XmlMapperDiscoveryResult {
        val statements = mutableListOf<XmlMapperStatementDeclaration>()
        val fragments = mutableListOf<XmlMapperFragmentDeclaration>()
        val includes = mutableListOf<XmlMapperIncludeReference>()
        val unsupportedSemantics = mutableListOf<XmlUnsupportedSemanticsEvidence>()
        val statementIds = mutableSetOf<String>()
        val fragmentIds = mutableSetOf<String>()

        var namespace: String? = null
        var depth = 0
        var rootSeen = false
        var currentOwner: XmlMapperDeclarationRef? = null
        var currentOwnerDepth = -1
        var startTagIndex = 0

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.DTD -> {
                    if (reader.text.contains('[') || reader.text.contains("<!ENTITY")) {
                        return failed(XmlMapperDiscoveryFailure.UNSAFE_DTD)
                    }
                }

                XMLStreamConstants.START_ELEMENT -> {
                    depth += 1
                    val localName = reader.localName
                    val qualifiedName = if (reader.prefix.isEmpty()) localName else "${reader.prefix}:$localName"
                    val startTag = startTags.getOrNull(startTagIndex++)
                        ?: return failed(XmlMapperDiscoveryFailure.UNMAPPABLE_SOURCE_RANGE)
                    if (startTag.qualifiedName != qualifiedName) {
                        return failed(XmlMapperDiscoveryFailure.UNMAPPABLE_SOURCE_RANGE)
                    }
                    val sourceRange = startTag.sourceRange

                    collectUnsupportedEvidence(reader, localName, sourceRange, unsupportedSemantics)

                    if (depth == 1) {
                        if (rootSeen || localName != "mapper" || !isUnqualifiedElement(reader)) {
                            return failed(XmlMapperDiscoveryFailure.INVALID_ROOT)
                        }
                        val mapperNamespace = reader.getAttributeValue(null, "namespace")
                        if (mapperNamespace == null || mapperNamespace.isBlank()) {
                            return failed(XmlMapperDiscoveryFailure.MISSING_NAMESPACE)
                        }
                        namespace = mapperNamespace
                        rootSeen = true
                        continue
                    }

                    if (!rootSeen || localName == "mapper") {
                        return failed(XmlMapperDiscoveryFailure.INVALID_ROOT)
                    }

                    val statementKind = statementKind(localName)
                    if (statementKind != null || localName == "sql") {
                        if (depth != 2 || currentOwner != null || !isUnqualifiedElement(reader)) {
                            return failed(XmlMapperDiscoveryFailure.INVALID_DECLARATION)
                        }
                        val id = reader.getAttributeValue(null, "id")
                        if (id == null || id.isBlank()) {
                            return failed(XmlMapperDiscoveryFailure.INVALID_DECLARATION)
                        }

                        currentOwner = if (statementKind != null) {
                            if (!statementIds.add(id)) {
                                return failed(XmlMapperDiscoveryFailure.DUPLICATE_STATEMENT_ID)
                            }
                            statements += XmlMapperStatementDeclaration(id, statementKind, sourceRange)
                            XmlMapperDeclarationRef.Statement(id, statementKind)
                        } else {
                            if (!fragmentIds.add(id)) {
                                return failed(XmlMapperDiscoveryFailure.DUPLICATE_FRAGMENT_ID)
                            }
                            fragments += XmlMapperFragmentDeclaration(id, sourceRange)
                            XmlMapperDeclarationRef.Fragment(id)
                        }
                        currentOwnerDepth = depth
                        continue
                    }

                    if (localName == "include") {
                        if (!isUnqualifiedElement(reader)) {
                            return failed(XmlMapperDiscoveryFailure.INVALID_INCLUDE)
                        }
                        val owner = currentOwner
                            ?: return failed(XmlMapperDiscoveryFailure.INVALID_INCLUDE)
                        val refid = reader.getAttributeValue(null, "refid")
                        if (refid == null || refid.isBlank()) {
                            return failed(XmlMapperDiscoveryFailure.INVALID_INCLUDE)
                        }
                        includes += XmlMapperIncludeReference(refid, owner, sourceRange)
                    }
                }

                XMLStreamConstants.END_ELEMENT -> {
                    if (currentOwner != null && depth == currentOwnerDepth) {
                        currentOwner = null
                        currentOwnerDepth = -1
                    }
                    depth -= 1
                    if (depth < 0) {
                        return failed(XmlMapperDiscoveryFailure.MALFORMED_XML)
                    }
                }
            }
        }

        val mapperNamespace = namespace
            ?: return failed(XmlMapperDiscoveryFailure.INVALID_ROOT)
        if (!rootSeen || depth != 0) {
            return failed(XmlMapperDiscoveryFailure.MALFORMED_XML)
        }
        if (startTagIndex != startTags.size) {
            return failed(XmlMapperDiscoveryFailure.UNMAPPABLE_SOURCE_RANGE)
        }

        return XmlMapperDiscoveryResult.Discovered(
            XmlMapperDocumentDiscovery(
                sourceFileId = snapshot.fileId,
                sourceRevision = snapshot.revision,
                namespace = mapperNamespace,
                statements = statements,
                fragments = fragments,
                includes = includes,
                unsupportedSemantics = unsupportedSemantics,
            ),
        )
    }

    private fun isUnqualifiedElement(reader: XMLStreamReader): Boolean =
        reader.prefix.isEmpty() && reader.namespaceURI.isNullOrEmpty()

    private fun collectUnsupportedEvidence(
        reader: XMLStreamReader,
        localName: String,
        sourceRange: SourceRange,
        target: MutableList<XmlUnsupportedSemanticsEvidence>,
    ) {
        reader.getAttributeValue(null, "databaseId")?.let {
            target += XmlUnsupportedSemanticsEvidence(
                kind = XmlUnsupportedSemanticsKind.DATABASE_ID,
                elementName = localName,
                value = it,
                sourceRange = sourceRange,
            )
        }
        reader.getAttributeValue(null, "lang")?.let {
            target += XmlUnsupportedSemanticsEvidence(
                kind = XmlUnsupportedSemanticsKind.LANGUAGE_DRIVER,
                elementName = localName,
                value = it,
                sourceRange = sourceRange,
            )
        }
    }

    private fun statementKind(localName: String): StatementKind? =
        when (localName) {
            "select" -> StatementKind.SELECT
            "insert" -> StatementKind.INSERT
            "update" -> StatementKind.UPDATE
            "delete" -> StatementKind.DELETE
            else -> null
        }

    private data class LexicalStartTag(
        val qualifiedName: String,
        val sourceRange: SourceRange,
    )

    private fun scanStartTags(content: String): List<LexicalStartTag>? {
        val result = mutableListOf<LexicalStartTag>()
        var offset = 0
        while (offset < content.length) {
            if (content[offset] != '<') {
                offset += 1
                continue
            }

            when {
                content.startsWith("<!--", offset) -> {
                    val end = content.indexOf("-->", offset + 4)
                    if (end < 0) return null
                    offset = end + 3
                }

                content.startsWith("<![CDATA[", offset) -> {
                    val end = content.indexOf("]]>", offset + 9)
                    if (end < 0) return null
                    offset = end + 3
                }

                content.startsWith("<?", offset) -> {
                    val end = content.indexOf("?>", offset + 2)
                    if (end < 0) return null
                    offset = end + 2
                }

                content.startsWith("</", offset) -> {
                    val end = findTagEnd(content, offset + 2) ?: return null
                    offset = end + 1
                }

                content.startsWith("<!", offset) -> {
                    val end = findMarkupDeclarationEnd(content, offset + 2) ?: return null
                    offset = end + 1
                }

                else -> {
                    val nameStart = offset + 1
                    if (nameStart >= content.length) return null
                    var nameEnd = nameStart
                    while (
                        nameEnd < content.length &&
                        !content[nameEnd].isWhitespace() &&
                        content[nameEnd] != '/' &&
                        content[nameEnd] != '>'
                    ) {
                        nameEnd += 1
                    }
                    if (nameEnd == nameStart) return null
                    val end = findTagEnd(content, nameEnd) ?: return null
                    result += LexicalStartTag(
                        qualifiedName = content.substring(nameStart, nameEnd),
                        sourceRange = SourceRange(offset, end + 1),
                    )
                    offset = end + 1
                }
            }
        }
        return result
    }

    private fun findTagEnd(content: String, startOffset: Int): Int? {
        var quote: Char? = null
        var offset = startOffset
        while (offset < content.length) {
            val current = content[offset]
            if (quote == null) {
                when (current) {
                    '\'', '"' -> quote = current
                    '>' -> return offset
                }
            } else if (current == quote) {
                quote = null
            }
            offset += 1
        }
        return null
    }

    private fun findMarkupDeclarationEnd(content: String, startOffset: Int): Int? {
        var quote: Char? = null
        var subsetDepth = 0
        var offset = startOffset
        while (offset < content.length) {
            val current = content[offset]
            if (quote == null) {
                when (current) {
                    '\'', '"' -> quote = current
                    '[' -> subsetDepth += 1
                    ']' -> if (subsetDepth > 0) subsetDepth -= 1
                    '>' -> if (subsetDepth == 0) return offset
                }
            } else if (current == quote) {
                quote = null
            }
            offset += 1
        }
        return null
    }

    private fun failed(reason: XmlMapperDiscoveryFailure): XmlMapperDiscoveryResult =
        XmlMapperDiscoveryResult.Failed(reason)
}
