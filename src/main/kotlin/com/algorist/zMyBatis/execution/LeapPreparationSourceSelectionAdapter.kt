package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.source.MyBatisDatabaseId
import com.algorist.zMyBatis.source.XmlMapperMethodCaptureAdapter
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

internal typealias LeapPreparationSourceSelector = (
    evidence: LeapActionSourceEvidence.Xml,
    effectiveDatabaseId: MyBatisDatabaseId?,
    checkCanceled: () -> Unit,
) -> LeapPreparationSourceSelectionResult

internal sealed interface LeapPreparationSourceSelectionTaskResult {
    data class Completed(
        val selection: LeapPreparationSourceSelectionResult,
    ) : LeapPreparationSourceSelectionTaskResult

    data object InvocationInvalidated : LeapPreparationSourceSelectionTaskResult
}

/**
 * Cancellable IntelliJ background boundary for post-target XML preparation-source selection.
 *
 * The worker owns immutable XML snapshot/discovery evidence plus the selected database-id context.
 * It never receives an action event, editor, dialog/component, or Database Tools console. Completion
 * is lifecycle-checked before the admission coordinator may revalidate source/target authority and
 * continue into input/candidate preparation.
 */
internal class LeapPreparationSourceSelectionAdapter(
    private val project: Project,
    private val selector: LeapPreparationSourceSelector =
        { evidence, effectiveDatabaseId, checkCanceled ->
            selectLeapPreparationSource(
                evidence = evidence,
                effectiveDatabaseId = effectiveDatabaseId,
                captureXmlMapperMethod = { statementId ->
                    checkCanceled()
                    XmlMapperMethodCaptureAdapter.capture(project, statementId).also {
                        checkCanceled()
                    }
                },
                checkCanceled = checkCanceled,
            )
        },
) {
    fun requestSelection(
        evidence: LeapActionSourceEvidence.Xml,
        effectiveDatabaseId: MyBatisDatabaseId?,
        isInvocationActive: () -> Boolean,
        onResult: (LeapPreparationSourceSelectionTaskResult) -> Unit,
        onCancelled: () -> Unit,
    ) {
        val liveInvocation = { !project.isDisposed && isInvocationActive() }
        if (!liveInvocation()) {
            onResult(LeapPreparationSourceSelectionTaskResult.InvocationInvalidated)
            return
        }

        object : Task.Backgroundable(project, "Resolving zMyBatis mapper source", true) {
            private var selection: LeapPreparationSourceSelectionResult? = null

            override fun run(indicator: ProgressIndicator) {
                selection = selector(
                    evidence,
                    effectiveDatabaseId,
                    indicator::checkCanceled,
                )
            }

            override fun onSuccess() {
                val completed = checkNotNull(selection) {
                    "source-selection background task completed without a result"
                }
                onResult(
                    if (liveInvocation()) {
                        LeapPreparationSourceSelectionTaskResult.Completed(completed)
                    } else {
                        LeapPreparationSourceSelectionTaskResult.InvocationInvalidated
                    },
                )
            }

            override fun onCancel() {
                if (liveInvocation()) {
                    onCancelled()
                } else {
                    onResult(LeapPreparationSourceSelectionTaskResult.InvocationInvalidated)
                }
            }
        }.queue()
    }
}
