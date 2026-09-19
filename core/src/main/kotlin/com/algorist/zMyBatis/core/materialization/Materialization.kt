package com.algorist.zMyBatis.core.materialization

import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.preparation.PreparationMetadata
import com.algorist.zMyBatis.core.preparation.PreparedBinding
import com.algorist.zMyBatis.core.preparation.PreparedExecution
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.StatementId
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.XmlStatementId
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

private val SHA_256_HEX = Regex("[0-9a-f]{64}")
private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)

@JvmInline
value class TargetDialectIdentity(val value: String) {
    init {
        require(value.isNotBlank()) { "target dialect identity must not be blank" }
    }
}

@JvmInline
value class MaterializationFingerprint internal constructor(val value: String) {
    init {
        require(SHA_256_HEX.matches(value)) {
            "materialization fingerprint must be a lowercase SHA-256 hex digest"
        }
    }
}

data class MaterializationSafetyFlags(
    val declaredMutation: Boolean,
    val containsRawInterpolation: Boolean,
)

enum class MaterializationFailureKind {
    DIALECT_LITERALIZATION_REQUIRED,
    RAW_INTERPOLATION_REQUIRES_POLICY,
    DIALECT_UNSUPPORTED,
    PREPARATION_METADATA_UNSUPPORTED,
    BINDING_METADATA_UNSUPPORTED,
    BINDING_VALUE_UNSUPPORTED,
    BINDING_VALUE_OUT_OF_RANGE,
    PLACEHOLDER_TOPOLOGY_UNPROVEN,
}

data class MaterializationFailure(
    val kind: MaterializationFailureKind,
    val code: String,
) {
    init {
        require(code.isNotBlank()) { "materialization failure code must not be blank" }
    }
}

sealed interface MaterializationResult {
    data class Success(val execution: MaterializedExecution) : MaterializationResult

    data class Failed(val failure: MaterializationFailure) : MaterializationResult
}

class MaterializedExecution internal constructor(
    val statementId: StatementId,
    val statementKind: StatementKind,
    sourceRevisions: Map<SourceFileId, SourceRevision>,
    val preparationMetadata: PreparationMetadata,
    val targetDialectIdentity: TargetDialectIdentity,
    val executionSql: String,
    val safetyFlags: MaterializationSafetyFlags,
    val fingerprint: MaterializationFingerprint,
) {
    private val sourceRevisionSnapshot = LinkedHashMap(
        sourceRevisions.entries.sortedBy { it.key.value }.associate { it.toPair() },
    )

    init {
        require(statementId.sourceFileId in sourceRevisionSnapshot) {
            "materialized execution must include its root source revision"
        }
        require(executionSql.isNotBlank()) { "materialized execution SQL must not be blank" }
    }

    val sourceRevisions: Map<SourceFileId, SourceRevision>
        get() = LinkedHashMap(sourceRevisionSnapshot)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MaterializedExecution) return false

        return statementId == other.statementId &&
            statementKind == other.statementKind &&
            sourceRevisionSnapshot == other.sourceRevisionSnapshot &&
            preparationMetadata == other.preparationMetadata &&
            targetDialectIdentity == other.targetDialectIdentity &&
            executionSql == other.executionSql &&
            safetyFlags == other.safetyFlags &&
            fingerprint == other.fingerprint
    }

    override fun hashCode(): Int {
        var result = statementId.hashCode()
        result = 31 * result + statementKind.hashCode()
        result = 31 * result + sourceRevisionSnapshot.hashCode()
        result = 31 * result + preparationMetadata.hashCode()
        result = 31 * result + targetDialectIdentity.hashCode()
        result = 31 * result + executionSql.hashCode()
        result = 31 * result + safetyFlags.hashCode()
        result = 31 * result + fingerprint.hashCode()
        return result
    }
}

fun interface ExecutionMaterializer {
    fun materialize(
        prepared: PreparedExecution,
        targetDialectIdentity: TargetDialectIdentity,
    ): MaterializationResult
}

/**
 * First fail-closed materialization island.
 *
 * This materializer never scans or substitutes question marks. It only admits PreparedExecution
 * instances that require no bound-value literalization and carry no raw-interpolation provenance.
 * Database-specific literal encoders belong to maintained dialect-owned materializers.
 */
object ZeroBindingExecutionMaterializer : ExecutionMaterializer {
    override fun materialize(
        prepared: PreparedExecution,
        targetDialectIdentity: TargetDialectIdentity,
    ): MaterializationResult {
        if (prepared.rawInterpolations.isNotEmpty()) {
            return failed(
                MaterializationFailureKind.RAW_INTERPOLATION_REQUIRES_POLICY,
                RAW_INTERPOLATION_POLICY_REQUIRED,
            )
        }
        if (prepared.orderedBindings.isNotEmpty()) {
            return failed(
                MaterializationFailureKind.DIALECT_LITERALIZATION_REQUIRED,
                BINDING_LITERALIZATION_REQUIRED,
            )
        }

        return materialized(
            prepared = prepared,
            targetDialectIdentity = targetDialectIdentity,
            executionSql = prepared.sqlWithPlaceholders,
        )
    }
}

/**
 * Maintained production materializer.
 *
 * Zero-binding behavior is deliberately inherited unchanged. Maintained non-zero PostgreSQL
 * bindings are admitted only through exact MyBatis handler semantics independently proven against
 * pgjdbc. LongTypeHandler/setLong maps to INT8; BooleanTypeHandler/setBoolean maps to BOOL.
 *
 * Placeholder substitution is admitted only when the SQL topology is trivially provable. This
 * intentionally rejects quoted/comment/dollar syntax instead of attempting a partial SQL lexer.
 */
object MaintainedExecutionMaterializer : ExecutionMaterializer {
    private const val POSTGRESQL = "postgresql"
    private const val MYBATIS_ENGINE_ID = "org.mybatis:mybatis"
    private const val MYBATIS_ENGINE_VERSION = "3.5.19"
    private const val XML_LANGUAGE_DRIVER = "org.apache.ibatis.scripting.xmltags.XMLLanguageDriver"
    private const val LONG_JAVA_TYPE = "java.lang.Long"
    private const val LONG_TYPE_HANDLER = "org.apache.ibatis.type.LongTypeHandler"
    private const val BIGINT_JDBC_TYPE = "BIGINT"
    private const val BOOLEAN_JAVA_TYPE = "java.lang.Boolean"
    private const val BOOLEAN_TYPE_HANDLER = "org.apache.ibatis.type.BooleanTypeHandler"
    private const val BOOLEAN_JDBC_TYPE = "BOOLEAN"
    private const val INPUT_MODE = "IN"

    override fun materialize(
        prepared: PreparedExecution,
        targetDialectIdentity: TargetDialectIdentity,
    ): MaterializationResult {
        if (prepared.rawInterpolations.isNotEmpty()) {
            return failed(
                MaterializationFailureKind.RAW_INTERPOLATION_REQUIRES_POLICY,
                RAW_INTERPOLATION_POLICY_REQUIRED,
            )
        }
        if (prepared.orderedBindings.isEmpty()) {
            return ZeroBindingExecutionMaterializer.materialize(prepared, targetDialectIdentity)
        }
        if (targetDialectIdentity.value != POSTGRESQL) {
            return failed(
                MaterializationFailureKind.DIALECT_UNSUPPORTED,
                POSTGRESQL_DIALECT_REQUIRED,
            )
        }
        if (
            prepared.preparationMetadata.engineIdentity != MYBATIS_ENGINE_ID ||
            prepared.preparationMetadata.engineVersion != MYBATIS_ENGINE_VERSION ||
            prepared.preparationMetadata.languageDriverIdentity != XML_LANGUAGE_DRIVER
        ) {
            return failed(
                MaterializationFailureKind.PREPARATION_METADATA_UNSUPPORTED,
                preparationMetadataFailureCode(prepared.orderedBindings),
            )
        }
        if (!hasProvenSimpleQuestionMarkTopology(prepared.sqlWithPlaceholders, prepared.orderedBindings.size)) {
            return failed(
                MaterializationFailureKind.PLACEHOLDER_TOPOLOGY_UNPROVEN,
                PLACEHOLDER_TOPOLOGY_REQUIRED,
            )
        }

        val replacements = ArrayList<String>(prepared.orderedBindings.size)
        prepared.orderedBindings.forEach { binding ->
            when (val rendered = renderPostgresqlBinding(binding)) {
                is BindingRender.Ready -> replacements += rendered.sql
                is BindingRender.Failed -> return MaterializationResult.Failed(rendered.failure)
            }
        }

        return materialized(
            prepared = prepared,
            targetDialectIdentity = targetDialectIdentity,
            executionSql = replaceProvenQuestionMarks(prepared.sqlWithPlaceholders, replacements),
        )
    }

    private fun preparationMetadataFailureCode(bindings: List<PreparedBinding>): String {
        val families = bindings.mapNotNullTo(linkedSetOf()) { binding ->
            when {
                binding.metadata.mappingJavaTypeIdentity == LONG_JAVA_TYPE -> BindingFamily.BIGINT
                binding.metadata.mappingJavaTypeIdentity == BOOLEAN_JAVA_TYPE -> BindingFamily.BOOLEAN
                binding.metadata.typeHandlerIdentity == LONG_TYPE_HANDLER -> BindingFamily.BIGINT
                binding.metadata.typeHandlerIdentity == BOOLEAN_TYPE_HANDLER -> BindingFamily.BOOLEAN
                else -> null
            }
        }
        return when (families.singleOrNull()) {
            BindingFamily.BIGINT -> POSTGRESQL_BIGINT_PREPARATION_METADATA_REQUIRED
            BindingFamily.BOOLEAN -> POSTGRESQL_BOOLEAN_PREPARATION_METADATA_REQUIRED
            null -> POSTGRESQL_PREPARATION_METADATA_REQUIRED
        }
    }

    private fun renderPostgresqlBinding(binding: PreparedBinding): BindingRender {
        val metadata = binding.metadata
        return when {
            metadata.mappingJavaTypeIdentity == LONG_JAVA_TYPE -> renderPostgresqlBigint(binding)
            metadata.mappingJavaTypeIdentity == BOOLEAN_JAVA_TYPE -> renderPostgresqlBoolean(binding)
            metadata.typeHandlerIdentity == LONG_TYPE_HANDLER -> renderPostgresqlBigint(binding)
            metadata.typeHandlerIdentity == BOOLEAN_TYPE_HANDLER -> renderPostgresqlBoolean(binding)
            else -> renderFailure(
                MaterializationFailureKind.BINDING_METADATA_UNSUPPORTED,
                POSTGRESQL_TYPE_HANDLER_REQUIRED,
            )
        }
    }

    private fun renderPostgresqlBigint(binding: PreparedBinding): BindingRender {
        val metadata = binding.metadata
        if (metadata.mappingJavaTypeIdentity != LONG_JAVA_TYPE) {
            return renderFailure(
                MaterializationFailureKind.BINDING_METADATA_UNSUPPORTED,
                POSTGRESQL_LONG_MAPPING_JAVA_TYPE_REQUIRED,
            )
        }
        if (metadata.typeHandlerIdentity != LONG_TYPE_HANDLER) {
            return renderFailure(
                MaterializationFailureKind.BINDING_METADATA_UNSUPPORTED,
                POSTGRESQL_LONG_TYPE_HANDLER_REQUIRED,
            )
        }
        if (metadata.jdbcTypeIdentity != null && metadata.jdbcTypeIdentity != BIGINT_JDBC_TYPE) {
            return renderFailure(
                MaterializationFailureKind.BINDING_METADATA_UNSUPPORTED,
                POSTGRESQL_BIGINT_JDBC_TYPE_REQUIRED,
            )
        }
        if (metadata.parameterMode != INPUT_MODE) {
            return renderFailure(
                MaterializationFailureKind.BINDING_METADATA_UNSUPPORTED,
                POSTGRESQL_INPUT_MODE_REQUIRED,
            )
        }
        if (metadata.numericScale != null) {
            return renderFailure(
                MaterializationFailureKind.BINDING_METADATA_UNSUPPORTED,
                POSTGRESQL_BIGINT_NUMERIC_SCALE_UNSUPPORTED,
            )
        }

        val value = binding.value as? InputValue.IntegerValue
            ?: return renderFailure(
                MaterializationFailureKind.BINDING_VALUE_UNSUPPORTED,
                POSTGRESQL_BIGINT_VALUE_REQUIRED,
            )
        if (value.value !in LONG_MIN..LONG_MAX) {
            return renderFailure(
                MaterializationFailureKind.BINDING_VALUE_OUT_OF_RANGE,
                POSTGRESQL_BIGINT_VALUE_OUT_OF_RANGE,
            )
        }

        return BindingRender.Ready("CAST(" + value.value + " AS BIGINT)")
    }

    private fun renderPostgresqlBoolean(binding: PreparedBinding): BindingRender {
        val metadata = binding.metadata
        if (metadata.mappingJavaTypeIdentity != BOOLEAN_JAVA_TYPE) {
            return renderFailure(
                MaterializationFailureKind.BINDING_METADATA_UNSUPPORTED,
                POSTGRESQL_BOOLEAN_MAPPING_JAVA_TYPE_REQUIRED,
            )
        }
        if (metadata.typeHandlerIdentity != BOOLEAN_TYPE_HANDLER) {
            return renderFailure(
                MaterializationFailureKind.BINDING_METADATA_UNSUPPORTED,
                POSTGRESQL_BOOLEAN_TYPE_HANDLER_REQUIRED,
            )
        }
        if (metadata.jdbcTypeIdentity != null && metadata.jdbcTypeIdentity != BOOLEAN_JDBC_TYPE) {
            return renderFailure(
                MaterializationFailureKind.BINDING_METADATA_UNSUPPORTED,
                POSTGRESQL_BOOLEAN_JDBC_TYPE_REQUIRED,
            )
        }
        if (metadata.parameterMode != INPUT_MODE) {
            return renderFailure(
                MaterializationFailureKind.BINDING_METADATA_UNSUPPORTED,
                POSTGRESQL_BOOLEAN_INPUT_MODE_REQUIRED,
            )
        }
        if (metadata.numericScale != null) {
            return renderFailure(
                MaterializationFailureKind.BINDING_METADATA_UNSUPPORTED,
                POSTGRESQL_BOOLEAN_NUMERIC_SCALE_UNSUPPORTED,
            )
        }

        val value = binding.value as? InputValue.BooleanValue
            ?: return renderFailure(
                MaterializationFailureKind.BINDING_VALUE_UNSUPPORTED,
                POSTGRESQL_BOOLEAN_VALUE_REQUIRED,
            )
        val literal = if (value.value) "TRUE" else "FALSE"
        return BindingRender.Ready("CAST(" + literal + " AS BOOLEAN)")
    }

    private fun hasProvenSimpleQuestionMarkTopology(sql: String, bindingCount: Int): Boolean {
        if (sql.indexOf('\'') >= 0 || sql.indexOf('"') >= 0 || sql.indexOf('$') >= 0) return false
        if ("--" in sql || "/*" in sql || "*/" in sql) return false
        return sql.count { it == '?' } == bindingCount
    }

    private fun replaceProvenQuestionMarks(sql: String, replacements: List<String>): String {
        val result = StringBuilder(sql.length + replacements.sumOf { it.length })
        var replacementIndex = 0
        sql.forEach { character ->
            if (character == '?') {
                result.append(replacements[replacementIndex++])
            } else {
                result.append(character)
            }
        }
        check(replacementIndex == replacements.size) {
            "proven placeholder topology changed during immutable materialization"
        }
        return result.toString()
    }

    private fun renderFailure(kind: MaterializationFailureKind, code: String): BindingRender.Failed =
        BindingRender.Failed(MaterializationFailure(kind, code))

    private enum class BindingFamily {
        BIGINT,
        BOOLEAN,
    }

    private sealed interface BindingRender {
        data class Ready(val sql: String) : BindingRender

        data class Failed(val failure: MaterializationFailure) : BindingRender
    }
}

private const val BINDING_LITERALIZATION_REQUIRED = "materialization-dialect-literalization-required"
private const val RAW_INTERPOLATION_POLICY_REQUIRED = "materialization-raw-interpolation-policy-required"
private const val POSTGRESQL_DIALECT_REQUIRED = "materialization-postgresql-dialect-required"
private const val POSTGRESQL_PREPARATION_METADATA_REQUIRED =
    "materialization-postgresql-preparation-metadata-unsupported"
private const val POSTGRESQL_BIGINT_PREPARATION_METADATA_REQUIRED =
    "materialization-postgresql-bigint-preparation-metadata-unsupported"
private const val POSTGRESQL_BOOLEAN_PREPARATION_METADATA_REQUIRED =
    "materialization-postgresql-boolean-preparation-metadata-unsupported"
private const val PLACEHOLDER_TOPOLOGY_REQUIRED = "materialization-placeholder-topology-unproven"
private const val POSTGRESQL_TYPE_HANDLER_REQUIRED =
    "materialization-postgresql-type-handler-unsupported"
private const val POSTGRESQL_LONG_MAPPING_JAVA_TYPE_REQUIRED =
    "materialization-postgresql-bigint-mapping-java-type-unsupported"
private const val POSTGRESQL_LONG_TYPE_HANDLER_REQUIRED =
    "materialization-postgresql-bigint-type-handler-unsupported"
private const val POSTGRESQL_BIGINT_JDBC_TYPE_REQUIRED =
    "materialization-postgresql-bigint-jdbc-type-unsupported"
private const val POSTGRESQL_INPUT_MODE_REQUIRED =
    "materialization-postgresql-bigint-parameter-mode-unsupported"
private const val POSTGRESQL_BIGINT_NUMERIC_SCALE_UNSUPPORTED =
    "materialization-postgresql-bigint-numeric-scale-unsupported"
private const val POSTGRESQL_BIGINT_VALUE_REQUIRED =
    "materialization-postgresql-bigint-value-unsupported"
private const val POSTGRESQL_BIGINT_VALUE_OUT_OF_RANGE =
    "materialization-postgresql-bigint-value-out-of-range"
private const val POSTGRESQL_BOOLEAN_MAPPING_JAVA_TYPE_REQUIRED =
    "materialization-postgresql-boolean-mapping-java-type-unsupported"
private const val POSTGRESQL_BOOLEAN_TYPE_HANDLER_REQUIRED =
    "materialization-postgresql-boolean-type-handler-unsupported"
private const val POSTGRESQL_BOOLEAN_JDBC_TYPE_REQUIRED =
    "materialization-postgresql-boolean-jdbc-type-unsupported"
private const val POSTGRESQL_BOOLEAN_INPUT_MODE_REQUIRED =
    "materialization-postgresql-boolean-parameter-mode-unsupported"
private const val POSTGRESQL_BOOLEAN_NUMERIC_SCALE_UNSUPPORTED =
    "materialization-postgresql-boolean-numeric-scale-unsupported"
private const val POSTGRESQL_BOOLEAN_VALUE_REQUIRED =
    "materialization-postgresql-boolean-value-unsupported"
private const val FINGERPRINT_VERSION = "zmybatis-materialized-execution-v1"

private fun failed(kind: MaterializationFailureKind, code: String): MaterializationResult.Failed =
    MaterializationResult.Failed(MaterializationFailure(kind, code))

private fun materialized(
    prepared: PreparedExecution,
    targetDialectIdentity: TargetDialectIdentity,
    executionSql: String,
): MaterializationResult.Success {
    val safetyFlags = MaterializationSafetyFlags(
        declaredMutation = prepared.statementKind != StatementKind.SELECT,
        containsRawInterpolation = false,
    )
    val fingerprint = fingerprint(
        prepared = prepared,
        targetDialectIdentity = targetDialectIdentity,
        executionSql = executionSql,
        safetyFlags = safetyFlags,
    )

    return MaterializationResult.Success(
        MaterializedExecution(
            statementId = prepared.statementId,
            statementKind = prepared.statementKind,
            sourceRevisions = prepared.sourceRevisions,
            preparationMetadata = prepared.preparationMetadata,
            targetDialectIdentity = targetDialectIdentity,
            executionSql = executionSql,
            safetyFlags = safetyFlags,
            fingerprint = fingerprint,
        ),
    )
}

private fun fingerprint(
    prepared: PreparedExecution,
    targetDialectIdentity: TargetDialectIdentity,
    executionSql: String,
    safetyFlags: MaterializationSafetyFlags,
): MaterializationFingerprint {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.field(FINGERPRINT_VERSION)
    digest.statementId(prepared.statementId)
    digest.field(prepared.statementKind.name)

    digest.field(prepared.sourceRevisions.size.toString())
    prepared.sourceRevisions.entries
        .sortedBy { it.key.value }
        .forEach { (fileId, revision) ->
            digest.field(fileId.value)
            digest.field(revision.value)
        }

    digest.field(prepared.preparationMetadata.engineIdentity)
    digest.field(prepared.preparationMetadata.engineVersion)
    digest.field(prepared.preparationMetadata.languageDriverIdentity)
    digest.field(targetDialectIdentity.value)
    digest.field(executionSql)
    digest.field(safetyFlags.declaredMutation.toString())
    digest.field(safetyFlags.containsRawInterpolation.toString())

    return MaterializationFingerprint(HexFormat.of().formatHex(digest.digest()))
}

private fun MessageDigest.statementId(statementId: StatementId) {
    when (statementId) {
        is XmlStatementId -> {
            field("xml")
            field(statementId.sourceFileId.value)
            field(statementId.namespace)
            field(statementId.statementId)
        }
        is JavaStatementId -> {
            field("java")
            field(statementId.sourceFileId.value)
            field(statementId.qualifiedMapperType)
            field(statementId.methodSignature.name)
            field(statementId.methodSignature.parameterTypeIdentities.size.toString())
            statementId.methodSignature.parameterTypeIdentities.forEach { field(it.value) }
        }
    }
}

private fun MessageDigest.field(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    update(
        byteArrayOf(
            (bytes.size ushr 24).toByte(),
            (bytes.size ushr 16).toByte(),
            (bytes.size ushr 8).toByte(),
            bytes.size.toByte(),
        ),
    )
    update(bytes)
}
