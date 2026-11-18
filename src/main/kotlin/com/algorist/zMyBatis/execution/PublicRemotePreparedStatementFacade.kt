package com.algorist.zMyBatis.execution

import com.intellij.database.remote.jdbc.RemotePreparedStatement
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.PreparedStatement

/**
 * JDK facade used only to let stock MyBatis TypeHandlers invoke JDBC PreparedStatement methods.
 *
 * The forwarding contract is intentionally narrow: the target method must exist with the exact
 * public signature on the maintained Database Tools RemotePreparedStatement interface. Runtime
 * implementation classes, delegates, fields and internal Database Tools APIs are never inspected.
 */
internal object PublicRemotePreparedStatementFacade {
    fun create(remote: RemotePreparedStatement): PreparedStatement =
        Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
        ) { proxy, method, args ->
            if (method.declaringClass == Any::class.java) {
                return@newProxyInstance when (method.name) {
                    "toString" -> "PublicRemotePreparedStatementFacade"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.singleOrNull()
                    else -> error("unsupported Object method: ${method.name}")
                }
            }

            val target = exactRemoteMethod(method)
                ?: throw UnsupportedOperationException(
                    "RemotePreparedStatement does not expose public exact signature " +
                        renderMethodSignature(method),
                )
            try {
                target.invoke(remote, *(args ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as PreparedStatement

    internal fun exactRemoteMethod(method: Method): Method? =
        try {
            RemotePreparedStatement::class.java.getMethod(method.name, *method.parameterTypes)
        } catch (_: NoSuchMethodException) {
            null
        }

    private fun renderMethodSignature(method: Method): String =
        "${method.name}(${method.parameterTypes.joinToString(",") { it.name }})"
}
