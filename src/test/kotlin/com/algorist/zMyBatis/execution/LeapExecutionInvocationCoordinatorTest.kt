package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.JavaAnnotationParameterContractFactory
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementCapture
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.settings.ConsoleSessionPolicy
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LeapExecutionInvocationCoordinatorTest {
    @Test
    fun policySnapshotMapsExplicitPresentationSessionAndPostExecutionInputs() {
        val reuse = leapExecutionInvocationPolicy(
            showSqlPreview = true,
            formatPreviewSql = false,
            consoleSessionPolicy = ConsoleSessionPolicy.REUSE,
            copyToClipboard = true,
        )
        assertTrue(reuse.approvalOptions.showSqlPreview)
        assertFalse(reuse.approvalOptions.formatPreviewSql)
        assertFalse(reuse.forceNewConsole)
        assertTrue(reuse.copyExecutedSqlToClipboard)

        val forceNew = leapExecutionInvocationPolicy(
            showSqlPreview = false,
            formatPreviewSql = true,
            consoleSessionPolicy = ConsoleSessionPolicy.NEW_EACH,
            copyToClipboard = false,
        )
        assertFalse(forceNew.approvalOptions.showSqlPreview)
        assertTrue(forceNew.approvalOptions.formatPreviewSql)
        assertTrue(forceNew.forceNewConsole)
        assertFalse(forceNew.copyExecutedSqlToClipboard)
    }

    @Test
    fun zeroInputContractSkipsModalAndBuildsValidatedEmptyEnvironment() {
        val capture = javaCapture("select 1")
        val contract = JavaAnnotationParameterContractFactory.build(capture)
        var activeChecks = 0

        val result = runLeapZeroInput(
            contract = contract,
            isInvocationActive = {
                activeChecks++
                true
            },
        )

        assertTrue(result is LeapExecutionInputResult.Accepted)
        val environment = (result as LeapExecutionInputResult.Accepted).environment
        assertTrue(environment.values.isEmpty())
        assertEquals(2, activeChecks)
    }

    @Test
    fun parameterContractIsDerivedFromTheSameImmutableJavaSource() {
        val capture = javaCapture("select #{id}")
        val source = PreparationSource.JavaAnnotation(capture)

        val expected = JavaAnnotationParameterContractFactory.build(capture)
        val actual = leapExecutionParameterContract(source)

        assertEquals(expected.statementId, actual.statementId)
        assertEquals(expected.sourceRevisions, actual.sourceRevisions)
        assertEquals(expected.requirements, actual.requirements)
        assertEquals(expected.aliases, actual.aliases)
        assertEquals(expected.blockingProblems, actual.blockingProblems)
    }

    @Test
    fun inputCancellationDoesNotRunPostApprovalLifecycleCheck() {
        var activeChecks = 0
        val result = runLeapExecutionInput(
            isInvocationActive = {
                activeChecks++
                true
            },
            showAndRead = { null },
        )

        assertSame(LeapExecutionInputResult.UserCancelled, result)
        assertEquals(1, activeChecks)
    }

    @Test
    fun acceptedInputIsRecheckedAfterModalBoundary() {
        val environment = emptyEnvironment()
        var activeChecks = 0

        val result = runLeapExecutionInput(
            isInvocationActive = {
                activeChecks++
                activeChecks == 1
            },
            showAndRead = { environment },
        )

        assertSame(LeapExecutionInputResult.InvocationInvalidated, result)
        assertEquals(2, activeChecks)
    }

    @Test
    fun coordinatorOwnsPersistedTargetInputAndPolicyBehindShippingCutover() {
        val coordinator = sourceText(
            "src/main/kotlin/com/algorist/zMyBatis/execution/LeapExecutionInvocationCoordinator.kt",
        )
        val action = sourceText(
            "src/main/kotlin/com/algorist/zMyBatis/MyBatisExecuteProxyAction.kt",
        )

        val contract = coordinator.indexOf("val parameterContract = leapExecutionParameterContract(source)")
        val persistedTarget = coordinator.indexOf("val persistedTarget = targetStore.load(sourceFileId)")
        val settings = coordinator.indexOf("val settings = ZMyBatisSettings.getInstance()")
        val input = coordinator.indexOf("inputAdapter.requestInput(")
        val targetRecheck = coordinator.indexOf("val currentTarget = targetStore.load(sourceFileId)", input)
        val delivery = coordinator.indexOf("deliveryCoordinator.execute(", targetRecheck)

        assertTrue(contract >= 0)
        assertTrue(contract < persistedTarget)
        assertTrue(persistedTarget < settings)
        assertTrue(settings < input)
        assertTrue(input < targetRecheck)
        assertTrue(targetRecheck < delivery)
        assertTrue(coordinator.contains("LeapExecutionInvocationFailure.MissingPersistedTarget"))
        assertTrue(coordinator.contains("LeapExecutionInvocationFailure.PersistedTargetChanged"))
        assertTrue(coordinator.contains("LeapExecutionInputResult.UserCancelled -> onCancelled()"))
        assertFalse(coordinator.contains("MyBatisEvaluator"))
        assertFalse(coordinator.contains(" ParameterInputDialog("))
        assertFalse(coordinator.contains("DbPsiFacade"))
        assertFalse(coordinator.contains("JBPopupFactory"))
        assertTrue(action.contains("LeapActionContextAdmissionCoordinator"))
        assertFalse(action.contains("LeapExecutionInvocationCoordinator"))
    }

    private fun emptyEnvironment(): InputEnvironment {
        val capture = javaCapture("select 1")
        val contract = JavaAnnotationParameterContractFactory.build(capture)
        val result = InputEnvironment.validate(contract, emptyList())
        return (result as InputEnvironmentResult.Success).environment
    }

    private fun javaCapture(sql: String): JavaAnnotationStatementCapture {
        val file = SourceFileId("vfs:/fixture/Mapper.java")
        val source = SourceSnapshot(
            fileId = file,
            revision = SourceRevision("document:443"),
            content = "package fixture; interface Mapper {}",
        )
        val statementId = JavaStatementId(
            sourceFileId = file,
            qualifiedMapperType = "fixture.Mapper",
            methodSignature = MethodSignature("find", emptyList()),
        )
        return JavaAnnotationStatementCapture(
            sourceGraph = StatementSourceGraph(
                rootStatement = CapturedStatement(
                    id = statementId,
                    kind = StatementKind.SELECT,
                    sourceRange = SourceRange(0, source.content.length),
                ),
                sourceSnapshots = listOf(source),
                dependencies = emptyList(),
            ),
            sqlSegments = listOf(sql),
            parameters = emptyList(),
        )
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
