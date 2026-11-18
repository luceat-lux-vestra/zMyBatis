package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.PreparedExecutionPlan
import com.intellij.database.console.JdbcConsole

internal sealed interface NativeConsoleSqlTextFailure {
    data object BindingsPresent : NativeConsoleSqlTextFailure

    data class DatabaseTools(
        val failure: DatabaseToolsSqlExecutionFailure,
    ) : NativeConsoleSqlTextFailure
}

/**
 * Explicit zero-binding/exact-text backend.
 *
 * The backend executes only the exact SQL text already frozen in the approved plan. It never
 * literalizes bindings and refuses any plan that still carries JDBC bindings.
 */
internal class NativeConsoleSqlTextBackend(
    private val consoleAdapter: DatabaseToolsConsoleAdapter,
) {
    fun execute(
        console: JdbcConsole,
        plan: PreparedExecutionPlan,
        preExecutionCheck: () -> Boolean,
        onExecuted: () -> Unit,
        onFailure: (NativeConsoleSqlTextFailure) -> Unit,
        onFinished: () -> Unit,
    ) {
        if (plan.orderedBindings.isNotEmpty()) {
            try {
                onFailure(NativeConsoleSqlTextFailure.BindingsPresent)
            } finally {
                onFinished()
            }
            return
        }

        consoleAdapter.executeSql(
            console = console,
            sql = plan.sqlWithPlaceholders,
            preExecutionCheck = preExecutionCheck,
            onExecuted = onExecuted,
            onFailure = { failure ->
                onFailure(NativeConsoleSqlTextFailure.DatabaseTools(failure))
            },
            onFinished = onFinished,
        )
    }
}
