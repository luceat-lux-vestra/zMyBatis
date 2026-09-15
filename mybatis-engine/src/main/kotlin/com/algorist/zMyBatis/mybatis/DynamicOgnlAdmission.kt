package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InternalBinding
import com.algorist.zMyBatis.core.input.InternalBindingKind
import org.apache.ibatis.builder.xml.XMLMapperEntityResolver
import org.apache.ibatis.ognl.ASTConst
import org.apache.ibatis.ognl.ASTProperty
import org.apache.ibatis.ognl.Node
import org.apache.ibatis.ognl.Ognl
import org.apache.ibatis.parsing.XNode
import org.apache.ibatis.parsing.XPathParser

internal object DynamicOgnlAdmission {
    private val allowedNodeTypes = setOf(
        "ASTAdd",
        "ASTAnd",
        "ASTChain",
        "ASTConst",
        "ASTDivide",
        "ASTEq",
        "ASTGreater",
        "ASTGreaterEq",
        "ASTLess",
        "ASTLessEq",
        "ASTMultiply",
        "ASTNegate",
        "ASTNot",
        "ASTNotEq",
        "ASTOr",
        "ASTProperty",
        "ASTRemainder",
        "ASTSubtract",
    )

    fun inspect(
        script: String,
        callerRootProperties: Set<String>,
        internalBindings: List<InternalBinding>,
    ): Result {
        val root = try {
            XPathParser(script, false, null, XMLMapperEntityResolver()).evalNode("/script")
        } catch (failure: RuntimeException) {
            return Result.MalformedScript(failure)
        } ?: return Result.MalformedScript(IllegalArgumentException("Dynamic script root is unavailable"))

        val sourceBindNames = linkedSetOf<String>()
        val inspected = inspectElements(root, callerRootProperties, internalBindings, sourceBindNames)
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
        return Result.Admitted
    }

    private fun inspectElements(
        node: XNode,
        callerRootProperties: Set<String>,
        internalBindings: List<InternalBinding>,
        sourceBindNames: MutableSet<String>,
    ): Result {
        // Positive foreach support is outside #143 because generated item/index authority is not yet
        // modeled.  Refuse the tag itself before MyBatis can iterate or synthesize __frch_* locals.
        if (node.name == "foreach") return Result.Unsupported("DynamicTag[foreach]")

        val expression = when (node.name) {
            "if", "when" -> node.getStringAttribute("test")
            "bind" -> {
                val bindResult = inspectBindAuthority(node, callerRootProperties, internalBindings, sourceBindNames)
                if (bindResult !is Result.Admitted) return bindResult
                node.getStringAttribute("value")
            }
            else -> null
        }
        if (expression != null) {
            val result = inspectExpression(expression, callerRootProperties)
            if (result !is Result.Admitted) return result
        }
        for (child in node.children) {
            val result = inspectElements(child, callerRootProperties, internalBindings, sourceBindNames)
            if (result !is Result.Admitted) return result
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

    private fun inspectExpression(
        expression: String,
        allowedRootProperties: Set<String>,
    ): Result {
        val parsed = try {
            Ognl.parseExpression(expression)
        } catch (failure: Exception) {
            return Result.MalformedExpression(failure)
        }
        val root = parsed as? Node
            ?: return Result.Unsupported("non-node-expression")
        return inspectNode(root, allowedRootProperties, rootProperty = root is ASTProperty)
    }

    private fun inspectNode(
        node: Node,
        allowedRootProperties: Set<String>,
        rootProperty: Boolean,
    ): Result {
        val nodeType = node.javaClass.simpleName
        if (nodeType !in allowedNodeTypes) return Result.Unsupported(nodeType)

        if (node is ASTProperty) {
            if (node.isIndexedAccess) return Result.Unsupported("ASTProperty[indexed]")
            if (node.jjtGetNumChildren() != 1) return Result.Unsupported("ASTProperty[shape]")
            val property = (node.jjtGetChild(0) as? ASTConst)?.value as? String
                ?: return Result.Unsupported("ASTProperty[dynamic]")
            if (property == "class") return Result.Unsupported("ASTProperty[class]")
            if (rootProperty && property !in allowedRootProperties) {
                return Result.UnprovenProperty(property)
            }
        }

        if (nodeType == "ASTChain") {
            for (index in 0 until node.jjtGetNumChildren()) {
                val child = node.jjtGetChild(index)
                val result = inspectNode(
                    child,
                    allowedRootProperties,
                    rootProperty = index == 0 && child is ASTProperty,
                )
                if (result !is Result.Admitted) return result
            }
            return Result.Admitted
        }

        for (index in 0 until node.jjtGetNumChildren()) {
            val child = node.jjtGetChild(index)
            val result = inspectNode(
                child,
                allowedRootProperties,
                rootProperty = child is ASTProperty,
            )
            if (result !is Result.Admitted) return result
        }
        return Result.Admitted
    }

    enum class BindAuthorityProblem {
        MISSING,
        AMBIGUOUS,
        KIND_UNSUPPORTED,
        SOURCE_CONTRACT_MISMATCH,
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
            val failure: Throwable,
        ) : Result
    }
}
