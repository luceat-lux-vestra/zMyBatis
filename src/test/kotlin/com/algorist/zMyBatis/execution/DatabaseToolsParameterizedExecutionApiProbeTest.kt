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
            "com.intellij.database.dataSource.connection.statements.SmartStatementFactoryService",
            "com.intellij.database.dataSource.connection.statements.SmartStatementFactory",
            "com.intellij.database.dataSource.connection.statements.ParameterizedSmartStatement",
            "com.intellij.database.dataSource.connection.statements.StatementParameters",
            "com.intellij.database.dataSource.connection.statements.ParameterizedStatementData",
            "com.intellij.database.dataSource.connection.statements.ParameterizedStatementBasis",
            "com.intellij.database.dataSource.connection.statements.ParameterizedStatementDecoration",
            "com.intellij.database.script.QueryParametersProvider",
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
                clazz.declaredFields
                    .sortedBy { it.toGenericString() }
                    .forEach { field ->
                        appendLine(
                            "FIELD ${Modifier.toString(field.modifiers)} ${field.toGenericString()} " +
                                "annotations=${field.annotations.joinToString { it.annotationClass.qualifiedName.orEmpty() }}"
                        )
                    }
                clazz.declaredConstructors
                    .sortedBy { it.toGenericString() }
                    .forEach { ctor ->
                        appendLine(
                            "CTOR ${Modifier.toString(ctor.modifiers)} ${ctor.toGenericString()} " +
                                "annotations=${ctor.annotations.joinToString { it.annotationClass.qualifiedName.orEmpty() }}"
                        )
                    }
                clazz.declaredMethods
                    .sortedBy { it.toGenericString() }
                    .forEach { method ->
                        appendLine(
                            "METHOD ${Modifier.toString(method.modifiers)} ${method.toGenericString()} " +
                                "annotations=${method.annotations.joinToString { it.annotationClass.qualifiedName.orEmpty() }}"
                        )
                    }
            }
        }

        fail("Database Tools 2026.2 focused API probe\n$report")
    }
}
