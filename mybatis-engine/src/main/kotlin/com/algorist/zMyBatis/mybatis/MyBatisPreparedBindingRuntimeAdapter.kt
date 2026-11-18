package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparedBinding
import com.algorist.zMyBatis.core.source.JavaTypeIdentity

private const val MAPPING_JAVA_TYPE_MISSING = "prepared-binding-mapping-java-type-missing"
private const val MAPPING_JAVA_TYPE_UNSUPPORTED = "prepared-binding-mapping-java-type-unsupported"

/**
 * Reconstructs only the safe JDK runtime value/class already represented by a captured
 * [PreparedBinding].
 *
 * This is deliberately the same value-conversion authority used while constructing the MyBatis
 * invocation environment. The IntelliJ execution module must not maintain a second scalar/array
 * conversion matrix.
 */
object MyBatisPreparedBindingRuntimeAdapter {
    sealed interface Result {
        data class Ready(
            val javaType: Class<*>,
            val value: Any?,
        ) : Result

        data class Failed(
            val failure: PreparationFailure,
        ) : Result
    }

    fun adapt(binding: PreparedBinding): Result {
        val mappingType = binding.metadata.mappingJavaTypeIdentity
            ?: return failed(
                binding,
                PreparationFailureKind.PARAMETER_MAPPING_MISMATCH,
                MAPPING_JAVA_TYPE_MISSING,
            )

        if (binding.value == InputValue.NullValue && mappingType == Object::class.java.name) {
            return Result.Ready(Object::class.java, null)
        }

        val canonicalType = when (mappingType) {
            "[B" -> "byte[]"
            else -> mappingType
        }
        if (canonicalType == Object::class.java.name) {
            return failed(
                binding,
                PreparationFailureKind.UNSUPPORTED_BINDING_VALUE,
                MAPPING_JAVA_TYPE_UNSUPPORTED,
            )
        }

        val typeIdentity = runCatching { JavaTypeIdentity(canonicalType) }
            .getOrElse {
                return failed(
                    binding,
                    PreparationFailureKind.UNSUPPORTED_BINDING_VALUE,
                    MAPPING_JAVA_TYPE_UNSUPPORTED,
                )
            }
        val javaType = MyBatisValueConversion.resolveSafeJavaType(typeIdentity)
            ?: return failed(
                binding,
                PreparationFailureKind.UNSUPPORTED_BINDING_VALUE,
                MAPPING_JAVA_TYPE_UNSUPPORTED,
            )

        return when (val converted = MyBatisValueConversion.toRuntimeValue(binding.value, typeIdentity)) {
            is MyBatisValueConversion.RuntimeValueResult.Ready ->
                Result.Ready(javaType, converted.value)
            is MyBatisValueConversion.RuntimeValueResult.Failed ->
                Result.Failed(
                    converted.failure.copy(bindingProperty = binding.property),
                )
        }
    }

    private fun failed(
        binding: PreparedBinding,
        kind: PreparationFailureKind,
        code: String,
    ): Result.Failed =
        Result.Failed(
            PreparationFailure(
                kind = kind,
                code = code,
                bindingProperty = binding.property,
            ),
        )
}
