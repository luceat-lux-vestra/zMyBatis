package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeachDynamicTopologyIntegrationTest {
    @Test
    fun foreachStructuralAttributeSynthesisFailsBeforeCollectionAuthorityLookup() {
        assertTopologyFailure(
            """
                <script>
                  <foreach collection="unproven" item="item" open="#{">
                    item}
                  </foreach>
                </script>
            """.trimIndent(),
        )
    }

    @Test
    fun nonForeachDynamicFragmentSynthesisAlsoFailsAtTheSharedPreEvaluationGate() {
        assertTopologyFailure(
            """
                <script>
                  <trim>
                    <if test="unproven">#{</if>
                    id}
                  </trim>
                </script>
            """.trimIndent(),
        )
    }

    private fun assertTopologyFailure(script: String) {
        val result = ForeachProvenanceAdmission.inspect(script, emptyContract())
        assertTrue(result is ForeachProvenanceAdmission.Result.Failed)
        result as ForeachProvenanceAdmission.Result.Failed
        assertEquals(PreparationFailureKind.UNSUPPORTED_SEMANTIC, result.failure.kind)
        assertEquals("java-annotation-dynamic-bound-token-topology-unsupported", result.failure.code)
    }

    private fun emptyContract(): ParameterContract {
        val fileId = SourceFileId("fixture/DynamicTopology.java")
        val revision = SourceRevision("dynamic-topology-revision")
        val statementId = JavaStatementId(
            fileId,
            "fixture.DynamicTopology",
            MethodSignature("query", emptyList()),
        )
        return ParameterContract(
            statementId = statementId,
            requirements = emptyList(),
            aliases = emptyList(),
            internalBindings = emptyList(),
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(fileId to revision),
        )
    }
}
