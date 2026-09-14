package com.algorist.zMyBatis.input

import com.algorist.zMyBatis.core.input.ForeachLocalRole
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractInputProvenancePresenterTest {
    @Test
    fun `source-backed evidence renders exact file and range without semantic inference`() {
        val provenance = InputProvenance(
            listOf(
                InputEvidence.GeneratedAlias(
                    parameterIndex = 0,
                    alias = "param1",
                    ruleId = "mybatis-generic-param",
                ),
                InputEvidence.MapperMethodParameter(
                    index = 0,
                    sourceName = "customerId",
                    typeIdentity = JavaTypeIdentity("java.lang.Long"),
                    source = source(10, 20),
                ),
                InputEvidence.ExplicitParamAlias(
                    parameterIndex = 0,
                    alias = "id",
                    source = source(21, 33),
                ),
                InputEvidence.Placeholder(
                    kind = InputKind.BOUND,
                    expression = "id",
                    source = source(40, 45),
                ),
                InputEvidence.OgnlExpression(
                    expression = "id != null",
                    source = source(50, 60),
                ),
                InputEvidence.ForeachCollection(
                    expression = "ids",
                    source = source(61, 70),
                ),
                InputEvidence.ForeachLocal(
                    name = "item",
                    role = ForeachLocalRole.ITEM,
                    source = source(71, 80),
                ),
                InputEvidence.BindLocal(
                    name = "pattern",
                    expression = "'%' + name + '%'",
                    source = source(81, 90),
                ),
            ),
        )

        val presentation = ContractInputProvenancePresenter.present(provenance)

        assertEquals(
            "mapper parameter #0 (customerId): java.lang.Long @ src/example/Mapper.java[10,20) (+7 more)",
            presentation.summary,
        )
        assertTrue(presentation.details.startsWith("generated alias param1 (mybatis-generic-param); "))
        assertTrue(presentation.details.contains("@Param(\"id\") @ src/example/Mapper.java[21,33)"))
        assertTrue(presentation.details.contains("BOUND: id @ src/example/Mapper.java[40,45)"))
        assertTrue(presentation.details.contains("OGNL: id != null @ src/example/Mapper.java[50,60)"))
        assertTrue(presentation.details.contains("foreach collection: ids @ src/example/Mapper.java[61,70)"))
        assertTrue(presentation.details.contains("foreach ITEM: item @ src/example/Mapper.java[71,80)"))
        assertTrue(
            presentation.details.contains(
                "bind pattern: '%' + name + '%' @ src/example/Mapper.java[81,90)",
            ),
        )
    }

    @Test
    fun `missing source range renders file identity without inventing an offset`() {
        val provenance = InputProvenance(
            listOf(
                InputEvidence.ExplicitParamAlias(
                    parameterIndex = 0,
                    alias = "id",
                    source = SourceEvidence(FILE, REVISION, null),
                ),
            ),
        )

        val presentation = ContractInputProvenancePresenter.present(provenance)

        assertEquals("@Param(\"id\") @ src/example/Mapper.java", presentation.summary)
        assertEquals(presentation.summary, presentation.details)
        assertFalse(presentation.details.contains("["))
    }

    private fun source(start: Int, endExclusive: Int): SourceEvidence =
        SourceEvidence(FILE, REVISION, SourceRange(start, endExclusive))

    private companion object {
        val FILE = SourceFileId("src/example/Mapper.java")
        val REVISION = SourceRevision("revision-1")
    }
}
