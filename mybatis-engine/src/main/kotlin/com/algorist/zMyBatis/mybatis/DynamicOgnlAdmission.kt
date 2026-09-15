package com.algorist.zMyBatis.mybatis

import org.apache.ibatis.builder.xml.XMLMapperEntityResolver
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

    fun inspect(script: String): Result {
        val root = try {
            XPathParser(script, false, null, XMLMapperEntityResolver()).evalNode("/script")
        } catch (failure: RuntimeException) {
            return Result.Malformed(failure)
        } ?: return Result.Malformed(IllegalArgumentException("Dynamic script root is unavailable"))

        return inspectElements(root)
    }

    private fun inspectElements(node: XNode): Result {
        val expression = when (node.name.lowercase()) {
            "if", "when" -> node.getStringAttribute("test")
            "bind" -> node.getStringAttribute("value")
            "foreach" -> node.getStringAttribute("collection")
            else -> null
        }
        if (expression != null) {
            val result = inspectExpression(expression)
            if (result !is Result.Admitted) return result
        }
        for (child in node.children) {
            val result = inspectElements(child)
            if (result !is Result.Admitted) return result
        }
        return Result.Admitted
    }

    private fun inspectExpression(expression: String): Result {
        val parsed = try {
            Ognl.parseExpression(expression)
        } catch (failure: Exception) {
            return Result.Malformed(failure)
        }
        val root = parsed as? Node
            ?: return Result.Unsupported("non-node-expression")
        return inspectNode(root)
    }

    private fun inspectNode(node: Node): Result {
        val nodeType = node.javaClass.simpleName
        if (nodeType !in allowedNodeTypes) return Result.Unsupported(nodeType)
        for (index in 0 until node.jjtGetNumChildren()) {
            val child = node.jjtGetChild(index)
            val result = inspectNode(child)
            if (result !is Result.Admitted) return result
        }
        return Result.Admitted
    }

    sealed interface Result {
        data object Admitted : Result

        data class Unsupported(
            val nodeType: String,
        ) : Result

        data class Malformed(
            val failure: Throwable,
        ) : Result
    }
}
