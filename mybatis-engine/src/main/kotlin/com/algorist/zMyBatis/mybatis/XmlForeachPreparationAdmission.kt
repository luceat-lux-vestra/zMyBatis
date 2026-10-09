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
 * currently proves through the source contract factory: one direct foreach, optionally inside
 * the sole bounded where-role wrapper, with no if/set or other dynamic composition.
 */
internal object XmlForeachPreparationAdmission {
    private const val CALLER_AUTHORITY_PROBLEM = "xml-caller-input-authority-unproven"
    private const val SOURCE_CONTRACT_MISMATCH =
        "xml-foreach-preparation-source-contract-mismatch"
    private const val SOURCE_UNSUPPORTED =
        "xml-foreach-preparation-source-unsupported"
    private const val CONTRACT_UNSUPPORTED =
        "xml-foreach-preparation-contract-unsupported"
    private const val GENERIC_ALIAS_RULE =
        "mybatis-3.5.19-param-name-resolver-generic"
    private const val COLLECTION_SHORTCUT_RULE =
        "mybatis-3.5.19-param-name-resolver-wrap-to-map-if-collection"

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

        if (sourceContract.statementId != contract.statementId) {
            return mismatch()
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
        val mapperParameter = requirement.provenance.evidence
            .filterIsInstance<InputEvidence.MapperMethodParameter>()
            .distinct()
            .singleOrNull()
            ?: return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, CONTRACT_UNSUPPORTED)
        val provenShape = collectionShape(mapperParameter.typeIdentity.value)
            ?: return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, CONTRACT_UNSUPPORTED)
        val alias = contract.aliases.singleOrNull {
            it.requirementId == requirement.id &&
                it.name == collection.evidence.expression
        } ?: return mismatch()

        if (
            requirement.kind != InputKind.BOUND ||
            requirement.expectedType.shape !in supportedShapes ||
            requirement.expectedType.shape != provenShape ||
            requirement.expectedType.javaTypeIdentity != mapperParameter.typeIdentity ||
            alias.kind !in supportedAliases ||
            !aliasAuthorityIsCoherent(alias, mapperParameter.index)
        ) {
            return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, CONTRACT_UNSUPPORTED)
        }

        return Result.Admitted(
            collectionRequirementIds = setOf(requirement.id),
            locals = locals.associate { it.name to it.kind },
        )
    }

    private fun collectionShape(typeIdentity: String): InputShape? {
        val canonical = typeIdentity.trim()
        if (canonical.endsWith("[]")) return InputShape.ARRAY
        return when (canonical.substringBefore('<').trim()) {
            "java.util.List", "java.util.Collection" -> InputShape.LIST
            "java.util.Map" -> InputShape.MAP
            else -> null
        }
    }

    private fun aliasAuthorityIsCoherent(
        alias: com.algorist.zMyBatis.core.input.InputAlias,
        expectedParameterIndex: Int,
    ): Boolean {
        val parameterIndexes = alias.provenance.evidence
            .filterIsInstance<InputEvidence.MapperMethodParameter>()
            .map { it.index }
            .distinct()
        return parameterIndexes == listOf(expectedParameterIndex) && when (alias.kind) {
            InputAliasKind.EXPLICIT_PARAM ->
                alias.provenance.evidence
                    .filterIsInstance<InputEvidence.ExplicitParamAlias>()
                    .any {
                        it.parameterIndex == expectedParameterIndex &&
                            it.alias == alias.name
                    }

            InputAliasKind.GENERIC_PARAM ->
                alias.provenance.evidence
                    .filterIsInstance<InputEvidence.GeneratedAlias>()
                    .any {
                        it.parameterIndex == expectedParameterIndex &&
                            it.alias == alias.name &&
                            it.ruleId == GENERIC_ALIAS_RULE
                    }

            InputAliasKind.COLLECTION,
            InputAliasKind.LIST,
            InputAliasKind.ARRAY,
            -> alias.provenance.evidence
                .filterIsInstance<InputEvidence.GeneratedAlias>()
                .any {
                    it.parameterIndex == expectedParameterIndex &&
                        it.alias == alias.name &&
                        it.ruleId == COLLECTION_SHORTCUT_RULE
                }

            else -> false
        }
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
