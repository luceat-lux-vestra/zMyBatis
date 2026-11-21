package com.algorist.zMyBatis.execution

import com.intellij.database.console.JdbcConsole

internal sealed interface NativeConsoleSqlTextFailure {
    data class DatabaseTools(
        val failure: DatabaseToolsSqlExecutionFailure,
    ) : NativeConsoleSqlTextFailure
}

/**
 * Native Database Tools SQL-text backend.
 *
 * Materialization and approval happen before this boundary. This backend receives exactly one
 * immutable executable SQL string and never derives, rewrites or binds values itself.
 */
internal class NativeConsoleSqlTextBackend(
    private val consoleAdapter: DatabaseToolsConsoleAdapter,
) {
    fun execute(
        console: JdbcConsole,
        executionSql: String,
        preExecutionCheck: () -> Boolean,
        onExecuted: () -> Unit,
        onFailure: (NativeConsoleSqlTextFailure) -> Unit,
        onFinished: () -> Unit,
    ) {
        consoleAdapter.executeSql(
            console = console,
            sql = executionSql,
            preExecutionCheck = preExecutionCheck,
            onExecuted = onExecuted,
            onFailure = { failure ->
                onFailure(NativeConsoleSqlTextFailure.DatabaseTools(failure))
            },
            onFinished = onFinished,
        )
    }
}
