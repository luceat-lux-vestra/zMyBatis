package com.algorist.zMyBatis.mybatis

import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import org.apache.ibatis.session.Configuration

/**
 * Parses and structurally inspects OGNL in a disposable MyBatis classloader without evaluating it.
 *
 * Keeping even parsing inside the child avoids depending on parent OGNL static configuration such
 * as expressionMaxLength. Only node type names and JDK String constants cross the boundary.
 */
internal object IsolatedOgnlAstAdmission {
    private const val OGNL = "org.apache.ibatis.ognl.Ognl"
    private const val EXPRESSION_SYNTAX = "org.apache.ibatis.ognl.ExpressionSyntaxException"

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
    private val platformLoader = ClassLoader.getPlatformClassLoader()

    fun inspect(
        expressions: List<String>,
        allowedRootProperties: Set<String>,
    ): Result {
        if (expressions.isEmpty()) return Result.Admitted

        val myBatisLocation = Configuration::class.java.protectionDomain?.codeSource?.location
            ?: return Result.Invariant("mybatis-code-source-unavailable")
        if (myBatisLocation.protocol != "file") {
            return Result.Invariant("mybatis-code-source-non-local")
        }

        return try {
            URLClassLoader(arrayOf(myBatisLocation), platformLoader).use { loader ->
                val ognlClass = Class.forName(OGNL, true, loader)
                if (ognlClass.classLoader !== loader) {
                    return Result.Invariant("mybatis-ognl-parser-not-isolated")
                }
                val parseExpression = ognlClass.getMethod("parseExpression", String::class.java)

                for (expression in expressions) {
                    val root = try {
                        parseExpression.invoke(null, expression)
                    } catch (failure: InvocationTargetException) {
                        val target = failure.targetException ?: failure
                        rethrowFatal(target)
                        return if (target.javaClass.name == EXPRESSION_SYNTAX) {
                            Result.Malformed(target.javaClass.name)
                        } else {
                            Result.Invariant(target.javaClass.name)
                        }
                    }
                    if (root == null || root.javaClass.classLoader !== loader) {
                        return Result.Invariant("mybatis-ognl-ast-not-isolated")
                    }
                    val inspected = inspectNode(
                        root,
                        allowedRootProperties,
                        rootProperty = root.javaClass.simpleName == "ASTProperty",
                    )
                    if (inspected !is Result.Admitted) return inspected
                }
                Result.Admitted
            }
        } catch (failure: Throwable) {
            rethrowFatal(failure)
            Result.Invariant(deepestDiagnosticType(failure))
        }
    }

    private fun inspectNode(
        node: Any,
        allowedRootProperties: Set<String>,
        rootProperty: Boolean,
    ): Result {
        val nodeType = node.javaClass.simpleName
        if (nodeType !in allowedNodeTypes) return Result.Unsupported(nodeType)

        if (nodeType == "ASTProperty") {
            val indexed = node.javaClass.getMethod("isIndexedAccess").invoke(node) as Boolean
            if (indexed) return Result.Unsupported("ASTProperty[indexed]")
            val childCount = childCount(node)
            if (childCount != 1) return Result.Unsupported("ASTProperty[shape]")
            val constant = child(node, 0)
            if (constant.javaClass.simpleName != "ASTConst") {
                return Result.Unsupported("ASTProperty[dynamic]")
            }
            val property = constant.javaClass.getMethod("getValue").invoke(constant) as? String
                ?: return Result.Unsupported("ASTProperty[dynamic]")
            if (property == "class") return Result.Unsupported("ASTProperty[class]")
            if (rootProperty && property !in allowedRootProperties) {
                return Result.UnprovenProperty(property)
            }
        }

        if (nodeType == "ASTChain") {
            for (index in 0 until childCount(node)) {
                val current = child(node, index)
                val result = inspectNode(
                    current,
                    allowedRootProperties,
                    rootProperty = index == 0 && current.javaClass.simpleName == "ASTProperty",
                )
                if (result !is Result.Admitted) return result
            }
            return Result.Admitted
        }

        for (index in 0 until childCount(node)) {
            val current = child(node, index)
            val result = inspectNode(
                current,
                allowedRootProperties,
                rootProperty = current.javaClass.simpleName == "ASTProperty",
            )
            if (result !is Result.Admitted) return result
        }
        return Result.Admitted
    }

    private fun childCount(node: Any): Int =
        node.javaClass.getMethod("jjtGetNumChildren").invoke(node) as Int

    private fun child(node: Any, index: Int): Any =
        node.javaClass
            .getMethod("jjtGetChild", Int::class.javaPrimitiveType)
            .invoke(node, index)
            ?: error("OGNL AST child is unavailable")

    private fun deepestDiagnosticType(failure: Throwable): String =
        generateSequence(failure) { it.cause }.lastOrNull()?.javaClass?.name ?: failure.javaClass.name

    private fun rethrowFatal(failure: Throwable) {
        val fatal = generateSequence(failure) { it.cause }
            .firstOrNull { it is VirtualMachineError || it is ThreadDeath }
        if (fatal != null) throw fatal
    }

    sealed interface Result {
        data object Admitted : Result

        data class Unsupported(
            val nodeType: String,
        ) : Result

        data class UnprovenProperty(
            val property: String,
        ) : Result

        data class Malformed(
            val diagnosticType: String,
        ) : Result

        data class Invariant(
            val diagnosticType: String,
        ) : Result
    }
}
