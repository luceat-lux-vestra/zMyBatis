package com.algorist.zMyBatis.execution

import com.intellij.database.remote.jdbc.RemoteResultSet
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4.3 public API characterization for structured prepared-backend result adaptation.
 *
 * Reflection is test-only. Production code must bind directly to the maintained public interfaces
 * characterized here and must not inspect Database Tools runtime implementation classes.
 */
class P4RemoteResultSetApiContractTest {
    @Test
    fun `263 exposes bounded public row and metadata surfaces`() {
        val methods = RemoteResultSet::class.java.methods.toList()

        assertTrue(
            "RemoteResultSet must expose row iteration",
            methods.any { method -> method.name == "next" && method.parameterCount == 0 },
        )
        assertTrue(
            "RemoteResultSet must expose indexed row-value access",
            methods.any { method ->
                method.name == "getString" &&
                    method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            },
        )
        assertTrue(
            "RemoteResultSet must expose deterministic close ownership",
            methods.any { method -> method.name == "close" && method.parameterCount == 0 },
        )

        val metadataGetter = methods.singleOrNull { method ->
            method.name == "getMetaData" && method.parameterCount == 0
        }
        assertNotNull(
            "RemoteResultSet must expose public metadata for bounded structured row adaptation",
            metadataGetter,
        )

        val metadataType = metadataGetter!!.returnType
        val metadataMethods = metadataType.methods.toList()
        assertTrue(
            "result metadata must expose a public column-count contract",
            metadataMethods.any { method ->
                method.name == "getColumnCount" && method.parameterCount == 0
            },
        )

        val columnLabelMethods = metadataMethods.filter { method ->
            method.name in setOf("getColumnLabel", "getColumnName") &&
                method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }
        assertTrue(
            "result metadata must expose a public label/name contract",
            columnLabelMethods.isNotEmpty(),
        )

        val resultValueMethods = methods
            .filter { method ->
                method.name in setOf("getString", "getObject") &&
                    method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
            }
            .map(::renderSignature)
            .distinct()
            .sorted()

        val selectedMetadataMethods = metadataMethods
            .filter { method ->
                method.name in setOf(
                    "getColumnCount",
                    "getColumnLabel",
                    "getColumnName",
                    "getColumnType",
                    "getColumnTypeName",
                )
            }
            .map(::renderSignature)
            .distinct()
            .sorted()

        println(
            "ZMYBATIS_P4_RESULT_API " +
                "resultType=${RemoteResultSet::class.java.name} " +
                "metadataType=${metadataType.name} " +
                "valueMethods=${resultValueMethods.joinToString(";")} " +
                "metadataMethods=${selectedMetadataMethods.joinToString(";")}",
        )
    }

    private fun renderSignature(method: java.lang.reflect.Method): String =
        "${method.name}(${method.parameterTypes.joinToString(",") { it.name }}):${method.returnType.name}"
}
