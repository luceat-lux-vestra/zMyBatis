package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InternalBinding
import com.algorist.zMyBatis.core.input.InternalBindingKind
import java.util.ArrayDeque
import org.apache.ibatis.builder.xml.XMLMapperEntityResolver
import org.apache.ibatis.parsing.XNode
import org.apache.ibatis.parsing.XPathParser

internal object DynamicOgnlAdmission {
    private const val MAX_SCRIPT_LENGTH = 2 * 1024 * 1024
    private const val MAX_DYNAMIC_NODES = 65_536
    private const val MAX_DYNAMIC_DEPTH = 64

    private val reservedContextNames = setOf("_parameter", "_databaseId")

    fun inspect(
        script: String,
        callerRootProperties: Set<String>,
        internalBindings: List<InternalBinding>,
    ): Result {
        if (script.length > MAX_SCRIPT_LENGTH) return Result.Unsupported("DynamicScript[length]")

        val root = try {
            XPathParser(script, false, null, XMLMapperEntityResolver()).evalNode("/script")
        } catch (failure: StackOverflowError) {
            return Result.Unsupported("DynamicScript[parser-depth]")
        } catch (failure: RuntimeException) {
            return Result.MalformedScript(failure)
        } ?: return Result.MalformedScript(IllegalArgumentException("Dynamic script root is unavailable"))

        val sourceBindNames = linkedSetOf<String>()
        val expressions = mutableListOf<String>()
        val inspected = inspectElements(
            root,
            callerRootProperties,
            internalBindings,
            sourceBindNames,
            expressions,
        )
        if (inspected !is Result.Admitted) return inspected

        val contractBindNames = internalBindings
            .filter { it.kind == InternalBindingKind.BIND }
            .map { it.name }
        if (contractBindNames.size != contractBindNames.toSet().size) {
            return Result.BindAuthority(null, BindAuthorityProblem.AMBIGUOUS)
        }
        if (sourceBindNames != contractBindNames.toSet()) {
            return Result.BindAuthority(null, BindAuthorityProblem.SOURCE_CONTRACT_MISMATCH)
        }

        val admittedCallerRoots = callerRootProperties - reservedContextNames
        return when (val admission = IsolatedOgnlAstAdmission.inspect(expressions, admittedCallerRoots)) {
            IsolatedOgnlAstAdmission.Result.Admitted -> Result.Admitted
            is IsolatedOgnlAstAdmission.Result.Unsupported -> Result.Unsupported(admission.nodeType)
            is IsolatedOgnlAstAdmission.Result.UnprovenProperty -> Result.UnprovenProperty(admission.property)
            is IsolatedOgnlAstAdmission.Result.Malformed -> Result.MalformedExpression(admission.diagnosticType)
            is IsolatedOgnlAstAdmission.Result.Invariant -> Result.Invariant(admission.diagnosticType)
        }
    }

    private fun inspectElements(
        root: XNode,
        callerRootProperties: Set<String>,
        internalBindings: List<InternalBinding>,
        sourceBindNames: MutableSet<String>,
        expressions: MutableList<String>,
    ): Result {
        val pending = ArrayDeque<Pair<XNode, Int>>()
        pending.addLast(root to 0)
        var visitedNodes = 0

        while (pending.isNotEmpty()) {
            if (++visitedNodes > MAX_DYNAMIC_NODES) {
                return Result.Unsupported("DynamicScript[node-budget]")
            }

            val (node, depth) = pending.removeLast()
            if (depth > MAX_DYNAMIC_DEPTH) {
                return Result.Unsupported("DynamicScript[depth]")
            }

            when (node.name) {
                "if", "when" -> {
                    val expression = node.getStringAttribute("test")
                        ?: return Result.MalformedScript(
                            IllegalArgumentException("dynamic test expression is unavailable"),
                        )
                    expressions += expression
                }
                "bind" -> {
                    val bindResult = inspectBindAuthority(
                        node,
                        callerRootProperties,
                        internalBindings,
                        sourceBindNames,
                    )
                    if (bindResult !is Result.Admitted) return bindResult
                    val expression = node.getStringAttribute("value")
                        ?: return Result.MalformedScript(IllegalArgumentException("bind value is unavailable"))
                    expressions += expression
                }
            }

            val children = node.children
            for (index in children.indices.reversed()) {
                pending.addLast(children[index] to depth + 1)
            }
        }

        return Result.Admitted
    }

    private fun inspectBindAuthority(
        node: XNode,
        callerRootProperties: Set<String>,
        internalBindings: List<InternalBinding>,
        sourceBindNames: MutableSet<String>,
    ): Result {
        val name = node.getStringAttribute("name")
            ?: return Result.MalformedScript(IllegalArgumentException("bind name is unavailable"))
        val expression = node.getStringAttribute("value")
            ?: return Result.MalformedScript(IllegalArgumentException("bind value is unavailable"))
        if (name in reservedContextNames) {
            return Result.BindAuthority(name, BindAuthorityProblem.RESERVED_CONTEXT)
        }
        if (!sourceBindNames.add(name)) return Result.BindAuthority(name, BindAuthorityProblem.AMBIGUOUS)
        if (name in callerRootProperties) return Result.BindAuthority(name, BindAuthorityProblem.AMBIGUOUS)

        val candidates = internalBindings.filter { it.name == name }
        if (candidates.isEmpty()) return Result.BindAuthority(name, BindAuthorityProblem.MISSING)
        if (candidates.size != 1) return Result.BindAuthority(name, BindAuthorityProblem.AMBIGUOUS)
        val binding = candidates.single()
        if (binding.kind != InternalBindingKind.BIND) {
            return Result.BindAuthority(name, BindAuthorityProblem.KIND_UNSUPPORTED)
        }
        val evidence = binding.provenance.evidence.filterIsInstance<InputEvidence.BindLocal>()
        if (
            evidence.size != 1 ||
            evidence.single().name != name ||
            evidence.single().expression != expression
        ) {
            return Result.BindAuthority(name, BindAuthorityProblem.SOURCE_CONTRACT_MISMATCH)
        }
        return Result.Admitted
    }

    enum class BindAuthorityProblem {
        MISSING,
        AMBIGUOUS,
        KIND_UNSUPPORTED,
        SOURCE_CONTRACT_MISMATCH,
        RESERVED_CONTEXT,
    }

    sealed interface Result {
        data object Admitted : Result

        data class Unsupported(
            val nodeType: String,
        ) : Result

        data class UnprovenProperty(
            val property: String,
        ) : Result

        data class BindAuthority(
            val name: String?,
            val problem: BindAuthorityProblem,
        ) : Result

        data class MalformedScript(
            val failure: Throwable,
        ) : Result

        data class MalformedExpression(
            val diagnosticType: String,
        ) : Result

        data class Invariant(
            val diagnosticType: String,
        ) : Result
    }
}
