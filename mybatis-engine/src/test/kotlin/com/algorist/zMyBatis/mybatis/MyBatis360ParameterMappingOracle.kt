package com.algorist.zMyBatis.mybatis

import org.apache.ibatis.mapping.BoundSql
import org.apache.ibatis.mapping.ParameterMapping
import org.apache.ibatis.session.Configuration
import org.apache.ibatis.type.ObjectTypeHandler

internal data class StockEffectiveParameterMappingMetadata(
    val javaTypeIdentity: String?,
    val jdbcTypeIdentity: String?,
    val typeHandlerIdentity: String?,
    val parameterMode: String?,
    val numericScale: Int?,
    val runtimeValue: Any?,
)

internal fun stockEffectiveParameterMappingMetadata(
    configuration: Configuration,
    boundSql: BoundSql,
    parameterObject: Any?,
    mapping: ParameterMapping,
): StockEffectiveParameterMappingMetadata {
    val runtimeValue = when {
        mapping.hasValue() -> mapping.value
        boundSql.hasAdditionalParameter(mapping.property) -> boundSql.getAdditionalParameter(mapping.property)
        parameterObject == null -> null
        configuration.typeHandlerRegistry.hasTypeHandler(parameterObject.javaClass, mapping.jdbcType) -> parameterObject
        else -> configuration.newMetaObject(parameterObject).getValue(mapping.property)
    }
    val mappingHandler = mapping.typeHandler
    val effectiveHandler = when {
        mappingHandler != null -> mappingHandler
        runtimeValue == null -> ObjectTypeHandler.INSTANCE
        else -> configuration.typeHandlerRegistry.getTypeHandler(runtimeValue.javaClass, mapping.jdbcType)
            ?: mapping.jdbcType?.let(configuration.typeHandlerRegistry::getTypeHandler)
    }
    val effectiveJavaType = when {
        mappingHandler != null -> mapping.javaType?.name
        runtimeValue != null -> runtimeValue.javaClass.name
        mapping.javaType != null -> mapping.javaType.name
        else -> Object::class.java.name
    }
    return StockEffectiveParameterMappingMetadata(
        javaTypeIdentity = effectiveJavaType,
        jdbcTypeIdentity = mapping.jdbcType?.name,
        typeHandlerIdentity = effectiveHandler?.javaClass?.name,
        parameterMode = mapping.mode?.name,
        numericScale = mapping.numericScale,
        runtimeValue = runtimeValue,
    )
}
