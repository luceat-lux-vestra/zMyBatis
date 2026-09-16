package com.algorist.zMyBatis.mybatis

import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.ArrayDeque
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
    private const val MAX_EXPRESSION_LENGTH = 65_536
    private const val MAX_AST_NODES = 131_072
    private const val MAX_AST_DEPTH = 256

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
        if (expressions.any { it.length > MAX_EXPRESSION_LENGTH }) {
            return Result.Unsupported("OGNL[expression-length]")
        }

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
                        if (target is StackOverflowError) {
                            return Result.Unsupported("OGNL[parser-depth]")
                        }
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
                    val inspected = inspectTree(root, allowedRootProperties)
                    if (inspected !is Result.Admitted) return inspected
                }
                Result.Admitted
            }
        } catch (failure: Throwable) {
            if (throwableChain(failure).any { it is StackOverflowError }) {
                return Result.Unsupported("OGNL[parser-depth]")
            }
            rethrowFatal(failure)
            Result.Invariant(deepestDiagnosticType(failure))
        }
    }

    private fun inspectTree(
        root: Any,
        allowedRootProperties: Set<String>,
    ): Result {
        val pending = ArrayDeque<PendingNode>()
        pending.addLast(PendingNode(root, root.javaClass.simpleName == "ASTProperty", depth = 1))
        var visitedNodes = 0

        while (pending.isNotEmpty()) {
            if (++visitedNodes > MAX_AST_NODES) {
                return Result.Unsupported("OGNL[ast-node-budget]")
            }

            val current = pending.removeLast()
            if (current.depth > MAX_AST_DEPTH) {
                return Result.Unsupported("OGNL[ast-depth]")
            }

            val node = current.node
            val nodeType = node.javaClass.simpleName
            if (nodeType !in allowedNodeTypes) return Result.Unsupported(nodeType)

            if (nodeType == "ASTProperty") {
                val indexed = node.javaClass.getMethod("isIndexedAccess").invoke(node) as Boolean
                if (indexed) return Result.Unsupported("ASTProperty[indexed]")
                val count = childCount(node)
                if (count != 1) return Result.Unsupported("ASTProperty[shape]")
                val constant = child(node, 0)
                if (constant.javaClass.simpleName != "ASTConst") {
                    return Result.Unsupported("ASTProperty[dynamic]")
                }
                val property = constant.javaClass.getMethod("getValue").invoke(constant) as? String
                    ?: return Result.Unsupported("ASTProperty[dynamic]")
                if (property == "class") return Result.Unsupported("ASTProperty[class]")
                if (current.rootProperty && property !in allowedRootProperties) {
                    return Result.UnprovenProperty(property)
                }
            }

            val count = childCount(node)
            for (index in count - 1 downTo 0) {
                val currentChild = child(node, index)
                val childIsProperty = currentChild.javaClass.simpleName == "ASTProperty"
                val childIsRoot = if (nodeType == "ASTChain") {
                    index == 0 && childIsProperty
                } else {
                    childIsProperty
                }
                pending.addLast(
                    PendingNode(
                        node = currentChild,
                        rootProperty = childIsRoot,
                        depth = current.depth + 1,
                    ),
                )
            }
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

    private fun throwableChain(failure: Throwable): List<Throwable> =
        generateSequence(failure) { it.cause }.toList()

    private fun deepestDiagnosticType(failure: Throwable): String =
        throwableChain(failure).lastOrNull()?.javaClass?.name ?: failure.javaClass.name

    private fun rethrowFatal(failure: Throwable) {
        val fatal = throwableChain(failure)
            .firstOrNull {
                (it is VirtualMachineError && it !is StackOverflowError) ||
                    it.javaClass.name == "java.lang.ThreadDeath"
            }
        if (fatal != null) throw fatal
    }

    private data class PendingNode(
        val node: Any,
        val rootProperty: Boolean,
        val depth: Int,
    )

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
