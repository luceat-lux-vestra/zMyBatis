package com.algorist.zMyBatis.core.execution

import com.algorist.zMyBatis.core.materialization.TargetDialectIdentity
import com.algorist.zMyBatis.core.source.SourceFileId

@JvmInline
value class StableDataSourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "stable datasource id must not be blank" }
    }
}

@JvmInline
value class ExplicitSchemaIdentity(val value: String) {
    init {
        require(value.isNotBlank()) { "explicit schema identity must not be blank" }
    }
}

data class ExecutionTargetId(
    val dataSourceId: StableDataSourceId,
    val schema: ExplicitSchemaIdentity,
)

/**
 * Persistable target selection authority.
 *
 * Project identity is intentionally external storage scope. [dataSourceDisplayName] is presentation
 * metadata only and never participates in equality or hashing.
 */
class ExecutionTargetDescriptor(
    val targetId: ExecutionTargetId,
    val dataSourceDisplayName: String?,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ExecutionTargetDescriptor && targetId == other.targetId)

    override fun hashCode(): Int = targetId.hashCode()

    override fun toString(): String =
        "ExecutionTargetDescriptor(targetId=$targetId, dataSourceDisplayName=<presentation>)"
}

/**
 * Source-to-target convenience association. This is deliberately separate from target identity so
 * moving a source association does not mutate or redefine the persisted execution target.
 */
data class SourceTargetAssociation(
    val sourceFileId: SourceFileId,
    val targetId: ExecutionTargetId,
)

/**
 * Exact live target authority produced only after re-resolving a persisted descriptor.
 *
 * Dialect identity belongs here rather than in persisted target identity so materialization always
 * receives dialect semantics derived from the currently resolved target.
 */
data class ResolvedExecutionTarget(
    val descriptor: ExecutionTargetDescriptor,
    val dialectIdentity: TargetDialectIdentity,
) {
    val targetId: ExecutionTargetId
        get() = descriptor.targetId
}

enum class TargetResolutionFailureKind {
    DATA_SOURCE_MISSING,
    DATA_SOURCE_AMBIGUOUS,
    SCHEMA_MISSING,
    SCHEMA_AMBIGUOUS,
    DEFAULT_SCHEMA_UNSUPPORTED,
    DIALECT_UNKNOWN,
    TARGET_STALE,
}

data class TargetResolutionFailure(
    val kind: TargetResolutionFailureKind,
    val code: String,
) {
    init {
        require(code.isNotBlank()) { "target resolution failure code must not be blank" }
    }
}

sealed interface TargetResolution {
    data class Success(val target: ResolvedExecutionTarget) : TargetResolution

    data class Failed(val failure: TargetResolutionFailure) : TargetResolution
}
