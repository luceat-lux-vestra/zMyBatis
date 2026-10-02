package com.algorist.zMyBatis.core.materialization

import com.algorist.zMyBatis.core.preparation.PreparationMetadata
import com.algorist.zMyBatis.core.preparation.PreparedExecution
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.StatementId
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.XmlStatementId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

private val SHA_256_HEX = Regex("[0-9a-f]{64}")

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
    BOUND_EXECUTION_REQUIRED,
    RAW_INTERPOLATION_REQUIRES_POLICY,
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
            executionSql == other.executionSql &&
            safetyFlags == other.safetyFlags &&
            fingerprint == other.fingerprint
    }

    override fun hashCode(): Int {
        var result = statementId.hashCode()
        result = 31 * result + statementKind.hashCode()
        result = 31 * result + sourceRevisionSnapshot.hashCode()
        result = 31 * result + preparationMetadata.hashCode()
        result = 31 * result + executionSql.hashCode()
        result = 31 * result + safetyFlags.hashCode()
        result = 31 * result + fingerprint.hashCode()
        return result
    }
}

fun interface ExecutionMaterializer {
    fun materialize(prepared: PreparedExecution): MaterializationResult
}

/**
 * Transitional fail-closed SQL-text materialization boundary.
 *
 * Only statements with zero bound parameters are admitted. Non-zero bindings remain structured on
 * PreparedExecution and must be handled by a future Database Tools execution adapter rather than
 * converted here into vendor-specific SQL literals.
 */
object ZeroBindingExecutionMaterializer : ExecutionMaterializer {
    override fun materialize(prepared: PreparedExecution): MaterializationResult {
        if (prepared.rawInterpolations.isNotEmpty()) {
            return failed(
                MaterializationFailureKind.RAW_INTERPOLATION_REQUIRES_POLICY,
                RAW_INTERPOLATION_POLICY_REQUIRED,
            )
        }
        if (prepared.orderedBindings.isNotEmpty()) {
            return failed(
                MaterializationFailureKind.BOUND_EXECUTION_REQUIRED,
                BOUND_EXECUTION_REQUIRED,
            )
        }

        return materialized(
            prepared = prepared,
            executionSql = prepared.sqlWithPlaceholders,
        )
    }
}

/**
 * Current maintained entry point.
 *
 * PostgreSQL-specific literalization experiments are intentionally not authoritative here. Until
 * the Database Tools parameterized-execution proof is complete, maintained SQL-text
 * materialization is exactly the DB-neutral zero-binding island.
 */
object MaintainedExecutionMaterializer : ExecutionMaterializer {
    override fun materialize(prepared: PreparedExecution): MaterializationResult =
        ZeroBindingExecutionMaterializer.materialize(prepared)
}

private const val BOUND_EXECUTION_REQUIRED = "materialization-bound-execution-required"
private const val RAW_INTERPOLATION_POLICY_REQUIRED = "materialization-raw-interpolation-policy-required"
private const val FINGERPRINT_VERSION = "zmybatis-materialized-execution-v2"

private fun failed(kind: MaterializationFailureKind, code: String): MaterializationResult.Failed =
    MaterializationResult.Failed(MaterializationFailure(kind, code))

private fun materialized(
    prepared: PreparedExecution,
    executionSql: String,
): MaterializationResult.Success {
    val safetyFlags = MaterializationSafetyFlags(
        declaredMutation = prepared.statementKind != StatementKind.SELECT,
        containsRawInterpolation = false,
    )
    val fingerprint = fingerprint(
        prepared = prepared,
        executionSql = executionSql,
        safetyFlags = safetyFlags,
    )

    return MaterializationResult.Success(
        MaterializedExecution(
            statementId = prepared.statementId,
            statementKind = prepared.statementKind,
            sourceRevisions = prepared.sourceRevisions,
            preparationMetadata = prepared.preparationMetadata,
            executionSql = executionSql,
            safetyFlags = safetyFlags,
            fingerprint = fingerprint,
        ),
    )
}

private fun fingerprint(
    prepared: PreparedExecution,
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
