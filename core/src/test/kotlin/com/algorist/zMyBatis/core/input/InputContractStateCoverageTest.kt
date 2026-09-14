package com.algorist.zMyBatis.core.input

import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class InputContractStateCoverageTest {
    private val statementId = JavaStatementId(
        SourceFileId("src/main/java/com/acme/UserMapper.java"),
        "com.acme.UserMapper",
        MethodSignature("find", listOf(JavaTypeIdentity("java.lang.Long"))),
    )
    private val sourceRevision = SourceRevision("document:coverage")
    private val source = SourceEvidence(statementId.sourceFileId, sourceRevision, SourceRange(0, 10))
    private val sourceRevisions = mapOf(statementId.sourceFileId to sourceRevision)

    @Test
    fun optionalRequirementMayBeOmittedWithoutInventingAValue() {
        val optional = InputRequirement(
            InputRequirementId("limit"),
            InputKind.BOUND,
            ExpectedInputType(InputShape.SCALAR, InputScalarType.INTEGER),
            InputRequiredness.OPTIONAL,
            provenance(InputEvidence.Placeholder(InputKind.BOUND, "limit", source)),
        )
        val contract = contract(requirements = listOf(optional))

        val result = InputEnvironment.validate(contract, emptyList())

        assertTrue(result is InputEnvironmentResult.Success)
        assertTrue((result as InputEnvironmentResult.Success).environment.values.isEmpty())
    }

    @Test
    fun ambiguousAndUnsupportedProblemsBothBlockPreparation() {
        val blockingKinds = listOf(
            InputContractProblemKind.AMBIGUOUS,
            InputContractProblemKind.UNSUPPORTED,
        )

        blockingKinds.forEach { kind ->
            val contract = contract(
                blockingProblems = listOf(
                    InputContractProblem(kind, "blocked-${kind.name.lowercase()}", null, null),
                ),
            )

            assertTrue(contract.isPreparationBlocked)
            assertEquals(
                InputEnvironmentResult.Failure(
                    listOf(InputEnvironmentFailure(InputEnvironmentFailureKind.CONTRACT_BLOCKED, null)),
                ),
                InputEnvironment.validate(contract, emptyList()),
            )
        }
    }

    @Test
    fun everyProvenCallerAliasKindRemainsCallerOwned() {
        val requirement = requiredInteger("id")
        val aliases = listOf(
            InputAlias(
                "customerId",
                requirement.id,
                InputAliasKind.EXPLICIT_PARAM,
                provenance(InputEvidence.ExplicitParamAlias(0, "customerId", source)),
            ),
            InputAlias(
                "id",
                requirement.id,
                InputAliasKind.SOURCE_PARAMETER_NAME,
                provenance(
                    InputEvidence.MapperMethodParameter(
                        0,
                        "id",
                        JavaTypeIdentity("java.lang.Long"),
                        source,
                    ),
                ),
            ),
            generatedAlias("param1", requirement, InputAliasKind.GENERIC_PARAM),
            generatedAlias("arg0", requirement, InputAliasKind.ARGUMENT),
            generatedAlias("collection", requirement, InputAliasKind.COLLECTION),
            generatedAlias("list", requirement, InputAliasKind.LIST),
            generatedAlias("array", requirement, InputAliasKind.ARRAY),
        )
        val contract = contract(requirements = listOf(requirement), aliases = aliases)

        assertEquals(
            listOf(
                InputAliasKind.EXPLICIT_PARAM,
                InputAliasKind.SOURCE_PARAMETER_NAME,
                InputAliasKind.GENERIC_PARAM,
                InputAliasKind.ARGUMENT,
                InputAliasKind.COLLECTION,
                InputAliasKind.LIST,
                InputAliasKind.ARRAY,
            ),
            contract.aliases.map { it.kind },
        )
    }

    @Test
    fun foreachBindAndAdditionalBindingsRemainInternalNotCallerInputs() {
        val internalBindings = listOf(
            InternalBinding(
                "item",
                InternalBindingKind.FOREACH_ITEM,
                provenance(InputEvidence.ForeachLocal("item", ForeachLocalRole.ITEM, source)),
            ),
            InternalBinding(
                "index",
                InternalBindingKind.FOREACH_INDEX,
                provenance(InputEvidence.ForeachLocal("index", ForeachLocalRole.INDEX, source)),
            ),
            InternalBinding(
                "pattern",
                InternalBindingKind.BIND,
                provenance(InputEvidence.BindLocal("pattern", "name + '%'", source)),
            ),
            InternalBinding(
                "__frch_item_0",
                InternalBindingKind.ADDITIONAL_PARAMETER,
                provenance(InputEvidence.ForeachLocal("item", ForeachLocalRole.ITEM, source)),
            ),
        )
        val contract = contract(internalBindings = internalBindings)

        assertEquals(
            listOf(
                InternalBindingKind.FOREACH_ITEM,
                InternalBindingKind.FOREACH_INDEX,
                InternalBindingKind.BIND,
                InternalBindingKind.ADDITIONAL_PARAMETER,
            ),
            contract.internalBindings.map { it.kind },
        )
        assertTrue(contract.requirements.isEmpty())
    }

    @Test
    fun arrayValuesExposeADefensiveElementSnapshot() {
        val callerOwned = mutableListOf<InputValue>(InputValue.IntegerValue(BigInteger.ONE))
        val array = InputValue.ArrayValue(callerOwned)

        callerOwned += InputValue.IntegerValue(BigInteger.TWO)

        assertEquals(
            listOf(InputValue.IntegerValue(BigInteger.ONE)),
            array.elements,
        )
    }

    private fun contract(
        requirements: List<InputRequirement> = emptyList(),
        aliases: List<InputAlias> = emptyList(),
        internalBindings: List<InternalBinding> = emptyList(),
        blockingProblems: List<InputContractProblem> = emptyList(),
    ): ParameterContract = ParameterContract(
        statementId,
        requirements,
        aliases,
        internalBindings,
        blockingProblems,
        sourceRevisions,
    )

    private fun requiredInteger(id: String): InputRequirement = InputRequirement(
        InputRequirementId(id),
        InputKind.BOUND,
        ExpectedInputType(InputShape.SCALAR, InputScalarType.INTEGER),
        InputRequiredness.REQUIRED,
        provenance(InputEvidence.Placeholder(InputKind.BOUND, id, source)),
    )

    private fun generatedAlias(
        name: String,
        requirement: InputRequirement,
        kind: InputAliasKind,
    ): InputAlias = InputAlias(
        name,
        requirement.id,
        kind,
        provenance(InputEvidence.GeneratedAlias(0, name, "mybatis-param-name-resolver")),
    )

    private fun provenance(vararg evidence: InputEvidence): InputProvenance = InputProvenance(evidence.toList())
}
