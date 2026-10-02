package com.algorist.zMyBatis.execution

import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Modifier

class DatabaseToolsParameterizedExecutionApiProbeTest {
    @Test
    fun dumpFocusedCandidateApi() {
        val candidates = listOf(
            "com.intellij.database.datagrid.DataRequest",
            "com.intellij.database.datagrid.DataRequest\$QueryRequest",
            "com.intellij.database.dataSource.connection.statements.SmartStatementFactory",
            "com.intellij.database.dataSource.connection.statements.ParameterizedStatementData",
            "com.intellij.database.dataSource.connection.statements.ExecutionResult",
            "com.intellij.database.dataSource.connection.statements.ResultsProducer",
            "com.intellij.database.dataSource.connection.statements.StandardResultsProcessors",
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
                clazz.declaredConstructors
                    .sortedBy { it.toGenericString() }
                    .forEach { appendLine("CTOR ${Modifier.toString(it.modifiers)} ${it.toGenericString()}") }
                clazz.declaredMethods
                    .sortedBy { it.toGenericString() }
                    .forEach { appendLine("METHOD ${Modifier.toString(it.modifiers)} ${it.toGenericString()}") }
            }
        }

        fail("Database Tools 2026.2 focused API probe\n$report")
    }
}
