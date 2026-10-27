package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.source.XmlMapperDocumentDiscovery
import com.algorist.zMyBatis.source.XmlProjectMapperSourceCaptureAdapter
import com.algorist.zMyBatis.source.XmlProjectMapperSourceCaptureResult
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

internal typealias LeapProjectXmlMapperExpander = (
    rootSnapshot: SourceSnapshot,
    rootDiscovery: XmlMapperDocumentDiscovery,
    checkCanceled: () -> Unit,
) -> XmlProjectMapperSourceCaptureResult

internal sealed interface LeapProjectXmlMapperExpansionTaskResult {
    data class Completed(
        val expansion: XmlProjectMapperSourceCaptureResult,
    ) : LeapProjectXmlMapperExpansionTaskResult

    data object InvocationInvalidated : LeapProjectXmlMapperExpansionTaskResult
}

/**
 * Cancellable IntelliJ background boundary for pre-target project XML mapper expansion.
 *
 * The worker receives only immutable root source evidence plus a cancellation probe. Completion is
 * lifecycle-checked before the admission coordinator may resume target selection.
 */
internal class LeapProjectXmlMapperExpansionAdapter(
    private val project: Project,
    private val expander: LeapProjectXmlMapperExpander =
        { rootSnapshot, rootDiscovery, checkCanceled ->
            XmlProjectMapperSourceCaptureAdapter.capture(
                project = project,
                rootSnapshot = rootSnapshot,
                rootDiscovery = rootDiscovery,
                checkCanceled = checkCanceled,
            )
        },
) {
    fun requestExpansion(
        rootSnapshot: SourceSnapshot,
        rootDiscovery: XmlMapperDocumentDiscovery,
        isInvocationActive: () -> Boolean,
        onResult: (LeapProjectXmlMapperExpansionTaskResult) -> Unit,
        onCancelled: () -> Unit,
    ) {
        val liveInvocation = { !project.isDisposed && isInvocationActive() }
        if (!liveInvocation()) {
            onResult(LeapProjectXmlMapperExpansionTaskResult.InvocationInvalidated)
            return
        }

        object : Task.Backgroundable(project, "Expanding zMyBatis mapper sources", true) {
            private var expansion: XmlProjectMapperSourceCaptureResult? = null

            override fun run(indicator: ProgressIndicator) {
                expansion = expander(
                    rootSnapshot,
                    rootDiscovery,
                    indicator::checkCanceled,
                )
            }

            override fun onSuccess() {
                val completed = checkNotNull(expansion) {
                    "project XML expansion background task completed without a result"
                }
                onResult(
                    if (liveInvocation()) {
                        LeapProjectXmlMapperExpansionTaskResult.Completed(completed)
                    } else {
                        LeapProjectXmlMapperExpansionTaskResult.InvocationInvalidated
                    },
                )
            }

            override fun onCancel() {
                if (liveInvocation()) {
                    onCancelled()
                } else {
                    onResult(LeapProjectXmlMapperExpansionTaskResult.InvocationInvalidated)
                }
            }
        }.queue()
    }
}
