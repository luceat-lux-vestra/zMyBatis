package com.algorist.zMyBatis.input

import com.algorist.zMyBatis.core.input.ExpectedInputType
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequiredness
import com.algorist.zMyBatis.core.input.InputRequirement
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputScalarType
import com.algorist.zMyBatis.core.input.InputShape
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.settings.ZMyBatisSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class ContractInputScaleProjectFixtureTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testLargeRetainedDialogConstructionRecordsEdtScaleEnvelope() {
        val settings = ZMyBatisSettings.getInstance()
        val originalRetention = settings.rememberLastInputs
        settings.rememberLastInputs = true

        val contract = contract(DIALOG_REQUIREMENT_COUNT)
        val retainedValue = "r".repeat(RETAINED_VALUE_CHARS)
        val retainedValues = contract.requirements.associate { requirement ->
            requirement.id to retainedValue
        }
        val retainedBytes = retainedValues.values.sumOf { value ->
            value.toByteArray(Charsets.UTF_8).size.toLong()
        }
        val history = ContractInputHistoryService.getInstance(project)
        history.save(
            contract = contract,
            rawValues = retainedValues,
            explicitlyRetainedIds = retainedValues.keys,
        )
        assertEquals(DIALOG_REQUIREMENT_COUNT, history.load(contract).size)

        val sentinel = CompletableFuture<Unit>()
        val result = CompletableFuture<DialogMeasurement>()

        try {
            ApplicationManager.getApplication().invokeLater(
                {
                    assertTrue(
                        "shipping parameter dialog construction must execute on the EDT",
                        ApplicationManager.getApplication().isDispatchThread,
                    )
                    ApplicationManager.getApplication().invokeLater(
                        { sentinel.complete(Unit) },
                        ModalityState.any(),
                    )

                    val startedNanos = System.nanoTime()
                    val dialog = ContractParameterInputDialog(project, contract)
                    val elapsedNanos = System.nanoTime() - startedNanos
                    val sentinelCompletedBeforeConstructorReturned = sentinel.isDone
                    dialog.close(DialogWrapper.CANCEL_EXIT_CODE)

                    result.complete(
                        DialogMeasurement(
                            elapsedNanos = elapsedNanos,
                            sentinelCompletedBeforeConstructorReturned =
                                sentinelCompletedBeforeConstructorReturned,
                        ),
                    )
                },
                ModalityState.any(),
            )

            val measurement = PlatformTestUtil.waitForFuture(result, FIXTURE_TIMEOUT_MILLIS)
            PlatformTestUtil.waitForFuture(sentinel, FIXTURE_TIMEOUT_MILLIS)

            assertFalse(
                "queued EDT work must not run while the synchronous shipping dialog constructor is outstanding",
                measurement.sentinelCompletedBeforeConstructorReturned,
            )

            println(
                "ZMYBATIS_INPUT_SCALE phase=retained-dialog-construction " +
                    "requirements=$DIALOG_REQUIREMENT_COUNT " +
                    "editor_mix=text:$DIALOG_REQUIREMENT_COUNT " +
                    "retained_values=$DIALOG_REQUIREMENT_COUNT " +
                    "retained_bytes=$retainedBytes " +
                    "execution_thread=EDT " +
                    "sentinel_during_constructor=false " +
                    "sentinel_after_constructor=true " +
                    "elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(measurement.elapsedNanos)}",
            )
        } finally {
            history.clear(contract.statementId)
            settings.rememberLastInputs = originalRetention
        }
    }

    fun testLargeSubmissionRecordsRealPolicyAdapterValidationOnEdt() {
        val contract = contract(SUBMISSION_REQUIREMENT_COUNT)
        val text = "x".repeat(SUBMISSION_VALUE_CHARS)
        val drafts = contract.requirements.map { requirement ->
            ContractInputDraft(
                requirementId = requirement.id,
                text = text,
                supplied = true,
                edited = true,
                retained = false,
                rawConfirmed = false,
            )
        }
        val aggregateInputBytes = drafts.sumOf { draft ->
            draft.text.toByteArray(Charsets.UTF_8).size.toLong()
        }
        val sentinel = CompletableFuture<Unit>()
        val result = CompletableFuture<SubmissionMeasurement>()

        ApplicationManager.getApplication().invokeLater(
            {
                assertTrue(
                    "shipping dialog submission path executes synchronously on the EDT",
                    ApplicationManager.getApplication().isDispatchThread,
                )
                ApplicationManager.getApplication().invokeLater(
                    { sentinel.complete(Unit) },
                    ModalityState.any(),
                )

                val startedNanos = System.nanoTime()
                val prepared = ContractInputSubmissionPolicy.prepare(
                    contract = contract,
                    drafts = drafts,
                    acceptRetained = false,
                )
                val elapsedNanos = System.nanoTime() - startedNanos
                result.complete(
                    SubmissionMeasurement(
                        prepared = prepared,
                        elapsedNanos = elapsedNanos,
                        sentinelCompletedBeforeSubmissionReturned = sentinel.isDone,
                    ),
                )
            },
            ModalityState.any(),
        )

        val measurement = PlatformTestUtil.waitForFuture(result, FIXTURE_TIMEOUT_MILLIS)
        PlatformTestUtil.waitForFuture(sentinel, FIXTURE_TIMEOUT_MILLIS)

        assertTrue(measurement.prepared is ContractInputAdapterResult.Success)
        val environment =
            (measurement.prepared as ContractInputAdapterResult.Success).environment
        assertEquals(SUBMISSION_REQUIREMENT_COUNT, environment.values.size)
        assertFalse(
            "queued EDT work must not run while synchronous submission/decode/validation is outstanding",
            measurement.sentinelCompletedBeforeSubmissionReturned,
        )

        println(
            "ZMYBATIS_INPUT_SCALE phase=submission-validation " +
                "requirements=$SUBMISSION_REQUIREMENT_COUNT " +
                "editor_mix=text:$SUBMISSION_REQUIREMENT_COUNT " +
                "aggregate_input_bytes=$aggregateInputBytes " +
                "validated_values=${environment.values.size} " +
                "execution_thread=EDT " +
                "sentinel_during_submission=false " +
                "sentinel_after_submission=true " +
                "elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(measurement.elapsedNanos)}",
        )
    }

    private fun contract(requirementCount: Int): ParameterContract {
        val requirements = (0 until requirementCount).map { index ->
            val id = InputRequirementId("input-${index.toString().padStart(4, '0')}")
            InputRequirement(
                id = id,
                kind = InputKind.BOUND,
                expectedType = ExpectedInputType(
                    shape = InputShape.SCALAR,
                    scalarType = InputScalarType.STRING,
                ),
                requiredness = InputRequiredness.REQUIRED,
                provenance = InputProvenance(
                    listOf(
                        InputEvidence.Placeholder(
                            kind = InputKind.BOUND,
                            expression = id.value,
                            source = SourceEvidence(FILE, REVISION, null),
                        ),
                    ),
                ),
            )
        }

        return ParameterContract(
            statementId = STATEMENT,
            requirements = requirements,
            aliases = emptyList(),
            internalBindings = emptyList(),
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(FILE to REVISION),
        )
    }

    private data class DialogMeasurement(
        val elapsedNanos: Long,
        val sentinelCompletedBeforeConstructorReturned: Boolean,
    )

    private data class SubmissionMeasurement(
        val prepared: ContractInputAdapterResult,
        val elapsedNanos: Long,
        val sentinelCompletedBeforeSubmissionReturned: Boolean,
    )

    companion object {
        private const val DIALOG_REQUIREMENT_COUNT = 128
        private const val RETAINED_VALUE_CHARS = 4 * 1024
        private const val SUBMISSION_REQUIREMENT_COUNT = 256
        private const val SUBMISSION_VALUE_CHARS = 4 * 1024
        private const val FIXTURE_TIMEOUT_SECONDS = 30L
        private const val FIXTURE_TIMEOUT_MILLIS = FIXTURE_TIMEOUT_SECONDS * 1_000

        private val FILE = SourceFileId("fixture://large-input-scale")
        private val REVISION = SourceRevision("fixture:472")
        private val STATEMENT = JavaStatementId(
            sourceFileId = FILE,
            qualifiedMapperType = "fixture.ScaleMapper",
            methodSignature = MethodSignature(
                name = "find",
                parameterTypeIdentities = listOf(JavaTypeIdentity("java.lang.String")),
            ),
        )
    }
}
