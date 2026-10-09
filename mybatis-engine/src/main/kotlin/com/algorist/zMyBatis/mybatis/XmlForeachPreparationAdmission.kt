package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.XmlMapperMethodParameterContractFactory
import com.algorist.zMyBatis.core.input.XmlStatementParameterContractFactory
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlMapperMethodCapture
import com.algorist.zMyBatis.core.source.XmlStatementId

/**
 * Proves bounded foreach authority against XML and the complete captured mapper method.
 *
 * The producer contract is rebuilt from independent source captures, including unused parameters
 * that can suppress collection aliases or shadow locals. Contract evidence cannot authenticate itself.
 * One direct foreach or a sole bounded where-role wrapper (set-role for UPDATE) is admitted
 * without Boolean-if or multiple-wrapper composition.
 * This proves consistency with supplied captures, not Java parsing, live-source validity or execution.
 */
internal object XmlForeachPreparationAdmission {
    private const val CALLER_AUTHORITY_PROBLEM = "xml-caller-input-authority-unproven"
    private const val SOURCE_CONTRACT_MISMATCH =
        "xml-foreach-preparation-source-contract-mismatch"
    private const val SOURCE_UNSUPPORTED =
        "xml-foreach-preparation-source-unsupported"
    private const val CONTRACT_UNSUPPORTED =
        "xml-foreach-preparation-contract-unsupported"
    private const val MAPPER_AUTHORITY_UNPROVEN =
        "xml-foreach-preparation-mapper-authority-unproven"
    private const val MAPPER_UNSUPPORTED = "xml-foreach-preparation-mapper-unsupported"
    private const val IDENTITY_MISMATCH = "xml-foreach-preparation-statement-mismatch"
    private const val REVISION_MISMATCH = "xml-foreach-preparation-revision-mismatch"

    fun inspect(
        sourceGraph: StatementSourceGraph,
        mapperMethod: XmlMapperMethodCapture?,
        contract: ParameterContract,
    ): Result {
        if (contract.blockingProblems.isNotEmpty()) {
            return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, CONTRACT_UNSUPPORTED)
        }

        val locals = contract.internalBindings.filter {
            it.kind == InternalBindingKind.FOREACH_ITEM ||
                it.kind == InternalBindingKind.FOREACH_INDEX
        }
        val collections = contract.requirements.flatMap { requirement ->
            requirement.provenance.evidence
                .filterIsInstance<InputEvidence.ForeachCollection>()
                .map { evidence -> CollectionAuthority(requirement.id, evidence) }
        }

        if (sourceGraph.rootStatement.id !is XmlStatementId) {
            return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, SOURCE_UNSUPPORTED)
        }
        val sourceContract = XmlStatementParameterContractFactory.build(sourceGraph)
        val sourceLocals = sourceContract.internalBindings.filter {
            it.kind == InternalBindingKind.FOREACH_ITEM ||
                it.kind == InternalBindingKind.FOREACH_INDEX
        }
        if (locals.isEmpty() && collections.isEmpty()) {
            return if (sourceLocals.isEmpty()) Result.NotPresent else mismatch()
        }
        if (
            locals.isEmpty() ||
            collections.size != 1 ||
            contract.internalBindings.size != locals.size
        ) {
            return mismatch()
        }

        if (sourceContract.blockingProblems.any { it.code != CALLER_AUTHORITY_PROBLEM }) {
            return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, SOURCE_UNSUPPORTED)
        }

        if (sourceContract.statementId != contract.statementId) {
            return mismatch()
        }

        val sourceCollections = sourceContract.blockingProblems
            .flatMap { it.provenance?.evidence.orEmpty() }
            .filterIsInstance<InputEvidence.ForeachCollection>()

        val collection = collections.single()
        if (
            sourceLocals.toSet() != locals.toSet() ||
            sourceCollections != listOf(collection.evidence)
        ) {
            return mismatch()
        }

        val mapper = mapperMethod
            ?: return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, MAPPER_AUTHORITY_UNPROVEN)
        if (mapper.statementId != sourceContract.statementId) {
            return failed(PreparationFailureKind.STATEMENT_ID_MISMATCH, IDENTITY_MISMATCH)
        }
        val sourceRevisions = sourceGraph.sourceSnapshots.associate { it.fileId to it.revision }
        if (mapper.mapperSource.fileId in sourceRevisions) {
            return failed(PreparationFailureKind.PREPARATION_INVARIANT, MAPPER_UNSUPPORTED)
        }
        val authorityRevisions = sourceRevisions +
            (mapper.mapperSource.fileId to mapper.mapperSource.revision)
        if (contract.sourceRevisions != authorityRevisions) {
            return failed(PreparationFailureKind.SOURCE_REVISION_MISMATCH, REVISION_MISMATCH)
        }

        val expected = XmlMapperMethodParameterContractFactory.build(sourceGraph, mapper)
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

        return Result.Admitted(
            collectionRequirementIds = setOf(collection.requirementId),
            locals = locals.associate { it.name to it.kind },
        )
    }

    private fun mismatch(): Result.Failed =
        failed(PreparationFailureKind.BINDING_RESOLUTION, SOURCE_CONTRACT_MISMATCH)

    private fun failed(
        kind: PreparationFailureKind,
        code: String,
    ) = Result.Failed(PreparationFailure(kind, code))

    private data class CollectionAuthority(
        val requirementId: InputRequirementId,
        val evidence: InputEvidence.ForeachCollection,
    )

    sealed interface Result {
        object NotPresent : Result

        data class Admitted(
            val collectionRequirementIds: Set<InputRequirementId>,
            val locals: Map<String, InternalBindingKind>,
        ) : Result

        data class Failed(
            val failure: PreparationFailure,
        ) : Result
    }
}
