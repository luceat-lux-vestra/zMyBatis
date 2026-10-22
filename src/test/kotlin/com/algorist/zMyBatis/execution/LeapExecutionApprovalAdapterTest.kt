package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.materialization.MaterializationResult
import com.algorist.zMyBatis.core.materialization.MaterializedExecution
import com.algorist.zMyBatis.core.materialization.TargetBoundZeroBindingExecutionMaterializer
import com.algorist.zMyBatis.core.preparation.PreparationMetadata
import com.algorist.zMyBatis.core.preparation.PreparedExecution
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.XmlStatementId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LeapExecutionApprovalAdapterTest {
    @Test
    fun noPreviewApprovalDoesNotFormatOrOpenModal() {
        val artifact = artifact(declaredMutation = false)
        var formatCalls = 0
        var modalCalls = 0

        val result = runLeapExecutionApproval(
            artifact = artifact,
            options = LeapExecutionApprovalOptions(
                showSqlPreview = false,
                formatPreviewSql = true,
            ),
            isInvocationActive = { true },
            formatPreview = {
                formatCalls++
                "formatted"
            },
            showPreview = {
                modalCalls++
                true
            },
        )

        assertSame(LeapExecutionApprovalResult.Approved, result)
        assertEquals(0, formatCalls)
        assertEquals(0, modalCalls)
    }

    @Test
    fun formattedPreviewIsPresentationOnlyAndOriginalArtifactRemainsAuthority() {
        val artifact = artifact(declaredMutation = true)
        val originalSql = artifact.executionSql
        var formatterInput: String? = null
        var previewText: String? = null

        val result = runLeapExecutionApproval(
            artifact = artifact,
            options = LeapExecutionApprovalOptions(
                showSqlPreview = false,
                formatPreviewSql = true,
            ),
            isInvocationActive = { true },
            formatPreview = { sql ->
                formatterInput = sql
                "FORMATTED<$sql>"
            },
            showPreview = { sql ->
                previewText = sql
                true
            },
        )

        assertSame(LeapExecutionApprovalResult.Approved, result)
        assertEquals(originalSql, formatterInput)
        assertEquals("FORMATTED<$originalSql>", previewText)
        assertEquals(originalSql, artifact.executionSql)
    }

    @Test
    fun userCancellationStopsAtApprovalBoundary() {
        val artifact = artifact(declaredMutation = true)
        var activeChecks = 0

        val result = runLeapExecutionApproval(
            artifact = artifact,
            options = LeapExecutionApprovalOptions(
                showSqlPreview = false,
                formatPreviewSql = false,
            ),
            isInvocationActive = {
                activeChecks++
                true
            },
            formatPreview = { error("formatting must be disabled") },
            showPreview = { false },
        )

        assertSame(LeapExecutionApprovalResult.UserCancelled, result)
        assertEquals(1, activeChecks)
    }

    @Test
    fun invocationIsRecheckedAfterPositiveModalApproval() {
        val artifact = artifact(declaredMutation = true)
        var activeChecks = 0

        val result = runLeapExecutionApproval(
            artifact = artifact,
            options = LeapExecutionApprovalOptions(
                showSqlPreview = false,
                formatPreviewSql = false,
            ),
            isInvocationActive = {
                activeChecks++
                activeChecks == 1
            },
            formatPreview = { it },
            showPreview = { true },
        )

        assertSame(LeapExecutionApprovalResult.InvocationInvalidated, result)
        assertEquals(2, activeChecks)
    }

    @Test
    fun explicitUserPreviewUsesSameImmutableArtifactText() {
        val artifact = artifact(declaredMutation = false)
        var previewText: String? = null

        val result = runLeapExecutionApproval(
            artifact = artifact,
            options = LeapExecutionApprovalOptions(
                showSqlPreview = true,
                formatPreviewSql = false,
            ),
            isInvocationActive = { true },
            formatPreview = { error("formatting must be disabled") },
            showPreview = {
                previewText = it
                true
            },
        )

        assertSame(LeapExecutionApprovalResult.Approved, result)
        assertEquals(artifact.executionSql, previewText)
        assertFalse(artifact.executionSql.isBlank())
        assertTrue(previewText === artifact.executionSql || previewText == artifact.executionSql)
    }

    private fun artifact(
        declaredMutation: Boolean,
    ): MaterializedExecution {
        val file = SourceFileId("vfs:/Mapper.xml")
        val prepared = PreparedExecution(
            statementId = XmlStatementId(file, "fixture.Mapper", "find"),
            statementKind = if (declaredMutation) StatementKind.UPDATE else StatementKind.SELECT,
            sourceRevisions = mapOf(file to SourceRevision("vfs:1")),
            sqlWithPlaceholders = "select 441 as artifact",
            orderedBindings = emptyList(),
            rawInterpolations = emptyList(),
            preparationMetadata = PreparationMetadata(
                engineIdentity = "org.mybatis:mybatis",
                engineVersion = "3.5.19",
                languageDriverIdentity = "org.apache.ibatis.scripting.xmltags.XMLLanguageDriver",
            ),
        )
        val target = ResolvedExecutionTarget(
            ExecutionTargetDescriptor(
                ExecutionTargetId(
                    StableDataSourceId("fixture-ds"),
                    ExplicitSchemaIdentity("public"),
                ),
                "fixture",
            ),
        )
        return (
            TargetBoundZeroBindingExecutionMaterializer(target).materialize(prepared) as
                MaterializationResult.Success
            ).execution
    }
}
