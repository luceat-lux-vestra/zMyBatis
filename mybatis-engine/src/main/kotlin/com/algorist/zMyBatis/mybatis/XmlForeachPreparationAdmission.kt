package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputAliasKind
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputShape
import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.XmlStatementParameterContractFactory
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph

/**
 * Proves the bounded source/contract authority required before XML foreach preparation is wired.
 *
 * This object does not execute MyBatis or authorize DynamicSqlSource. It only establishes that the
 * supplied consumer contract is the same foreach authority that the authoritative XML source
 * currently proves through the #321 source contract factory.
 */
internal object XmlForeachPreparationAdmission {
    private const val CALLER_AUTHORITY_PROBLEM = "xml-caller-input-authority-unproven"
    private const val SOURCE_CONTRACT_MISMATCH =
        "xml-foreach-preparation-source-contract-mismatch"
    private const val SOURCE_UNSUPPORTED =
        "xml-foreach-preparation-source-unsupported"
    private const val CONTRACT_UNSUPPORTED =
        "xml-foreach-preparation-contract-unsupported"

    private val supportedShapes = setOf(InputShape.LIST, InputShape.ARRAY, InputShape.MAP)
    private val supportedAliases = setOf(
        InputAliasKind.EXPLICIT_PARAM,
        InputAliasKind.GENERIC_PARAM,
        InputAliasKind.COLLECTION,
        InputAliasKind.LIST,
        InputAliasKind.ARRAY,
    )

    fun inspect(
        sourceGraph: StatementSourceGraph,
        contract: ParameterContract,
    ): Result {
        val locals = contract.internalBindings.filter {
            it.kind == InternalBindingKind.FOREACH_ITEM ||
                it.kind == InternalBindingKind.FOREACH_INDEX
        }
        val collections = contract.requirements.flatMap { requirement ->
            requirement.provenance.evidence
                .filterIsInstance<InputEvidence.ForeachCollection>()
                .map { evidence -> CollectionAuthority(requirement.id, evidence) }
        }

        if (locals.isEmpty() && collections.isEmpty()) {
            return Result.NotPresent
        }
        if (
            locals.isEmpty() ||
            collections.size != 1 ||
            contract.internalBindings.size != locals.size
        ) {
            return mismatch()
        }

        val sourceContract = XmlStatementParameterContractFactory.build(sourceGraph)
        if (sourceContract.blockingProblems.any { it.code != CALLER_AUTHORITY_PROBLEM }) {
            return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, SOURCE_UNSUPPORTED)
        }

        val sourceLocals = sourceContract.internalBindings.filter {
            it.kind == InternalBindingKind.FOREACH_ITEM ||
                it.kind == InternalBindingKind.FOREACH_INDEX
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

        val requirement = contract.requirement(collection.requirementId)
            ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, SOURCE_CONTRACT_MISMATCH)
        val alias = contract.aliases.singleOrNull {
            it.requirementId == requirement.id &&
                it.name == collection.evidence.expression
        } ?: return mismatch()

        if (
            requirement.kind != InputKind.BOUND ||
            requirement.expectedType.shape !in supportedShapes ||
            alias.kind !in supportedAliases
        ) {
            return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, CONTRACT_UNSUPPORTED)
        }

        return Result.Admitted(
            collectionRequirementIds = setOf(requirement.id),
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
        data object NotPresent : Result

        data class Admitted(
            val collectionRequirementIds: Set<InputRequirementId>,
            val locals: Map<String, InternalBindingKind>,
        ) : Result

        data class Failed(
            val failure: PreparationFailure,
        ) : Result
    }
}
