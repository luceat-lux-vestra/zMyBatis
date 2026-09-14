package com.algorist.zMyBatis.input

import org.junit.Assert.assertEquals
import org.junit.Test

class ContractInputProblemPresenterTest {
    @Test
    fun `every blocking state is presented with an explicit product category`() {
        assertEquals("UNKNOWN", ContractInputProblemPresenter.category(ContractInputPresentationProblemKind.CONTRACT_UNKNOWN))
        assertEquals(
            "AMBIGUOUS",
            ContractInputProblemPresenter.category(ContractInputPresentationProblemKind.CONTRACT_AMBIGUOUS),
        )
        assertEquals(
            "UNSUPPORTED",
            ContractInputProblemPresenter.category(ContractInputPresentationProblemKind.CONTRACT_UNSUPPORTED),
        )
        assertEquals(
            "UNSUPPORTED",
            ContractInputProblemPresenter.category(ContractInputPresentationProblemKind.PRESENTATION_UNSUPPORTED),
        )
    }

    @Test
    fun `problem text preserves category while humanizing diagnostic code`() {
        val problem = ContractInputPresentationProblem(
            kind = ContractInputPresentationProblemKind.CONTRACT_AMBIGUOUS,
            code = "duplicate-explicit-alias",
            requirementId = null,
            provenance = null,
        )

        assertEquals("AMBIGUOUS — duplicate explicit alias", ContractInputProblemPresenter.text(problem))
    }
}
