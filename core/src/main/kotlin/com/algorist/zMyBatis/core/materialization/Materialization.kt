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
 * Database-specific literal encoders belong to later dialect-owned materializers.
 */
object ZeroBindingExecutionMaterializer : ExecutionMaterializer {
    private const val BINDING_LITERALIZATION_REQUIRED = "materialization-dialect-literalization-required"
    private const val RAW_INTERPOLATION_POLICY_REQUIRED = "materialization-raw-interpolation-policy-required"
    private const val FINGERPRINT_VERSION = "zmybatis-materialized-execution-v1"

    override fun materialize(
        prepared: PreparedExecution,
        targetDialectIdentity: TargetDialectIdentity,
    ): MaterializationResult {
        if (prepared.rawInterpolations.isNotEmpty()) {
            return MaterializationResult.Failed(
                MaterializationFailure(
                    MaterializationFailureKind.RAW_INTERPOLATION_REQUIRES_POLICY,
                    RAW_INTERPOLATION_POLICY_REQUIRED,
                ),
            )
        }
        if (prepared.orderedBindings.isNotEmpty()) {
            return MaterializationResult.Failed(
                MaterializationFailure(
                    MaterializationFailureKind.DIALECT_LITERALIZATION_REQUIRED,
                    BINDING_LITERALIZATION_REQUIRED,
                ),
            )
        }

        val safetyFlags = MaterializationSafetyFlags(
            declaredMutation = prepared.statementKind != StatementKind.SELECT,
            containsRawInterpolation = false,
        )
        val fingerprint = fingerprint(
            prepared = prepared,
            targetDialectIdentity = targetDialectIdentity,
            safetyFlags = safetyFlags,
        )

        return MaterializationResult.Success(
            MaterializedExecution(
                statementId = prepared.statementId,
                statementKind = prepared.statementKind,
                sourceRevisions = prepared.sourceRevisions,
                preparationMetadata = prepared.preparationMetadata,
                targetDialectIdentity = targetDialectIdentity,
                executionSql = prepared.sqlWithPlaceholders,
                safetyFlags = safetyFlags,
                fingerprint = fingerprint,
            ),
        )
    }

    private fun fingerprint(
        prepared: PreparedExecution,
        targetDialectIdentity: TargetDialectIdentity,
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
        digest.field(prepared.sqlWithPlaceholders)
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
}
