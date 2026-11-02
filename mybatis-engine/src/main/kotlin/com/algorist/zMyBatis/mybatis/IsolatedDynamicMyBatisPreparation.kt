package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Type
import java.net.URLClassLoader
import java.util.concurrent.CancellationException
import org.apache.ibatis.session.Configuration

/**
 * Executes stock MyBatis DynamicSqlSource preparation in a fresh classloader.
 *
 * MyBatis 3.6.0 initializes DynamicContext by registering a ContextMap accessor in the shaded
 * process-global OGNL runtime. Loading/evaluating either a `<script>` or static `${...}` through
 * DynamicSqlSource in the application/plugin classloader would therefore violate the preparation
 * isolation contract. A fresh loader scopes that stock-MyBatis mutation to one preparation while
 * only JDK-safe snapshots cross back to the caller.
 */
internal object IsolatedDynamicMyBatisPreparation {
    private const val CONFIGURATION = "org.apache.ibatis.session.Configuration"
    private const val LANGUAGE_DRIVER = "org.apache.ibatis.scripting.LanguageDriver"
    private const val SQL_SOURCE = "org.apache.ibatis.mapping.SqlSource"
    private const val BOUND_SQL = "org.apache.ibatis.mapping.BoundSql"
    private const val PARAMETER_MAPPING = "org.apache.ibatis.mapping.ParameterMapping"
    private const val PARAM_MAP = $$"org.apache.ibatis.binding.MapperMethod$ParamMap"
    private const val JDBC_TYPE = "org.apache.ibatis.type.JdbcType"
    private const val OBJECT_TYPE_HANDLER = "org.apache.ibatis.type.ObjectTypeHandler"
    private const val OGNL_RUNTIME = "org.apache.ibatis.ognl.OgnlRuntime"

    private const val PARSE_FAILURE = "mybatis-sql-source-parse-failure"
    private const val OGNL_FAILURE = "mybatis-ognl-evaluation-failure"
    private const val BINDING_FAILURE = "mybatis-binding-resolution-failure"
    private const val INVARIANT_FAILURE = "mybatis-preparation-invariant-failure"

    private val platformLoader = ClassLoader.getPlatformClassLoader()

    fun prepare(
        script: String,
        parameterType: MyBatisParameterType,
        parameterValues: Map<String, Any?>,
        admittedForeachLocals: Map<String, InternalBindingKind> = emptyMap(),
        databaseId: String? = null,
    ): Result {
        if (!safeParameterType(parameterType)) {
            return Result.Failed(invariantFailure("mybatis-isolated-parameter-type-not-jdk-owned"))
        }
        if (!safeInboundValue(parameterValues)) {
            return Result.Failed(invariantFailure("mybatis-isolated-parameter-value-not-jdk-owned"))
        }
        if (admittedForeachLocals.values.any {
                it != InternalBindingKind.FOREACH_ITEM && it != InternalBindingKind.FOREACH_INDEX
            }
        ) {
            return Result.Failed(invariantFailure("mybatis-isolated-foreach-local-kind-invalid"))
        }

        val myBatisLocation = MyBatisRuntimeArtifact.resolve(Configuration::class.java)
            ?: return Result.Failed(invariantFailure("mybatis-code-source-unavailable"))
        if (myBatisLocation.protocol != "file") {
            return Result.Failed(invariantFailure("mybatis-code-source-non-local"))
        }

        return try {
            URLClassLoader(arrayOf(myBatisLocation), platformLoader).use { loader ->
                prepareIn(loader, script, parameterType, parameterValues, admittedForeachLocals, databaseId)
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
        admittedForeachLocals: Map<String, InternalBindingKind>,
        databaseId: String?,
    ): Result {
        val configurationClass = Class.forName(CONFIGURATION, true, loader)
        if (configurationClass.classLoader !== loader) {
            return Result.Failed(invariantFailure("mybatis-configuration-not-isolated"))
        }
        val ognlRuntimeClass = Class.forName(OGNL_RUNTIME, false, loader)
        if (ognlRuntimeClass.classLoader !== loader) {
            return Result.Failed(invariantFailure("mybatis-ognl-runtime-not-isolated"))
        }
        val languageDriverClass = Class.forName(LANGUAGE_DRIVER, true, loader)
        val sqlSourceClass = Class.forName(SQL_SOURCE, true, loader)
        val boundSqlClass = Class.forName(BOUND_SQL, true, loader)
        val paramMapClass = Class.forName(PARAM_MAP, true, loader)

        val configuration = configurationClass.getDeclaredConstructor().newInstance()
        databaseId?.let {
            configurationClass
                .getMethod("setDatabaseId", String::class.java)
                .invoke(configuration, it)
        }
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

        val snapshots = snapshotMappings(loader, configuration, boundSql, parameterObject, mappings, admittedForeachLocals)

        return Result.Ready(
            MyBatisBoundSqlSnapshot(
                sql = sql,
                mappings = snapshots,
                languageDriverIdentity = languageDriver.javaClass.name,
            ),
        )
    }

    /** Copies mapping values and generated identities while the owning isolated runtime is live. */
    fun snapshotMappings(
        loader: URLClassLoader,
        configuration: Any,
        boundSql: Any,
        parameterObject: Any?,
        mappings: List<*>,
        admittedForeachLocals: Map<String, InternalBindingKind>,
    ): List<MyBatisParameterMappingSnapshot> {
        val configurationClass = Class.forName(CONFIGURATION, true, loader)
        val boundSqlClass = Class.forName(BOUND_SQL, true, loader)
        val parameterMappingClass = Class.forName(PARAMETER_MAPPING, true, loader)
        return mappings.map { mapping ->
            checkNotNull(mapping) { "mybatis-null-parameter-mapping" }
            snapshotMapping(
                loader = loader,
                configurationClass = configurationClass,
                configuration = configuration,
                boundSqlClass = boundSqlClass,
                boundSql = boundSql,
                parameterMappingClass = parameterMappingClass,
                mapping = mapping,
                parameterObject = parameterObject,
                admittedForeachLocals = admittedForeachLocals,
            )
        }
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
        admittedForeachLocals: Map<String, InternalBindingKind>,
    ): MyBatisParameterMappingSnapshot {
        val property = parameterMappingClass.getMethod("getProperty").invoke(mapping) as? String

        val additional = property?.let {
            boundSqlClass
                .getMethod("hasAdditionalParameter", String::class.java)
                .invoke(boundSql, it) as Boolean
        } ?: false
        val capturedValue = parameterMappingClass.getMethod("hasValue").invoke(mapping) as Boolean
        val foreachLocal = if (capturedValue && property != null) {
            foreachLocalSnapshot(property, admittedForeachLocals)
        } else {
            null
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
                parameterMappingClass = parameterMappingClass,
                mapping = mapping,
                property = property,
                capturedValue = capturedValue,
                additional = additional,
                parameterObject = parameterObject,
            )
        }

        val metadata = when (runtimeValue) {
            is MyBatisRuntimeValueSnapshot.Ready ->
                effectiveMappingMetadata(loader, configuration, mapping, runtimeValue.value)
            is MyBatisRuntimeValueSnapshot.Failed ->
                rawMappingMetadata(mapping)
        }

        return MyBatisParameterMappingSnapshot(
            property = property,
            additionalParameter = additional,
            foreachLocal = foreachLocal,
            runtimeValue = runtimeValue,
            mappingJavaTypeIdentity = metadata.javaTypeIdentity,
            jdbcTypeIdentity = metadata.jdbcTypeIdentity,
            typeHandlerIdentity = metadata.typeHandlerIdentity,
            parameterMode = metadata.parameterMode,
            numericScale = metadata.numericScale,
        )
    }

    internal fun effectiveMappingMetadata(
        loader: ClassLoader,
        configuration: Any,
        mapping: Any,
        runtimeValue: Any?,
    ): MyBatisEffectiveMappingMetadata {
        val mappingClass = mapping.javaClass
        val mappingJavaType = mappingClass.getMethod("getJavaType").invoke(mapping) as? Class<*>
        val jdbcType = mappingClass.getMethod("getJdbcType").invoke(mapping)
        val mappingHandler = mappingClass.getMethod("getTypeHandler").invoke(mapping)
        val effectiveHandler = when {
            mappingHandler != null -> mappingHandler
            runtimeValue == null -> Class.forName(OBJECT_TYPE_HANDLER, true, loader)
                .getField("INSTANCE")
                .get(null)
            else -> {
                val registry = configuration.javaClass
                    .getMethod("getTypeHandlerRegistry")
                    .invoke(configuration)
                val jdbcTypeClass = Class.forName(JDBC_TYPE, true, loader)
                registry.javaClass
                    .getMethod("getTypeHandler", Type::class.java, jdbcTypeClass)
                    .invoke(registry, runtimeValue.javaClass, jdbcType)
                    ?: jdbcType?.let {
                        registry.javaClass
                            .getMethod("getTypeHandler", jdbcTypeClass)
                            .invoke(registry, it)
                    }
            }
        }
        val effectiveJavaType = when {
            mappingHandler != null -> mappingJavaType?.name
            runtimeValue != null -> runtimeValue.javaClass.name
            mappingJavaType != null -> mappingJavaType.name
            else -> Object::class.java.name
        }
        return MyBatisEffectiveMappingMetadata(
            javaTypeIdentity = effectiveJavaType,
            jdbcTypeIdentity = enumName(jdbcType),
            typeHandlerIdentity = effectiveHandler?.javaClass?.name,
            parameterMode = enumName(mappingClass.getMethod("getMode").invoke(mapping)),
            numericScale = mappingClass.getMethod("getNumericScale").invoke(mapping) as? Int,
        )
    }

    private fun rawMappingMetadata(mapping: Any): MyBatisEffectiveMappingMetadata {
        val mappingClass = mapping.javaClass
        return MyBatisEffectiveMappingMetadata(
            javaTypeIdentity = (mappingClass.getMethod("getJavaType").invoke(mapping) as? Class<*>)?.name,
            jdbcTypeIdentity = enumName(mappingClass.getMethod("getJdbcType").invoke(mapping)),
            typeHandlerIdentity = mappingClass.getMethod("getTypeHandler").invoke(mapping)?.javaClass?.name,
            parameterMode = enumName(mappingClass.getMethod("getMode").invoke(mapping)),
            numericScale = mappingClass.getMethod("getNumericScale").invoke(mapping) as? Int,
        )
    }

    private fun foreachLocalSnapshot(
        property: String,
        admittedForeachLocals: Map<String, InternalBindingKind>,
    ): MyBatisForeachLocalSnapshot? {
        if (admittedForeachLocals.isEmpty()) return null
        val root = property.substringBefore('.')
        val kind = admittedForeachLocals[root] ?: return null
        return MyBatisForeachLocalSnapshot(root, kind)
    }

    private fun snapshotRuntimeValue(
        loader: URLClassLoader,
        configurationClass: Class<*>,
        configuration: Any,
        boundSqlClass: Class<*>,
        boundSql: Any,
        parameterMappingClass: Class<*>,
        mapping: Any,
        property: String,
        capturedValue: Boolean,
        additional: Boolean,
        parameterObject: Any?,
    ): MyBatisRuntimeValueSnapshot = try {
        val value = when {
            capturedValue -> parameterMappingClass.getMethod("getValue").invoke(mapping)
            additional -> boundSqlClass
                .getMethod("getAdditionalParameter", String::class.java)
                .invoke(boundSql, property)
            parameterObject == null -> null
            else -> {
                val typeHandlerRegistry = configurationClass
                    .getMethod("getTypeHandlerRegistry")
                    .invoke(configuration)
                val hasTypeHandler = typeHandlerRegistry.javaClass
                    .getMethod("hasTypeHandler", Type::class.java)
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

    private fun safeParameterType(parameterType: MyBatisParameterType): Boolean = when (parameterType) {
        MyBatisParameterType.None,
        MyBatisParameterType.Multi,
        -> true
        is MyBatisParameterType.Single -> jdkOwned(parameterType.type)
    }

    private fun safeInboundValue(value: Any?): Boolean {
        if (value == null) return true
        if (!jdkOwned(value.javaClass)) return false
        return when (value) {
            is Map<*, *> -> value.entries.all { (key, item) ->
                safeInboundValue(key) && safeInboundValue(item)
            }
            is List<*> -> value.all(::safeInboundValue)
            else -> !value.javaClass.isArray ||
                (0 until ReflectArray.getLength(value)).all { index ->
                    safeInboundValue(ReflectArray.get(value, index))
                }
        }
    }

    private fun jdkOwned(type: Class<*>): Boolean =
        type.classLoader == null || type.classLoader === platformLoader

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
            val copy = ArrayList<Any?>(value.size)
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
        return when {
            valueLoader === loader -> CrossLoaderValue.Unsupported(value.javaClass.name)
            valueLoader == null || valueLoader === platformLoader -> CrossLoaderValue.Ready(value)
            else -> CrossLoaderValue.Unsupported(value.javaClass.name)
        }
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
        val fatal = throwableChain(failure).firstOrNull {
            it !is Exception || it is CancellationException
        }
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
