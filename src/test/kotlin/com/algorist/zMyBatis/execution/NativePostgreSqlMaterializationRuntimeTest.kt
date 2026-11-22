package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlanResult
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlanner
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.input.ExecutionInputOrigin
import com.algorist.zMyBatis.core.input.InputEnvironment
import com.algorist.zMyBatis.core.input.InputEnvironmentResult
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.ProvidedInput
import com.algorist.zMyBatis.core.materialization.NativeExecutionPlanMaterializer
import com.algorist.zMyBatis.core.materialization.PreparedPlanSqlMaterializationResult
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationRequestResult
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.preparation.PreparedExecution
import com.algorist.zMyBatis.core.source.CapturedStatement
import com.algorist.zMyBatis.core.source.ConventionalMyBatisDatabaseIds
import com.algorist.zMyBatis.core.source.JavaAnnotationStatementCapture
import com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.MyBatisDatabaseId
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
import com.intellij.database.dataSource.DatabaseDriver
import com.intellij.database.dataSource.DatabaseDriverImpl
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dataSource.url.template.UrlTemplate
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.database.remote.jdbc.RemotePreparedStatement
import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Real PostgreSQL proof for the shipping bound execution bridge.
 *
 * Stock MyBatis prepares the ordered bindings, NativeExecutionPlanMaterializer produces the exact
 * executable SQL artifact, and Database Tools owns the PostgreSQL connection/session used for the
 * semantic round-trip. No retired prepared/modal backend participates in this proof.
 */
class NativePostgreSqlMaterializationRuntimeTest : BasePlatformTestCase() {

    fun testNativeMaterializedCommonScalarEnvelopeThroughDatabaseToolsOwnedSession() {
        val fixture = registerPostgreSqlDataSource()
        try {
            val instant = Instant.parse("2026-11-18T03:00:17Z")
            val captured = prepareCapturedBindingExecution(
                byteValue = 7.toByte(),
                shortValue = 8.toShort(),
                intValue = 9,
                longValue = 42L,
                floatValue = 11.25f,
                doubleValue = 12.5,
                decimalValue = BigDecimal("13.75"),
                flag = true,
                text = "captured-proof",
                binary = byteArrayOf(1, 2, 3),
                date = LocalDate.of(2026, 11, 18),
                time = LocalTime.of(12, 34, 56),
                dateTime = LocalDateTime.of(2026, 11, 18, 12, 34, 56),
                instant = instant,
                items = listOf("left", "right"),
                effectiveDatabaseId = MyBatisDatabaseId(ConventionalMyBatisDatabaseIds.POSTGRESQL),
            )

            // Instant/TIMESTAMP remains outside the first materialization envelope because JDBC
            // timestamp timezone conversion needs a separate parity proof. Remove only that one
            // captured mapping while preserving every other common scalar and foreach binding.
            val bindings = captured.orderedBindings
                .filterNot { it.property == "instant" }
                .mapIndexed { index, binding -> binding.copy(index = index) }
            check(bindings.size == 16) {
                "native materialized scalar proof must retain sixteen non-Instant bindings"
            }
            val nullBinding = bindings.single { it.property == "nullableText" }
            check(nullBinding.value == InputValue.NullValue)
            check(nullBinding.metadata.mappingJavaTypeIdentity == "java.lang.Object")
            check(
                nullBinding.metadata.typeHandlerIdentity ==
                    "org.apache.ibatis.type.ObjectTypeHandler",
            )
            check(nullBinding.metadata.effectiveJdbcTypeIdentity == "VARCHAR")

            val sql = """
                SELECT
                  (?::smallint)::text,
                  (?::smallint)::text,
                  (?::integer)::text,
                  (?::bigint)::text,
                  (?::real)::text,
                  (?::double precision)::text,
                  (?::numeric)::text,
                  (?::boolean)::text,
                  ?::varchar,
                  encode(?::bytea, 'hex'),
                  (?::date)::text,
                  (?::time)::text,
                  (?::timestamp)::text,
                  pg_typeof(?)::text,
                  array_to_string(ARRAY[?, ?]::text[], '|')
            """.trimIndent()
            val execution = PreparedExecution(
                statementId = captured.statementId,
                statementKind = captured.statementKind,
                sourceRevisions = captured.sourceRevisions,
                sqlWithPlaceholders = sql,
                orderedBindings = bindings,
                rawInterpolations = captured.rawInterpolations,
                preparationMetadata = captured.preparationMetadata,
            )
            val dbDataSource = resolveDbDataSource(fixture.dataSource)
            val target = materializationTarget(dbDataSource)
            val plan = when (val frozen = PreparedExecutionPlanner.freeze(execution, target)) {
                is PreparedExecutionPlanResult.Ready -> frozen.plan
                is PreparedExecutionPlanResult.Failed ->
                    error("native PostgreSQL materialized plan creation failed: ${frozen.failure.code}")
            }
            val artifact = when (val materialized = NativeExecutionPlanMaterializer.materialize(plan)) {
                is PreparedPlanSqlMaterializationResult.Ready -> materialized.artifact
                is PreparedPlanSqlMaterializationResult.Failed ->
                    error(
                        "native PostgreSQL materialization failed: " +
                            "${materialized.failure.kind} / ${materialized.failure.code}",
                    )
            }

            val actual = executeRaw(
                fixture.dataSource,
                "native-materialized-common-scalars",
            ) { connection ->
                withPrepared(connection, artifact.executionSql) { statement ->
                    statement.execute()
                    withResultSet(statement) { resultSet ->
                        check(resultSet.next())
                        (1..15).map { column -> resultSet.getString(column) }
                    }
                }
            }
            val expected = listOf(
                "7",
                "8",
                "9",
                "42",
                "11.25",
                "12.5",
                "13.75",
                "true",
                "captured-proof",
                "010203",
                "2026-11-18",
                "12:34:56",
                "2026-11-18 12:34:56",
                "character varying",
                "left|right",
            )
            check(actual == expected) {
                "native PostgreSQL materialized PostgreSQL scalar round-trip mismatch: $actual"
            }

            println(
                "ZMYBATIS_NATIVE_MATERIALIZATION_POSTGRES " +
                    "bindingCount=${plan.orderedBindings.size} " +
                    "resultColumns=${actual.size} " +
                    "integer=true decimal=true boolean=true varchar=true binary=true " +
                    "date=true time=true timestamp=true typedNullType=true foreach=true",
            )
        } finally {
            fixture.dispose()
        }
    }


    private fun prepareCapturedBindingExecution(
        byteValue: Byte,
        shortValue: Short,
        intValue: Int,
        longValue: Long,
        floatValue: Float,
        doubleValue: Double,
        decimalValue: BigDecimal,
        flag: Boolean,
        text: String,
        binary: ByteArray,
        date: LocalDate,
        time: LocalTime,
        dateTime: LocalDateTime,
        instant: Instant,
        items: List<String>,
        effectiveDatabaseId: MyBatisDatabaseId? = null,
    ): PreparedExecution {
        val fileId = SourceFileId("fixture/NativeMaterializationRuntimeMapper.java")
        val revision = SourceRevision("native-materialization-runtime-r1")
        val sourceSnapshot = SourceSnapshot(
            fileId,
            revision,
            "authoritative-native-materialization-runtime-source",
        )
        val parameterDefinitions = listOf(
            Triple("java.lang.Byte", "byteValue", InputValue.IntegerValue(BigInteger.valueOf(byteValue.toLong()))),
            Triple("java.lang.Short", "shortValue", InputValue.IntegerValue(BigInteger.valueOf(shortValue.toLong()))),
            Triple("java.lang.Integer", "intValue", InputValue.IntegerValue(BigInteger.valueOf(intValue.toLong()))),
            Triple("java.lang.Long", "longValue", InputValue.IntegerValue(BigInteger.valueOf(longValue))),
            Triple("java.lang.Float", "floatValue", InputValue.DecimalValue(BigDecimal(floatValue.toString()))),
            Triple("java.lang.Double", "doubleValue", InputValue.DecimalValue(BigDecimal(doubleValue.toString()))),
            Triple("java.math.BigDecimal", "decimalValue", InputValue.DecimalValue(decimalValue)),
            Triple("java.lang.Boolean", "flag", InputValue.BooleanValue(flag)),
            Triple("java.lang.String", "text", InputValue.Text(text)),
            Triple(
                "byte[]",
                "binary",
                InputValue.ArrayValue(
                    binary.map { item -> InputValue.IntegerValue(BigInteger.valueOf(item.toLong())) },
                ),
            ),
            Triple("java.time.LocalDate", "date", InputValue.DateValue(date)),
            Triple("java.time.LocalTime", "time", InputValue.TimeValue(time)),
            Triple("java.time.LocalDateTime", "dateTime", InputValue.DateTimeValue(dateTime)),
            Triple("java.time.Instant", "instant", InputValue.InstantValue(instant)),
            Triple("java.lang.String", "nullableText", InputValue.NullValue),
            Triple(
                "java.util.List<java.lang.String>",
                "items",
                InputValue.ListValue(items.map { InputValue.Text(it) }),
            ),
        )
        val parameterTypes = parameterDefinitions.map { JavaTypeIdentity(it.first) }
        val statementId = JavaStatementId(
            fileId,
            "fixture.NativeMaterializationRuntimeMapper",
            MethodSignature("query", parameterTypes),
        )
        val script = """
            <script>
              SELECT
                (#{byteValue,jdbcType=TINYINT}::smallint)::text,
                (#{shortValue,jdbcType=SMALLINT}::smallint)::text,
                (#{intValue,jdbcType=INTEGER}::integer)::text,
                (#{longValue,jdbcType=BIGINT}::bigint)::text,
                (#{floatValue,jdbcType=REAL}::real)::text,
                (#{doubleValue,jdbcType=DOUBLE}::double precision)::text,
                (#{decimalValue,jdbcType=DECIMAL}::numeric)::text,
                (#{flag,jdbcType=BOOLEAN}::boolean)::text,
                #{text,jdbcType=VARCHAR}::varchar,
                encode(#{binary,jdbcType=VARBINARY}::bytea, 'hex'),
                (#{date,jdbcType=DATE}::date)::text,
                (#{time,jdbcType=TIME}::time)::text,
                (#{dateTime,jdbcType=TIMESTAMP}::timestamp)::text,
                (#{instant,jdbcType=TIMESTAMP}::timestamp)::text,
                CASE WHEN #{nullableText,jdbcType=VARCHAR}::varchar IS NULL
                  THEN 'NULL' ELSE 'NON_NULL' END,
                array_to_string(
                  ARRAY[
                    <foreach collection="items" item="item" separator=",">
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
                parameters = parameterDefinitions.mapIndexed { index, parameter ->
                    JavaMethodParameterMetadata(
                        index = index,
                        sourceName = parameter.second,
                        typeIdentity = JavaTypeIdentity(parameter.first),
                        myBatisParamAlias = parameter.second,
                    )
                },
            ),
        )
        val contract = MyBatisInvocationContractFactory.build(source)
        check(!contract.isPreparationBlocked) {
            "native materialization captured-binding invocation contract must be executable: " +
                contract.blockingProblems
        }

        val provided = parameterDefinitions.map { parameter ->
            val alias = contract.aliases.single { it.name == parameter.second }
            ProvidedInput(
                requirementId = alias.requirementId,
                value = parameter.third,
                origin = ExecutionInputOrigin.USER_ENTERED,
            )
        }
        val environment = InputEnvironment.validate(contract, provided)
        check(environment is InputEnvironmentResult.Success) {
            "native materialization captured-binding input environment must validate: $environment"
        }
        val request = MyBatisPreparationRequest.create(
            source = source,
            parameterContract = contract,
            inputEnvironment = environment.environment,
            effectiveDatabaseId = effectiveDatabaseId,
        )
        check(request is PreparationRequestResult.Ready) {
            "native materialization captured-binding preparation request must be ready: $request"
        }
        return when (val prepared = MyBatisPreparationEngine.prepare(request.request)) {
            is PreparationResult.Success -> prepared.execution
            is PreparationResult.Failed -> error(
                "native materialization captured-binding preparation failed: " +
                    "${prepared.failure.kind} / ${prepared.failure.code}",
            )
        }
    }


    private fun registerPostgreSqlDataSource(): Fixture {
        val postgresJar = Path.of(
            checkNotNull(System.getProperty("zmybatis.postgresql.materialization.driverJar")) {
                "PostgreSQL materialization driver JAR path must be supplied by the dedicated Gradle task"
            },
        )
        check(postgresJar.toFile().isFile) {
            "PostgreSQL materialization driver JAR path must resolve to a local file"
        }

        val driverManager = DatabaseDriverManager.getInstance()
        val driver = driverManager.createDriver(
            "zmybatis-postgres-materialization-${UUID.randomUUID()}",
            "zMyBatis PostgreSQL materialization runtime proof",
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
            name = "zMyBatis PostgreSQL materialization runtime proof"
            databaseDriver = driver
            driverClass = "org.postgresql.Driver"
            // Match the packaged PostgreSQL E2E fixture: the user connection property is
            // part of the configured datasource authority, while Database Tools owns the
            // connection/session lifecycle. The CI PostgreSQL service uses trust auth.
            url = "jdbc:postgresql://127.0.0.1:5432/zmybatis?user=zmybatis"
            username = "zmybatis"
            authProviderId = DatabaseAuthProviderNames.CREDENTIALS_ID
            isAutoSynchronize = false
            // BasePlatformTestCase replaces and deletes its per-test project directory between
            // methods, while Database Tools may launch/relaunch the JDBC remote process after that
            // teardown. Keep the process working directory outside that disposable fixture root.
            workingDir = Files.createDirectories(
                Path.of("build", "postgres-materialization-remote-working-dir"),
            ).toAbsolutePath().toString()
        }

        val manager = LocalDataSourceManager.getInstance(project)
        manager.addDataSource(dataSource)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

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
                    "native-materialization-$name.sql",
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
            failure.get()?.let { throw AssertionError("PostgreSQL materialization RawRequest failed", it) }
            return checkNotNull(outcome.get()) { "PostgreSQL materialization RawRequest completed without an outcome" }
        } finally {
            Disposer.dispose(console)
        }
    }


    private fun resolveDbDataSource(dataSource: LocalDataSource): DbDataSource =
        checkNotNull(DbPsiFacade.getInstance(project).findDataSource(dataSource.uniqueId)) {
            "native materialization runtime fixture must resolve to DbDataSource after datasource registration events"
        }

    private fun materializationTarget(dataSource: DbDataSource): ResolvedExecutionTarget =
        ResolvedExecutionTarget(
            descriptor = ExecutionTargetDescriptor(
                targetId = ExecutionTargetId(
                    dataSourceId = StableDataSourceId(
                        checkNotNull(databaseToolsStableDataSourceId(dataSource)) {
                            "native materialization DbDataSource must expose stable identity"
                        },
                    ),
                    schema = ExplicitSchemaIdentity("public"),
                ),
                dataSourceDisplayName = "native materialization runtime fixture",
            ),
            effectiveDatabaseId = MyBatisDatabaseId(ConventionalMyBatisDatabaseIds.POSTGRESQL),
        )


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

}
