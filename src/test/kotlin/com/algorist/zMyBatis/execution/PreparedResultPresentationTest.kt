package com.algorist.zMyBatis.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedResultPresentationTest {

    @Test
    fun queryPresentationCopiesBoundedRowsAndNormalizesColumnNames() {
        val columns = mutableListOf(
            PreparedResultColumn(index = 1, label = "id", jdbcTypeName = "BIGINT"),
            PreparedResultColumn(index = 2, label = null, jdbcTypeName = null),
        )
        val firstRow = mutableListOf<String?>("42", null)
        val rows = mutableListOf<List<String?>>(firstRow)

        val presentation = preparedResultPresentation(
            DatabaseSessionPreparedOutcome.Query(
                columns = columns,
                rows = rows,
            ),
        )

        assertTrue(presentation is PreparedResultPresentation.Query)
        presentation as PreparedResultPresentation.Query
        assertEquals(
            listOf(
                PreparedQueryColumnPresentation(1, "id", "BIGINT"),
                PreparedQueryColumnPresentation(2, "Column 2", null),
            ),
            presentation.columns,
        )
        assertEquals(listOf(listOf("42", null)), presentation.rows)

        columns.clear()
        firstRow[0] = "mutated"
        rows.clear()

        assertEquals(2, presentation.columns.size)
        assertEquals("42", presentation.rows.single().first())
    }

    @Test
    fun updateAndCancellationRemainDistinctPresentationStates() {
        assertEquals(
            PreparedResultPresentation.Update(3),
            preparedResultPresentation(DatabaseSessionPreparedOutcome.Update(3)),
        )
        assertSame(
            PreparedResultPresentation.Cancelled,
            preparedResultPresentation(DatabaseSessionPreparedOutcome.Cancelled),
        )
    }

    @Test
    fun typedBackendFailureProducesBoundedDiagnosticWithoutRuntimeExceptionType() {
        val secretDiagnostic = "fixture.SecretBearingSQLException"
        val presentation = preparedResultPresentation(
            DatabaseSessionPreparedOutcome.Failed(
                DatabaseSessionPreparedFailure(
                    kind = DatabaseSessionPreparedFailureKind.DATABASE_ERROR,
                    code = "prepared-backend-database-error",
                    diagnosticType = secretDiagnostic,
                    sqlState = "23505",
                    vendorCode = 17,
                ),
            ),
        )

        assertTrue(presentation is PreparedResultPresentation.Failure)
        presentation as PreparedResultPresentation.Failure
        assertEquals("zMyBatis: Database Execution Failed", presentation.title)
        assertEquals(
            "prepared-backend-database-error; SQLSTATE=23505; vendor=17",
            presentation.diagnosticCode,
        )
        assertFalse(presentation.message.contains(secretDiagnostic))
        assertFalse(presentation.diagnosticCode.contains(secretDiagnostic))
    }

    @Test
    fun bindingFailureMayExposeOnlyTheBindingIndexNotTheBoundValue() {
        val presentation = preparedResultPresentation(
            DatabaseSessionPreparedOutcome.Failed(
                DatabaseSessionPreparedFailure(
                    kind = DatabaseSessionPreparedFailureKind.BINDING_FAILED,
                    code = "prepared-backend-binding-failed",
                    bindingIndex = 4,
                    diagnosticType = "fixture.ValueMustNotAppear",
                ),
            ),
        ) as PreparedResultPresentation.Failure

        assertEquals(
            "prepared-backend-binding-failed; binding=4",
            presentation.diagnosticCode,
        )
        assertFalse(presentation.message.contains("ValueMustNotAppear"))
        assertFalse(presentation.diagnosticCode.contains("ValueMustNotAppear"))
    }
}
