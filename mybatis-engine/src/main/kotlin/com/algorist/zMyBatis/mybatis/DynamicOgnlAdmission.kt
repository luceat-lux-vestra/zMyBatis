package com.algorist.zMyBatis.mybatis

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
        allowedRootProperties: Set<String>,
    ): Result {
        val root = try {
            XPathParser(script, false, null, XMLMapperEntityResolver()).evalNode("/script")
        } catch (failure: RuntimeException) {
            return Result.MalformedScript(failure)
        } ?: return Result.MalformedScript(IllegalArgumentException("Dynamic script root is unavailable"))

        return inspectElements(root, allowedRootProperties)
    }

    private fun inspectElements(
        node: XNode,
        allowedRootProperties: Set<String>,
    ): Result {
        val expression = when (node.name.lowercase()) {
            "if", "when" -> node.getStringAttribute("test")
            "bind" -> node.getStringAttribute("value")
            "foreach" -> node.getStringAttribute("collection")
            else -> null
        }
        if (expression != null) {
            val result = inspectExpression(expression, allowedRootProperties)
            if (result !is Result.Admitted) return result
        }
        for (child in node.children) {
            val result = inspectElements(child, allowedRootProperties)
            if (result !is Result.Admitted) return result
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

    sealed interface Result {
        data object Admitted : Result

        data class Unsupported(
            val nodeType: String,
        ) : Result

        data class UnprovenProperty(
            val property: String,
        ) : Result

        data class MalformedScript(
            val failure: Throwable,
        ) : Result

        data class MalformedExpression(
            val failure: Throwable,
        ) : Result
    }
}
