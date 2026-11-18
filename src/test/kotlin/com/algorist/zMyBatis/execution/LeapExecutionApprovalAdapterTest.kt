package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlan
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlanResult
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlanner
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
        val plan = plan(declaredMutation = false)
        var formatCalls = 0
        var modalCalls = 0

        val result = runLeapExecutionApproval(
            plan = plan,
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
    fun formattedPreviewIsPresentationOnlyAndOriginalPlanRemainsAuthority() {
        val plan = plan(declaredMutation = true)
        val originalSql = plan.sqlWithPlaceholders
        var formatterInput: String? = null
        var previewText: String? = null

        val result = runLeapExecutionApproval(
            plan = plan,
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
        assertEquals(originalSql, plan.sqlWithPlaceholders)
    }

    @Test
    fun userCancellationStopsAtApprovalBoundary() {
        val plan = plan(declaredMutation = true)
        var activeChecks = 0

        val result = runLeapExecutionApproval(
            plan = plan,
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
        val plan = plan(declaredMutation = true)
        var activeChecks = 0

        val result = runLeapExecutionApproval(
            plan = plan,
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
    fun explicitUserPreviewUsesSameImmutablePlanText() {
        val plan = plan(declaredMutation = false)
        var previewText: String? = null

        val result = runLeapExecutionApproval(
            plan = plan,
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
        assertEquals(plan.sqlWithPlaceholders, previewText)
        assertFalse(plan.sqlWithPlaceholders.isBlank())
        assertTrue(previewText === plan.sqlWithPlaceholders || previewText == plan.sqlWithPlaceholders)
    }

    @Test
    fun boundPreviewShowsTypedMetadataWithoutBoundValue() {
        val file = SourceFileId("vfs:/Mapper.xml")
        val prepared = PreparedExecution(
            statementId = XmlStatementId(file, "fixture.Mapper", "find"),
            statementKind = StatementKind.SELECT,
            sourceRevisions = mapOf(file to SourceRevision("vfs:1")),
            sqlWithPlaceholders = "select ?",
            orderedBindings = listOf(
                com.algorist.zMyBatis.core.preparation.PreparedBinding(
                    index = 0,
                    property = "name",
                    value = com.algorist.zMyBatis.core.input.InputValue.Text("Ada"),
                    origin = com.algorist.zMyBatis.core.preparation.PreparedBindingOrigin.CallerInput(
                        com.algorist.zMyBatis.core.input.InputRequirementId("method-param:0"),
                        com.algorist.zMyBatis.core.input.InputProvenance(
                            listOf(
                                com.algorist.zMyBatis.core.input.InputEvidence.Placeholder(
                                    kind = com.algorist.zMyBatis.core.input.InputKind.BOUND,
                                    expression = "name",
                                    source = com.algorist.zMyBatis.core.input.SourceEvidence(
                                        sourceFileId = file,
                                        sourceRevision = SourceRevision("vfs:1"),
                                        sourceRange = null,
                                    ),
                                ),
                            ),
                        ),
                    ),
                    metadata = com.algorist.zMyBatis.core.preparation.PreparedBindingMetadata(
                        declaredJavaTypeIdentity = null,
                        mappingJavaTypeIdentity = "java.lang.String",
                        jdbcTypeIdentity = "VARCHAR",
                        effectiveJdbcTypeIdentity = "VARCHAR",
                        typeHandlerIdentity = "org.apache.ibatis.type.StringTypeHandler",
                        parameterMode = "IN",
                        numericScale = null,
                    ),
                ),
            ),
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
        val plan = when (val result = PreparedExecutionPlanner.freeze(prepared, target)) {
            is PreparedExecutionPlanResult.Ready -> result.plan
            is PreparedExecutionPlanResult.Failed -> error("fixture plan failed")
        }

        val preview = preparedPlanPreview(plan)

        assertTrue(preview.contains("select ?"))
        assertTrue(preview.contains("#1 property=name"))
        assertTrue(preview.contains("jdbcType=VARCHAR"))
        assertFalse(preview.contains("Ada"))
    }

    private fun plan(
        declaredMutation: Boolean,
    ): PreparedExecutionPlan {
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
        return when (val result = PreparedExecutionPlanner.freeze(prepared, target)) {
            is PreparedExecutionPlanResult.Ready -> result.plan
            is PreparedExecutionPlanResult.Failed ->
                error("fixture plan failed: ${result.failure.kind} / ${result.failure.code}")
        }
    }
}
