package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.XmlMapperMethodParameterContractFactory
import com.algorist.zMyBatis.core.input.XmlStatementParameterContractFactory
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlMapperMethodCapture
import com.algorist.zMyBatis.core.source.XmlStatementId

/**
 * Proves Boolean-if admission against authoritative XML and the complete captured mapper method.
 *
 * Rebuilding the producer contract preserves its naming/type rules, including unused parameters
 * that can suppress a generated alias. Evidence supplied in a contract cannot prove itself.
 * The mapper capture is an input from the source authority boundary, not reconstructed from aliases.
 * The producer admits flat Boolean-if siblings directly or inside one attribute-free direct where.
 * Rebuilding also authenticates each placeholder's immediate condition and rejects nested/mixed tags.
 * This proof neither evaluates OGNL nor enables XML dynamic preparation or execution.
 */
internal object XmlBooleanIfPreparationAdmission {
    private const val CALLER_AUTHORITY_PROBLEM = "xml-caller-input-authority-unproven"
    private const val SOURCE_UNSUPPORTED = "xml-boolean-if-preparation-source-unsupported"
    private const val CONTRACT_UNSUPPORTED = "xml-boolean-if-preparation-contract-unsupported"
    private const val MAPPER_UNSUPPORTED = "xml-boolean-if-preparation-mapper-unsupported"
    private const val CONTRACT_MISMATCH = "xml-boolean-if-preparation-source-contract-mismatch"
    private const val IDENTITY_MISMATCH = "xml-boolean-if-preparation-statement-mismatch"
    private const val REVISION_MISMATCH = "xml-boolean-if-preparation-revision-mismatch"

    fun inspect(
        sourceGraph: StatementSourceGraph,
        mapperMethod: XmlMapperMethodCapture,
        contract: ParameterContract,
    ): Result {
        if (contract.isPreparationBlocked) {
            return failed(PreparationFailureKind.INPUT_CONTRACT_BLOCKED, CONTRACT_UNSUPPORTED)
        }
        val statementId = sourceGraph.rootStatement.id as? XmlStatementId
            ?: return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, SOURCE_UNSUPPORTED)
        if (contract.statementId != statementId || mapperMethod.statementId != statementId) {
            return failed(PreparationFailureKind.STATEMENT_ID_MISMATCH, IDENTITY_MISMATCH)
        }

        if (sourceGraph.sourceSnapshots.size != 1 || sourceGraph.dependencies.isNotEmpty()) {
            return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, SOURCE_UNSUPPORTED)
        }

        val sourceContract = XmlStatementParameterContractFactory.build(sourceGraph)
        if (sourceContract.blockingProblems.any { it.code != CALLER_AUTHORITY_PROBLEM }) {
            return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, SOURCE_UNSUPPORTED)
        }
        val sourceConditions = sourceContract.blockingProblems
            .flatMap { it.provenance?.evidence.orEmpty() }
            .filterIsInstance<InputEvidence.OgnlExpression>()
        val suppliedConditions = buildList {
            addAll(contract.requirements.flatMap { it.provenance.evidence })
            addAll(contract.aliases.flatMap { it.provenance.evidence })
            addAll(contract.internalBindings.flatMap { it.provenance.evidence })
        }.filterIsInstance<InputEvidence.OgnlExpression>()
        if (sourceConditions.isEmpty() && suppliedConditions.isEmpty()) return Result.NotPresent
        if (sourceConditions.isEmpty()) return mismatch()

        val sourceRevisions = sourceGraph.sourceSnapshots.associate { it.fileId to it.revision }
        if (mapperMethod.mapperSource.fileId in sourceRevisions) {
            return failed(PreparationFailureKind.PREPARATION_INVARIANT, MAPPER_UNSUPPORTED)
        }
        val authorityRevisions = sourceRevisions +
            (mapperMethod.mapperSource.fileId to mapperMethod.mapperSource.revision)
        if (contract.sourceRevisions != authorityRevisions) {
            return failed(PreparationFailureKind.SOURCE_REVISION_MISMATCH, REVISION_MISMATCH)
        }

        val expected = XmlMapperMethodParameterContractFactory.build(sourceGraph, mapperMethod)
        if (expected.isPreparationBlocked) {
            return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, MAPPER_UNSUPPORTED)
        }
        if (
            contract.requirements != expected.requirements ||
            contract.aliases != expected.aliases ||
            contract.internalBindings != expected.internalBindings
        ) {
            return mismatch()
        }

        val conditions = sourceConditions.distinct().map { condition ->
            val requirement = expected.requirements.singleOrNull {
                condition in it.provenance.evidence
            } ?: return mismatch()
            Result.Condition(requirement.id, condition.expression)
        }
        return Result.Admitted(conditions)
    }

    private fun mismatch(): Result.Failed =
        failed(PreparationFailureKind.PREPARATION_INVARIANT, CONTRACT_MISMATCH)

    private fun failed(kind: PreparationFailureKind, code: String): Result.Failed =
        Result.Failed(PreparationFailure(kind, code))

    sealed interface Result {
        object NotPresent : Result

        data class Condition(
            val conditionRequirementId: InputRequirementId,
            val conditionAlias: String,
        )

        class Admitted(conditions: List<Condition>) : Result {
            private val conditionSnapshot = conditions.toList()
            val conditions: List<Condition>
                get() = conditionSnapshot.toList()
        }

        data class Failed(val failure: PreparationFailure) : Result
    }
}
