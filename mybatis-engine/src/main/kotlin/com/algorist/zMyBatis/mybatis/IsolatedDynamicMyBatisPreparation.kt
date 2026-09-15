package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import java.lang.reflect.Array as ReflectArray
import java.net.URLClassLoader
import org.apache.ibatis.session.Configuration

/**
 * Executes stock MyBatis dynamic-script preparation in a fresh classloader.
 *
 * MyBatis 3.5.19 initializes DynamicContext by registering a ContextMap accessor in the shaded
 * process-global OGNL runtime. Loading/evaluating dynamic scripts in the application/plugin
 * classloader would therefore violate the preparation isolation contract. A fresh loader keeps that
 * stock-MyBatis mutation scoped to one preparation while only immutable/JDK-safe snapshots cross
 * back to the caller.
 */
internal object IsolatedDynamicMyBatisPreparation {
    private const val CONFIGURATION = "org.apache.ibatis.session.Configuration"
    private const val LANGUAGE_DRIVER = "org.apache.ibatis.scripting.LanguageDriver"
    private const val SQL_SOURCE = "org.apache.ibatis.mapping.SqlSource"
    private const val BOUND_SQL = "org.apache.ibatis.mapping.BoundSql"
    private const val PARAMETER_MAPPING = "org.apache.ibatis.mapping.ParameterMapping"
    private const val PARAM_MAP = "org.apache.ibatis.binding.MapperMethod\$ParamMap"

    private const val PARSE_FAILURE = "mybatis-sql-source-parse-failure"
    private const val OGNL_FAILURE = "mybatis-ognl-evaluation-failure"
    private const val BINDING_FAILURE = "mybatis-binding-resolution-failure"
    private const val INVARIANT_FAILURE = "mybatis-preparation-invariant-failure"

    fun prepare(
        script: String,
        parameterType: MyBatisParameterType,
        parameterValues: Map<String, Any?>,
    ): Result {
        val myBatisLocation = Configuration::class.java.protectionDomain?.codeSource?.location
            ?: return Result.Failed(invariantFailure("mybatis-code-source-unavailable"))

        return try {
            URLClassLoader(
                arrayOf(myBatisLocation),
                ClassLoader.getPlatformClassLoader(),
            ).use { loader ->
                prepareIn(loader, script, parameterType, parameterValues)
            }
        } catch (failure: Throwable) {
            rethrowFatal(failure)
            Result.Failed(classifyPreparationFailure(failure))
        }
    }

    private fun prepareIn(
        loader: URLClassLoader,
        script: String,
        parameterType: MyBatisParameterType,
        parameterValues: Map<String, Any?>,
    ): Result {
        val configurationClass = Class.forName(CONFIGURATION, true, loader)
        if (configurationClass.classLoader !== loader) {
            return Result.Failed(invariantFailure("mybatis-configuration-not-isolated"))
        }
        val languageDriverClass = Class.forName(LANGUAGE_DRIVER, true, loader)
        val sqlSourceClass = Class.forName(SQL_SOURCE, true, loader)
        val boundSqlClass = Class.forName(BOUND_SQL, true, loader)
        val parameterMappingClass = Class.forName(PARAMETER_MAPPING, true, loader)
        val paramMapClass = Class.forName(PARAM_MAP, true, loader)

        val configuration = configurationClass.getDeclaredConstructor().newInstance()
        val languageDriver = configurationClass
            .getMethod("getDefaultScriptingLanguageInstance")
            .invoke(configuration)
            ?: return Result.Failed(invariantFailure("mybatis-language-driver-unavailable"))
        if (languageDriver.javaClass.classLoader !== loader) {
            return Result.Failed(invariantFailure("mybatis-language-driver-not-isolated"))
        }

        val resolvedParameterType = when (parameterType) {
            MyBatisParameterType.None -> null
            is MyBatisParameterType.Single -> parameterType.type
            MyBatisParameterType.Multi -> paramMapClass
        }
        val parameterObject = createParameterObject(paramMapClass, parameterValues)

        val sqlSource = languageDriverClass
            .getMethod(
                "createSqlSource",
                configurationClass,
                String::class.java,
                Class::class.java,
            )
            .invoke(languageDriver, configuration, script, resolvedParameterType)
            ?: return Result.Failed(invariantFailure("mybatis-sql-source-unavailable"))

        val boundSql = sqlSourceClass
            .getMethod("getBoundSql", Any::class.java)
            .invoke(sqlSource, parameterObject)
            ?: return Result.Failed(invariantFailure("mybatis-bound-sql-unavailable"))

        val sql = boundSqlClass.getMethod("getSql").invoke(boundSql) as? String
            ?: return Result.Failed(invariantFailure("mybatis-bound-sql-text-unavailable"))
        val mappings = boundSqlClass.getMethod("getParameterMappings").invoke(boundSql) as? List<*>
            ?: return Result.Failed(invariantFailure("mybatis-parameter-mappings-unavailable"))

        val snapshots = mappings.map { mapping ->
            mapping ?: return Result.Failed(invariantFailure("mybatis-null-parameter-mapping"))
            snapshotMapping(
                loader = loader,
                configurationClass = configurationClass,
                configuration = configuration,
                boundSqlClass = boundSqlClass,
                boundSql = boundSql,
                parameterMappingClass = parameterMappingClass,
                mapping = mapping,
                parameterObject = parameterObject,
            )
        }

        return Result.Ready(
            MyBatisBoundSqlSnapshot(
                sql = sql,
                mappings = snapshots,
                languageDriverIdentity = languageDriver.javaClass.name,
            ),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun createParameterObject(
        paramMapClass: Class<*>,
        parameterValues: Map<String, Any?>,
    ): Any? {
        if (parameterValues.isEmpty()) return null
        val parameterObject = paramMapClass.getDeclaredConstructor().newInstance()
        val map = parameterObject as? MutableMap<String, Any?>
            ?: error("MyBatis ParamMap does not implement java.util.Map")
        map.putAll(parameterValues)
        return parameterObject
    }

    private fun snapshotMapping(
        loader: URLClassLoader,
        configurationClass: Class<*>,
        configuration: Any,
        boundSqlClass: Class<*>,
        boundSql: Any,
        parameterMappingClass: Class<*>,
        mapping: Any,
        parameterObject: Any?,
    ): MyBatisParameterMappingSnapshot {
        val property = parameterMappingClass.getMethod("getProperty").invoke(mapping) as? String
        val mode = enumName(parameterMappingClass.getMethod("getMode").invoke(mapping))
        val javaType = (parameterMappingClass.getMethod("getJavaType").invoke(mapping) as? Class<*>)?.name
        val jdbcType = enumName(parameterMappingClass.getMethod("getJdbcType").invoke(mapping))
        val typeHandler = parameterMappingClass.getMethod("getTypeHandler").invoke(mapping)
        val typeHandlerIdentity = typeHandler?.javaClass?.name
        val numericScale = parameterMappingClass.getMethod("getNumericScale").invoke(mapping) as? Int

        val additional = if (property == null) {
            false
        } else {
            boundSqlClass
                .getMethod("hasAdditionalParameter", String::class.java)
                .invoke(boundSql, property) as Boolean
        }

        val runtimeValue = if (property == null) {
            MyBatisRuntimeValueSnapshot.Ready(null)
        } else {
            snapshotRuntimeValue(
                loader = loader,
                configurationClass = configurationClass,
                configuration = configuration,
                boundSqlClass = boundSqlClass,
                boundSql = boundSql,
                property = property,
                additional = additional,
                parameterObject = parameterObject,
            )
        }

        return MyBatisParameterMappingSnapshot(
            property = property,
            additionalParameter = additional,
            runtimeValue = runtimeValue,
            mappingJavaTypeIdentity = javaType,
            jdbcTypeIdentity = jdbcType,
            typeHandlerIdentity = typeHandlerIdentity,
            parameterMode = mode,
            numericScale = numericScale,
        )
    }

    private fun snapshotRuntimeValue(
        loader: URLClassLoader,
        configurationClass: Class<*>,
        configuration: Any,
        boundSqlClass: Class<*>,
        boundSql: Any,
        property: String,
        additional: Boolean,
        parameterObject: Any?,
    ): MyBatisRuntimeValueSnapshot = try {
        val value = when {
            additional -> boundSqlClass
                .getMethod("getAdditionalParameter", String::class.java)
                .invoke(boundSql, property)
            parameterObject == null -> null
            else -> {
                val typeHandlerRegistry = configurationClass
                    .getMethod("getTypeHandlerRegistry")
                    .invoke(configuration)
                val hasTypeHandler = typeHandlerRegistry.javaClass
                    .getMethod("hasTypeHandler", Class::class.java)
                    .invoke(typeHandlerRegistry, parameterObject.javaClass) as Boolean
                if (hasTypeHandler) {
                    parameterObject
                } else {
                    val metaObject = configurationClass
                        .getMethod("newMetaObject", Any::class.java)
                        .invoke(configuration, parameterObject)
                    metaObject.javaClass
                        .getMethod("getValue", String::class.java)
                        .invoke(metaObject, property)
                }
            }
        }

        when (val copied = copyAcrossLoader(value, loader)) {
            is CrossLoaderValue.Ready -> MyBatisRuntimeValueSnapshot.Ready(copied.value)
            is CrossLoaderValue.Unsupported -> MyBatisRuntimeValueSnapshot.Failed(
                bindingFailure = false,
                diagnosticType = copied.typeName,
            )
        }
    } catch (failure: Throwable) {
        rethrowFatal(failure)
        val chain = throwableChain(failure)
        MyBatisRuntimeValueSnapshot.Failed(
            bindingFailure = chain.any(::isBindingFailure),
            diagnosticType = diagnosticType(chain, failure),
        )
    }

    private fun copyAcrossLoader(value: Any?, loader: ClassLoader): CrossLoaderValue {
        if (value == null) return CrossLoaderValue.Ready(null)

        if (value is Map<*, *>) {
            val copy = linkedMapOf<Any?, Any?>()
            for ((key, item) in value) {
                val copiedKey = copyAcrossLoader(key, loader)
                if (copiedKey is CrossLoaderValue.Unsupported) return copiedKey
                val copiedValue = copyAcrossLoader(item, loader)
                if (copiedValue is CrossLoaderValue.Unsupported) return copiedValue
                copy[(copiedKey as CrossLoaderValue.Ready).value] =
                    (copiedValue as CrossLoaderValue.Ready).value
            }
            return CrossLoaderValue.Ready(copy)
        }

        if (value is List<*>) {
            val copy = mutableListOf<Any?>()
            for (item in value) {
                val copied = copyAcrossLoader(item, loader)
                if (copied is CrossLoaderValue.Unsupported) return copied
                copy += (copied as CrossLoaderValue.Ready).value
            }
            return CrossLoaderValue.Ready(copy)
        }

        if (value.javaClass.isArray) {
            val size = ReflectArray.getLength(value)
            val copy = arrayOfNulls<Any?>(size)
            for (index in 0 until size) {
                val copied = copyAcrossLoader(ReflectArray.get(value, index), loader)
                if (copied is CrossLoaderValue.Unsupported) return copied
                copy[index] = (copied as CrossLoaderValue.Ready).value
            }
            return CrossLoaderValue.Ready(copy)
        }

        val valueLoader = value.javaClass.classLoader
        if (valueLoader === loader) return CrossLoaderValue.Unsupported(value.javaClass.name)
        if (valueLoader == null || valueLoader === ClassLoader.getPlatformClassLoader()) {
            return CrossLoaderValue.Ready(value)
        }
        return CrossLoaderValue.Unsupported(value.javaClass.name)
    }

    private fun enumName(value: Any?): String? = (value as? Enum<*>)?.name

    private fun classifyPreparationFailure(failure: Throwable): PreparationFailure {
        val chain = throwableChain(failure)
        val diagnostic = diagnosticType(chain, failure)
        return when {
            chain.any { it.javaClass.name.contains(".ognl.") } -> PreparationFailure(
                PreparationFailureKind.OGNL,
                OGNL_FAILURE,
                diagnosticType = diagnostic,
            )
            chain.any(::isBindingFailure) -> PreparationFailure(
                PreparationFailureKind.BINDING_RESOLUTION,
                BINDING_FAILURE,
                diagnosticType = diagnostic,
            )
            chain.any { it.javaClass.name == "org.apache.ibatis.builder.BuilderException" } -> PreparationFailure(
                PreparationFailureKind.MYBATIS_PARSE,
                PARSE_FAILURE,
                diagnosticType = diagnostic,
            )
            else -> PreparationFailure(
                PreparationFailureKind.PREPARATION_INVARIANT,
                INVARIANT_FAILURE,
                diagnosticType = diagnostic,
            )
        }
    }

    private fun isBindingFailure(failure: Throwable): Boolean =
        failure.javaClass.name in setOf(
            "org.apache.ibatis.binding.BindingException",
            "org.apache.ibatis.reflection.ReflectionException",
        )

    private fun throwableChain(failure: Throwable): List<Throwable> =
        generateSequence(failure) { it.cause }.toList()

    private fun diagnosticType(chain: List<Throwable>, fallback: Throwable): String =
        chain.lastOrNull()?.javaClass?.name ?: fallback.javaClass.name

    private fun invariantFailure(diagnostic: String) = PreparationFailure(
        PreparationFailureKind.PREPARATION_INVARIANT,
        INVARIANT_FAILURE,
        diagnosticType = diagnostic,
    )

    private fun rethrowFatal(failure: Throwable) {
        val fatal = throwableChain(failure).firstOrNull { it is VirtualMachineError || it is ThreadDeath }
        if (fatal != null) throw fatal
    }

    sealed interface Result {
        data class Ready(
            val boundSql: MyBatisBoundSqlSnapshot,
        ) : Result

        data class Failed(
            val failure: PreparationFailure,
        ) : Result
    }

    private sealed interface CrossLoaderValue {
        data class Ready(
            val value: Any?,
        ) : CrossLoaderValue

        data class Unsupported(
            val typeName: String,
        ) : CrossLoaderValue
    }
}
