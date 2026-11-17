package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationMetadata
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparedExecution
import com.algorist.zMyBatis.core.preparation.PreparedRawInterpolation
import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource
import com.algorist.zMyBatis.core.source.XmlStatementId
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import org.apache.ibatis.session.Configuration

/**
 * Bounded XML mapper preparation island for #149 / #161.
 *
 * A statement-scoped structural projection of the captured mapper graph is parsed by an isolated
 * stock MyBatis 3.6.0 runtime. Source snapshots remain the revision authority; unrelated mapper
 * declarations do not become preparation authority.
 *
 * Static zero-input statements and the deliberately narrow proven scalar/temporal bound-input
 * island and source-proven foreach/Boolean-if contracts are admitted. Dynamic preparation owns
 * a fresh runtime; flat Boolean-if siblings directly or inside bounded where/set/trim wrappers require
 * complete mapper capture. A single foreach requires the same capture and may be direct or inside
 * a where-role wrapper, or a set-role wrapper for UPDATE. Direct foreach and direct Boolean-if
 * siblings may coexist. UPDATE wrapper composition still allows exactly one foreach only when the
 * other wrapper contains static text/caller bindings.
 * Stock MyBatis owns evaluation and WHERE/SET trimming. No MyBatis object
 * crosses the child-classloader boundary.
 */
object XmlMapperPreparationEngine {
    private const val ENGINE_ID = "org.mybatis:mybatis"
    private const val ENGINE_VERSION = "3.6.0"
    private const val CONFIGURATION = "org.apache.ibatis.session.Configuration"
    private const val XML_MAPPER_BUILDER = "org.apache.ibatis.builder.xml.XMLMapperBuilder"
    private const val MAPPED_STATEMENT = "org.apache.ibatis.mapping.MappedStatement"
    private const val BOUND_SQL = "org.apache.ibatis.mapping.BoundSql"
    private const val RESOURCES = "org.apache.ibatis.io.Resources"

    private const val WRONG_SOURCE = "xml-preparation-source-kind-unsupported"
    private const val INPUT_CONTRACT_UNSUPPORTED = "xml-preparation-input-contract-unsupported"
    private const val RAW_INPUT_UNSUPPORTED = "xml-preparation-raw-input-unsupported"
    private const val BOUND_SHAPE_UNSUPPORTED = "xml-preparation-bound-shape-unsupported"
    private const val ALIAS_KIND_UNSUPPORTED = "xml-preparation-alias-kind-unsupported"
    private const val PARAMETER_OBJECT_CONTRACT_INVALID =
        "xml-preparation-parameter-object-contract-invalid"
    private const val PARAMETER_OBJECT_PROPERTY_MISSING =
        "xml-preparation-parameter-object-property-missing"
    private const val NAMED_MAP_CONTRACT_INVALID =
        "xml-preparation-named-map-contract-invalid"
    private const val NAMED_MAP_PROPERTY_MISSING =
        "xml-preparation-named-map-property-missing"
    private const val PARAMETER_OBJECT_FALLBACK_RULE =
        "mybatis-default-parameter-handler-type-handler-fallback"
    private const val RAW_PARAMETER_OBJECT_FALLBACK_RULE =
        "mybatis-dynamic-context-fallback-parameter-object"
    private const val PARAMETER_OBJECT_PROPERTY_RULE =
        "mybatis-default-parameter-handler-meta-object-property"
    private const val NAMED_MAP_PROPERTY_RULE =
        "mybatis-default-parameter-handler-named-map-property"
    private const val GENERIC_ALIAS_RULE =
        "mybatis-param-name-resolver-generic"
    private const val MAPPING_PROPERTY_MISSING = "xml-preparation-mapping-property-missing"
    private const val MAPPING_PROPERTY_NESTED = "xml-preparation-nested-mapping-unsupported"
    private const val MAPPING_ALIAS_UNRESOLVED = "xml-preparation-mapping-alias-unresolved"
    private const val MAPPING_CARDINALITY_MISMATCH = "xml-preparation-mapping-cardinality-mismatch"
    private const val PARAMETER_MODE_UNSUPPORTED = "xml-preparation-parameter-mode-unsupported"
    private const val TYPE_HANDLER_UNSUPPORTED = "xml-preparation-type-handler-unsupported"
    private const val BINDING_VALUE_UNSUPPORTED = "xml-preparation-binding-value-unsupported"
    private const val DYNAMIC_UNSUPPORTED = "xml-preparation-dynamic-sql-unsupported"
    private const val ROOT_STATEMENT_MISSING = "xml-preparation-root-statement-missing"
    private const val ROOT_RESOURCE_MISMATCH = "xml-preparation-root-resource-mismatch"
    private const val STATEMENT_KIND_MISMATCH = "xml-preparation-statement-kind-mismatch"
    private const val EMPTY_SQL = "mybatis-prepared-sql-empty"
    private const val PARSE_FAILURE = "mybatis-xml-mapper-parse-failure"
    private const val BOOLEAN_BOUND_TOKEN_TOPOLOGY_UNSUPPORTED =
        "xml-boolean-if-bound-token-topology-unsupported"
    private const val FOREACH_BOUND_TOKEN_TOPOLOGY_UNSUPPORTED =
        "xml-foreach-bound-token-topology-unsupported"
    private const val INVARIANT_FAILURE = "mybatis-preparation-invariant-failure"
    private const val CLASSLOADER_INVARIANT = "mybatis-xml-classloader-isolation-failure"

    private val rawInterpolation = Regex("""\$\{([^}]*)}""")
    private val runtimeDynamicSyntax = Regex(
        """(?is)<\s*(?:if|choose|when|otherwise|foreach|where|set|trim|bind)\b|\$\{""",
    )
    private val rawStructuralAttribute = Regex("""(?s)<(?![!?])[^>]*\$\{""")
    private val simpleIdentifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val parameterObjectMapValueTypes = setOf(
        "java.lang.Boolean",
        "java.lang.Byte",
        "java.lang.Short",
        "java.lang.Integer",
        "java.lang.Long",
        "java.lang.Float",
        "java.lang.Double",
        "java.lang.String",
        "java.math.BigInteger",
        "java.math.BigDecimal",
        "java.time.Instant",
        "java.time.LocalDate",
        "java.time.LocalDateTime",
        "java.time.LocalTime",
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

        val projection = when (
            val result = XmlMapperPreparationProjection.project(
                source = source,
                statementId = statementId,
                effectiveDatabaseId = request.effectiveDatabaseId,
            )
        ) {
            is XmlMapperPreparationProjection.Result.Ready -> result
            is XmlMapperPreparationProjection.Result.Failed -> return PreparationResult.Failed(result.failure)
        }

        MyBatisParameterMappingCapabilityAdmission.failureForSourceTexts(
            projection.documents.map { it.content },
        )?.let { return PreparationResult.Failed(it) }

        DynamicOgnlSafetyAdmission.failureForMapperDocuments(
            projection.documents.map { it.content },
            databaseIdAvailable = request.effectiveDatabaseId != null,
        )?.let { return PreparationResult.Failed(it) }

        val dynamicDocuments = projection.documents.filter { document ->
            runtimeDynamicSyntax.containsMatchIn(document.content)
        }
        if (dynamicDocuments.isNotEmpty()) {
            val topologyFailureCode = if (
                dynamicDocuments.any { document ->
                    Regex("""(?is)<\s*foreach\b""").containsMatchIn(document.content)
                }
            ) {
                FOREACH_BOUND_TOKEN_TOPOLOGY_UNSUPPORTED
            } else {
                BOOLEAN_BOUND_TOKEN_TOPOLOGY_UNSUPPORTED
            }
            for (document in dynamicDocuments) {
                DynamicBoundTokenTopologyAdmission.failureOrNull(
                    document.content,
                    rootPath = "/mapper",
                    unsupportedCode = topologyFailureCode,
                    parseFailureCode = PARSE_FAILURE,
                )?.let { return PreparationResult.Failed(it) }
            }
        }

        val usage = MyBatisInvocationContractFactory.XmlInvocationUsageScanner.scan(projection)
        rawSourceFailureP2(usage.rawUses, request)?.let {
            return PreparationResult.Failed(it)
        }

        val foreachLocals = DynamicForeachProvenanceScanner.forMapperDocuments(
            projection.documents.map { it.content },
        )

        val payload = when (val result = MyBatisInvocationParameterObjectFactory.create(request)) {
            is MyBatisInvocationParameterObjectFactory.Result.Success -> result.payload
            is MyBatisInvocationParameterObjectFactory.Result.Failed ->
                return PreparationResult.Failed(result.failure)
        }

        val requiresFreshRuntime = dynamicDocuments.isNotEmpty()

        return try {
            if (requiresFreshRuntime) {
                val runtime = createIsolatedRuntime(fresh = true)
                runtime.loader.use {
                    prepareP2WithContextLoader(
                        runtime,
                        source,
                        projection,
                        statementId,
                        request,
                        payload,
                        foreachLocals,
                    )
                }
            } else {
                prepareP2WithContextLoader(
                    isolatedRuntime,
                    source,
                    projection,
                    statementId,
                    request,
                    payload,
                    foreachLocals,
                )
            }
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
                        kind = if (diagnosticType(failure).contains(".ognl.")) {
                            PreparationFailureKind.OGNL
                        } else {
                            PreparationFailureKind.MYBATIS_PARSE
                        },
                        code = if (diagnosticType(failure).contains(".ognl.")) {
                            "mybatis-ognl-evaluation-failure"
                        } else {
                            PARSE_FAILURE
                        },
                        diagnosticType = diagnosticType(failure),
                    ),
                )
            }
        }
    }

    private fun prepareP2WithContextLoader(
        runtime: IsolatedRuntime,
        source: XmlMapperPreparationSource,
        projection: XmlMapperPreparationProjection.Result.Ready,
        statementId: XmlStatementId,
        request: MyBatisPreparationRequest,
        payload: MyBatisInvocationParameterObjectFactory.Ready,
        foreachLocals: Map<String, com.algorist.zMyBatis.core.input.InternalBindingKind>,
    ): PreparationResult {
        val thread = Thread.currentThread()
        val previousContextLoader = thread.contextClassLoader
        var switched = false
        return try {
            thread.contextClassLoader = runtime.loader
            switched = true
            prepareP2In(runtime, source, projection, statementId, request, payload, foreachLocals)
        } finally {
            if (switched) thread.contextClassLoader = previousContextLoader
        }
    }

    private fun prepareP2In(
        runtime: IsolatedRuntime,
        source: XmlMapperPreparationSource,
        projection: XmlMapperPreparationProjection.Result.Ready,
        statementId: XmlStatementId,
        request: MyBatisPreparationRequest,
        payload: MyBatisInvocationParameterObjectFactory.Ready,
        foreachLocals: Map<String, com.algorist.zMyBatis.core.input.InternalBindingKind>,
    ): PreparationResult {
        val configurationClass = runtime.configurationClass
        val builderClass = runtime.builderClass
        val mappedStatementClass = runtime.mappedStatementClass
        val boundSqlClass = runtime.boundSqlClass

        val configuration = configurationClass.getDeclaredConstructor().newInstance()
        request.effectiveDatabaseId?.let { databaseId ->
            configurationClass
                .getMethod("setDatabaseId", String::class.java)
                .invoke(configuration, databaseId.value)
        }
        val sqlFragments = configurationClass.getMethod("getSqlFragments").invoke(configuration) as? Map<*, *>
            ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, INVARIANT_FAILURE)
        val constructor = builderClass.getConstructor(
            InputStream::class.java,
            configurationClass,
            String::class.java,
            Map::class.java,
        )

        projection.documents.forEach { document ->
            val resource = projectedResourceIdentity(document)
            ByteArrayInputStream(document.content.toByteArray(StandardCharsets.UTF_8)).use { input ->
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
            rethrowFatal(failure)
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

        val projectedRoot = projection.documents.singleOrNull { it.rootStatementDocument }
            ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, INVARIANT_FAILURE)
        val mappedResource = mappedStatementClass.getMethod("getResource").invoke(mappedStatement) as? String
        if (mappedResource != projectedResourceIdentity(projectedRoot)) {
            return failed(PreparationFailureKind.PREPARATION_INVARIANT, ROOT_RESOURCE_MISMATCH)
        }

        val sqlCommandType = mappedStatementClass.getMethod("getSqlCommandType").invoke(mappedStatement) as? Enum<*>
            ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, INVARIANT_FAILURE)
        if (sqlCommandType.name != request.statementKind.name) {
            return failed(PreparationFailureKind.PREPARATION_INVARIANT, STATEMENT_KIND_MISMATCH)
        }

        val parameterObject = payload.parameterObject
        val boundSql = try {
            mappedStatementClass
                .getMethod("getBoundSql", Any::class.java)
                .invoke(mappedStatement, parameterObject)
        } catch (failure: InvocationTargetException) {
            rethrowFatal(failure)
            throw failure.targetException ?: failure
        } ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, INVARIANT_FAILURE)
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
        val snapshots = IsolatedDynamicMyBatisPreparation.snapshotMappings(
            runtime.loader,
            configuration,
            boundSql,
            parameterObject,
            mappings,
            foreachLocals,
        )
        val bindings = when (
            val captured = MyBatisInvocationBindingCapture.capture(
                snapshots = snapshots,
                request = request,
                payload = payload,
            )
        ) {
            is MyBatisInvocationBindingCapture.Result.Ready -> captured.bindings
            is MyBatisInvocationBindingCapture.Result.Failed ->
                return PreparationResult.Failed(captured.failure)
        }

        val rawInterpolations = mutableListOf<PreparedRawInterpolation>()
        for (requirement in request.parameterContract.requirements.filter { it.usesRawInterpolation }) {
            val provided = request.inputEnvironment.value(requirement.id)
                ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, RAW_INPUT_UNSUPPORTED)
            rawInterpolations += PreparedRawInterpolation(
                requirementId = requirement.id,
                provenance = requirement.provenance,
                origin = provided.origin,
            )
        }

        val languageDriver = mappedStatementClass.getMethod("getLang").invoke(mappedStatement)
            ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, INVARIANT_FAILURE)
        if (languageDriver.javaClass.classLoader !== runtime.loader) {
            return failed(
                PreparationFailureKind.PREPARATION_INVARIANT,
                "mybatis-language-driver-not-isolated",
            )
        }

        return try {
            PreparationResult.Success(
                PreparedExecution(
                    statementId = request.statementId,
                    statementKind = request.statementKind,
                    sourceRevisions = request.sourceRevisions,
                    sqlWithPlaceholders = sql,
                    orderedBindings = bindings,
                    rawInterpolations = rawInterpolations,
                    preparationMetadata = PreparationMetadata(
                        engineIdentity = ENGINE_ID,
                        engineVersion = ENGINE_VERSION,
                        languageDriverIdentity = languageDriver.javaClass.name,
                        databaseId = request.effectiveDatabaseId?.value,
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

    private fun rawSourceFailureP2(
        rawUses: List<MyBatisInvocationContractFactory.RawUse>,
        request: MyBatisPreparationRequest,
    ): PreparationFailure? {
        val expected = rawUses
            .groupingBy { it.source.sourceFileId to it.expression.trim() }
            .eachCount()
        val proven = request.parameterContract.requirements
            .asSequence()
            .filter { it.usesRawInterpolation }
            .flatMap { it.provenance.evidence.asSequence() }
            .filterIsInstance<InputEvidence.Placeholder>()
            .filter { it.kind == InputKind.RAW_INTERPOLATION }
            .map { it.source.sourceFileId to it.expression.trim() }
            .groupingBy { it }
            .eachCount()
        return if (expected == proven) null else rawInputUnsupported()
    }

    /**
     * Static preparation reuses an immutable loader. Admitted dynamic preparation owns a fresh loader and bypasses
     * shared resource-stream caching so closing one invocation cannot interrupt another DTD read.
     * Every preparation owns its Configuration, mapper builders and fragment map.
     */
    private fun createIsolatedRuntime(fresh: Boolean = false): IsolatedRuntime {
        val myBatisLocation = MyBatisRuntimeArtifact.resolve(Configuration::class.java)
            ?: throw IsolationFailure("mybatis-code-source-unavailable")
        if (myBatisLocation.protocol != "file") {
            throw IsolationFailure("mybatis-code-source-non-local")
        }

        val loader = if (fresh) {
            object : URLClassLoader(arrayOf(myBatisLocation), platformLoader) {
                // A fresh loader must not close a globally cached DTD JAR stream owned by another invocation.
                override fun getResourceAsStream(name: String): InputStream? =
                    getResource(name)?.openConnection()?.apply { useCaches = false }?.getInputStream()
            }
        } else {
            URLClassLoader(arrayOf(myBatisLocation), platformLoader)
        }
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
     * MyBatis Resources.classForName() falls back through default, TCCL, its own loader,
     * and the system classloader, and initializes a class once found. This dedicated Resources copy
     * is hardened once during isolated-runtime initialization and is never mutated per preparation.
     * prepareP2WithContextLoader() pins the TCCL leg to the same isolated loader for each call.
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

    private fun rawInputUnsupported(): PreparationFailure = PreparationFailure(
        PreparationFailureKind.UNSUPPORTED_SEMANTIC,
        RAW_INPUT_UNSUPPORTED,
    )

    private fun projectedResourceIdentity(
        document: XmlMapperPreparationProjection.ProjectedDocument,
    ): String {
        val base = "zmybatis:${document.sourceFileId.value}@${document.sourceRevision.value}"
        return if (document.rootStatementDocument) base else "$base#projection-fragments"
    }

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
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = failure
        while (current != null && visited.add(current)) {
            if (current !is Exception || current is CancellationException) throw current
            current = if (current is InvocationTargetException) current.targetException else current.cause
        }
    }
}
