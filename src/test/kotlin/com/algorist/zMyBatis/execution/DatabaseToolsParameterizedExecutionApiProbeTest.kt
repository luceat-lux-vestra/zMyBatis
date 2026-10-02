package com.algorist.zMyBatis.execution

import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Modifier

class DatabaseToolsParameterizedExecutionApiProbeTest {
    @Test
    fun dumpCandidatePublicApi() {
        val candidates = listOf(
            "com.intellij.database.script.QueryParametersProvider",
            "com.intellij.database.run.ConsoleRunContextParametersTuner",
            "com.intellij.database.console.ConsoleRunContextParametersTuner",
            "com.intellij.database.run.ConsoleDataRequest",
            "com.intellij.database.script.ScriptModel\$PStorage",
            "com.intellij.database.console.JdbcConsole",
            "com.intellij.database.console.evaluation.EvaluationRequest",
            "com.intellij.database.dataSource.DatabaseConnection",
            "com.intellij.database.dataSource.DatabaseConnectionCore",
            "com.intellij.database.datagrid.DataRequest\$RawRequest",
            "com.intellij.database.dataSource.connection.statements.SmartStatementFactoryService",
            "com.intellij.database.dataSource.connection.statements.StatementParameters",
            "com.intellij.database.dataSource.connection.statements.StandardExecutionMode",
            "com.intellij.database.dataSource.connection.statements.StandardResultsProcessors",
            "com.intellij.database.datagrid.mutating.ColumnQueryData",
            "com.intellij.database.datagrid.JdbcColumnDescriptor",
        )

        val report = buildString {
            candidates.forEach { name ->
                appendLine("=== $name ===")
                val clazz = runCatching { Class.forName(name) }.getOrElse { error ->
                    appendLine("NOT_FOUND: ${error.javaClass.name}: ${error.message}")
                    return@forEach
                }
                appendLine("modifiers=${Modifier.toString(clazz.modifiers)}")
                appendLine("annotations=${clazz.annotations.joinToString { it.annotationClass.qualifiedName.orEmpty() }}")
                clazz.constructors
                    .sortedBy { it.toGenericString() }
                    .forEach { appendLine("CTOR ${it.toGenericString()}") }
                clazz.methods
                    .asSequence()
                    .filter { method ->
                        val token = method.name.lowercase()
                        token.contains("param") ||
                            token.contains("query") ||
                            token.contains("statement") ||
                            token.contains("storage") ||
                            token.contains("execute") ||
                            token.contains("request") ||
                            token.contains("connection") ||
                            token.contains("session") ||
                            token.contains("bind") ||
                            token.contains("power") ||
                            token.contains("raw") ||
                            token.contains("offset") ||
                            token.contains("data")
                    }
                    .sortedBy { it.toGenericString() }
                    .forEach { appendLine("METHOD ${it.toGenericString()}") }
            }
        }

        fail("Database Tools 2026.2 API probe\n$report")
    }
}
