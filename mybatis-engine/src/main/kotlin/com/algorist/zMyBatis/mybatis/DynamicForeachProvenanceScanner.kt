package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InternalBindingKind
import java.util.ArrayDeque
import org.apache.ibatis.builder.xml.XMLMapperEntityResolver
import org.apache.ibatis.parsing.XNode
import org.apache.ibatis.parsing.XPathParser

/**
 * Extracts foreach local declarations for binding provenance only.
 *
 * This scanner never admits or rejects MyBatis semantics. Stock MyBatis still owns foreach
 * evaluation and expansion; the extracted names only let the post-runtime snapshot distinguish
 * caller mappings from values captured from foreach-local scope.
 */
internal object DynamicForeachProvenanceScanner {
    fun forScript(script: String): Map<String, InternalBindingKind> =
        if (script.startsWith("<script>")) {
            scanDocuments(listOf(script), "/script")
        } else {
            emptyMap()
        }

    fun forMapperDocuments(contents: List<String>): Map<String, InternalBindingKind> =
        scanDocuments(contents, "/mapper")

    private fun scanDocuments(
        contents: List<String>,
        rootPath: String,
    ): Map<String, InternalBindingKind> {
        val locals = linkedMapOf<String, InternalBindingKind>()
        val ambiguous = linkedSetOf<String>()

        for (content in contents) {
            val root = try {
                XPathParser(content, false, null, XMLMapperEntityResolver()).evalNode(rootPath)
            } catch (_: RuntimeException) {
                continue
            } ?: continue

            val pending = ArrayDeque<XNode>()
            pending.addLast(root)
            while (pending.isNotEmpty()) {
                val node = pending.removeLast()
                if (node.name == "foreach") {
                    node.getStringAttribute("item")
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let { register(locals, ambiguous, it, InternalBindingKind.FOREACH_ITEM) }
                    node.getStringAttribute("index")
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let { register(locals, ambiguous, it, InternalBindingKind.FOREACH_INDEX) }
                }

                val children = node.children
                for (index in children.indices.reversed()) {
                    pending.addLast(children[index])
                }
            }
        }

        return locals.filterKeys { it !in ambiguous }
    }

    private fun register(
        locals: MutableMap<String, InternalBindingKind>,
        ambiguous: MutableSet<String>,
        name: String,
        kind: InternalBindingKind,
    ) {
        if (name in ambiguous) return
        val previous = locals[name]
        when {
            previous == null -> locals[name] = kind
            previous != kind -> {
                locals.remove(name)
                ambiguous += name
            }
        }
    }
}
