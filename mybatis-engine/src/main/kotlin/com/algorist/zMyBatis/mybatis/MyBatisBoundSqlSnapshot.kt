package com.algorist.zMyBatis.mybatis

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
    val runtimeValue: MyBatisRuntimeValueSnapshot,
    val mappingJavaTypeIdentity: String?,
    val jdbcTypeIdentity: String?,
    val typeHandlerIdentity: String?,
    val parameterMode: String?,
    val numericScale: Int?,
)

internal sealed interface MyBatisRuntimeValueSnapshot {
    data class Ready(
        val value: Any?,
    ) : MyBatisRuntimeValueSnapshot

    data class Failed(
        val bindingFailure: Boolean,
        val diagnosticType: String,
    ) : MyBatisRuntimeValueSnapshot
}
