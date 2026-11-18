package com.algorist.zMyBatis.execution

import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dataSource.connection.statements.SmartStatementFactoryService
import com.intellij.database.datagrid.DataRequest
import java.lang.reflect.Modifier
import java.sql.PreparedStatement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3.1 maintained-platform characterization for IDEA/DataGrip 263.
 *
 * This is intentionally a test-only reflection boundary. Production code must not use reflective
 * Database Tools access. The probe proves which public/maintained binary surface the compiled plugin
 * can actually see before P3 introduces any execution backend.
 */
class P3DatabaseToolsPreparedBackendApiProbeTest {

    @Test
    fun `263 exposes a session-owned prepared execution surface with cancellation`() {
        val processRaw = DataRequest.RawRequest::class.java.declaredMethods
            .filter { method -> method.name == "processRaw" }
            .singleOrNull { method ->
                method.parameterTypes.any { type -> type == DatabaseConnectionCore::class.java }
            }
        assertNotNull(
            "RawRequest.processRaw must expose DatabaseConnectionCore owned by the Database Tools session",
            processRaw,
        )
        assertTrue(
            "processRaw must remain overridable by a third-party plugin",
            Modifier.isProtected(processRaw!!.modifiers) || Modifier.isPublic(processRaw.modifiers),
        )

        val remoteConnectionGetter = DatabaseConnectionCore::class.java.methods
            .singleOrNull { method ->
                method.name == "getRemoteConnection" && method.parameterCount == 0
            }
        assertNotNull(
            "DatabaseConnectionCore must expose its Database Tools-owned remote connection",
            remoteConnectionGetter,
        )

        val remoteConnectionType = remoteConnectionGetter!!.returnType
        val prepareMethods = remoteConnectionType.methods
            .filter { method ->
                method.name == "prepareStatement" &&
                    method.parameterTypes.firstOrNull() == String::class.java
            }
        assertTrue(
            "Database Tools remote connection must expose true prepared-statement creation",
            prepareMethods.isNotEmpty(),
        )

        val statementTypes = prepareMethods.map { it.returnType }.distinct()
        val statementMethods = statementTypes.flatMap { type -> type.methods.asList() }

        assertTrue(
            "prepared statement must expose execution",
            statementMethods.any { method ->
                method.name in setOf("execute", "executeQuery", "executeUpdate")
            },
        )
        assertTrue(
            "prepared statement must expose cancellation",
            statementMethods.any { method -> method.name == "cancel" },
        )
        assertTrue(
            "prepared statement must expose deterministic close/disposal",
            statementMethods.any { method -> method.name == "close" },
        )
        assertTrue(
            "prepared statement must expose result-set or update-count retrieval",
            statementMethods.any { method ->
                method.name in setOf("getResultSet", "getUpdateCount", "executeQuery", "executeUpdate")
            },
        )

        val bindingMethods = statementMethods
            .filter { method ->
                method.name.startsWith("set") &&
                    method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
            }
        assertTrue(
            "prepared statement must expose indexed binding operations",
            bindingMethods.isNotEmpty(),
        )

        val directJdbcPreparedStatement = statementTypes.any { type ->
            PreparedStatement::class.java.isAssignableFrom(type)
        }
        val genericObjectBinding = bindingMethods.any { method -> method.name == "setObject" }
        val explicitNullBinding = bindingMethods.any { method -> method.name == "setNull" }

        println(
            "ZMYBATIS_P3_API " +
                "remoteConnectionType=${remoteConnectionType.name} " +
                "statementTypes=${statementTypes.joinToString(",") { it.name }} " +
                "directJdbcPreparedStatement=$directJdbcPreparedStatement " +
                "genericObjectBinding=$genericObjectBinding " +
                "explicitNullBinding=$explicitNullBinding " +
                "cancel=true close=true",
        )
    }

    @Test
    fun `263 keeps the Database Tools parameterized statement factory directly linkable`() {
        val poweredBy = SmartStatementFactoryService::class.java.methods
            .singleOrNull { method ->
                method.name == "poweredBy" &&
                    method.parameterTypes.contentEquals(arrayOf(DatabaseConnectionCore::class.java))
            }
        assertNotNull(
            "SmartStatementFactoryService.poweredBy(DatabaseConnectionCore) must remain directly linkable",
            poweredBy,
        )

        val factoryType = poweredBy!!.returnType
        val parameterized = factoryType.methods.singleOrNull { method ->
            method.name == "parameterized" && method.parameterCount == 0
        }
        assertNotNull(
            "Database Tools must still expose its parameterized statement abstraction",
            parameterized,
        )

        assertFalse(
            "P3 must not depend on an internal implementation class as its compile-time owner",
            factoryType.name.contains(".impl."),
        )
    }
}
