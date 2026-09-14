package com.algorist.zMyBatis.input

/** Presentation-only formatting for fail-closed input-contract states. */
internal object ContractInputProblemPresenter {
    fun text(problem: ContractInputPresentationProblem): String =
        "${category(problem.kind)} — ${humanize(problem.code)}"

    fun category(kind: ContractInputPresentationProblemKind): String = when (kind) {
        ContractInputPresentationProblemKind.CONTRACT_UNKNOWN -> "UNKNOWN"
        ContractInputPresentationProblemKind.CONTRACT_AMBIGUOUS -> "AMBIGUOUS"
        ContractInputPresentationProblemKind.CONTRACT_UNSUPPORTED,
        ContractInputPresentationProblemKind.PRESENTATION_UNSUPPORTED,
        -> "UNSUPPORTED"
    }

    private fun humanize(value: String): String = value
        .replace('-', ' ')
        .replace('_', ' ')
        .lowercase()
}
