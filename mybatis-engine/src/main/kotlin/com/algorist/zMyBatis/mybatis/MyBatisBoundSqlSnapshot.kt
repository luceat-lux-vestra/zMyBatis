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
    val foreachLocal: MyBatisForeachLocalSnapshot?,
    val runtimeValue: MyBatisRuntimeValueSnapshot,
    val mappingJavaTypeIdentity: String?,
    val jdbcTypeIdentity: String?,
    val typeHandlerIdentity: String?,
    val parameterMode: String?,
    val numericScale: Int?,
)

internal data class MyBatisForeachLocalSnapshot(
    val sourceLocalName: String,
    val kind: InternalBindingKind,
) {
    init {
        require(sourceLocalName.isNotBlank()) { "foreach local source name must not be blank" }
        require(kind == InternalBindingKind.FOREACH_ITEM || kind == InternalBindingKind.FOREACH_INDEX) {
            "foreach local kind must be foreach item or index"
        }
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
