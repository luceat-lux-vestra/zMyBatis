package com.algorist.zMyBatis.execution

/**
 * Immutable presentation snapshot derived from an already-completed prepared execution.
 *
 * This model deliberately contains no execution plan, datasource/session handle, callback, or SQL
 * authority. Presentation can therefore be retained or discarded without becoming an execution
 * capability.
 */
internal sealed interface PreparedResultPresentation {
    data class Query(
        val columns: List<PreparedQueryColumnPresentation>,
        val rows: List<List<String?>>,
    ) : PreparedResultPresentation {
        init {
            require(rows.all { row -> row.size == columns.size }) {
                "prepared query presentation rows must match the column count"
            }
        }
    }

    data class Update(
        val updateCount: Int,
    ) : PreparedResultPresentation {
        init {
            require(updateCount >= 0) {
                "prepared update presentation count must not be negative"
            }
        }
    }

    data object Cancelled : PreparedResultPresentation

    data class Failure(
        val title: String,
        val message: String,
        val diagnosticCode: String,
    ) : PreparedResultPresentation {
        init {
            require(title.isNotBlank()) { "prepared failure title must not be blank" }
            require(message.isNotBlank()) { "prepared failure message must not be blank" }
            require(diagnosticCode.isNotBlank()) { "prepared failure diagnostic code must not be blank" }
        }
    }
}

internal data class PreparedQueryColumnPresentation(
    val index: Int,
    val label: String,
    val jdbcTypeName: String?,
) {
    init {
        require(index > 0) { "prepared presentation column index must be one-based" }
        require(label.isNotBlank()) { "prepared presentation column label must not be blank" }
        require(jdbcTypeName == null || jdbcTypeName.isNotBlank()) {
            "prepared presentation JDBC type must be null or non-blank"
        }
    }
}

internal fun preparedResultPresentation(
    outcome: DatabaseSessionPreparedOutcome,
): PreparedResultPresentation =
    when (outcome) {
        is DatabaseSessionPreparedOutcome.Query ->
            PreparedResultPresentation.Query(
                columns = outcome.columns.map { column ->
                    PreparedQueryColumnPresentation(
                        index = column.index,
                        label = column.label ?: "Column ${column.index}",
                        jdbcTypeName = column.jdbcTypeName,
                    )
                }.toList(),
                rows = outcome.rows.map { row -> row.toList() }.toList(),
            )

        is DatabaseSessionPreparedOutcome.Update ->
            PreparedResultPresentation.Update(outcome.updateCount)

        DatabaseSessionPreparedOutcome.Cancelled ->
            PreparedResultPresentation.Cancelled

        is DatabaseSessionPreparedOutcome.Failed ->
            failurePresentation(outcome.failure)
    }

private fun failurePresentation(
    failure: DatabaseSessionPreparedFailure,
): PreparedResultPresentation.Failure {
    val userMessage = when (failure.kind) {
        DatabaseSessionPreparedFailureKind.PLAN_TARGET_MISMATCH,
        DatabaseSessionPreparedFailureKind.AUTHORITY_INVALIDATED,
        -> "The prepared execution authority is no longer current. Run zMyBatis again from the mapper statement."

        DatabaseSessionPreparedFailureKind.BINDINGS_REQUIRED,
        DatabaseSessionPreparedFailureKind.BINDING_FAILED,
        -> "The prepared statement bindings could not be executed through the supported binding path."

        DatabaseSessionPreparedFailureKind.CONSOLE_UNAVAILABLE,
        DatabaseSessionPreparedFailureKind.REQUEST_FAILED,
        -> "The Database Tools session is unavailable for prepared execution."

        DatabaseSessionPreparedFailureKind.RESULT_METADATA_UNSUPPORTED,
        DatabaseSessionPreparedFailureKind.RESULT_LIMIT_EXCEEDED,
        DatabaseSessionPreparedFailureKind.NO_RESULT,
        -> "The prepared execution completed without a supported bounded result presentation."

        DatabaseSessionPreparedFailureKind.DATABASE_ERROR ->
            "The database rejected the prepared execution."

        DatabaseSessionPreparedFailureKind.EXECUTION_FAILED,
        DatabaseSessionPreparedFailureKind.RESOURCE_CLOSE_FAILED,
        -> "The prepared execution could not complete through the supported Database Tools path."
    }

    val title = when (failure.kind) {
        DatabaseSessionPreparedFailureKind.PLAN_TARGET_MISMATCH,
        DatabaseSessionPreparedFailureKind.AUTHORITY_INVALIDATED,
        -> "zMyBatis: Execution Authority Changed"

        DatabaseSessionPreparedFailureKind.BINDINGS_REQUIRED,
        DatabaseSessionPreparedFailureKind.BINDING_FAILED,
        -> "zMyBatis: Prepared Binding Unsupported"

        DatabaseSessionPreparedFailureKind.CONSOLE_UNAVAILABLE,
        DatabaseSessionPreparedFailureKind.REQUEST_FAILED,
        -> "zMyBatis: Database Session Unavailable"

        DatabaseSessionPreparedFailureKind.RESULT_METADATA_UNSUPPORTED,
        DatabaseSessionPreparedFailureKind.RESULT_LIMIT_EXCEEDED,
        DatabaseSessionPreparedFailureKind.NO_RESULT,
        -> "zMyBatis: Prepared Result Unavailable"

        DatabaseSessionPreparedFailureKind.DATABASE_ERROR ->
            "zMyBatis: Database Execution Failed"

        DatabaseSessionPreparedFailureKind.EXECUTION_FAILED,
        DatabaseSessionPreparedFailureKind.RESOURCE_CLOSE_FAILED,
        -> "zMyBatis: Prepared Execution Failed"
    }

    return PreparedResultPresentation.Failure(
        title = title,
        message = userMessage,
        diagnosticCode = buildString {
            append(failure.code)
            failure.sqlState?.let { sqlState ->
                append("; SQLSTATE=")
                append(sqlState)
            }
            failure.vendorCode?.let { vendorCode ->
                append("; vendor=")
                append(vendorCode)
            }
            failure.bindingIndex?.let { bindingIndex ->
                append("; binding=")
                append(bindingIndex)
            }
        },
    )
}
