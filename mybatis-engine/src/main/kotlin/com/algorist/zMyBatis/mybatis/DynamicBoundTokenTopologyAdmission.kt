package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import java.util.ArrayDeque
import org.apache.ibatis.builder.xml.XMLMapperEntityResolver
import org.apache.ibatis.parsing.GenericTokenParser
import org.apache.ibatis.parsing.XNode
import org.apache.ibatis.parsing.XPathParser
import org.w3c.dom.CharacterData
import org.w3c.dom.Node

/**
 * Proves a narrow structural invariant before DynamicSqlSource evaluation: every MyBatis bound
 * token opener that can reach the rendered SQL must already be complete inside one authoritative
 * source fragment.
 *
 * Dynamic tags concatenate independently parsed text and structural attributes. Without this gate,
 * fragments such as foreach `open="#{"` plus body text `id}` can synthesize a new `#{id}` mapping
 * that did not exist as one source placeholder. This admission does not evaluate dynamic SQL; it
 * only rejects fragments whose residual token metasyntax could create, escape, or extend a bound
 * token when MyBatis later combines fragments.
 */
internal object DynamicBoundTokenTopologyAdmission {
    private const val TOPOLOGY_UNSUPPORTED = "java-annotation-dynamic-bound-token-topology-unsupported"
    private const val PARSE_FAILURE = "mybatis-sql-source-parse-failure"
    private const val MAX_SCRIPT_LENGTH = 2 * 1024 * 1024
    private const val MAX_NODES = 131_072
    private const val MAX_DEPTH = 128

    private val structuralSqlAttributes = mapOf(
        "foreach" to setOf("open", "close", "separator"),
        "trim" to setOf("prefix", "suffix", "prefixOverrides", "suffixOverrides"),
    )
    private val boundTokenParser = GenericTokenParser("#{", "}") { "x" }

    fun failureOrNull(script: String): PreparationFailure? {
        if (script.length > MAX_SCRIPT_LENGTH) return unsupported()

        val root = try {
            XPathParser(script, false, null, XMLMapperEntityResolver()).evalNode("/script")
        } catch (_: StackOverflowError) {
            return unsupported()
        } catch (failure: RuntimeException) {
            return PreparationFailure(
                kind = PreparationFailureKind.MYBATIS_PARSE,
                code = PARSE_FAILURE,
                diagnosticType = failure.javaClass.name,
            )
        } ?: return PreparationFailure(
            kind = PreparationFailureKind.MYBATIS_PARSE,
            code = PARSE_FAILURE,
            diagnosticType = IllegalArgumentException::class.java.name,
        )

        val pending = ArrayDeque<PendingNode>()
        pending.addLast(PendingNode(root.node, depth = 0))
        var visited = 0

        while (pending.isNotEmpty()) {
            if (++visited > MAX_NODES) return unsupported()
            val current = pending.removeLast()
            if (current.depth > MAX_DEPTH) return unsupported()

            val node = current.node
            when (node.nodeType) {
                Node.TEXT_NODE,
                Node.CDATA_SECTION_NODE,
                -> {
                    val text = (node as CharacterData).data
                    if (!fragmentIsTopologySafe(text)) return unsupported()
                }
                Node.ELEMENT_NODE -> {
                    val xNode = root.newXNode(node)
                    for (attribute in structuralSqlAttributes[xNode.name].orEmpty()) {
                        val value = xNode.getStringAttribute(attribute) ?: continue
                        if (!fragmentIsTopologySafe(value)) return unsupported()
                    }
                }
            }

            val children = node.childNodes
            if (children != null) {
                for (index in children.length - 1 downTo 0) {
                    pending.addLast(PendingNode(children.item(index), current.depth + 1))
                }
            }
        }

        return null
    }

    private fun fragmentIsTopologySafe(fragment: String): Boolean {
        val residual = boundTokenParser.parse(fragment)
        return residual.none { it == '#' || it == '{' || it == '\\' }
    }

    private fun unsupported() = PreparationFailure(
        kind = PreparationFailureKind.UNSUPPORTED_SEMANTIC,
        code = TOPOLOGY_UNSUPPORTED,
    )

    private data class PendingNode(
        val node: Node,
        val depth: Int,
    )
}
