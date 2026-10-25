package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.execution.TargetResolutionFailure
import com.algorist.zMyBatis.core.execution.TargetResolutionFailureKind
import com.algorist.zMyBatis.core.materialization.MaterializationResult
import com.algorist.zMyBatis.core.materialization.MaterializedExecution
import com.algorist.zMyBatis.core.materialization.TargetBoundZeroBindingExecutionMaterializer
import com.algorist.zMyBatis.core.preparation.PreparationMetadata
import com.algorist.zMyBatis.core.preparation.PreparedExecution
import com.algorist.zMyBatis.core.source.MyBatisDatabaseId
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.XmlStatementId
import com.algorist.zMyBatis.source.SourceGraphRevalidationFailure
import com.algorist.zMyBatis.source.SourceGraphRevalidationResult
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeapExecutionDeliveryCoordinatorTest {
    private val source = SourceSnapshot(
        SourceFileId("vfs:/Mapper.xml"),
        SourceRevision("vfs:1"),
        "source",
    )
    private val authority = PreparationSourceAuthority(listOf(source))

    @Test
    fun inactiveInvocationStopsBeforeSourceAndTargetChecks() {
        var sourceChecks = 0
        var targetChecks = 0

        val result = leapDeliveryAdmission(
            authority = authority,
            isInvocationActive = { false },
            revalidateSources = {
                sourceChecks++
                SourceGraphRevalidationResult.Current
            },
            resolveTarget = {
                targetChecks++
                missingTarget()
            },
        )

        assertTrue(result is LeapDeliveryAdmission.Rejected)
        result as LeapDeliveryAdmission.Rejected
        assertEquals(LeapExecutionDeliveryFailure.InvocationInvalidated, result.failure)
        assertEquals(0, sourceChecks)
        assertEquals(0, targetChecks)
    }

    @Test
    fun staleCompleteSourceAuthorityStopsBeforeTargetResolution() {
        var targetChecks = 0
        val sourceFailure = SourceGraphRevalidationFailure.SourceChanged(
            sourceFileId = source.fileId,
            expectedRevision = source.revision,
            actualRevision = SourceRevision("vfs:2"),
        )

        val result = leapDeliveryAdmission(
            authority = authority,
            isInvocationActive = { true },
            revalidateSources = { SourceGraphRevalidationResult.Failed(sourceFailure) },
            resolveTarget = {
                targetChecks++
                missingTarget()
            },
        )

        assertTrue(result is LeapDeliveryAdmission.Rejected)
        result as LeapDeliveryAdmission.Rejected
        assertEquals(LeapExecutionDeliveryFailure.SourceInvalid(sourceFailure), result.failure)
        assertEquals(0, targetChecks)
    }

    @Test
    fun currentSourceThenMissingExactTargetReturnsTypedTargetFailure() {
        var sourceChecks = 0
        var targetChecks = 0

        val result = leapDeliveryAdmission(
            authority = authority,
            isInvocationActive = { true },
            revalidateSources = {
                sourceChecks++
                assertEquals(listOf(source), it)
                SourceGraphRevalidationResult.Current
            },
            resolveTarget = {
                targetChecks++
                missingTarget()
            },
        )

        assertTrue(result is LeapDeliveryAdmission.Rejected)
        result as LeapDeliveryAdmission.Rejected
        assertTrue(result.failure is LeapExecutionDeliveryFailure.TargetResolutionFailed)
        assertEquals(1, sourceChecks)
        assertEquals(1, targetChecks)
    }

    @Test
    fun artifactMustMatchExactTargetAndTargetDerivedDatabaseIdContext() {
        val target = resolvedTarget("PostgreSQL")
        val artifact = materialized(target)

        assertTrue(artifactMatchesResolvedTarget(artifact, target))
        assertFalse(
            artifactMatchesResolvedTarget(
                artifact,
                ResolvedExecutionTarget(target.descriptor, MyBatisDatabaseId("Oracle")),
            ),
        )
        assertFalse(
            artifactMatchesResolvedTarget(
                artifact,
                ResolvedExecutionTarget(
                    ExecutionTargetDescriptor(
                        ExecutionTargetId(
                            StableDataSourceId("other-ds"),
                            ExplicitSchemaIdentity("public"),
                        ),
                        "other",
                    ),
                    MyBatisDatabaseId("PostgreSQL"),
                ),
            ),
        )
    }

    @Test
    fun coordinatorComposesMaintainedDeliveryBoundaries() {
        val coordinator = sourceText(
            "src/main/kotlin/com/algorist/zMyBatis/execution/LeapExecutionDeliveryCoordinator.kt",
        )

        val firstAdmission = coordinator.indexOf("val initialTarget = when (")
        val candidate = coordinator.indexOf("candidateAdapter.requestCandidate(")
        val continuation = coordinator.indexOf("private fun continueWithCandidate(")
        val approval = coordinator.indexOf("approvalAdapter.requestApproval(", continuation)
        val approvedDelivery = coordinator.indexOf("private fun deliverApproved(")
        val secondAdmission = coordinator.indexOf("val deliveryTarget = when (", approvedDelivery)
        val acquire = coordinator.indexOf("consoleAdapter.acquireAndDeliverConsole(", approvedDelivery)
        val deliver = coordinator.indexOf("consoleAdapter.executeMaterialized(", approvedDelivery)

        assertTrue(firstAdmission >= 0)
        assertTrue(firstAdmission < candidate)
        assertTrue(candidate < continuation)
        assertTrue(continuation < approval)
        assertTrue(approval < approvedDelivery)
        assertTrue(approvedDelivery < secondAdmission)
        assertTrue(secondAdmission < acquire)
        assertTrue(acquire < deliver)
        assertTrue(coordinator.contains("LeapExecutionApprovalResult.UserCancelled -> onCancelled()"))
        assertTrue(coordinator.contains("StatementSourceGraphRevalidator::revalidateSnapshots"))
        assertTrue(coordinator.contains("targetResolver.resolve(targetDescriptor)"))
        assertTrue(coordinator.contains("LeapExecutionCandidateResult.InvocationInvalidated"))
        assertFalse(coordinator.contains("LeapExecutionCandidateCoordinator.build("))
    }

    private fun missingTarget(): DatabaseToolsTargetResolution =
        DatabaseToolsTargetResolution.Failed(
            TargetResolutionFailure(
                TargetResolutionFailureKind.DATA_SOURCE_MISSING,
                "target-resolution-data-source-missing",
            ),
        )

    private fun targetId(): ExecutionTargetId =
        ExecutionTargetId(
            StableDataSourceId("fixture-ds"),
            ExplicitSchemaIdentity("public"),
        )

    private fun resolvedTarget(databaseId: String): ResolvedExecutionTarget =
        ResolvedExecutionTarget(
            ExecutionTargetDescriptor(targetId(), "fixture"),
            MyBatisDatabaseId(databaseId),
        )

    private fun materialized(target: ResolvedExecutionTarget): MaterializedExecution {
        val file = SourceFileId("vfs:/Mapper.xml")
        val prepared = PreparedExecution(
            statementId = XmlStatementId(file, "fixture.Mapper", "find"),
            statementKind = StatementKind.SELECT,
            sourceRevisions = mapOf(file to SourceRevision("vfs:1")),
            sqlWithPlaceholders = "select 1",
            orderedBindings = emptyList(),
            rawInterpolations = emptyList(),
            preparationMetadata = PreparationMetadata(
                engineIdentity = "org.mybatis:mybatis",
                engineVersion = "3.5.19",
                languageDriverIdentity = "org.apache.ibatis.scripting.xmltags.XMLLanguageDriver",
                databaseId = target.effectiveDatabaseId?.value,
            ),
        )
        return (
            TargetBoundZeroBindingExecutionMaterializer(target).materialize(prepared) as
                MaterializationResult.Success
            ).execution
    }

    private fun sourceText(relativePath: String): String =
        Files.readString(repositoryRoot().resolve(relativePath))

    private fun repositoryRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (true) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                return current
            }
            current = current.parent ?: error("Repository root not found")
        }
    }
}
