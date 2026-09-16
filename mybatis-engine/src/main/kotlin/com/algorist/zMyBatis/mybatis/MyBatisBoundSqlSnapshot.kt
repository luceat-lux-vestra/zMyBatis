package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InternalBindingKind

internal sealed interface MyBatisParameterType {
    data object None : MyBatisParameterType

    data class Single(
        val type: Class<*>,
    ) : MyBatisParameterType

    data object Multi : MyBatisParameterType
}

internal data class MyBatisBoundSqlSnapshot(
    val sql: String,
    val mappings: List<MyBatisParameterMappingSnapshot>,
    val languageDriverIdentity: String,
)

internal data class MyBatisParameterMappingSnapshot(
    val property: String?,
    val additionalParameter: Boolean,
    val generatedLocal: MyBatisGeneratedLocalSnapshot?,
    val runtimeValue: MyBatisRuntimeValueSnapshot,
    val mappingJavaTypeIdentity: String?,
    val jdbcTypeIdentity: String?,
    val typeHandlerIdentity: String?,
    val parameterMode: String?,
    val numericScale: Int?,
)

internal data class MyBatisGeneratedLocalSnapshot(
    val sourceLocalName: String,
    val kind: InternalBindingKind,
    val uniqueNumber: Int,
) {
    init {
        require(sourceLocalName.isNotBlank()) { "generated local source name must not be blank" }
        require(kind == InternalBindingKind.FOREACH_ITEM || kind == InternalBindingKind.FOREACH_INDEX) {
            "generated local kind must be foreach item or index"
        }
        require(uniqueNumber >= 0) { "generated local unique number must not be negative" }
    }
}

internal sealed interface MyBatisRuntimeValueSnapshot {
    data class Ready(
        val value: Any?,
    ) : MyBatisRuntimeValueSnapshot

    data class Failed(
        val bindingFailure: Boolean,
        val diagnosticType: String,
    ) : MyBatisRuntimeValueSnapshot
}
