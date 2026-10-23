package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.SourceTargetAssociation
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.execution.TargetResolutionFailure
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementSelectionResult
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementVariantsCapture
import com.algorist.zMyBatis.core.source.MyBatisDatabaseId
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.XmlStatementId
import com.algorist.zMyBatis.core.source.selectJavaAnnotationStatementVariant
import com.algorist.zMyBatis.services.ConsoleCacheService
import com.algorist.zMyBatis.services.ExecutionTargetDescriptorStore
import com.algorist.zMyBatis.source.JavaAnnotationSourceCaptureAdapter
import com.algorist.zMyBatis.source.JavaAnnotationSourceCaptureFailure
import com.algorist.zMyBatis.source.JavaAnnotationSourceCaptureResult
import com.algorist.zMyBatis.source.SourceGraphRevalidationFailure
import com.algorist.zMyBatis.source.SourceGraphRevalidationResult
import com.algorist.zMyBatis.source.StatementSourceGraphRevalidator
import com.algorist.zMyBatis.source.XmlActionSourceCaptureAdapter
import com.algorist.zMyBatis.source.XmlActionSourceCaptureFailure
import com.algorist.zMyBatis.source.XmlActionSourceCaptureResult
import com.algorist.zMyBatis.source.XmlMapperDocumentDiscovery
import com.algorist.zMyBatis.source.XmlMapperMethodCaptureAdapter
import com.algorist.zMyBatis.source.XmlMapperMethodCaptureFailure
import com.algorist.zMyBatis.source.XmlMapperMethodCaptureResult
import com.algorist.zMyBatis.source.XmlStatementSourceGraphFailure
import com.algorist.zMyBatis.source.XmlStatementSourceGraphResolver
import com.algorist.zMyBatis.source.XmlStatementSourceGraphResult
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.DasUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface LeapActionSourceEvidence {
    val sourceFileId: SourceFileId

    data class Java(
        val capture: JavaAnnotationStatementVariantsCapture,
    ) : LeapActionSourceEvidence {
        override val sourceFileId: SourceFileId
            get() = capture.sourceGraph.rootStatement.id.sourceFileId
    }

    data class Xml(
        val statementId: XmlStatementId,
        val snapshots: List<SourceSnapshot>,
        val discoveries: List<XmlMapperDocumentDiscovery>,
    ) : LeapActionSourceEvidence {
        override val sourceFileId: SourceFileId
            get() = statementId.sourceFileId
    }
}

internal sealed interface LeapActionSourceEvidenceResult {
    data class Captured(val evidence: LeapActionSourceEvidence) : LeapActionSourceEvidenceResult
    data class Failed(val failure: LeapActionSourceEvidenceFailure) : LeapActionSourceEvidenceResult
}

internal sealed interface LeapActionSourceEvidenceFailure {
    data class Java(
        val failure: JavaAnnotationSourceCaptureFailure,
    ) : LeapActionSourceEvidenceFailure

    data class Xml(
        val failure: XmlActionSourceCaptureFailure,
    ) : LeapActionSourceEvidenceFailure

    data object XmlProjectIndexUnavailable : LeapActionSourceEvidenceFailure
    data object UnsupportedContext : LeapActionSourceEvidenceFailure
}

internal object LeapActionSourceEvidenceAdapter {
    fun capture(
        project: Project,
        editor: Editor,
        psiFile: PsiFile,
    ): LeapActionSourceEvidenceResult =
        when (psiFile) {
            is PsiJavaFile ->
                when (val result = JavaAnnotationSourceCaptureAdapter.capture(project, editor)) {
                    is JavaAnnotationSourceCaptureResult.Captured ->
                        LeapActionSourceEvidenceResult.Captured(
                            LeapActionSourceEvidence.Java(result.capture),
                        )
                    is JavaAnnotationSourceCaptureResult.Failed ->
                        LeapActionSourceEvidenceResult.Failed(
                            LeapActionSourceEvidenceFailure.Java(result.failure),
                        )
                }

            is XmlFile ->
                when (val result = XmlActionSourceCaptureAdapter.capture(project, editor)) {
                    is XmlActionSourceCaptureResult.Captured ->
                        LeapActionSourceEvidenceResult.Captured(
                            LeapActionSourceEvidence.Xml(
                                statementId = result.capture.statementId,
                                snapshots = listOf(result.capture.snapshot),
                                discoveries = listOf(result.capture.discovery),
                            ),
                        )
                    is XmlActionSourceCaptureResult.Failed ->
                        LeapActionSourceEvidenceResult.Failed(
                            LeapActionSourceEvidenceFailure.Xml(result.failure),
                        )
                }

            else -> LeapActionSourceEvidenceResult.Failed(
                LeapActionSourceEvidenceFailure.UnsupportedContext,
            )
        }
}

internal sealed interface LeapPreparationSourceSelectionResult {
    data class Ready(val source: PreparationSource) : LeapPreparationSourceSelectionResult
    data class Failed(val failure: LeapPreparationSourceSelectionFailure) : LeapPreparationSourceSelectionResult
}

internal sealed interface LeapPreparationSourceSelectionFailure {
    data object JavaVariantMissing : LeapPreparationSourceSelectionFailure
    data object JavaVariantAmbiguous : LeapPreparationSourceSelectionFailure
    data object JavaDatabaseIdAuthorityUnavailable : LeapPreparationSourceSelectionFailure

    data class JavaDatabaseIdMappingUnproven(
        val declaredDatabaseIds: List<String>,
    ) : LeapPreparationSourceSelectionFailure

    data class XmlSourceGraphFailed(
        val failure: XmlStatementSourceGraphFailure,
    ) : LeapPreparationSourceSelectionFailure

    data class XmlMapperMethodFailed(
        val failure: XmlMapperMethodCaptureFailure,
    ) : LeapPreparationSourceSelectionFailure
}

internal fun selectLeapPreparationSource(
    evidence: LeapActionSourceEvidence,
    effectiveDatabaseId: MyBatisDatabaseId?,
    captureXmlMapperMethod: (XmlStatementId) -> XmlMapperMethodCaptureResult,
): LeapPreparationSourceSelectionResult {
    return when (evidence) {
        is LeapActionSourceEvidence.Java ->
            when (
                val selection = selectJavaAnnotationStatementVariant(
                    evidence.capture,
                    effectiveDatabaseId,
                )
            ) {
                is JavaAnnotationStatementSelectionResult.Selected ->
                    LeapPreparationSourceSelectionResult.Ready(
                        PreparationSource.JavaAnnotation(selection.capture),
                    )
                JavaAnnotationStatementSelectionResult.Missing ->
                    LeapPreparationSourceSelectionResult.Failed(
                        LeapPreparationSourceSelectionFailure.JavaVariantMissing,
                    )
                JavaAnnotationStatementSelectionResult.Ambiguous ->
                    LeapPreparationSourceSelectionResult.Failed(
                        LeapPreparationSourceSelectionFailure.JavaVariantAmbiguous,
                    )
                JavaAnnotationStatementSelectionResult.AuthorityUnavailable ->
                    LeapPreparationSourceSelectionResult.Failed(
                        LeapPreparationSourceSelectionFailure.JavaDatabaseIdAuthorityUnavailable,
                    )
                is JavaAnnotationStatementSelectionResult.MappingUnproven ->
                    LeapPreparationSourceSelectionResult.Failed(
                        LeapPreparationSourceSelectionFailure.JavaDatabaseIdMappingUnproven(
                            selection.declaredDatabaseIds,
                        ),
                    )
            }

        is LeapActionSourceEvidence.Xml -> {
            val graph = when (
                val resolution = XmlStatementSourceGraphResolver.resolve(
                    rootStatementId = evidence.statementId,
                    snapshots = evidence.snapshots,
                    discoveries = evidence.discoveries,
                    effectiveDatabaseId = effectiveDatabaseId,
                )
            ) {
                is XmlStatementSourceGraphResult.Resolved -> resolution.graph
                is XmlStatementSourceGraphResult.Failed ->
                    return LeapPreparationSourceSelectionResult.Failed(
                        LeapPreparationSourceSelectionFailure.XmlSourceGraphFailed(
                            resolution.failure,
                        ),
                    )
            }

            val mapperMethod = when (val method = captureXmlMapperMethod(evidence.statementId)) {
                is XmlMapperMethodCaptureResult.Captured -> method.capture
                is XmlMapperMethodCaptureResult.Failed -> {
                    if (method.failure.allowsAbsentMapperMethod()) {
                        null
                    } else {
                        return LeapPreparationSourceSelectionResult.Failed(
                            LeapPreparationSourceSelectionFailure.XmlMapperMethodFailed(
                                method.failure,
                            ),
                        )
                    }
                }
            }
            LeapPreparationSourceSelectionResult.Ready(
                XmlMapperPreparationSource(
                    sourceGraph = graph,
                    effectiveDatabaseId = effectiveDatabaseId,
                    mapperMethod = mapperMethod,
                ),
            )
        }
    }
}

private fun XmlMapperMethodCaptureFailure.allowsAbsentMapperMethod(): Boolean =
    this == XmlMapperMethodCaptureFailure.UNRESOLVED_MAPPER_TYPE ||
        this == XmlMapperMethodCaptureFailure.MISSING_METHOD

internal fun leapExecutionTargetDescriptor(
    stableDataSourceId: String?,
    dataSourceDisplayName: String,
    schemaName: String?,
): ExecutionTargetDescriptor? {
    val stableId = stableDataSourceId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val explicitSchema = schemaName?.takeIf(String::isNotBlank) ?: return null
    return try {
        ExecutionTargetDescriptor(
            targetId = ExecutionTargetId(
                dataSourceId = StableDataSourceId(stableId),
                schema = ExplicitSchemaIdentity(explicitSchema),
            ),
            dataSourceDisplayName = dataSourceDisplayName.takeIf(String::isNotEmpty),
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal sealed interface LeapExecutionTargetAdmissionResult {
    data class Ready(
        val descriptor: ExecutionTargetDescriptor,
        val effectiveDatabaseId: MyBatisDatabaseId?,
    ) : LeapExecutionTargetAdmissionResult

    data object UserCancelled : LeapExecutionTargetAdmissionResult
    data object InvocationInvalidated : LeapExecutionTargetAdmissionResult

    data class Failed(
        val failure: LeapExecutionTargetAdmissionFailure,
    ) : LeapExecutionTargetAdmissionResult
}

internal sealed interface LeapExecutionTargetAdmissionFailure {
    data object NoEligibleExplicitTarget : LeapExecutionTargetAdmissionFailure
    data object SelectedTargetUnavailable : LeapExecutionTargetAdmissionFailure
    data object PersistenceFailed : LeapExecutionTargetAdmissionFailure

    data class TargetResolutionFailed(
        val failure: TargetResolutionFailure,
    ) : LeapExecutionTargetAdmissionFailure
}

internal class LeapExecutionTargetAdmissionAdapter(
    private val project: Project,
) {
    private val store = ExecutionTargetDescriptorStore.getInstance(project)
    private val resolver = DatabaseToolsTargetResolver(project)

    fun requestTarget(
        sourceFileId: SourceFileId,
        dataContext: DataContext,
        isInvocationActive: () -> Boolean,
        onResult: (LeapExecutionTargetAdmissionResult) -> Unit,
    ) {
        if (!isInvocationActive()) {
            onResult(LeapExecutionTargetAdmissionResult.InvocationInvalidated)
            return
        }

        val persisted = store.load(sourceFileId)
        if (persisted != null) {
            when (val resolved = resolver.resolve(persisted.descriptor)) {
                is DatabaseToolsTargetResolution.Success -> {
                    if (resolved.resolvedTarget.targetId == persisted.descriptor.targetId) {
                        onResult(
                            LeapExecutionTargetAdmissionResult.Ready(
                                descriptor = persisted.descriptor,
                                effectiveDatabaseId = resolved.resolvedTarget.effectiveDatabaseId,
                            ),
                        )
                        return
                    }
                }
                is DatabaseToolsTargetResolution.Failed -> store.remove(sourceFileId)
            }
        }

        showExplicitTargetChooser(
            sourceFileId = sourceFileId,
            dataContext = dataContext,
            isInvocationActive = isInvocationActive,
            onResult = onResult,
        )
    }

    private fun showExplicitTargetChooser(
        sourceFileId: SourceFileId,
        dataContext: DataContext,
        isInvocationActive: () -> Boolean,
        onResult: (LeapExecutionTargetAdmissionResult) -> Unit,
    ) {
        ApplicationManager.getApplication().invokeLater({
            if (!isInvocationActive()) {
                onResult(LeapExecutionTargetAdmissionResult.InvocationInvalidated)
                return@invokeLater
            }

            val completed = AtomicBoolean(false)
            fun finish(result: LeapExecutionTargetAdmissionResult) {
                if (completed.compareAndSet(false, true)) {
                    onResult(result)
                }
            }

            try {
                val group = DefaultActionGroup()
                var eligibleTargets = 0

                for (dataSource in DbPsiFacade.getInstance(project).dataSources) {
                    val stableId = databaseToolsStableDataSourceId(dataSource) ?: continue
                    val schemas = DasUtil.getSchemas(dataSource)
                        .filter { it.name.isNotBlank() }
                        .toList()
                    if (schemas.isEmpty()) continue

                    val dataSourceGroup = DefaultActionGroup(dataSource.name, true)
                    dataSourceGroup.templatePresentation.icon = dataSource.icon
                    val eligibleBeforeDataSource = eligibleTargets
                    for (schema in schemas) {
                        val descriptor = leapExecutionTargetDescriptor(
                            stableDataSourceId = stableId,
                            dataSourceDisplayName = dataSource.name,
                            schemaName = schema.name,
                        ) ?: continue
                        eligibleTargets += 1
                        dataSourceGroup.add(
                            object : AnAction(schema.name) {
                                override fun getActionUpdateThread(): ActionUpdateThread =
                                    ActionUpdateThread.BGT

                                override fun actionPerformed(event: AnActionEvent) {
                                    if (!isInvocationActive()) {
                                        finish(LeapExecutionTargetAdmissionResult.InvocationInvalidated)
                                        return
                                    }
                                    persistAndResolve(
                                        sourceFileId = sourceFileId,
                                        descriptor = descriptor,
                                        isInvocationActive = isInvocationActive,
                                        finish = ::finish,
                                    )
                                }
                            },
                        )
                    }
                    if (eligibleTargets > eligibleBeforeDataSource) {
                        group.add(dataSourceGroup)
                    }
                }

                if (eligibleTargets == 0) {
                    finish(
                        LeapExecutionTargetAdmissionResult.Failed(
                            LeapExecutionTargetAdmissionFailure.NoEligibleExplicitTarget,
                        ),
                    )
                    return@invokeLater
                }

                val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                    "Choose Data Source & Schema",
                    group,
                    dataContext,
                    JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                    true,
                )
                popup.addListener(
                    object : JBPopupListener {
                        override fun onClosed(event: LightweightWindowEvent) {
                            if (!event.isOk) {
                                finish(LeapExecutionTargetAdmissionResult.UserCancelled)
                            }
                        }
                    },
                )
                popup.showInBestPositionFor(dataContext)
            } catch (ex: ProcessCanceledException) {
                throw ex
            } catch (_: Exception) {
                finish(
                    LeapExecutionTargetAdmissionResult.Failed(
                        LeapExecutionTargetAdmissionFailure.SelectedTargetUnavailable,
                    ),
                )
            }
        }, ModalityState.any())
    }

    private fun persistAndResolve(
        sourceFileId: SourceFileId,
        descriptor: ExecutionTargetDescriptor,
        isInvocationActive: () -> Boolean,
        finish: (LeapExecutionTargetAdmissionResult) -> Unit,
    ) {
        if (!isInvocationActive()) {
            finish(LeapExecutionTargetAdmissionResult.InvocationInvalidated)
            return
        }

        try {
            store.save(
                association = SourceTargetAssociation(sourceFileId, descriptor.targetId),
                descriptor = descriptor,
            )
        } catch (ex: ProcessCanceledException) {
            throw ex
        } catch (_: Exception) {
            store.remove(sourceFileId)
            finish(
                LeapExecutionTargetAdmissionResult.Failed(
                    LeapExecutionTargetAdmissionFailure.PersistenceFailed,
                ),
            )
            return
        }

        if (!isInvocationActive()) {
            finish(LeapExecutionTargetAdmissionResult.InvocationInvalidated)
            return
        }

        when (val resolved = resolver.resolve(descriptor)) {
            is DatabaseToolsTargetResolution.Success -> {
                if (resolved.resolvedTarget.targetId != descriptor.targetId) {
                    store.remove(sourceFileId)
                    finish(
                        LeapExecutionTargetAdmissionResult.Failed(
                            LeapExecutionTargetAdmissionFailure.SelectedTargetUnavailable,
                        ),
                    )
                } else {
                    finish(
                        LeapExecutionTargetAdmissionResult.Ready(
                            descriptor = descriptor,
                            effectiveDatabaseId = resolved.resolvedTarget.effectiveDatabaseId,
                        ),
                    )
                }
            }
            is DatabaseToolsTargetResolution.Failed -> {
                store.remove(sourceFileId)
                finish(
                    LeapExecutionTargetAdmissionResult.Failed(
                        LeapExecutionTargetAdmissionFailure.TargetResolutionFailed(
                            resolved.failure,
                        ),
                    ),
                )
            }
        }
    }
}

internal sealed interface LeapActionContextAdmissionFailure {
    data object ProjectMismatch : LeapActionContextAdmissionFailure
    data object InvocationInvalidated : LeapActionContextAdmissionFailure
    data object MissingEditor : LeapActionContextAdmissionFailure
    data object MissingPsiFile : LeapActionContextAdmissionFailure

    data class SourceCaptureFailed(
        val failure: LeapActionSourceEvidenceFailure,
    ) : LeapActionContextAdmissionFailure

    data class SourceSelectionFailed(
        val failure: LeapPreparationSourceSelectionFailure,
    ) : LeapActionContextAdmissionFailure

    data class SourceInvalid(
        val failure: SourceGraphRevalidationFailure,
    ) : LeapActionContextAdmissionFailure

    data class TargetAdmissionFailed(
        val failure: LeapExecutionTargetAdmissionFailure,
    ) : LeapActionContextAdmissionFailure

    data object TargetChanged : LeapActionContextAdmissionFailure

    data class InvocationFailed(
        val failure: LeapExecutionInvocationFailure,
    ) : LeapActionContextAdmissionFailure
}

/**
 * Non-shipping real-action-context admission boundary for #66.
 *
 * This class extracts immutable source evidence and exact target authority from an IDE action event,
 * then delegates to #443. The registered shipping action does not call this coordinator yet.
 */
@Suppress("unused", "LongMethod")
internal class LeapActionContextAdmissionCoordinator(
    private val project: Project,
) {
    private val targetAdmission = LeapExecutionTargetAdmissionAdapter(project)
    private val targetResolver = DatabaseToolsTargetResolver(project)
    private val invocationCoordinator = LeapExecutionInvocationCoordinator(project)
    private val cache = ConsoleCacheService.getInstance(project)

    fun execute(
        event: AnActionEvent,
        onExecuted: () -> Unit,
        onCancelled: () -> Unit,
        onFailure: (LeapActionContextAdmissionFailure) -> Unit,
    ) {
        if (event.project !== project) {
            onFailure(LeapActionContextAdmissionFailure.ProjectMismatch)
            return
        }

        val editor = event.getData(CommonDataKeys.EDITOR)
            ?: run {
                onFailure(LeapActionContextAdmissionFailure.MissingEditor)
                return
            }
        val psiFile = event.getData(CommonDataKeys.PSI_FILE)
            ?: run {
                onFailure(LeapActionContextAdmissionFailure.MissingPsiFile)
                return
            }
        val liveInvocation = {
            !project.isDisposed &&
                !editor.isDisposed &&
                !cache.isShuttingDown()
        }
        if (!liveInvocation()) {
            onFailure(LeapActionContextAdmissionFailure.InvocationInvalidated)
            return
        }

        val evidence = when (
            val capture = LeapActionSourceEvidenceAdapter.capture(project, editor, psiFile)
        ) {
            is LeapActionSourceEvidenceResult.Captured -> capture.evidence
            is LeapActionSourceEvidenceResult.Failed -> {
                onFailure(LeapActionContextAdmissionFailure.SourceCaptureFailed(capture.failure))
                return
            }
        }

        continueWithCapturedEvidence(
            evidence = evidence,
            dataContext = event.dataContext,
            liveInvocation = liveInvocation,
            onExecuted = onExecuted,
            onCancelled = onCancelled,
            onFailure = onFailure,
        )
    }

    private fun continueWithCapturedEvidence(
        evidence: LeapActionSourceEvidence,
        dataContext: DataContext,
        liveInvocation: () -> Boolean,
        onExecuted: () -> Unit,
        onCancelled: () -> Unit,
        onFailure: (LeapActionContextAdmissionFailure) -> Unit,
    ) {
        if (evidence !is LeapActionSourceEvidence.Xml) {
            requestTarget(
                evidence = evidence,
                dataContext = dataContext,
                liveInvocation = liveInvocation,
                onExecuted = onExecuted,
                onCancelled = onCancelled,
                onFailure = onFailure,
            )
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val expanded = XmlProjectMapperSourceCaptureAdapter.capture(
                project = project,
                rootSnapshot = evidence.snapshots.single(),
                rootDiscovery = evidence.discoveries.single(),
            )
            ApplicationManager.getApplication().invokeLater({
                if (!liveInvocation()) {
                    onFailure(LeapActionContextAdmissionFailure.InvocationInvalidated)
                    return@invokeLater
                }

                when (expanded) {
                    is XmlProjectMapperSourceCaptureResult.Captured ->
                        requestTarget(
                            evidence = evidence.copy(
                                snapshots = expanded.capture.snapshots,
                                discoveries = expanded.capture.discoveries,
                            ),
                            dataContext = dataContext,
                            liveInvocation = liveInvocation,
                            onExecuted = onExecuted,
                            onCancelled = onCancelled,
                            onFailure = onFailure,
                        )
                    XmlProjectMapperSourceCaptureResult.IndexUnavailable ->
                        onFailure(
                            LeapActionContextAdmissionFailure.SourceCaptureFailed(
                                LeapActionSourceEvidenceFailure.XmlProjectIndexUnavailable,
                            ),
                        )
                }
            }, ModalityState.any())
        }
    }

    private fun requestTarget(
        evidence: LeapActionSourceEvidence,
        dataContext: DataContext,
        liveInvocation: () -> Boolean,
        onExecuted: () -> Unit,
        onCancelled: () -> Unit,
        onFailure: (LeapActionContextAdmissionFailure) -> Unit,
    ) {
        targetAdmission.requestTarget(
            sourceFileId = evidence.sourceFileId,
            dataContext = dataContext,
            isInvocationActive = liveInvocation,
            onResult = { target ->
                when (target) {
                    is LeapExecutionTargetAdmissionResult.Ready ->
                        continueWithTarget(
                            evidence = evidence,
                            target = target,
                            liveInvocation = liveInvocation,
                            onExecuted = onExecuted,
                            onCancelled = onCancelled,
                            onFailure = onFailure,
                        )
                    LeapExecutionTargetAdmissionResult.UserCancelled -> onCancelled()
                    LeapExecutionTargetAdmissionResult.InvocationInvalidated ->
                        onFailure(LeapActionContextAdmissionFailure.InvocationInvalidated)
                    is LeapExecutionTargetAdmissionResult.Failed ->
                        onFailure(
                            LeapActionContextAdmissionFailure.TargetAdmissionFailed(
                                target.failure,
                            ),
                        )
                }
            },
        )
    }

    private fun continueWithTarget(
        evidence: LeapActionSourceEvidence,
        target: LeapExecutionTargetAdmissionResult.Ready,
        liveInvocation: () -> Boolean,
        onExecuted: () -> Unit,
        onCancelled: () -> Unit,
        onFailure: (LeapActionContextAdmissionFailure) -> Unit,
    ) {
        if (!liveInvocation()) {
            onFailure(LeapActionContextAdmissionFailure.InvocationInvalidated)
            return
        }

        val source = when (
            val selection = selectLeapPreparationSource(
                evidence = evidence,
                effectiveDatabaseId = target.effectiveDatabaseId,
                captureXmlMapperMethod = { statementId ->
                    XmlMapperMethodCaptureAdapter.capture(project, statementId)
                },
            )
        ) {
            is LeapPreparationSourceSelectionResult.Ready -> selection.source
            is LeapPreparationSourceSelectionResult.Failed -> {
                onFailure(
                    LeapActionContextAdmissionFailure.SourceSelectionFailed(
                        selection.failure,
                    ),
                )
                return
            }
        }

        when (
            val revalidation = StatementSourceGraphRevalidator.revalidateSnapshots(
                preparationSourceAuthority(source).snapshots,
            )
        ) {
            SourceGraphRevalidationResult.Current -> Unit
            is SourceGraphRevalidationResult.Failed -> {
                onFailure(
                    LeapActionContextAdmissionFailure.SourceInvalid(
                        revalidation.failure,
                    ),
                )
                return
            }
        }

        if (!liveInvocation()) {
            onFailure(LeapActionContextAdmissionFailure.InvocationInvalidated)
            return
        }

        val currentTarget = targetResolver.resolve(target.descriptor)
        if (
            currentTarget !is DatabaseToolsTargetResolution.Success ||
            currentTarget.resolvedTarget.targetId != target.descriptor.targetId ||
            currentTarget.resolvedTarget.effectiveDatabaseId != target.effectiveDatabaseId
        ) {
            onFailure(LeapActionContextAdmissionFailure.TargetChanged)
            return
        }

        invocationCoordinator.execute(
            source = source,
            isInvocationActive = liveInvocation,
            onExecuted = onExecuted,
            onCancelled = onCancelled,
            onFailure = { failure ->
                onFailure(LeapActionContextAdmissionFailure.InvocationFailed(failure))
            },
        )
    }
}
