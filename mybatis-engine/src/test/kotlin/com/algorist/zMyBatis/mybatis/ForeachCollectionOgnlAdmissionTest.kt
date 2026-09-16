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

class ForeachCollectionOgnlAdmissionTest {
    @Test
    fun aliasTextParsedAsNullLiteralCannotBecomeCollectionAuthority() {
        assertOgnlAliasRejected("null")
    }

    @Test
    fun aliasTextParsedAsBooleanLiteralCannotBecomeCollectionAuthority() {
        assertOgnlAliasRejected("true")
    }

    @Test
    fun classPropertyCannotBecomeCollectionAuthority() {
        assertOgnlAliasRejected("class")
    }

    private fun assertOgnlAliasRejected(alias: String) {
        val result = ForeachProvenanceAdmission.inspect(
            "<script><foreach collection=\"$alias\" item=\"item\">#{item}</foreach></script>",
            contract(alias),
        )

        assertTrue(result is ForeachProvenanceAdmission.Result.Failed)
        result as ForeachProvenanceAdmission.Result.Failed
        assertEquals(PreparationFailureKind.UNSUPPORTED_SEMANTIC, result.failure.kind)
        assertEquals("mybatis-foreach-collection-expression-unsupported", result.failure.code)
        assertEquals(alias, result.failure.bindingProperty)
    }

    private fun contract(alias: String): ParameterContract {
        val fileId = SourceFileId("fixture/ForeachCollectionOgnl.java")
        val revision = SourceRevision("foreach-collection-ognl-revision")
        val source = SourceEvidence(fileId, revision, SourceRange(0, 16))
        val type = JavaTypeIdentity("java.util.List<java.lang.Long>")
        val statementId = JavaStatementId(
            fileId,
            "fixture.ForeachCollectionOgnl",
            MethodSignature("query", listOf(type)),
        )
        val requirementId = InputRequirementId("foreach-collection-ognl")
        val provenance = InputProvenance(
            listOf(
                InputEvidence.MapperMethodParameter(0, alias, type, source),
                InputEvidence.ExplicitParamAlias(0, alias, source),
                InputEvidence.ForeachCollection(alias, source),
            ),
        )
        return ParameterContract(
            statementId = statementId,
            requirements = listOf(
                InputRequirement(
                    id = requirementId,
                    kind = InputKind.BOUND,
                    expectedType = ExpectedInputType(
                        shape = InputShape.LIST,
                        javaTypeIdentity = type,
                        nullability = InputNullability.NON_NULL,
                    ),
                    requiredness = InputRequiredness.REQUIRED,
                    provenance = provenance,
                ),
            ),
            aliases = listOf(
                InputAlias(alias, requirementId, InputAliasKind.EXPLICIT_PARAM, provenance),
            ),
            internalBindings = listOf(
                InternalBinding(
                    "item",
                    InternalBindingKind.FOREACH_ITEM,
                    InputProvenance(listOf(InputEvidence.ForeachLocal("item", ForeachLocalRole.ITEM, source))),
                ),
            ),
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(fileId to revision),
        )
    }
}
