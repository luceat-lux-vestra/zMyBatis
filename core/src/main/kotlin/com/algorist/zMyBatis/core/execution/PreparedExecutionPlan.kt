package com.algorist.zMyBatis.core.execution

import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.preparation.PreparationMetadata
import com.algorist.zMyBatis.core.preparation.PreparedBinding
import com.algorist.zMyBatis.core.preparation.PreparedBindingOrigin
import com.algorist.zMyBatis.core.preparation.PreparedExecution
import com.algorist.zMyBatis.core.preparation.PreparedRawInterpolation
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.StatementId
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.XmlStatementId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

private val PREPARED_PLAN_SHA_256_HEX = Regex("[0-9a-f]{64}")
private const val PREPARED_PLAN_FINGERPRINT_VERSION = "zmybatis-prepared-execution-plan-v1"
private const val DATABASE_ID_MISMATCH = "prepared-execution-plan-database-id-mismatch"

@JvmInline
value class PreparedExecutionPlanFingerprint internal constructor(val value: String) {
    init {
        require(PREPARED_PLAN_SHA_256_HEX.matches(value)) {
            "prepared execution plan fingerprint must be a lowercase SHA-256 hex digest"
        }
    }
}

data class PreparedExecutionPlanSafetyFlags(
    val declaredMutation: Boolean,
    val containsRawInterpolation: Boolean,
)

enum class PreparedExecutionPlanFailureKind {
    DATABASE_ID_MISMATCH,
}

data class PreparedExecutionPlanFailure(
    val kind: PreparedExecutionPlanFailureKind,
    val code: String,
) {
    init {
        require(code.isNotBlank()) { "prepared execution plan failure code must not be blank" }
    }
}

sealed interface PreparedExecutionPlanResult {
    data class Ready(val plan: PreparedExecutionPlan) : PreparedExecutionPlanResult
    data class Failed(val failure: PreparedExecutionPlanFailure) : PreparedExecutionPlanResult
}

/**
 * Immutable execution authority approved and later delivered by the P4 orchestration path.
 *
 * The plan freezes the exact prepared SQL/binding authority together with the exact target. Bound
 * values participate in the fingerprint but are never rendered into diagnostics by this model.
 */
class PreparedExecutionPlan internal constructor(
    val statementId: StatementId,
    val statementKind: StatementKind,
    sourceRevisions: Map<SourceFileId, SourceRevision>,
    val preparationMetadata: PreparationMetadata,
    val executionTargetId: ExecutionTargetId,
    val databaseId: String?,
    val sqlWithPlaceholders: String,
    orderedBindings: List<PreparedBinding>,
    rawInterpolations: List<PreparedRawInterpolation>,
    val safetyFlags: PreparedExecutionPlanSafetyFlags,
    val fingerprint: PreparedExecutionPlanFingerprint,
) {
    private val sourceRevisionSnapshot = LinkedHashMap(
        sourceRevisions.entries.sortedBy { it.key.value }.associate { it.toPair() },
    )
    private val bindingSnapshot = orderedBindings.toList()
    private val rawInterpolationSnapshot = rawInterpolations.toList()

    init {
        require(statementId.sourceFileId in sourceRevisionSnapshot) {
            "prepared execution plan must include its root source revision"
        }
        require(sqlWithPlaceholders.isNotBlank()) {
            "prepared execution plan SQL must not be blank"
        }
        require(bindingSnapshot.indices.all { index -> bindingSnapshot[index].index == index }) {
            "prepared execution plan bindings must be contiguous and ordered"
        }
        require(rawInterpolationSnapshot.map { it.requirementId }.distinct().size == rawInterpolationSnapshot.size) {
            "prepared execution plan raw interpolation requirements must not be duplicated"
        }
        require(databaseId == preparationMetadata.databaseId) {
            "prepared execution plan database id must match preparation metadata"
        }
    }

    val sourceRevisions: Map<SourceFileId, SourceRevision>
        get() = LinkedHashMap(sourceRevisionSnapshot)

    val orderedBindings: List<PreparedBinding>
        get() = bindingSnapshot.toList()

    val rawInterpolations: List<PreparedRawInterpolation>
        get() = rawInterpolationSnapshot.toList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreparedExecutionPlan) return false

        return statementId == other.statementId &&
            statementKind == other.statementKind &&
            sourceRevisionSnapshot == other.sourceRevisionSnapshot &&
            preparationMetadata == other.preparationMetadata &&
            executionTargetId == other.executionTargetId &&
            databaseId == other.databaseId &&
            sqlWithPlaceholders == other.sqlWithPlaceholders &&
            bindingSnapshot == other.bindingSnapshot &&
            rawInterpolationSnapshot == other.rawInterpolationSnapshot &&
            safetyFlags == other.safetyFlags &&
            fingerprint == other.fingerprint
    }

    override fun hashCode(): Int {
        var result = statementId.hashCode()
        result = 31 * result + statementKind.hashCode()
        result = 31 * result + sourceRevisionSnapshot.hashCode()
        result = 31 * result + preparationMetadata.hashCode()
        result = 31 * result + executionTargetId.hashCode()
        result = 31 * result + databaseId.hashCode()
        result = 31 * result + sqlWithPlaceholders.hashCode()
        result = 31 * result + bindingSnapshot.hashCode()
        result = 31 * result + rawInterpolationSnapshot.hashCode()
        result = 31 * result + safetyFlags.hashCode()
        result = 31 * result + fingerprint.hashCode()
        return result
    }
}

object PreparedExecutionPlanner {
    fun freeze(
        prepared: PreparedExecution,
        target: ResolvedExecutionTarget,
    ): PreparedExecutionPlanResult {
        val databaseId = target.effectiveDatabaseId?.value
        if (prepared.preparationMetadata.databaseId != databaseId) {
            return PreparedExecutionPlanResult.Failed(
                PreparedExecutionPlanFailure(
                    PreparedExecutionPlanFailureKind.DATABASE_ID_MISMATCH,
                    DATABASE_ID_MISMATCH,
                ),
            )
        }

        val safetyFlags = PreparedExecutionPlanSafetyFlags(
            declaredMutation = prepared.statementKind != StatementKind.SELECT,
            containsRawInterpolation = prepared.rawInterpolations.isNotEmpty(),
        )
        val fingerprint = fingerprint(
            prepared = prepared,
            target = target,
            safetyFlags = safetyFlags,
        )
        return PreparedExecutionPlanResult.Ready(
            PreparedExecutionPlan(
                statementId = prepared.statementId,
                statementKind = prepared.statementKind,
                sourceRevisions = prepared.sourceRevisions,
                preparationMetadata = prepared.preparationMetadata,
                executionTargetId = target.targetId,
                databaseId = databaseId,
                sqlWithPlaceholders = prepared.sqlWithPlaceholders,
                orderedBindings = prepared.orderedBindings,
                rawInterpolations = prepared.rawInterpolations,
                safetyFlags = safetyFlags,
                fingerprint = fingerprint,
            ),
        )
    }
}

private fun fingerprint(
    prepared: PreparedExecution,
    target: ResolvedExecutionTarget,
    safetyFlags: PreparedExecutionPlanSafetyFlags,
): PreparedExecutionPlanFingerprint {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.field(PREPARED_PLAN_FINGERPRINT_VERSION)
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
    digest.nullableField(prepared.preparationMetadata.databaseId)

    digest.field(target.targetId.dataSourceId.value)
    digest.field(target.targetId.schema.value)
    digest.nullableField(target.effectiveDatabaseId?.value)

    digest.field(prepared.sqlWithPlaceholders)

    digest.field(prepared.orderedBindings.size.toString())
    prepared.orderedBindings.forEach { binding ->
        digest.field(binding.index.toString())
        digest.field(binding.property)
        digest.bindingOrigin(binding.origin)
        digest.inputValue(binding.value)
        digest.nullableField(binding.metadata.declaredJavaTypeIdentity?.value)
        digest.nullableField(binding.metadata.mappingJavaTypeIdentity)
        digest.nullableField(binding.metadata.jdbcTypeIdentity)
        digest.nullableField(binding.metadata.effectiveJdbcTypeIdentity)
        digest.field(binding.metadata.typeHandlerIdentity)
        digest.field(binding.metadata.parameterMode)
        digest.nullableField(binding.metadata.numericScale?.toString())
    }

    digest.field(prepared.rawInterpolations.size.toString())
    prepared.rawInterpolations.forEach { raw ->
        digest.field(raw.requirementId.value)
        digest.field(raw.origin.name)
        digest.provenance(raw.provenance)
    }

    digest.field(safetyFlags.declaredMutation.toString())
    digest.field(safetyFlags.containsRawInterpolation.toString())

    return PreparedExecutionPlanFingerprint(HexFormat.of().formatHex(digest.digest()))
}

private fun MessageDigest.bindingOrigin(origin: PreparedBindingOrigin) {
    when (origin) {
        is PreparedBindingOrigin.CallerInput -> {
            field("caller")
            field(origin.requirementId.value)
            provenance(origin.provenance)
        }
        is PreparedBindingOrigin.MyBatisAdditional -> {
            field("additional")
            field(origin.internalBinding.name)
            field(origin.internalBinding.kind.name)
            provenance(origin.internalBinding.provenance)
        }
    }
}

private fun MessageDigest.provenance(provenance: InputProvenance) {
    field(provenance.evidence.size.toString())
    provenance.evidence.forEach(::evidence)
}

private fun MessageDigest.evidence(evidence: InputEvidence) {
    when (evidence) {
        is InputEvidence.MapperMethodParameter -> {
            field("mapper-method-parameter")
            field(evidence.index.toString())
            nullableField(evidence.sourceName)
            field(evidence.typeIdentity.value)
            sourceEvidence(evidence.source)
        }
        is InputEvidence.ExplicitParamAlias -> {
            field("explicit-param-alias")
            field(evidence.parameterIndex.toString())
            field(evidence.alias)
            sourceEvidence(evidence.source)
        }
        is InputEvidence.GeneratedAlias -> {
            field("generated-alias")
            field(evidence.parameterIndex.toString())
            field(evidence.alias)
            field(evidence.ruleId)
        }
        is InputEvidence.ParameterObjectFallback -> {
            field("parameter-object-fallback")
            field(evidence.parameterIndex.toString())
            field(evidence.mappingProperty)
            field(evidence.ruleId)
        }
        is InputEvidence.ParameterObjectProperty -> {
            field("parameter-object-property")
            field(evidence.parameterIndex.toString())
            field(evidence.mappingProperty)
            field(evidence.ruleId)
        }
        is InputEvidence.NamedMapProperty -> {
            field("named-map-property")
            field(evidence.parameterIndex.toString())
            field(evidence.alias)
            field(evidence.mappingProperty)
            field(evidence.key)
            field(evidence.ruleId)
        }
        is InputEvidence.XmlParameterObject -> {
            field("xml-parameter-object")
            sourceEvidence(evidence.source)
        }
        is InputEvidence.RuntimeGeneratedBinding -> {
            field("runtime-generated-binding")
            field(evidence.property)
            sourceEvidence(evidence.source)
        }
        is InputEvidence.Placeholder -> {
            field("placeholder")
            field(evidence.kind.name)
            field(evidence.expression)
            nullableField(evidence.enclosingOgnlExpression)
            sourceEvidence(evidence.source)
        }
        is InputEvidence.OgnlExpression -> {
            field("ognl-expression")
            field(evidence.expression)
            nullableField(evidence.boundedWhereRole)
            sourceEvidence(evidence.source)
        }
        is InputEvidence.ForeachCollection -> {
            field("foreach-collection")
            field(evidence.expression)
            sourceEvidence(evidence.source)
        }
        is InputEvidence.ForeachLocal -> {
            field("foreach-local")
            field(evidence.name)
            field(evidence.role.name)
            sourceEvidence(evidence.source)
        }
        is InputEvidence.BindLocal -> {
            field("bind-local")
            field(evidence.name)
            field(evidence.expression)
            sourceEvidence(evidence.source)
        }
    }
}

private fun MessageDigest.sourceEvidence(source: SourceEvidence) {
    field(source.sourceFileId.value)
    field(source.sourceRevision.value)
    val range = source.sourceRange
    if (range == null) {
        field("no-range")
    } else {
        field("range")
        field(range.startOffset.toString())
        field(range.endOffsetExclusive.toString())
    }
}

private fun MessageDigest.inputValue(value: InputValue) {
    when (value) {
        InputValue.NullValue -> field("null")
        is InputValue.Text -> {
            field("text")
            field(value.value)
        }
        is InputValue.RawText -> {
            field("raw-text")
            field(value.value)
        }
        is InputValue.BooleanValue -> {
            field("boolean")
            field(value.value.toString())
        }
        is InputValue.IntegerValue -> {
            field("integer")
            field(value.value.toString())
        }
        is InputValue.DecimalValue -> {
            field("decimal")
            field(value.value.unscaledValue().toString())
            field(value.value.scale().toString())
        }
        is InputValue.DateValue -> {
            field("date")
            field(value.value.toString())
        }
        is InputValue.TimeValue -> {
            field("time")
            field(value.value.toString())
        }
        is InputValue.DateTimeValue -> {
            field("date-time")
            field(value.value.toString())
        }
        is InputValue.InstantValue -> {
            field("instant")
            field(value.value.toString())
        }
        is InputValue.UuidValue -> {
            field("uuid")
            field(value.value.toString())
        }
        is InputValue.ObjectValue -> {
            field("object")
            field(value.entries.size.toString())
            value.entries.entries.sortedBy { it.key }.forEach { (key, item) ->
                field(key)
                inputValue(item)
            }
        }
        is InputValue.MapValue -> {
            field("map")
            field(value.entries.size.toString())
            value.entries.entries.sortedBy { it.key }.forEach { (key, item) ->
                field(key)
                inputValue(item)
            }
        }
        is InputValue.ListValue -> {
            field("list")
            field(value.elements.size.toString())
            value.elements.forEach(::inputValue)
        }
        is InputValue.ArrayValue -> {
            field("array")
            field(value.elements.size.toString())
            value.elements.forEach(::inputValue)
        }
    }
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

private fun MessageDigest.nullableField(value: String?) {
    if (value == null) {
        field("<null>")
    } else {
        field("<value>")
        field(value)
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
