package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.preparation.PreparedBinding
import com.algorist.zMyBatis.mybatis.MyBatisPreparedBindingRuntimeAdapter
import java.sql.PreparedStatement
import java.sql.SQLException
import org.apache.ibatis.type.JdbcType
import org.apache.ibatis.type.ObjectTypeHandler
import org.apache.ibatis.type.TypeException
import org.apache.ibatis.type.TypeHandler
import org.apache.ibatis.type.TypeHandlerRegistry

private const val PARAMETER_MODE_UNSUPPORTED = "prepared-binding-parameter-mode-unsupported"
private const val TYPE_HANDLER_UNSUPPORTED = "prepared-binding-type-handler-unsupported"
private const val JDBC_TYPE_UNSUPPORTED = "prepared-binding-jdbc-type-unsupported"
private const val TYPE_HANDLER_MISSING = "prepared-binding-type-handler-missing"
private const val TYPE_HANDLER_IDENTITY_MISMATCH = "prepared-binding-type-handler-identity-mismatch"
private const val REMOTE_METHOD_UNSUPPORTED = "prepared-binding-remote-method-unsupported"
private const val TYPE_HANDLER_BIND_FAILED = "prepared-binding-type-handler-bind-failed"

internal enum class PreparedBindingBindFailureKind {
    PARAMETER_MODE_UNSUPPORTED,
    TYPE_HANDLER_UNSUPPORTED,
    RUNTIME_VALUE_UNSUPPORTED,
    JDBC_TYPE_UNSUPPORTED,
    TYPE_HANDLER_MISSING,
    TYPE_HANDLER_IDENTITY_MISMATCH,
    REMOTE_METHOD_UNSUPPORTED,
    TYPE_HANDLER_BIND_FAILED,
}

internal data class PreparedBindingBindFailure(
    val kind: PreparedBindingBindFailureKind,
    val code: String,
    val bindingIndex: Int,
) {
    init {
        require(code.isNotBlank()) { "prepared binding failure code must not be blank" }
        require(bindingIndex >= 0) { "prepared binding failure index must not be negative" }
    }
}

internal sealed interface PreparedBindingBindResult {
    data object Bound : PreparedBindingBindResult
    data class Failed(val failure: PreparedBindingBindFailure) : PreparedBindingBindResult
}

/**
 * Binds one captured PreparedBinding through the stock MyBatis TypeHandler selected by the captured
 * Java/JDBC metadata.
 *
 * No JDBC setter matrix exists here. Standard TypeHandlers own setter choice. Application/custom
 * handlers are outside the 1.0 authority and fail before the statement is touched.
 */
internal object StockMyBatisPreparedBindingBinder {
    fun bind(
        registry: TypeHandlerRegistry,
        statement: PreparedStatement,
        binding: PreparedBinding,
    ): PreparedBindingBindResult {
        if (binding.metadata.parameterMode != "IN") {
            return failed(
                binding,
                PreparedBindingBindFailureKind.PARAMETER_MODE_UNSUPPORTED,
                PARAMETER_MODE_UNSUPPORTED,
            )
        }
        if (!binding.metadata.typeHandlerIdentity.startsWith("org.apache.ibatis.type.")) {
            return failed(
                binding,
                PreparedBindingBindFailureKind.TYPE_HANDLER_UNSUPPORTED,
                TYPE_HANDLER_UNSUPPORTED,
            )
        }

        val runtime = when (val adapted = MyBatisPreparedBindingRuntimeAdapter.adapt(binding)) {
            is MyBatisPreparedBindingRuntimeAdapter.Result.Ready -> adapted
            is MyBatisPreparedBindingRuntimeAdapter.Result.Failed ->
                return failed(
                    binding,
                    PreparedBindingBindFailureKind.RUNTIME_VALUE_UNSUPPORTED,
                    adapted.failure.code,
                )
        }

        val jdbcTypeIdentity = if (runtime.value == null) {
            binding.metadata.effectiveJdbcTypeIdentity
        } else {
            binding.metadata.jdbcTypeIdentity
        }
        val jdbcType = when {
            jdbcTypeIdentity == null -> null
            else -> runCatching { JdbcType.valueOf(jdbcTypeIdentity) }.getOrElse {
                return failed(
                    binding,
                    PreparedBindingBindFailureKind.JDBC_TYPE_UNSUPPORTED,
                    JDBC_TYPE_UNSUPPORTED,
                )
            }
        }
        if (runtime.value == null && jdbcType == null) {
            return failed(
                binding,
                PreparedBindingBindFailureKind.JDBC_TYPE_UNSUPPORTED,
                JDBC_TYPE_UNSUPPORTED,
            )
        }

        val handler: TypeHandler<*> = when {
            runtime.value == null &&
                binding.metadata.typeHandlerIdentity == ObjectTypeHandler::class.java.name ->
                ObjectTypeHandler.INSTANCE
            jdbcType == null ->
                registry.getTypeHandler(runtime.javaType)
            else ->
                registry.getTypeHandler(runtime.javaType, jdbcType)
        } ?: return failed(
            binding,
            PreparedBindingBindFailureKind.TYPE_HANDLER_MISSING,
            TYPE_HANDLER_MISSING,
        )

        if (handler.javaClass.name != binding.metadata.typeHandlerIdentity) {
            return failed(
                binding,
                PreparedBindingBindFailureKind.TYPE_HANDLER_IDENTITY_MISMATCH,
                TYPE_HANDLER_IDENTITY_MISMATCH,
            )
        }

        @Suppress("UNCHECKED_CAST")
        val untyped = handler as TypeHandler<Any>
        return try {
            untyped.setParameter(
                statement,
                binding.index + 1,
                runtime.value,
                jdbcType,
            )
            PreparedBindingBindResult.Bound
        } catch (_: UnsupportedOperationException) {
            failed(
                binding,
                PreparedBindingBindFailureKind.REMOTE_METHOD_UNSUPPORTED,
                REMOTE_METHOD_UNSUPPORTED,
            )
        } catch (_: SQLException) {
            failed(
                binding,
                PreparedBindingBindFailureKind.TYPE_HANDLER_BIND_FAILED,
                TYPE_HANDLER_BIND_FAILED,
            )
        } catch (_: TypeException) {
            failed(
                binding,
                PreparedBindingBindFailureKind.TYPE_HANDLER_BIND_FAILED,
                TYPE_HANDLER_BIND_FAILED,
            )
        }
    }

    private fun failed(
        binding: PreparedBinding,
        kind: PreparedBindingBindFailureKind,
        code: String,
    ): PreparedBindingBindResult.Failed =
        PreparedBindingBindResult.Failed(
            PreparedBindingBindFailure(
                kind = kind,
                code = code,
                bindingIndex = binding.index,
            ),
        )
}
