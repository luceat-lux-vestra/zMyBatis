package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InternalBinding
import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicOgnlAdmissionContractTest {
    private val source = SourceEvidence(
        SourceFileId("fixture/BindMapper.java"),
        SourceRevision("bind-revision-1"),
        SourceRange(0, 10),
    )

    @Test
    fun bindExpressionMustMatchAuthoritativeContractEvidenceExactly() {
        val binding = bind("pattern", "'%' + name + '%'")
        val result = DynamicOgnlAdmission.inspect(
            "<script><bind name=\"pattern\" value=\"'x' + name\"/>select 1</script>",
            setOf("name"),
            listOf(binding),
        )

        assertTrue(result is DynamicOgnlAdmission.Result.BindAuthority)
        result as DynamicOgnlAdmission.Result.BindAuthority
        assertEquals("pattern", result.name)
        assertEquals(
            DynamicOgnlAdmission.BindAuthorityProblem.SOURCE_CONTRACT_MISMATCH,
            result.problem,
        )
    }

    @Test
    fun exactBindExpressionAndCallerRootAreAdmitted() {
        val expression = "'%' + name + '%'"
        val result = DynamicOgnlAdmission.inspect(
            "<script><bind name=\"pattern\" value=\"$expression\"/>select 1</script>",
            setOf("name"),
            listOf(bind("pattern", expression)),
        )

        assertEquals(DynamicOgnlAdmission.Result.Admitted, result)
    }

    @Test
    fun bindNameCannotShadowCallerAuthorityInThisSlice() {
        val expression = "name + 'x'"
        val result = DynamicOgnlAdmission.inspect(
            "<script><bind name=\"name\" value=\"$expression\"/>select 1</script>",
            setOf("name"),
            listOf(bind("name", expression)),
        )

        assertTrue(result is DynamicOgnlAdmission.Result.BindAuthority)
        result as DynamicOgnlAdmission.Result.BindAuthority
        assertEquals(DynamicOgnlAdmission.BindAuthorityProblem.AMBIGUOUS, result.problem)
    }

    @Test
    fun bindLocalsAreNotImplicitExpressionRoots() {
        val first = "name + 'x'"
        val second = "first + 'y'"
        val result = DynamicOgnlAdmission.inspect(
            "<script>" +
                "<bind name=\"first\" value=\"$first\"/>" +
                "<bind name=\"second\" value=\"$second\"/>" +
                "select 1</script>",
            setOf("name"),
            listOf(bind("first", first), bind("second", second)),
        )

        assertTrue(result is DynamicOgnlAdmission.Result.UnprovenProperty)
        result as DynamicOgnlAdmission.Result.UnprovenProperty
        assertEquals("first", result.property)
    }

    private fun bind(name: String, expression: String) = InternalBinding(
        name = name,
        kind = InternalBindingKind.BIND,
        provenance = InputProvenance(
            listOf(InputEvidence.BindLocal(name, expression, source)),
        ),
    )
}
