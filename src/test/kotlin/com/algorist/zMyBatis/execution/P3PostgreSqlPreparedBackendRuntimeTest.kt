package com.algorist.zMyBatis.execution

import com.intellij.database.console.JdbcConsole
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
import java.sql.Types
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * P3.3 stop-gate proof against the real PostgreSQL service started by e2e.yml.
 *
 * This is test-only characterization. Production execution is not routed here.
 */
class P3PostgreSqlPreparedBackendRuntimeTest : BasePlatformTestCase() {

    fun testRealPostgreSqlPreparedExecutionThroughDatabaseToolsOwnedSession() {
        val fixture = registerPostgreSqlDataSource()
        try {
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
            url = "jdbc:postgresql://127.0.0.1:5432/zmybatis"
            username = "zmybatis"
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
