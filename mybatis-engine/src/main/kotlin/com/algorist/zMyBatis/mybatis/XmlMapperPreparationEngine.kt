package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputAliasKind
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputShape
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationMetadata
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparedBinding
import com.algorist.zMyBatis.core.preparation.PreparedBindingMetadata
import com.algorist.zMyBatis.core.preparation.PreparedBindingOrigin
import com.algorist.zMyBatis.core.preparation.PreparedExecution
import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.XmlStatementId
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import org.apache.ibatis.session.Configuration

/**
 * Bounded XML mapper preparation island for #149 / #161.
 *
 * The complete captured mapper documents are parsed by an isolated stock MyBatis 3.5.19 runtime.
 * Static zero-input statements and the deliberately narrow proven scalar/temporal bound-input
 * island are admitted. No MyBatis object crosses the child-classloader boundary.
 */
object XmlMapperPreparationEngine {
    private const val ENGINE_ID = "org.mybatis:mybatis"
    private const val ENGINE_VERSION = "3.5.19"
    private const val CONFIGURATION = "org.apache.ibatis.session.Configuration"
    private const val XML_MAPPER_BUILDER = "org.apache.ibatis.builder.xml.XMLMapperBuilder"
    private const val MAPPED_STATEMENT = "org.apache.ibatis.mapping.MappedStatement"
    private const val DYNAMIC_SQL_SOURCE = "org.apache.ibatis.scripting.xmltags.DynamicSqlSource"
    private const val BOUND_SQL = "org.apache.ibatis.mapping.BoundSql"
    private const val RESOURCES = "org.apache.ibatis.io.Resources"

    private const val WRONG_SOURCE = "xml-preparation-source-kind-unsupported"
    private const val INPUT_CONTRACT_UNSUPPORTED = "xml-preparation-input-contract-unsupported"
    private const val RAW_INPUT_UNSUPPORTED = "xml-preparation-raw-input-unsupported"
    private const val BOUND_SHAPE_UNSUPPORTED = "xml-preparation-bound-shape-unsupported"
    private const val ALIAS_KIND_UNSUPPORTED = "xml-preparation-alias-kind-unsupported"
    private const val MAPPING_PROPERTY_MISSING = "xml-preparation-mapping-property-missing"
    private const val MAPPING_PROPERTY_NESTED = "xml-preparation-nested-mapping-unsupported"
    private const val MAPPING_ALIAS_UNRESOLVED = "xml-preparation-mapping-alias-unresolved"
    private const val MAPPING_CARDINALITY_MISMATCH = "xml-preparation-mapping-cardinality-mismatch"
    private const val PARAMETER_MODE_UNSUPPORTED = "xml-preparation-parameter-mode-unsupported"
    private const val TYPE_HANDLER_UNSUPPORTED = "xml-preparation-type-handler-unsupported"
    private const val BINDING_VALUE_UNSUPPORTED = "xml-preparation-binding-value-unsupported"
    private const val DYNAMIC_UNSUPPORTED = "xml-preparation-dynamic-sql-unsupported"
    private const val CLASS_LOADING_UNSUPPORTED = "xml-preparation-runtime-class-loading-unsupported"
    private const val EXTERNAL_ENTITY_UNSUPPORTED = "xml-preparation-external-entity-unsupported"
    private const val ROOT_STATEMENT_MISSING = "xml-preparation-root-statement-missing"
    private const val ROOT_RESOURCE_MISMATCH = "xml-preparation-root-resource-mismatch"
    private const val STATEMENT_KIND_MISMATCH = "xml-preparation-statement-kind-mismatch"
    private const val EMPTY_SQL = "mybatis-prepared-sql-empty"
    private const val PARSE_FAILURE = "mybatis-xml-mapper-parse-failure"
    private const val INVARIANT_FAILURE = "mybatis-preparation-invariant-failure"
    private const val CLASSLOADER_INVARIANT = "mybatis-xml-classloader-isolation-failure"

    private val dangerousAttribute = Regex(
        """(?i)\b(?:databaseId|lang|parameterType|parameterMap|resultType|resultMap|typeHandler|javaType)\s*=""",
    )
    private val dangerousElement = Regex(
        """(?i)<\s*(?:resultMap|parameterMap|cache|cache-ref|selectKey)\b""",
    )
    private val safeMapperDoctype = Regex(
        """(?is)<!DOCTYPE\s+mapper\s+PUBLIC\s+["']-//mybatis\.org//DTD Mapper 3\.0//EN["']\s+["']https?://mybatis\.org/dtd/mybatis-3-mapper\.dtd["']\s*>""",
    )
    private val platformLoader = ClassLoader.getPlatformClassLoader()
    private val isolatedRuntime: IsolatedRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createIsolatedRuntime()
    }

    private data class IsolatedRuntime(
        val loader: URLClassLoader,
        val configurationClass: Class<*>,
        val builderClass: Class<*>,
        val mappedStatementClass: Class<*>,
        val dynamicSqlSourceClass: Class<*>,
        val boundSqlClass: Class<*>,
    )

    private class IsolationFailure(
        val failureCode: String,
        cause: Throwable? = null,
    ) : RuntimeException(failureCode, cause)

    fun prepare(request: MyBatisPreparationRequest): PreparationResult {
        val source = request.source as? XmlMapperPreparationSource
            ?: return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, WRONG_SOURCE)
        val statementId = source.sourceGraph.rootStatement.id as? XmlStatementId
            ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, INVARIANT_FAILURE)

        inputContractFailure(request)?.let { return PreparationResult.Failed(it) }

        val parameterValuesResult = MyBatisValueConversion.parameterValues(request)
        if (parameterValuesResult is MyBatisValueConversion.ParameterValuesResult.Failed) {
            return PreparationResult.Failed(parameterValuesResult.failure)
        }
        val parameterValues =
            (parameterValuesResult as MyBatisValueConversion.ParameterValuesResult.Ready).values

        for (snapshot in source.sourceGraph.sourceSnapshots) {
            preflight(snapshot)?.let { return PreparationResult.Failed(it) }
        }

        return try {
            prepareWithContextLoader(isolatedRuntime, source, statementId, request, parameterValues)
        } catch (failure: Throwable) {
            rethrowFatal(failure)
            when (failure) {
                is IsolationFailure -> failed(
                    PreparationFailureKind.PREPARATION_INVARIANT,
                    failure.failureCode,
                    failure.cause?.javaClass?.name,
                )
                is SecurityException -> failed(
                    PreparationFailureKind.PREPARATION_INVARIANT,
                    CLASSLOADER_INVARIANT,
                    failure.javaClass.name,
                )
                else -> PreparationResult.Failed(
                    PreparationFailure(
                        kind = PreparationFailureKind.MYBATIS_PARSE,
                        code = PARSE_FAILURE,
                        diagnosticType = diagnosticType(failure),
                    ),
                )
            }
        }
    }

    private fun prepareWithContextLoader(
        runtime: IsolatedRuntime,
        source: XmlMapperPreparationSource,
        statementId: XmlStatementId,
        request: MyBatisPreparationRequest,
        parameterValues: Map<String, Any?>,
    ): PreparationResult {
        val thread = Thread.currentThread()
        val previousContextLoader = thread.contextClassLoader
        var switched = false
        return try {
            thread.contextClassLoader = runtime.loader
            switched = true
            prepareIn(runtime, source, statementId, request, parameterValues)
        } finally {
            if (switched) {
                thread.contextClassLoader = previousContextLoader
            }
        }
    }

    private fun prepareIn(
        runtime: IsolatedRuntime,
        source: XmlMapperPreparationSource,
        statementId: XmlStatementId,
        request: MyBatisPreparationRequest,
        parameterValues: Map<String, Any?>,
    ): PreparationResult {
        val configurationClass = runtime.configurationClass
        val builderClass = runtime.builderClass
        val mappedStatementClass = runtime.mappedStatementClass
        val dynamicSqlSourceClass = runtime.dynamicSqlSourceClass
        val boundSqlClass = runtime.boundSqlClass

        val configuration = configurationClass.getDeclaredConstructor().newInstance()
        val sqlFragments = configurationClass.getMethod("getSqlFragments").invoke(configuration) as? Map<*, *>
            ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, INVARIANT_FAILURE)
        val constructor = builderClass.getConstructor(
            InputStream::class.java,
            configurationClass,
            String::class.java,
            Map::class.java,
        )

        source.sourceGraph.sourceSnapshots.forEach { snapshot ->
            val resource = resourceIdentity(snapshot)
            ByteArrayInputStream(snapshot.content.toByteArray(StandardCharsets.UTF_8)).use { input ->
                val builder = constructor.newInstance(input, configuration, resource, sqlFragments)
                builderClass.getMethod("parse").invoke(builder)
            }
        }

        val canonicalStatementId = "${statementId.namespace}.${statementId.statementId}"
        val mappedStatement = try {
            configurationClass
                .getMethod("getMappedStatement", String::class.java)
                .invoke(configuration, canonicalStatementId)
        } catch (failure: InvocationTargetException) {
            return PreparationResult.Failed(
                PreparationFailure(
                    PreparationFailureKind.MYBATIS_PARSE,
                    ROOT_STATEMENT_MISSING,
                    diagnosticType = diagnosticType(failure),
                ),
            )
        }
        if (mappedStatement == null || !mappedStatementClass.isInstance(mappedStatement)) {
            return failed(PreparationFailureKind.MYBATIS_PARSE, ROOT_STATEMENT_MISSING)
        }

        val rootSnapshot = source.sourceGraph.sourceSnapshots.singleOrNull {
            it.fileId == statementId.sourceFileId
        } ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, INVARIANT_FAILURE)
        val mappedResource = mappedStatementClass.getMethod("getResource").invoke(mappedStatement) as? String
        if (mappedResource != resourceIdentity(rootSnapshot)) {
            return failed(PreparationFailureKind.PREPARATION_INVARIANT, ROOT_RESOURCE_MISMATCH)
        }

        val sqlCommandType = mappedStatementClass.getMethod("getSqlCommandType").invoke(mappedStatement) as? Enum<*>
            ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, INVARIANT_FAILURE)
        if (sqlCommandType.name != request.statementKind.name) {
            return failed(PreparationFailureKind.PREPARATION_INVARIANT, STATEMENT_KIND_MISMATCH)
        }

        val sqlSource = mappedStatementClass.getMethod("getSqlSource").invoke(mappedStatement)
            ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, INVARIANT_FAILURE)
        if (dynamicSqlSourceClass.isInstance(sqlSource)) {
            return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, DYNAMIC_UNSUPPORTED)
        }

        val parameterObject = parameterValues.takeIf { it.isNotEmpty() }
        val boundSql = mappedStatementClass
            .getMethod("getBoundSql", Any::class.java)
            .invoke(mappedStatement, parameterObject)
            ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, INVARIANT_FAILURE)
        if (!boundSqlClass.isInstance(boundSql)) {
            return failed(PreparationFailureKind.PREPARATION_INVARIANT, INVARIANT_FAILURE)
        }

        val sql = boundSqlClass.getMethod("getSql").invoke(boundSql) as? String
            ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, INVARIANT_FAILURE)
        if (sql.isBlank()) {
            return failed(PreparationFailureKind.PREPARATION_INVARIANT, EMPTY_SQL)
        }
        val mappings = boundSqlClass.getMethod("getParameterMappings").invoke(boundSql) as? List<*>
            ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, INVARIANT_FAILURE)
        val bindings = when (
            val captured = captureBindings(
                mappings = mappings,
                request = request,
                parameterValues = parameterValues,
                isolatedLoader = runtime.loader,
            )
        ) {
            is XmlBindingCapture.Ready -> captured.bindings
            is XmlBindingCapture.Failed -> return PreparationResult.Failed(captured.failure)
        }

        val languageDriver = mappedStatementClass.getMethod("getLang").invoke(mappedStatement)
            ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, INVARIANT_FAILURE)
        if (languageDriver.javaClass.classLoader !== runtime.loader) {
            return failed(PreparationFailureKind.PREPARATION_INVARIANT, "mybatis-language-driver-not-isolated")
        }

        return try {
            PreparationResult.Success(
                PreparedExecution(
                    statementId = request.statementId,
                    statementKind = request.statementKind,
                    sourceRevisions = request.sourceRevisions,
                    sqlWithPlaceholders = sql,
                    orderedBindings = bindings,
                    rawInterpolations = emptyList(),
                    preparationMetadata = PreparationMetadata(
                        engineIdentity = ENGINE_ID,
                        engineVersion = ENGINE_VERSION,
                        languageDriverIdentity = languageDriver.javaClass.name,
                    ),
                ),
            )
        } catch (failure: IllegalArgumentException) {
            failed(
                PreparationFailureKind.PREPARATION_INVARIANT,
                INVARIANT_FAILURE,
                failure.javaClass.name,
            )
        }
    }

    /**
     * The isolated runtime loader is created once for the plugin lifetime and then treated as
     * immutable. Every preparation still owns a fresh MyBatis Configuration, mapper builders, and
     * fragment map. Reusing the loader avoids racing URLClassLoader.close() against concurrent local
     * DTD reads from the same MyBatis JAR while keeping application/plugin classes unreachable.
     */
    private fun createIsolatedRuntime(): IsolatedRuntime {
        val myBatisLocation = Configuration::class.java.protectionDomain?.codeSource?.location
            ?: throw IsolationFailure("mybatis-code-source-unavailable")
        if (myBatisLocation.protocol != "file") {
            throw IsolationFailure("mybatis-code-source-non-local")
        }

        val loader = URLClassLoader(arrayOf(myBatisLocation), platformLoader)
        try {
            if (!hardenMyBatisResourceClassLoaders(loader)) {
                throw IsolationFailure(CLASSLOADER_INVARIANT)
            }

            val configurationClass = Class.forName(CONFIGURATION, true, loader)
            if (configurationClass.classLoader !== loader) {
                throw IsolationFailure("mybatis-configuration-not-isolated")
            }
            val builderClass = Class.forName(XML_MAPPER_BUILDER, true, loader)
            if (builderClass.classLoader !== loader) {
                throw IsolationFailure("mybatis-xml-builder-not-isolated")
            }

            return IsolatedRuntime(
                loader = loader,
                configurationClass = configurationClass,
                builderClass = builderClass,
                mappedStatementClass = Class.forName(MAPPED_STATEMENT, true, loader),
                dynamicSqlSourceClass = Class.forName(DYNAMIC_SQL_SOURCE, true, loader),
                boundSqlClass = Class.forName(BOUND_SQL, true, loader),
            )
        } catch (failure: Throwable) {
            try {
                loader.close()
            } catch (_: Exception) {
                // Preserve the initialization failure that caused the runtime to be rejected.
            }
            throw failure
        }
    }

    /**
     * MyBatis 3.5.19 Resources.classForName() falls back through default, TCCL, its own loader,
     * and the system classloader, and initializes a class once found. This dedicated Resources copy
     * is hardened once during isolated-runtime initialization and is never mutated per preparation.
     * prepareWithContextLoader() pins the TCCL leg to the same isolated loader for each call.
     */
    private fun hardenMyBatisResourceClassLoaders(loader: URLClassLoader): Boolean {
        val resourcesClass = Class.forName(RESOURCES, true, loader)
        if (resourcesClass.classLoader !== loader) return false

        resourcesClass
            .getMethod("setDefaultClassLoader", ClassLoader::class.java)
            .invoke(null, loader)
        if (resourcesClass.getMethod("getDefaultClassLoader").invoke(null) !== loader) return false

        val wrapperField = resourcesClass.getDeclaredField("classLoaderWrapper")
        if (!wrapperField.trySetAccessible()) return false
        val wrapper = wrapperField.get(null) ?: return false
        if (wrapper.javaClass.classLoader !== loader) return false

        val systemLoaderField = wrapper.javaClass.getDeclaredField("systemClassLoader")
        if (!systemLoaderField.trySetAccessible()) return false
        systemLoaderField.set(wrapper, loader)
        return systemLoaderField.get(wrapper) === loader
    }

    private fun preflight(snapshot: SourceSnapshot): PreparationFailure? {
        val content = snapshot.content
        if (containsRawInterpolation(content)) {
            return PreparationFailure(PreparationFailureKind.UNSUPPORTED_SEMANTIC, RAW_INPUT_UNSUPPORTED)
        }
        if (dangerousAttribute.containsMatchIn(content) || dangerousElement.containsMatchIn(content)) {
            return PreparationFailure(PreparationFailureKind.UNSUPPORTED_SEMANTIC, CLASS_LOADING_UNSUPPORTED)
        }
        if (content.contains("<!ENTITY", ignoreCase = true)) {
            return PreparationFailure(PreparationFailureKind.UNSUPPORTED_SEMANTIC, EXTERNAL_ENTITY_UNSUPPORTED)
        }
        val doctypeCount = Regex("(?i)<!DOCTYPE\\b").findAll(content).count()
        if (doctypeCount > 1 || (doctypeCount == 1 && !safeMapperDoctype.containsMatchIn(content))) {
            return PreparationFailure(PreparationFailureKind.UNSUPPORTED_SEMANTIC, EXTERNAL_ENTITY_UNSUPPORTED)
        }
        return null
    }

    private fun containsRawInterpolation(content: String): Boolean =
        content.indices.any { index ->
            index + 1 < content.length &&
                content[index].code == 36 &&
                content[index + 1] == '{'
        }

    private fun inputContractFailure(request: MyBatisPreparationRequest): PreparationFailure? {
        if (request.parameterContract.internalBindings.isNotEmpty()) {
            return PreparationFailure(
                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                INPUT_CONTRACT_UNSUPPORTED,
            )
        }
        if (request.parameterContract.requirements.any { it.kind != InputKind.BOUND }) {
            return PreparationFailure(
                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                RAW_INPUT_UNSUPPORTED,
            )
        }
        if (
            request.parameterContract.requirements.any {
                it.expectedType.shape !in setOf(InputShape.SCALAR, InputShape.TEMPORAL)
            }
        ) {
            return PreparationFailure(
                PreparationFailureKind.UNSUPPORTED_BINDING_VALUE,
                BOUND_SHAPE_UNSUPPORTED,
            )
        }
        if (
            request.parameterContract.aliases.any {
                it.kind !in setOf(InputAliasKind.EXPLICIT_PARAM, InputAliasKind.GENERIC_PARAM)
            }
        ) {
            return PreparationFailure(
                PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                ALIAS_KIND_UNSUPPORTED,
            )
        }
        if (
            request.parameterContract.requirements.any { requirement ->
                request.parameterContract.aliases.none { it.requirementId == requirement.id }
            }
        ) {
            return PreparationFailure(
                PreparationFailureKind.BINDING_RESOLUTION,
                MAPPING_ALIAS_UNRESOLVED,
            )
        }
        return null
    }

    private fun captureBindings(
        mappings: List<*>,
        request: MyBatisPreparationRequest,
        parameterValues: Map<String, Any?>,
        isolatedLoader: ClassLoader,
    ): XmlBindingCapture {
        val aliasesByName = request.inputEnvironment.aliases.groupBy { it.name }
        val bindings = mutableListOf<PreparedBinding>()
        val mappingCounts = linkedMapOf<InputRequirementId, Int>()

        mappings.forEachIndexed { index, mappingValue ->
            val mapping = mappingValue
                ?: return XmlBindingCapture.Failed(
                    PreparationFailure(
                        PreparationFailureKind.PREPARATION_INVARIANT,
                        INVARIANT_FAILURE,
                    ),
                )
            val mappingClass = mapping.javaClass
            if (mappingClass.classLoader !== isolatedLoader) {
                return XmlBindingCapture.Failed(
                    PreparationFailure(
                        PreparationFailureKind.PREPARATION_INVARIANT,
                        CLASSLOADER_INVARIANT,
                    ),
                )
            }
            val property = (mappingClass.getMethod("getProperty").invoke(mapping) as? String)
                ?.takeIf { it.isNotBlank() }
                ?: return XmlBindingCapture.Failed(
                    PreparationFailure(
                        PreparationFailureKind.PARAMETER_MAPPING_MISMATCH,
                        MAPPING_PROPERTY_MISSING,
                    ),
                )
            if ('.' in property) {
                return XmlBindingCapture.Failed(
                    PreparationFailure(
                        PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                        MAPPING_PROPERTY_NESTED,
                        property,
                    ),
                )
            }

            val aliasCandidates = aliasesByName[property].orEmpty()
            if (aliasCandidates.size != 1) {
                return XmlBindingCapture.Failed(
                    PreparationFailure(
                        PreparationFailureKind.BINDING_RESOLUTION,
                        MAPPING_ALIAS_UNRESOLVED,
                        property,
                    ),
                )
            }
            val alias = aliasCandidates.single()
            val requirement = request.parameterContract.requirement(alias.requirementId)
                ?: return XmlBindingCapture.Failed(
                    PreparationFailure(
                        PreparationFailureKind.PREPARATION_INVARIANT,
                        INVARIANT_FAILURE,
                        property,
                    ),
                )
            if (requirement.kind != InputKind.BOUND) {
                return XmlBindingCapture.Failed(
                    PreparationFailure(
                        PreparationFailureKind.PARAMETER_MAPPING_MISMATCH,
                        RAW_INPUT_UNSUPPORTED,
                        property,
                    ),
                )
            }
            if (!parameterValues.containsKey(property)) {
                return XmlBindingCapture.Failed(
                    PreparationFailure(
                        PreparationFailureKind.BINDING_RESOLUTION,
                        MAPPING_ALIAS_UNRESOLVED,
                        property,
                    ),
                )
            }

            val parameterMode = (mappingClass.getMethod("getMode").invoke(mapping) as? Enum<*>)?.name
                ?: return XmlBindingCapture.Failed(
                    PreparationFailure(
                        PreparationFailureKind.PREPARATION_INVARIANT,
                        INVARIANT_FAILURE,
                        property,
                    ),
                )
            if (parameterMode != "IN") {
                return XmlBindingCapture.Failed(
                    PreparationFailure(
                        PreparationFailureKind.UNSUPPORTED_SEMANTIC,
                        PARAMETER_MODE_UNSUPPORTED,
                        property,
                    ),
                )
            }

            val typeHandler = mappingClass.getMethod("getTypeHandler").invoke(mapping)
                ?: return XmlBindingCapture.Failed(
                    PreparationFailure(
                        PreparationFailureKind.UNSUPPORTED_TYPE_HANDLER,
                        TYPE_HANDLER_UNSUPPORTED,
                        property,
                    ),
                )
            val typeHandlerIdentity = typeHandler.javaClass.name
            if (
                typeHandler.javaClass.classLoader !== isolatedLoader ||
                !typeHandlerIdentity.startsWith("org.apache.ibatis.type.")
            ) {
                return XmlBindingCapture.Failed(
                    PreparationFailure(
                        PreparationFailureKind.UNSUPPORTED_TYPE_HANDLER,
                        TYPE_HANDLER_UNSUPPORTED,
                        property,
                        typeHandlerIdentity,
                    ),
                )
            }

            val runtimeValue = parameterValues.getValue(property)
            val coreValue = MyBatisValueConversion.toCoreValue(runtimeValue, requirement, property, alias)
                ?: return XmlBindingCapture.Failed(
                    PreparationFailure(
                        PreparationFailureKind.UNSUPPORTED_BINDING_VALUE,
                        BINDING_VALUE_UNSUPPORTED,
                        property,
                        runtimeValue?.javaClass?.name,
                    ),
                )

            val javaType = mappingClass.getMethod("getJavaType").invoke(mapping) as? Class<*>
            val jdbcType = mappingClass.getMethod("getJdbcType").invoke(mapping) as? Enum<*>
            val numericScale = mappingClass.getMethod("getNumericScale").invoke(mapping) as? Int

            bindings += PreparedBinding(
                index = index,
                property = property,
                value = coreValue,
                origin = PreparedBindingOrigin.CallerInput(requirement.id, requirement.provenance),
                metadata = PreparedBindingMetadata(
                    declaredJavaTypeIdentity = requirement.expectedType.javaTypeIdentity,
                    mappingJavaTypeIdentity = javaType?.name,
                    jdbcTypeIdentity = jdbcType?.name,
                    typeHandlerIdentity = typeHandlerIdentity,
                    parameterMode = parameterMode,
                    numericScale = numericScale,
                ),
            )
            mappingCounts[requirement.id] = (mappingCounts[requirement.id] ?: 0) + 1
        }

        val expectedMappingCounts = request.parameterContract.requirements.associate { requirement ->
            requirement.id to requirement.provenance.evidence
                .filterIsInstance<InputEvidence.Placeholder>()
                .count { it.kind == InputKind.BOUND }
        }
        if (
            expectedMappingCounts.values.any { it == 0 } ||
            mappingCounts != expectedMappingCounts
        ) {
            return XmlBindingCapture.Failed(
                PreparationFailure(
                    PreparationFailureKind.PARAMETER_MAPPING_MISMATCH,
                    MAPPING_CARDINALITY_MISMATCH,
                ),
            )
        }
        return XmlBindingCapture.Ready(bindings)
    }

    private sealed interface XmlBindingCapture {
        data class Ready(val bindings: List<PreparedBinding>) : XmlBindingCapture

        data class Failed(val failure: PreparationFailure) : XmlBindingCapture
    }

    private fun resourceIdentity(snapshot: SourceSnapshot): String =
        "zmybatis:${snapshot.fileId.value}@${snapshot.revision.value}"

    private fun failed(
        kind: PreparationFailureKind,
        code: String,
        diagnosticType: String? = null,
    ): PreparationResult.Failed = PreparationResult.Failed(
        PreparationFailure(kind, code, diagnosticType = diagnosticType),
    )

    private fun diagnosticType(failure: Throwable): String =
        generateSequence(failure) { current ->
            when (current) {
                is InvocationTargetException -> current.targetException
                else -> current.cause
            }
        }.lastOrNull()?.javaClass?.name ?: failure.javaClass.name

    private fun rethrowFatal(failure: Throwable) {
        if (failure is VirtualMachineError || failure is LinkageError) {
            throw failure
        }

        var type: Class<*>? = failure.javaClass
        while (type != null) {
            if (type.name == "java.lang.ThreadDeath") {
                throw failure
            }
            type = type.superclass
        }
    }
}
