package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
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
import com.algorist.zMyBatis.mybatis.MyBatisInvocationContractFactory
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LeapExecutionInvocationCoordinatorTest {
    @Test
    fun policySnapshotMapsOnlyExplicitPresentationInputs() {
        val policy = leapExecutionInvocationPolicy(
            showSqlPreview = true,
            formatPreviewSql = false,
        )
        assertTrue(policy.approvalOptions.showSqlPreview)
        assertFalse(policy.approvalOptions.formatPreviewSql)
    }

    @Test
    fun parameterContractIsDerivedFromTheSameImmutableJavaSource() {
        val capture = javaCapture("select #{id}")
        val source = PreparationSource.JavaAnnotation(capture)

        val expected = MyBatisInvocationContractFactory.build(source)
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
    fun coordinatorOwnsPersistedTargetInputAndPolicyForShippingAuthority() {
        val coordinator = sourceText(
            "src/main/kotlin/com/algorist/zMyBatis/execution/LeapExecutionInvocationCoordinator.kt",
        )

        val contract = coordinator.indexOf("val parameterContract = leapExecutionParameterContract(source)")
        val persistedTarget = coordinator.indexOf("val persistedTarget = targetStore.load(sourceFileId)")
        val settings = coordinator.indexOf("val settings = ZMyBatisSettings.getInstance()")
        val zeroInput = coordinator.indexOf("if (parameterContract.requirements.isEmpty())")
        val input = coordinator.indexOf("inputAdapter.requestInput(")
        val targetRecheck = coordinator.indexOf("val currentTarget = targetStore.load(sourceFileId)")
        val delivery = coordinator.indexOf("deliveryCoordinator.execute(", targetRecheck)

        assertTrue(contract >= 0)
        assertTrue(contract < persistedTarget)
        assertTrue(persistedTarget < settings)
        assertTrue(settings < zeroInput)
        assertTrue(zeroInput < input)
        assertTrue(targetRecheck < zeroInput)
        assertTrue(targetRecheck < delivery)
        assertTrue(coordinator.contains("InputEnvironment.validate(parameterContract, emptyList())"))
        assertTrue(coordinator.contains("is InputEnvironmentResult.Success -> continueWithInput(emptyInput.environment)"))
        assertTrue(coordinator.contains("LeapExecutionInvocationFailure.MissingPersistedTarget"))
        assertTrue(coordinator.contains("LeapExecutionInvocationFailure.PersistedTargetChanged"))
        assertTrue(coordinator.contains("LeapExecutionInputResult.UserCancelled -> onCancelled()"))
        assertTrue(coordinator.contains("dataContext = dataContext"))
        assertFalse(coordinator.contains("MyBatisEvaluator"))
        assertFalse(coordinator.contains(" ParameterInputDialog("))
        assertFalse(coordinator.contains("DbPsiFacade"))
        assertFalse(coordinator.contains("JBPopupFactory"))
        assertFalse(coordinator.contains("ConsoleSessionPolicy"))
        assertFalse(coordinator.contains("forceNewConsole"))
    }

    private fun emptyEnvironment(): InputEnvironment {
        val capture = javaCapture("select 1")
        val source = PreparationSource.JavaAnnotation(capture)
        val contract = MyBatisInvocationContractFactory.build(source)
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
