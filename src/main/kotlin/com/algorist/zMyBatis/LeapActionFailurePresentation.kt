package com.algorist.zMyBatis

import com.algorist.zMyBatis.execution.LeapActionContextAdmissionFailure
import com.algorist.zMyBatis.execution.LeapActionSourceEvidenceFailure
import com.algorist.zMyBatis.execution.LeapExecutionDeliveryFailure
import com.algorist.zMyBatis.execution.LeapExecutionInvocationFailure
import com.algorist.zMyBatis.source.JavaAnnotationSourceCaptureFailure

internal enum class LeapActionFailureSeverity {
    INFORMATION,
    ERROR,
}

internal data class LeapActionFailurePresentation(
    val title: String,
    val message: String,
    val severity: LeapActionFailureSeverity = LeapActionFailureSeverity.ERROR,
)

internal fun leapActionFailurePresentation(
    failure: LeapActionContextAdmissionFailure,
): LeapActionFailurePresentation =
    when (failure) {
        LeapActionContextAdmissionFailure.ProjectMismatch,
        LeapActionContextAdmissionFailure.InvocationInvalidated,
        -> invalidatedPresentation()

        LeapActionContextAdmissionFailure.MissingEditor,
        LeapActionContextAdmissionFailure.MissingPsiFile,
        -> LeapActionFailurePresentation(
            title = "zMyBatis: Mapper Context Unavailable",
            message = "The mapper editor context is no longer available. Run zMyBatis again from the mapper source.",
        )

        is LeapActionContextAdmissionFailure.SourceCaptureFailed ->
            sourceCapturePresentation(failure.failure)

        is LeapActionContextAdmissionFailure.SourceSelectionFailed ->
            LeapActionFailurePresentation(
                title = "zMyBatis: Mapper Not Executable",
                message = "This mapper statement cannot be selected safely for the current execution target.",
            )

        is LeapActionContextAdmissionFailure.SourceInvalid ->
            invalidatedPresentation("The mapper source changed while zMyBatis was preparing the query.")

        is LeapActionContextAdmissionFailure.TargetAdmissionFailed ->
            LeapActionFailurePresentation(
                title = "zMyBatis: Execution Target Unavailable",
                message = "A stable datasource and explicit schema could not be resolved for this mapper. Select the execution target again.",
            )

        LeapActionContextAdmissionFailure.TargetChanged ->
            invalidatedPresentation("The datasource, schema, or target database context changed while zMyBatis was preparing the query.")

        is LeapActionContextAdmissionFailure.InvocationFailed ->
            invocationFailurePresentation(failure.failure)
    }

private fun sourceCapturePresentation(
    failure: LeapActionSourceEvidenceFailure,
): LeapActionFailurePresentation =
    if (
        failure is LeapActionSourceEvidenceFailure.Java &&
        failure.failure == JavaAnnotationSourceCaptureFailure.PROVIDER_ANNOTATION
    ) {
        LeapActionFailurePresentation(
            title = "zMyBatis: Provider Not Supported",
            message = "@SelectProvider / @InsertProvider / @UpdateProvider / @DeleteProvider are runtime-only mapper extensions and are not executable in Leap v1.",
            severity = LeapActionFailureSeverity.INFORMATION,
        )
    } else {
        LeapActionFailurePresentation(
            title = "zMyBatis: Mapper Source Not Executable",
            message = "The current mapper source cannot be captured authoritatively. No SQL was executed.",
        )
    }

private fun invocationFailurePresentation(
    failure: LeapExecutionInvocationFailure,
): LeapActionFailurePresentation =
    when (failure) {
        LeapExecutionInvocationFailure.InvocationInvalidated ->
            invalidatedPresentation()

        is LeapExecutionInvocationFailure.InputContractBlocked ->
            LeapActionFailurePresentation(
                title = "zMyBatis: Input Contract Unsupported",
                message = "Required mapper inputs cannot be proven safely for this statement. No SQL was executed.",
            )

        LeapExecutionInvocationFailure.MissingPersistedTarget,
        LeapExecutionInvocationFailure.PersistedTargetChanged,
        -> invalidatedPresentation("The selected datasource or schema changed while zMyBatis was collecting inputs.")

        is LeapExecutionInvocationFailure.DeliveryFailed ->
            deliveryFailurePresentation(failure.failure)
    }

private fun deliveryFailurePresentation(
    failure: LeapExecutionDeliveryFailure,
): LeapActionFailurePresentation =
    when (failure) {
        LeapExecutionDeliveryFailure.InvocationInvalidated,
        is LeapExecutionDeliveryFailure.SourceInvalid,
        is LeapExecutionDeliveryFailure.TargetResolutionFailed,
        is LeapExecutionDeliveryFailure.CandidateTargetIdentityMismatch,
        LeapExecutionDeliveryFailure.ArtifactSourceAuthorityMismatch,
        LeapExecutionDeliveryFailure.TargetSemanticDrift,
        -> invalidatedPresentation()

        is LeapExecutionDeliveryFailure.PreparationFailed,
        is LeapExecutionDeliveryFailure.MaterializationFailed,
        -> LeapActionFailurePresentation(
            title = "zMyBatis: Query Not Executable",
            message = "MyBatis preparation or SQL materialization is outside the currently proven execution envelope. No SQL was executed.",
        )

        is LeapExecutionDeliveryFailure.ConsoleAcquisitionFailed ->
            LeapActionFailurePresentation(
                title = "zMyBatis: Console Unavailable",
                message = "The selected Database Tools console could not be acquired safely. No SQL was executed.",
            )

        is LeapExecutionDeliveryFailure.SqlExecutionFailed ->
            LeapActionFailurePresentation(
                title = "zMyBatis: Execution Failed",
                message = "Database Tools could not complete the approved SQL execution.",
            )
    }

private fun invalidatedPresentation(
    message: String = "The execution context changed while zMyBatis was preparing the query. Run zMyBatis again.",
): LeapActionFailurePresentation =
    LeapActionFailurePresentation(
        title = "zMyBatis: Invocation Invalidated",
        message = message,
    )
