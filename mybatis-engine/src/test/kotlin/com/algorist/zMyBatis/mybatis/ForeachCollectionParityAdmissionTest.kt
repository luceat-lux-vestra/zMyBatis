package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExpectedInputType
import com.algorist.zMyBatis.core.input.ForeachLocalRole
import com.algorist.zMyBatis.core.input.InputAlias
import com.algorist.zMyBatis.core.input.InputAliasKind
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputNullability
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequiredness
import com.algorist.zMyBatis.core.input.InputRequirement
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputShape
import com.algorist.zMyBatis.core.input.InternalBinding
import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeachCollectionParityAdmissionTest {
    @Test
    fun repeatedCollectionAliasIsSupportedWhenSourceAndEvidenceCountsMatch() {
        val result = ForeachProvenanceAdmission.inspect(
            """
                <script>
                  <foreach collection="ids" item="first">#{first}</foreach>
                  <foreach collection="ids" item="second">#{second}</foreach>
                </script>
            """.trimIndent(),
            contract(
                collectionEvidenceCount = 2,
                locals = listOf("first", "second"),
            ),
        )

        assertTrue(result is ForeachProvenanceAdmission.Result.Admitted)
        result as ForeachProvenanceAdmission.Result.Admitted
        assertEquals(1, result.collectionRequirementIds.size)
        assertEquals(
            mapOf(
                "first" to InternalBindingKind.FOREACH_ITEM,
                "second" to InternalBindingKind.FOREACH_ITEM,
            ),
            result.locals,
        )
    }

    @Test
    fun staleDuplicateCollectionEvidenceFailsSourceContractParity() {
        val result = ForeachProvenanceAdmission.inspect(
            "<script><foreach collection=\"ids\" item=\"item\">#{item}</foreach></script>",
            contract(
                collectionEvidenceCount = 2,
                locals = listOf("item"),
            ),
        )

        assertTrue(result is ForeachProvenanceAdmission.Result.Failed)
        result as ForeachProvenanceAdmission.Result.Failed
        assertEquals(PreparationFailureKind.BINDING_RESOLUTION, result.failure.kind)
        assertEquals("mybatis-foreach-source-contract-mismatch", result.failure.code)
        assertEquals(null, result.failure.bindingProperty)
    }

    private fun contract(
        collectionEvidenceCount: Int,
        locals: List<String>,
    ): ParameterContract {
        val fileId = SourceFileId("fixture/ForeachCollectionParity.java")
        val revision = SourceRevision("foreach-collection-parity-revision")
        val parameterType = JavaTypeIdentity("java.util.List<java.lang.Long>")
        val statementId = JavaStatementId(
            fileId,
            "fixture.ForeachCollectionParity",
            MethodSignature("query", listOf(parameterType)),
        )
        val requirementId = InputRequirementId("foreach-collection-parity:ids")
        val parameterSource = SourceEvidence(fileId, revision, SourceRange(0, 5))
        val collectionEvidence = (0 until collectionEvidenceCount).map { index ->
            InputEvidence.ForeachCollection(
                expression = "ids",
                source = SourceEvidence(
                    fileId,
                    revision,
                    SourceRange(10 + (index * 10), 15 + (index * 10)),
                ),
            )
        }
        val provenance = InputProvenance(
            buildList {
                add(InputEvidence.MapperMethodParameter(0, "ids", parameterType, parameterSource))
                add(InputEvidence.ExplicitParamAlias(0, "ids", parameterSource))
                addAll(collectionEvidence)
            },
        )
        val requirement = InputRequirement(
            id = requirementId,
            kind = InputKind.BOUND,
            expectedType = ExpectedInputType(
                shape = InputShape.LIST,
                javaTypeIdentity = parameterType,
                nullability = InputNullability.NON_NULL,
            ),
            requiredness = InputRequiredness.REQUIRED,
            provenance = provenance,
        )
        return ParameterContract(
            statementId = statementId,
            requirements = listOf(requirement),
            aliases = listOf(
                InputAlias(
                    name = "ids",
                    requirementId = requirementId,
                    kind = InputAliasKind.EXPLICIT_PARAM,
                    provenance = provenance,
                ),
            ),
            internalBindings = locals.mapIndexed { index, local ->
                InternalBinding(
                    name = local,
                    kind = InternalBindingKind.FOREACH_ITEM,
                    provenance = InputProvenance(
                        listOf(
                            InputEvidence.ForeachLocal(
                                name = local,
                                role = ForeachLocalRole.ITEM,
                                source = SourceEvidence(
                                    fileId,
                                    revision,
                                    SourceRange(100 + (index * 10), 105 + (index * 10)),
                                ),
                            ),
                        ),
                    ),
                )
            },
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(fileId to revision),
        )
    }
}
