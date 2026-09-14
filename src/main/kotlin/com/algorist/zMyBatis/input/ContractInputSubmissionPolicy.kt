package com.algorist.zMyBatis.input

import com.algorist.zMyBatis.core.input.InputEnvironmentFailure
import com.algorist.zMyBatis.core.input.InputEnvironmentFailureKind
import com.algorist.zMyBatis.core.input.InputRequiredness
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.ParameterContract

/**
 * UI-state snapshot for one contract field.
 *
 * `text` is executable only when [supplied] is true and this policy proves the explicit-input,
 * retained-value, and raw-confirmation rules. Placeholder/example text never enters this type.
 */
data class ContractInputDraft(
    val requirementId: InputRequirementId,
    val text: String,
    val supplied: Boolean,
    val edited: Boolean,
    val retained: Boolean,
    val rawConfirmed: Boolean,
)

/**
 * Converts explicit UI state into the adapter's text entries without moving decoding/type authority
 * into Swing. The resulting entries are still validated by [ContractInputAdapter] and core.
 */
object ContractInputSubmissionPolicy {
    fun prepare(
        contract: ParameterContract,
        drafts: List<ContractInputDraft>,
        acceptRetained: Boolean,
    ): ContractInputAdapterResult {
        val presentation = ContractInputPresentationFactory.create(contract)
        if (!presentation.canSubmit) {
            return ContractInputAdapterResult.Failure(
                presentation.problems.map { ContractInputAdapterFailure.Presentation(it) },
            )
        }

        val fields = presentation.fields.associateBy { it.requirementId }
        val failures = mutableListOf<ContractInputAdapterFailure>()
        val entries = mutableListOf<ContractInputTextEntry>()

        drafts.forEach { draft ->
            val field = fields[draft.requirementId]
            if (field == null) {
                failures += ContractInputAdapterFailure.Environment(
                    InputEnvironmentFailure(
                        InputEnvironmentFailureKind.UNKNOWN_REQUIREMENT,
                        draft.requirementId,
                    ),
                )
                return@forEach
            }
            if (!draft.supplied) return@forEach

            if (draft.retained && !draft.edited && !acceptRetained) {
                failures += presentationFailure(
                    field,
                    "retained-input-requires-explicit-acceptance",
                )
                return@forEach
            }

            if (
                field.requiredness == InputRequiredness.REQUIRED &&
                !draft.retained &&
                !draft.edited
            ) {
                failures += presentationFailure(
                    field,
                    "required-input-not-explicitly-entered",
                )
                return@forEach
            }

            if (field.requiresExplicitRawConfirmation && !draft.rawConfirmed) {
                failures += presentationFailure(
                    field,
                    "raw-input-requires-explicit-confirmation",
                )
                return@forEach
            }

            entries += ContractInputTextEntry(
                requirementId = draft.requirementId,
                text = draft.text,
                origin = if (draft.retained && !draft.edited) {
                    ContractInputTextOrigin.USER_ACCEPTED_RETAINED
                } else {
                    ContractInputTextOrigin.USER_ENTERED
                },
            )
        }

        val suppliedIds = drafts.filter { it.supplied }.map { it.requirementId }.toSet()
        presentation.fields
            .filter { it.requiredness == InputRequiredness.REQUIRED }
            .filterNot { it.requirementId in suppliedIds }
            .forEach { field ->
                failures += presentationFailure(field, "required-input-not-supplied")
            }

        if (failures.isNotEmpty()) {
            return ContractInputAdapterResult.Failure(failures)
        }
        return ContractInputAdapter.prepare(contract, entries)
    }

    private fun presentationFailure(
        field: ContractInputField,
        code: String,
    ): ContractInputAdapterFailure.Presentation = ContractInputAdapterFailure.Presentation(
        ContractInputPresentationProblem(
            kind = ContractInputPresentationProblemKind.PRESENTATION_UNSUPPORTED,
            code = code,
            requirementId = field.requirementId,
            provenance = field.provenance,
        ),
    )
}
