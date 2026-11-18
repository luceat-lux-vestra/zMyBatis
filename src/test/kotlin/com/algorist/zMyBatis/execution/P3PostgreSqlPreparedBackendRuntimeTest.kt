package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.ForeachLocalRole
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.preparation.PreparedBindingOrigin
import com.algorist.zMyBatis.core.preparation.PreparedExecution
import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementCapture
import com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.SourceSnapshot
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.mybatis.MyBatisInvocationContractFactory
import com.algorist.zMyBatis.mybatis.MyBatisPreparationEngine
import com.intellij.database.console.JdbcConsole
import com.intellij.database.dataSource.DatabaseAuthProviderNames
import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dataSource.DatabaseDriver
import com.intellij.database.dataSource.DatabaseDriverImpl
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.database.remote.jdbc.RemotePreparedStatement
import com.intellij.database.dataSource.url.template.UrlTemplate
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory
import java.nio.file.Path
import java.util.UUID
import java.sql.PreparedStatement
import java.sql.Types
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.apache.ibatis.type.StringTypeHandler

/**
 * P3.3 stop-gate proof against the real PostgreSQL service started by e2e.yml.
 *
 * This is test-only characterization. Production execution is not routed here.
 */
class P3PostgreSqlPreparedBackendRuntimeTest : BasePlatformTestCase() {

    fun testRealPostgreSqlPreparedExecutionThroughDatabaseToolsOwnedSession() {
        val fixture = registerPostgreSqlDataSource()
        try {
            val typeHandlerBridge = executeRaw(fixture.dataSource, "typehandler-bridge") { connection ->
                characterizeStockTypeHandlerBridge(connection)
            }

            val functional = executeRaw(fixture.dataSource, "functional") { connection ->
                val target = queryTarget(connection)
                check(target.database == "zmybatis") { "unexpected PostgreSQL database authority" }
                check(target.schema == "public") { "unexpected PostgreSQL schema authority" }

                val single = withPrepared(connection, "SELECT ?::BIGINT") { statement ->
                    statement.setLong(1, 41L)
                    statement.execute()
                    withResultSet(statement) { result ->
                        check(result.next())
                        result.getString(1).toLong()
                    }
                }
                check(single == 41L) { "single ordered bind did not round-trip" }

                val multiple = withPrepared(connection, "SELECT ?::BIGINT + ?::BIGINT") { statement ->
                    statement.setLong(1, 20L)
                    statement.setLong(2, 22L)
                    statement.execute()
                    withResultSet(statement) { result ->
                        check(result.next())
                        result.getString(1).toLong()
                    }
                }
                check(multiple == 42L) { "multiple ordered binds did not preserve order" }

                val typedNull = withPrepared(
                    connection,
                    "SELECT CASE WHEN ?::VARCHAR IS NULL THEN 'YES' ELSE 'NO' END",
                ) { statement ->
                    statement.setNull(1, Types.VARCHAR)
                    statement.execute()
                    withResultSet(statement) { result ->
                        check(result.next())
                        result.getString(1) == "YES"
                    }
                }
                check(typedNull) { "typed null did not retain VARCHAR null authority" }

                withPrepared(
                    connection,
                    "CREATE TEMP TABLE zmybatis_p3_probe(id BIGINT PRIMARY KEY, label VARCHAR(64))",
                ) { statement ->
                    statement.execute()
                }
                val updateCount = withPrepared(
                    connection,
                    "INSERT INTO zmybatis_p3_probe(id, label) VALUES (?, ?)",
                ) { statement ->
                    statement.setLong(1, 1L)
                    statement.setString(2, "runtime-proof")
                    statement.execute()
                    statement.updateCount
                }
                check(updateCount == 1) { "prepared DML update count was not one" }

                FunctionalOutcome(
                    database = target.database,
                    schema = target.schema,
                    singleBinding = true,
                    multipleBindings = true,
                    typedNull = true,
                    selectResult = true,
                    dmlUpdateCount = updateCount,
                )
            }

            val databaseError = executeRaw(fixture.dataSource, "database-error") { connection ->
                val failure = withPrepared(connection, "SELECT 1 / ?") { statement ->
                    statement.setInt(1, 0)
                    runCatching { statement.execute() }.exceptionOrNull()
                }
                checkNotNull(failure) { "PostgreSQL division-by-zero must propagate as a database error" }
                ErrorOutcome(propagated = true, diagnosticType = failure.javaClass.name)
            }

            val cancellation = executeRaw(fixture.dataSource, "cancellation") { connection ->
                withPrepared(connection, "SELECT pg_sleep(5)") { statement ->
                    val cancelFailure = AtomicReference<Throwable?>()
                    val scheduler = Executors.newSingleThreadScheduledExecutor()
                    try {
                        scheduler.schedule(
                            {
                                runCatching { statement.cancel() }
                                    .onFailure(cancelFailure::set)
                            },
                            250,
                            TimeUnit.MILLISECONDS,
                        )
                        val started = System.nanoTime()
                        val executionFailure = runCatching { statement.execute() }.exceptionOrNull()
                        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

                        check(cancelFailure.get() == null) {
                            "RemotePreparedStatement.cancel itself failed"
                        }
                        checkNotNull(executionFailure) {
                            "cancelled pg_sleep unexpectedly completed without an execution failure"
                        }
                        check(elapsedMillis < 4_500L) {
                            "cancellation did not interrupt pg_sleep before its natural completion"
                        }
                        CancellationOutcome(interrupted = true, elapsedMillis = elapsedMillis)
                    } finally {
                        scheduler.shutdownNow()
                    }
                }
            }

            provePreparedExecutionForeachBindings(fixture.dataSource)

            assertTrue(typeHandlerBridge.runtimeClass.isNotBlank())
            assertEquals(
                typeHandlerBridge.directJdbcPreparedStatement,
                typeHandlerBridge.directStockHandlerRoundTrip,
            )
            assertTrue(typeHandlerBridge.jdbcFacadeMethods.all { it.isNotBlank() })
            assertEquals("zmybatis", functional.database)
            assertEquals("public", functional.schema)
            assertEquals(1, functional.dmlUpdateCount)
            assertTrue(databaseError.propagated)
            assertTrue(cancellation.interrupted)

            println(
                "ZMYBATIS_P3_RUNTIME " +
                    "database=zmybatis schema=public " +
                    "singleBind=${functional.singleBinding} " +
                    "multiBind=${functional.multipleBindings} " +
                    "typedNull=${functional.typedNull} " +
                    "select=${functional.selectResult} " +
                    "dmlUpdateCount=${functional.dmlUpdateCount} " +
                    "databaseError=${databaseError.propagated} " +
                    "databaseErrorType=${databaseError.diagnosticType.substringAfterLast('.')} " +
                    "cancelled=${cancellation.interrupted} " +
                    "cancelElapsedMs=${cancellation.elapsedMillis}",
            )
        } finally {
            fixture.dispose()
        }
    }

    private fun provePreparedExecutionForeachBindings(dataSource: LocalDataSource) {
        val execution = prepareForeachTextExecution(listOf("alpha", "beta", "gamma"))
        val bindings = execution.orderedBindings

        assertEquals(3, bindings.size)
        assertEquals(listOf(0, 1, 2), bindings.map { it.index })
        assertEquals(List(3) { "item" }, bindings.map { it.property })
        assertEquals(
            listOf(InputValue.Text("alpha"), InputValue.Text("beta"), InputValue.Text("gamma")),
            bindings.map { it.value },
        )
        bindings.forEach { binding ->
            val origin = binding.origin as PreparedBindingOrigin.MyBatisAdditional
            assertEquals(InternalBindingKind.FOREACH_ITEM, origin.internalBinding.kind)
            val local = origin.internalBinding.provenance.evidence
                .filterIsInstance<InputEvidence.ForeachLocal>()
                .single()
            assertEquals(ForeachLocalRole.ITEM, local.role)
            assertEquals("item", local.name)
            assertEquals("java.lang.String", binding.metadata.mappingJavaTypeIdentity)
            assertEquals("VARCHAR", binding.metadata.jdbcTypeIdentity)
            assertEquals("org.apache.ibatis.type.StringTypeHandler", binding.metadata.typeHandlerIdentity)
        }

        val result = executeRaw(dataSource, "foreach-prepared-execution") { connection ->
            withPrepared(connection, execution.sqlWithPlaceholders) { statement ->
                for (binding in bindings) {
                    val value = binding.value as? InputValue.Text
                        ?: error("P3.4 text foreach proof received a non-text binding")
                    statement.setString(binding.index + 1, value.value)
                }
                statement.execute()
                withResultSet(statement) { resultSet ->
                    check(resultSet.next())
                    resultSet.getString(1)
                }
            }
        }

        assertEquals("alpha|beta|gamma", result)
        println(
            "ZMYBATIS_P3_FOREACH " +
                "preparedExecution=true bindingCount=${bindings.size} " +
                "foreachIdentity=true orderPreserved=true handler=StringTypeHandler",
        )
    }

    private fun characterizeStockTypeHandlerBridge(connection: DatabaseConnectionCore): TypeHandlerBridgeOutcome =
        withPrepared(connection, "SELECT ?::VARCHAR") { statement ->
            val runtimeClass = statement.javaClass
            val runtimeInterfaces = runtimeClass.interfaces.map { it.name }.sorted()
            val directJdbc = PreparedStatement::class.java.isInstance(statement)
            val jdbcFacadeMethods = runtimeClass.methods
                .filter { method -> PreparedStatement::class.java.isAssignableFrom(method.returnType) }
                .map { method -> method.name }
                .distinct()
                .sorted()

            var directRoundTrip = false
            if (directJdbc) {
                val jdbc = statement as PreparedStatement
                StringTypeHandler().setParameter(jdbc, 1, "stock-handler-direct-proof", null)
                statement.execute()
                directRoundTrip = withResultSet(statement) { resultSet ->
                    check(resultSet.next())
                    resultSet.getString(1) == "stock-handler-direct-proof"
                }
                check(directRoundTrip) {
                    "stock MyBatis StringTypeHandler direct PreparedStatement round-trip must succeed"
                }
            }

            println(
                "ZMYBATIS_P3_TYPEHANDLER_RUNTIME " +
                    "runtimeClass=${runtimeClass.name} " +
                    "interfaces=${runtimeInterfaces.joinToString(",").ifEmpty { "none" }} " +
                    "directJdbcPreparedStatement=$directJdbc " +
                    "jdbcFacadeMethods=${jdbcFacadeMethods.joinToString(",").ifEmpty { "none" }} " +
                    "directStockHandlerRoundTrip=$directRoundTrip",
            )
            TypeHandlerBridgeOutcome(
                runtimeClass = runtimeClass.name,
                directJdbcPreparedStatement = directJdbc,
                directStockHandlerRoundTrip = directRoundTrip,
                jdbcFacadeMethods = jdbcFacadeMethods,
            )
        }

    private fun prepareForeachTextExecution(values: List<String>): PreparedExecution {
        val fileId = SourceFileId("fixture/P3ForeachRuntimeMapper.java")
        val revision = SourceRevision("p3-foreach-runtime-r1")
        val sourceSnapshot = SourceSnapshot(fileId, revision, "authoritative-p3-foreach-runtime-source")
        val listType = JavaTypeIdentity("java.util.List<java.lang.String>")
        val statementId = JavaStatementId(
            fileId,
            "fixture.P3ForeachRuntimeMapper",
            MethodSignature("query", listOf(listType)),
        )
        val script = """
            <script>
              SELECT array_to_string(
                ARRAY[
                  <foreach collection="values" item="item" separator=",">
                    #{item,jdbcType=VARCHAR}
                  </foreach>
                ]::text[],
                '|'
              )
            </script>
        """.trimIndent()
        val source = PreparationSource.JavaAnnotation(
            JavaAnnotationStatementCapture(
                sourceGraph = StatementSourceGraph(
                    rootStatement = CapturedStatement(
                        statementId,
                        StatementKind.SELECT,
                        SourceRange(0, sourceSnapshot.content.length),
                    ),
                    sourceSnapshots = listOf(sourceSnapshot),
                    dependencies = emptyList(),
                ),
                sqlSegments = listOf(script),
                parameters = listOf(
                    JavaMethodParameterMetadata(
                        index = 0,
                        sourceName = "values",
                        typeIdentity = listType,
                        myBatisParamAlias = "values",
                    ),
                ),
            ),
        )
        val contract = MyBatisInvocationContractFactory.build(source)
        check(!contract.isPreparationBlocked) {
            "P3.4 foreach invocation contract must be executable: ${contract.blockingProblems}"
        }
        val requirement = contract.requirements.single()
        val environment = InputEnvironment.validate(
            contract,
            listOf(
                ProvidedInput(
                    requirementId = requirement.id,
                    value = InputValue.ListValue(values.map { InputValue.Text(it) }),
                    origin = ExecutionInputOrigin.USER_ENTERED,
                ),
            ),
        )
        check(environment is InputEnvironmentResult.Success) {
            "P3.4 foreach input environment must validate: $environment"
        }
        val request = MyBatisPreparationRequest.create(
            source = source,
            parameterContract = contract,
            inputEnvironment = environment.environment,
        )
        check(request is PreparationRequestResult.Ready) {
            "P3.4 foreach preparation request must be ready: $request"
        }
        return when (val prepared = MyBatisPreparationEngine.prepare(request.request)) {
            is PreparationResult.Success -> prepared.execution
            is PreparationResult.Failed -> error(
                "P3.4 foreach preparation failed: ${prepared.failure.kind} / ${prepared.failure.code}",
            )
        }
    }

    private fun registerPostgreSqlDataSource(): Fixture {
        val postgresJar = Path.of(
            checkNotNull(System.getProperty("zmybatis.p3.postgresql.driverJar")) {
                "P3 PostgreSQL driver JAR path must be supplied by the dedicated Gradle task"
            },
        )
        check(postgresJar.toFile().isFile) {
            "P3 PostgreSQL driver JAR path must resolve to a local file"
        }

        val driverManager = DatabaseDriverManager.getInstance()
        val driver = driverManager.createDriver(
            "zmybatis-p3-postgresql-${UUID.randomUUID()}",
            "zMyBatis P3 PostgreSQL runtime proof",
            UrlTemplate("default", "jdbc:postgresql://{host::127.0.0.1}:{port::5432}/{database::zmybatis}"),
        ) as DatabaseDriverImpl
        driver.apply {
            setDriverClass("org.postgresql.Driver")
            setSqlDialect("PostgreSQL")
            setAdditionalClasspathElements(
                SimpleClasspathElementFactory.createElements("jar://$postgresJar!/"),
            )
            setOption(DatabaseDriver.OPTION_AUTO_COMMIT, true)
        }
        driverManager.updateDriver(driver)

        val dataSource = LocalDataSource().apply {
            name = "zMyBatis P3 PostgreSQL runtime proof"
            databaseDriver = driver
            driverClass = "org.postgresql.Driver"
            // Match the packaged PostgreSQL E2E fixture: the user connection property is
            // part of the configured datasource authority, while Database Tools owns the
            // connection/session lifecycle. The CI PostgreSQL service uses trust auth.
            url = "jdbc:postgresql://127.0.0.1:5432/zmybatis?user=zmybatis"
            username = "zmybatis"
            authProviderId = DatabaseAuthProviderNames.CREDENTIALS_ID
            isAutoSynchronize = false
        }

        val manager = LocalDataSourceManager.getInstance(project)
        manager.addDataSource(dataSource)

        return Fixture(
            dataSource = dataSource,
            dispose = {
                manager.removeDataSource(dataSource)
                driverManager.removeDriver(driver)
            },
        )
    }

    private fun <T> executeRaw(
        dataSource: LocalDataSource,
        name: String,
        block: (RemoteConnection) -> T,
    ): T {
        val console = JdbcConsole.newConsole(project)
            .fromDataSource(dataSource)
            .forFile(
                LightVirtualFile(
                    "p3-$name.sql",
                    FileTypeManager.getInstance().getFileTypeByExtension("sql"),
                    "",
                ),
            )
            .build()
        try {
            val outcome = AtomicReference<T?>()
            val failure = AtomicReference<Throwable?>()
            val completed = CountDownLatch(1)

            val request = object : DataRequest.RawRequest(console) {
                override fun processRaw(
                    context: DataRequest.Context,
                    connection: DatabaseConnectionCore,
                ) {
                    try {
                        outcome.set(block(connection.remoteConnection))
                    } catch (throwable: Throwable) {
                        failure.set(throwable)
                    } finally {
                        completed.countDown()
                    }
                }
            }

            console.messageBus.dataProducer.processRequest(request)
            assertTrue(
                "Database Tools RawRequest must complete against the registered PostgreSQL datasource",
                completed.await(30, TimeUnit.SECONDS),
            )
            failure.get()?.let { throw AssertionError("P3 PostgreSQL RawRequest failed", it) }
            return checkNotNull(outcome.get()) { "P3 PostgreSQL RawRequest completed without an outcome" }
        } finally {
            Disposer.dispose(console)
        }
    }

    private fun queryTarget(connection: RemoteConnection): TargetOutcome =
        withPrepared(connection, "SELECT current_database(), current_schema()") { statement ->
            statement.execute()
            withResultSet(statement) { result ->
                check(result.next())
                TargetOutcome(
                    database = result.getString(1),
                    schema = result.getString(2),
                )
            }
        }

    private inline fun <T> withPrepared(
        connection: RemoteConnection,
        sql: String,
        block: (RemotePreparedStatement) -> T,
    ): T {
        val statement = connection.prepareStatement(sql)
        try {
            return block(statement)
        } finally {
            runCatching { statement.close() }
        }
    }

    private inline fun <T> withResultSet(
        statement: RemotePreparedStatement,
        block: (com.intellij.database.remote.jdbc.RemoteResultSet) -> T,
    ): T {
        val result = checkNotNull(statement.resultSet) {
            "prepared SELECT must expose a result set"
        }
        try {
            return block(result)
        } finally {
            runCatching { result.close() }
        }
    }

    private data class Fixture(
        val dataSource: LocalDataSource,
        val dispose: () -> Unit,
    )

    private data class TargetOutcome(
        val database: String,
        val schema: String,
    )

    private data class TypeHandlerBridgeOutcome(
        val runtimeClass: String,
        val directJdbcPreparedStatement: Boolean,
        val directStockHandlerRoundTrip: Boolean,
        val jdbcFacadeMethods: List<String>,
    )

    private data class FunctionalOutcome(
        val database: String,
        val schema: String,
        val singleBinding: Boolean,
        val multipleBindings: Boolean,
        val typedNull: Boolean,
        val selectResult: Boolean,
        val dmlUpdateCount: Int,
    )

    private data class ErrorOutcome(
        val propagated: Boolean,
        val diagnosticType: String,
    )

    private data class CancellationOutcome(
        val interrupted: Boolean,
        val elapsedMillis: Long,
    )
}
