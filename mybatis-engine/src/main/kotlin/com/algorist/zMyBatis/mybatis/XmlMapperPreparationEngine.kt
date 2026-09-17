package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationMetadata
import com.algorist.zMyBatis.core.preparation.PreparationResult
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
 * Bounded XML mapper preparation island for #149.
 *
 * The complete captured mapper documents are parsed by an isolated stock MyBatis 3.5.19 runtime.
 * Only static, zero-input mapped statements are admitted in this slice. No MyBatis object crosses
 * the child-classloader boundary.
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
    private const val PLACEHOLDER_UNSUPPORTED = "xml-preparation-placeholder-unsupported"
    private const val DYNAMIC_UNSUPPORTED = "xml-preparation-dynamic-sql-unsupported"
    private const val CLASS_LOADING_UNSUPPORTED = "xml-preparation-runtime-class-loading-unsupported"
    private const val EXTERNAL_ENTITY_UNSUPPORTED = "xml-preparation-external-entity-unsupported"
    private const val ROOT_STATEMENT_MISSING = "xml-preparation-root-statement-missing"
    private const val ROOT_RESOURCE_MISMATCH = "xml-preparation-root-resource-mismatch"
    private const val STATEMENT_KIND_MISMATCH = "xml-preparation-statement-kind-mismatch"
    private const val MAPPING_UNEXPECTED = "xml-preparation-parameter-mapping-unexpected"
    private const val EMPTY_SQL = "mybatis-prepared-sql-empty"
    private const val PARSE_FAILURE = "mybatis-xml-mapper-parse-failure"
    private const val INVARIANT_FAILURE = "mybatis-preparation-invariant-failure"
    private const val CLASSLOADER_INVARIANT = "mybatis-xml-classloader-isolation-failure"

    private val placeholder = Regex("""[#${'$'}]\{""")
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

    fun prepare(request: MyBatisPreparationRequest): PreparationResult {
        val source = request.source as? XmlMapperPreparationSource
            ?: return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, WRONG_SOURCE)
        val statementId = source.sourceGraph.rootStatement.id as? XmlStatementId
            ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, INVARIANT_FAILURE)

        if (
            request.parameterContract.requirements.isNotEmpty() ||
            request.parameterContract.aliases.isNotEmpty() ||
            request.parameterContract.internalBindings.isNotEmpty() ||
            request.inputEnvironment.aliases.isNotEmpty() ||
            request.inputEnvironment.values.isNotEmpty()
        ) {
            return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, INPUT_CONTRACT_UNSUPPORTED)
        }

        for (snapshot in source.sourceGraph.sourceSnapshots) {
            preflight(snapshot)?.let { return PreparationResult.Failed(it) }
        }

        val myBatisLocation = Configuration::class.java.protectionDomain?.codeSource?.location
            ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, "mybatis-code-source-unavailable")
        if (myBatisLocation.protocol != "file") {
            return failed(PreparationFailureKind.PREPARATION_INVARIANT, "mybatis-code-source-non-local")
        }

        return try {
            URLClassLoader(arrayOf(myBatisLocation), platformLoader).use { loader ->
                prepareWithContextLoader(loader, source, statementId, request)
            }
        } catch (failure: Throwable) {
            rethrowFatal(failure)
            if (failure is SecurityException) {
                failed(
                    PreparationFailureKind.PREPARATION_INVARIANT,
                    CLASSLOADER_INVARIANT,
                    failure.javaClass.name,
                )
            } else {
                PreparationResult.Failed(
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
        loader: URLClassLoader,
        source: XmlMapperPreparationSource,
        statementId: XmlStatementId,
        request: MyBatisPreparationRequest,
    ): PreparationResult {
        val thread = Thread.currentThread()
        val previousContextLoader = thread.contextClassLoader
        var switched = false
        return try {
            thread.contextClassLoader = loader
            switched = true
            prepareIn(loader, source, statementId, request)
        } finally {
            if (switched) {
                thread.contextClassLoader = previousContextLoader
            }
        }
    }

    private fun prepareIn(
        loader: URLClassLoader,
        source: XmlMapperPreparationSource,
        statementId: XmlStatementId,
        request: MyBatisPreparationRequest,
    ): PreparationResult {
        if (!hardenMyBatisResourceClassLoaders(loader)) {
            return failed(PreparationFailureKind.PREPARATION_INVARIANT, CLASSLOADER_INVARIANT)
        }

        val configurationClass = Class.forName(CONFIGURATION, true, loader)
        if (configurationClass.classLoader !== loader) {
            return failed(PreparationFailureKind.PREPARATION_INVARIANT, "mybatis-configuration-not-isolated")
        }
        val builderClass = Class.forName(XML_MAPPER_BUILDER, true, loader)
        if (builderClass.classLoader !== loader) {
            return failed(PreparationFailureKind.PREPARATION_INVARIANT, "mybatis-xml-builder-not-isolated")
        }
        val mappedStatementClass = Class.forName(MAPPED_STATEMENT, true, loader)
        val dynamicSqlSourceClass = Class.forName(DYNAMIC_SQL_SOURCE, true, loader)
        val boundSqlClass = Class.forName(BOUND_SQL, true, loader)

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

        val boundSql = mappedStatementClass
            .getMethod("getBoundSql", Any::class.java)
            .invoke(mappedStatement, null)
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
        if (mappings.isNotEmpty()) {
            return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, MAPPING_UNEXPECTED)
        }

        val languageDriver = mappedStatementClass.getMethod("getLang").invoke(mappedStatement)
            ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, INVARIANT_FAILURE)
        if (languageDriver.javaClass.classLoader !== loader) {
            return failed(PreparationFailureKind.PREPARATION_INVARIANT, "mybatis-language-driver-not-isolated")
        }

        return try {
            PreparationResult.Success(
                PreparedExecution(
                    statementId = request.statementId,
                    statementKind = request.statementKind,
                    sourceRevisions = request.sourceRevisions,
                    sqlWithPlaceholders = sql,
                    orderedBindings = emptyList(),
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
     * MyBatis 3.5.19 Resources.classForName() falls back through default, TCCL, its own loader,
     * and the system classloader, and initializes a class once found. Because this Resources copy
     * lives in a fresh child loader, pinning both mutable fallback fields to that child is local to
     * one preparation and prevents mapper namespaces from escaping to plugin/test/application
     * classloaders. prepareWithContextLoader() pins the TCCL leg for the same interval.
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
        if (containsPlaceholder(content)) {
            return PreparationFailure(PreparationFailureKind.UNSUPPORTED_SEMANTIC, PLACEHOLDER_UNSUPPORTED)
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

    private fun containsPlaceholder(content: String): Boolean =
        content.indices.any { index ->
            index + 1 < content.length &&
                content[index + 1] == '{' &&
                (content[index] == '#' || content[index].code == 36)
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
