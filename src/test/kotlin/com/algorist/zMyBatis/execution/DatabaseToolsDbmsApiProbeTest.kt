package com.algorist.zMyBatis.execution

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.model.DasDataSource
import com.intellij.database.psi.DbDataSource
import com.intellij.database.util.DbImplUtil
import java.lang.reflect.Modifier
import org.junit.Test

/**
 * Temporary development probe for #169.
 *
 * Intentionally fails so CI preserves the exact public Database Tools 2026.2 API surface in the
 * test log. This file must be removed before the PR becomes merge-ready.
 */
class DatabaseToolsDbmsApiProbeTest {
    @Test
    fun dumpRelevantPublicApiSurface() {
        val classes = listOf(
            DbDataSource::class.java,
            DasDataSource::class.java,
            LocalDataSource::class.java,
            DbImplUtil::class.java,
        )
        val keywords = listOf("dbms", "database", "driver", "dialect", "system", "connection", "config")

        val report = classes.joinToString(separator = "\n\n") { type ->
            val methods = type.methods
                .filter { Modifier.isPublic(it.modifiers) }
                .filter { method ->
                    keywords.any { keyword -> method.name.contains(keyword, ignoreCase = true) }
                }
                .sortedWith(compareBy({ it.name }, { it.parameterCount }, { it.toGenericString() }))
                .joinToString(separator = "\n") { it.toGenericString() }
            "${type.name}\n${methods.ifBlank { "<no relevant public methods>" }}"
        }

        throw AssertionError("DBMS_API_PROBE_BEGIN\n$report\nDBMS_API_PROBE_END")
    }
}
