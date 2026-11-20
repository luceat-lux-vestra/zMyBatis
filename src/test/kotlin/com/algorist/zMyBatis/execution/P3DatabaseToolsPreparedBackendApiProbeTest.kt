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
    fun `263 QueryRequest binding slot remains erased and requires runtime characterization`() {
        val processRaw = DataRequest.RawRequest::class.java.declaredMethods
            .singleOrNull { method ->
                method.name == "processRaw" &&
                    method.parameterTypes.any { type -> type == DatabaseConnectionCore::class.java }
            }
        assertNotNull(processRaw)
        assertTrue(
            "RawRequest remains a side-effect request and exposes no result value to the native query-result pipeline",
            processRaw!!.returnType == Void.TYPE,
        )

        val constructors = DataRequest.QueryRequest::class.java.declaredConstructors.toList()
        assertTrue("QueryRequest constructors must remain observable", constructors.isNotEmpty())
        assertTrue(
            "IDEA 263 exposes the query parameter slot as erased Object; " +
                "this assertion characterizes the binary signature and does not prove the accepted runtime payload type. " +
                "Observed: " + constructors.joinToString { constructor ->
                    constructor.parameterTypes.joinToString(
                        prefix = "(",
                        postfix = ")",
                    ) { it.name }
                },
            constructors.any { constructor ->
                constructor.parameterTypes.lastOrNull() == Any::class.java
            },
        )
    }

    @Test
    fun `263 characterizes public result bridge candidates for prepared native UX`() {
        fun methods(type: Class<*>): String =
            (type.methods.toList() + type.declaredMethods.toList())
                .distinctBy { method ->
                    method.name + method.parameterTypes.joinToString { it.name } + method.returnType.name
                }
                .filter { method ->
                    val name = method.name.lowercase()
                    name.contains("result") ||
                        name.contains("response") ||
                        name.contains("consumer") ||
                        name.contains("process") ||
                        name.contains("produce")
                }
                .sortedBy { it.name }
                .joinToString(";") { method ->
                    method.name +
                        method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name } +
                        ":" + method.returnType.name +
                        ":" + Modifier.toString(method.modifiers)
                }

        val optionalCandidates = listOf(
            "com.intellij.database.datagrid.ResultsProducer",
            "com.intellij.database.datagrid.StandardResultsProcessors",
            "com.intellij.database.datagrid.DataConsumer",
            "com.intellij.database.datagrid.DatabaseTopics",
        ).joinToString("|") { className ->
            runCatching {
                val type = Class.forName(className)
                "$className={${methods(type)}}"
            }.getOrElse { "$className=<absent>" }
        }

        println(
            "ZMYBATIS_P3_API resultBridge " +
                "context={${methods(DataRequest.Context::class.java)}} " +
                "rawRequest={${methods(DataRequest.RawRequest::class.java)}} " +
                "candidates=$optionalCandidates",
        )
    }

    @Test
    fun `263 characterizes native QueryRequest and parameterized statement surfaces`() {
        fun methodSignatures(type: Class<*>): String =
            (type.methods.toList() + type.declaredMethods.toList())
                .distinctBy { method ->
                    method.name +
                        method.parameterTypes.joinToString { it.name } +
                        method.returnType.name
                }
                .sortedBy { it.name }
                .joinToString(";") { method ->
                    method.name +
                        method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name } +
                        ":" + method.returnType.name +
                        ":" + Modifier.toString(method.modifiers)
                }

        val queryRequestType = DataRequest.QueryRequest::class.java
        val constructors = queryRequestType.declaredConstructors.joinToString(";") { constructor ->
            constructor.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
        }
        val fields = queryRequestType.declaredFields.joinToString(";") { field ->
            field.name + ":" + field.type.name + ":" + field.genericType.typeName
        }
        val nested = DataRequest::class.java.declaredClasses
            .map { type -> type.name }
            .sorted()
            .joinToString(",")

        val poweredBy = SmartStatementFactoryService::class.java.methods.single { method ->
            method.name == "poweredBy" &&
                method.parameterTypes.contentEquals(arrayOf(DatabaseConnectionCore::class.java))
        }
        val factoryType = poweredBy.returnType
        val parameterizedMethod = factoryType.methods.single { method ->
            method.name == "parameterized" && method.parameterCount == 0
        }
        val parameterizedType = parameterizedMethod.returnType

        assertTrue("QueryRequest constructors must remain observable", constructors.isNotBlank())
        assertTrue(
            "Database Tools parameterized statement surface must expose execution methods",
            parameterizedType.methods.any { method -> method.name == "execute" },
        )

        println(
            "ZMYBATIS_P3_API nativePreparedResult " +
                "queryRequestConstructors={$constructors} " +
                "queryRequestFields={$fields} " +
                "queryRequestMethods={${methodSignatures(queryRequestType)}} " +
                "dataRequestNested={$nested} " +
                "parameterizedType=${parameterizedType.name} " +
                "parameterizedMethods={${methodSignatures(parameterizedType)}}",
        )
    }

    @Test
    fun `263 characterizes prepared native result payload types`() {
        fun describe(className: String): String =
            runCatching {
                val type = Class.forName(className)
                val constructors = type.declaredConstructors
                    .sortedBy { it.parameterCount }
                    .joinToString(";") { constructor ->
                        Modifier.toString(constructor.modifiers) +
                            constructor.parameters.joinToString(prefix = "(", postfix = ")") { parameter ->
                                parameter.name + ":" +
                                    parameter.type.name + ":" +
                                    parameter.parameterizedType.typeName
                            }
                    }
                val fields = type.declaredFields
                    .sortedBy { it.name }
                    .joinToString(";") { field ->
                        Modifier.toString(field.modifiers) + ":" +
                            field.name + ":" +
                            field.type.name + ":" +
                            field.genericType.typeName
                    }
                val methods = (type.methods.toList() + type.declaredMethods.toList())
                    .distinctBy { method ->
                        method.name +
                            method.parameterTypes.joinToString { it.name } +
                            method.returnType.name
                    }
                    .sortedWith(compareBy({ it.name }, { it.parameterCount }))
                    .joinToString(";") { method ->
                        Modifier.toString(method.modifiers) + ":" +
                            method.name +
                            method.parameters.joinToString(prefix = "(", postfix = ")") { parameter ->
                                parameter.name + ":" +
                                    parameter.type.name + ":" +
                                    parameter.parameterizedType.typeName
                            } +
                            ":" + method.returnType.name +
                            ":" + method.genericReturnType.typeName
                    }
                className +
                    "{modifiers=" + Modifier.toString(type.modifiers) +
                    ",super=" + (type.superclass?.name ?: "<none>") +
                    ",interfaces=" + type.interfaces.joinToString(",") { it.name } +
                    ",constructors=" + constructors +
                    ",fields=" + fields +
                    ",methods=" + methods + "}"
            }.getOrElse { failure ->
                className + "=<absent:" + failure.javaClass.name + ">"
            }

        val classes = listOf(
            "com.intellij.database.dataSource.connection.statements.ParameterizedStatementData",
            "com.intellij.database.dataSource.connection.statements.ParameterizedStatementBasis",
            "com.intellij.database.dataSource.connection.statements.ParameterizedStatementDecoration",
            "com.intellij.database.dataSource.connection.statements.ExecutionMode",
            "com.intellij.database.dataSource.connection.statements.ExecutionResult",
            "com.intellij.database.dataSource.connection.statements.ResultsProducer",
            "com.intellij.database.dataSource.connection.statements.ResultsProducer\$Processor",
            "com.intellij.database.dataSource.connection.statements.ResultsProducer\$VoidReadyProcessor",
            "com.intellij.database.datagrid.DataRequest\$RawQueryRequest",
            "com.intellij.database.datagrid.DataRequest\$IsolatedQueryRequest",
            "com.intellij.database.datagrid.DataRequest\$Visitor",
            "com.intellij.database.datagrid.DataRequest\$Context",
        )

        val descriptions = classes.joinToString("|") { className -> describe(className) }
        println("ZMYBATIS_P3_API nativePreparedPayload " + descriptions)
    }

    @Test
    fun `263 characterizes console session attach surface`() {
        fun signature(type: Class<*>): String =
            (type.methods.toList() + type.declaredMethods.toList())
                .distinctBy { method ->
                    method.name +
                        method.parameterTypes.joinToString { it.name } +
                        method.returnType.name
                }
                .filter { method ->
                    val name = method.name.lowercase()
                    name.contains("client") ||
                        name.contains("attach") ||
                        name.contains("detach") ||
                        name.contains("session") ||
                        name.contains("console")
                }
                .sortedBy { it.name }
                .joinToString(";") { method ->
                    method.name +
                        method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name } +
                        ":" + method.returnType.name +
                        ":" + Modifier.toString(method.modifiers)
                }

        val classes = listOf(
            "com.intellij.database.console.session.DatabaseSession",
            "com.intellij.database.console.session.DatabaseSessionManager",
            "com.intellij.database.console.JdbcConsole",
            "com.intellij.database.console.JdbcConsole\$Builder",
        ).joinToString("|") { className ->
            runCatching {
                val type = Class.forName(className)
                "$className={${signature(type)}} constructors={" +
                    type.declaredConstructors.joinToString(";") { constructor ->
                        constructor.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
                    } +
                    "}"
            }.getOrElse { "$className=<absent>" }
        }

        val builderType = Class.forName("com.intellij.database.console.JdbcConsole\$Builder")
        val sessionType = Class.forName("com.intellij.database.console.session.DatabaseSession")
        val useSession = builderType.methods.singleOrNull { method ->
            method.name == "useSession" &&
                method.parameterTypes.contentEquals(arrayOf(sessionType))
        }
        assertNotNull(
            "IDEA 263 must expose JdbcConsole.Builder.useSession(DatabaseSession) so mapper-created consoles can reuse the Database Tools session",
            useSession,
        )
        assertTrue(
            "JdbcConsole.Builder.useSession(DatabaseSession) must remain public",
            Modifier.isPublic(useSession!!.modifiers),
        )

        val attach = sessionType.methods.singleOrNull { method ->
            method.name == "attach" &&
                method.parameterCount == 1 &&
                method.parameterTypes.single().name ==
                    "com.intellij.database.console.client.DatabaseSessionClient"
        }
        assertNotNull(
            "IDEA 263 must expose DatabaseSession.attach(DatabaseSessionClient) for compatibility characterization; " +
                "public availability alone does not establish exact-session identity for a built JdbcConsole",
            attach,
        )
        assertTrue(
            "DatabaseSession.attach(DatabaseSessionClient) must remain public",
            Modifier.isPublic(attach!!.modifiers),
        )

        println("ZMYBATIS_P3_API consoleSession=$classes")
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
