package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlanResult
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlanner
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.execution.StableDataSourceId
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
import com.algorist.zMyBatis.core.preparation.PreparedBinding
import com.algorist.zMyBatis.core.preparation.PreparedBindingOrigin
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
import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dataSource.DatabaseDriver
import com.intellij.database.dataSource.DatabaseDriverImpl
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.database.remote.jdbc.RemotePreparedStatement
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.DbImplUtil
import com.intellij.database.dataSource.url.template.UrlTemplate
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Path
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.apache.ibatis.type.JdbcType
import org.apache.ibatis.type.ObjectTypeHandler
import org.apache.ibatis.type.StringTypeHandler
import org.apache.ibatis.type.TypeHandler
import org.apache.ibatis.type.TypeHandlerRegistry

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

            val stockFacade = executeRaw(fixture.dataSource, "stock-typehandler-facade") { connection ->
                provePublicInterfaceStockTypeHandlerFacade(connection)
            }

            val capturedBindingAdapter = executeRaw(
                fixture.dataSource,
                "captured-binding-adapter",
            ) { connection ->
                proveCapturedPreparedBindingAdapter(connection)
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
            assertEquals(15, stockFacade.bindingCount)
            assertTrue(stockFacade.roundTrip)
            assertTrue(stockFacade.forwardedSetterSignatures.isNotEmpty())
            assertEquals(17, capturedBindingAdapter.bindingCount)
            assertEquals(2, capturedBindingAdapter.additionalBindingCount)
            assertTrue(capturedBindingAdapter.typedNull)
            assertTrue(capturedBindingAdapter.handlerIdentityMatched)
            assertTrue(capturedBindingAdapter.customHandlerRejected)
            assertTrue(capturedBindingAdapter.roundTrip)
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

    fun testProductionDatabaseSessionPreparedBackendThroughDatabaseToolsOwnedSession() {
        val fixture = registerPostgreSqlDataSource()
        try {
            val instant = Instant.parse("2026-11-18T03:00:17Z")
            val execution = prepareCapturedBindingExecution(
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
            val dbDataSource = p4DbDataSource(fixture.dataSource)
            val target = p4PreparedTarget(dbDataSource)
            val plan = when (val result = PreparedExecutionPlanner.freeze(execution, target)) {
                is PreparedExecutionPlanResult.Ready -> result.plan
                is PreparedExecutionPlanResult.Failed ->
                    error("P4 runtime plan creation failed: ${result.failure.kind} / ${result.failure.code}")
            }

            val outcome = executeProductionPreparedBackend(
                dataSource = dbDataSource,
                plan = plan,
                target = target,
            )
            check(outcome is DatabaseSessionPreparedOutcome.Query) {
                "P4 production backend must return structured query rows: $outcome"
            }
            check(outcome.columns.size == 16) {
                "P4 production backend must expose all sixteen result columns"
            }
            check(outcome.rows.size == 1) {
                "P4 production backend must expose the single runtime row"
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
                Timestamp.from(instant).toLocalDateTime().toString().replace('T', ' '),
                "NULL",
                "left|right",
            )
            check(outcome.rows.single() == expected) {
                "P4 production backend PostgreSQL round-trip mismatch"
            }

            println(
                "ZMYBATIS_P4_PREPARED_BACKEND " +
                    "bindingCount=${plan.orderedBindings.size} " +
                    "queryColumns=${outcome.columns.size} " +
                    "queryRows=${outcome.rows.size} " +
                    "sessionOwned=true targetMatched=true structuredRows=true",
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

    private fun characterizeStockTypeHandlerBridge(connection: RemoteConnection): TypeHandlerBridgeOutcome =
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

    private fun provePublicInterfaceStockTypeHandlerFacade(
        connection: RemoteConnection,
    ): StockTypeHandlerFacadeOutcome =
        withPrepared(
            connection,
            """
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
                  (?::timestamp)::text,
                  CASE WHEN ?::varchar IS NULL THEN 'NULL' ELSE 'NON_NULL' END
            """.trimIndent(),
        ) { statement ->
            val forwarded = mutableListOf<String>()
            val facade = publicPreparedStatementFacade(statement, forwarded)
            val registry = TypeHandlerRegistry()
            val date = LocalDate.of(2026, 11, 18)
            val time = LocalTime.of(12, 34, 56)
            val dateTime = LocalDateTime.of(2026, 11, 18, 12, 34, 56)
            val instant = Instant.parse("2026-11-18T03:00:17Z")
            val bindings = listOf(
                StockBinding(Byte::class.javaObjectType, 7.toByte(), null),
                StockBinding(Short::class.javaObjectType, 8.toShort(), null),
                StockBinding(Int::class.javaObjectType, 9, null),
                StockBinding(Long::class.javaObjectType, 10L, null),
                StockBinding(Float::class.javaObjectType, 11.25f, null),
                StockBinding(Double::class.javaObjectType, 12.5, null),
                StockBinding(BigDecimal::class.java, BigDecimal("13.75"), null),
                StockBinding(Boolean::class.javaObjectType, true, null),
                StockBinding(String::class.java, "binding-proof", null),
                StockBinding(ByteArray::class.java, byteArrayOf(1, 2, 3), null),
                StockBinding(LocalDate::class.java, date, null),
                StockBinding(LocalTime::class.java, time, null),
                StockBinding(LocalDateTime::class.java, dateTime, null),
                StockBinding(Instant::class.java, instant, null),
                StockBinding(String::class.java, null, JdbcType.VARCHAR),
            )

            bindings.forEachIndexed { index, binding ->
                bindWithStockTypeHandler(registry, facade, index + 1, binding)
            }

            statement.execute()
            val actual = withResultSet(statement) { resultSet ->
                check(resultSet.next())
                (1..15).map { column -> resultSet.getString(column) }
            }
            val expected = listOf(
                "7",
                "8",
                "9",
                "10",
                "11.25",
                "12.5",
                "13.75",
                "true",
                "binding-proof",
                "010203",
                date.toString(),
                time.toString(),
                dateTime.toString().replace('T', ' '),
                Timestamp.from(instant).toLocalDateTime().toString().replace('T', ' '),
                "NULL",
            )
            check(actual == expected) {
                "stock TypeHandler facade PostgreSQL round-trip mismatch"
            }
            check(forwarded.size == bindings.size) {
                "every stock binding must produce exactly one prepared-statement setter call"
            }
            check(forwarded.all { signature -> signature.startsWith("set") }) {
                "stock TypeHandler facade must forward only setter calls during binding"
            }

            val signatures = forwarded.distinct().sorted()
            println(
                "ZMYBATIS_P3_TYPEHANDLER_FACADE " +
                    "publicInterfacesOnly=true " +
                    "bindingCount=${bindings.size} " +
                    "roundTrip=true " +
                    "forwardedSetterCount=${signatures.size} " +
                    "setters=${signatures.joinToString(";")}",
            )
            StockTypeHandlerFacadeOutcome(
                bindingCount = bindings.size,
                roundTrip = true,
                forwardedSetterSignatures = signatures,
            )
        }

    private fun proveCapturedPreparedBindingAdapter(
        connection: RemoteConnection,
    ): CapturedBindingAdapterOutcome {
        val instant = Instant.parse("2026-11-18T03:00:17Z")
        val execution = prepareCapturedBindingExecution(
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
        )
        val bindings = execution.orderedBindings
        check(bindings.size == 17) { "captured binding proof must produce seventeen ordered bindings" }
        check(bindings.map { it.index } == bindings.indices.toList()) {
            "captured bindings must remain contiguous and ordered"
        }
        val additional = bindings.filter { it.additionalParameter }
        check(additional.size == 2) { "foreach must produce two additional bindings" }
        check(
            additional.all { binding ->
                val origin = binding.origin as? PreparedBindingOrigin.MyBatisAdditional
                origin?.internalBinding?.kind == InternalBindingKind.FOREACH_ITEM
            },
        ) {
            "foreach additional bindings must retain FOREACH_ITEM identity"
        }

        return withPrepared(connection, execution.sqlWithPlaceholders) { statement ->
            val forwarded = mutableListOf<String>()
            val facade = publicPreparedStatementFacade(statement, forwarded)
            val registry = TypeHandlerRegistry()
            var typedNullSeen = false
            var handlerIdentityMatched = true

            for (binding in bindings) {
                val result = bindCapturedWithStockTypeHandler(
                    registry = registry,
                    statement = facade,
                    binding = binding,
                )
                typedNullSeen = typedNullSeen || result.typedNull
                handlerIdentityMatched = handlerIdentityMatched && result.handlerIdentityMatched
            }

            val forged = bindings.first().copy(
                metadata = bindings.first().metadata.copy(
                    typeHandlerIdentity = "fixture.CustomTypeHandler",
                ),
            )
            val beforeNegativeControl = forwarded.size
            val customFailure = runCatching {
                bindCapturedWithStockTypeHandler(
                    registry = registry,
                    statement = facade,
                    binding = forged,
                )
            }.exceptionOrNull()
            check(customFailure is UnsupportedOperationException) {
                "custom TypeHandler metadata must fail closed before binding"
            }
            check(forwarded.size == beforeNegativeControl) {
                "custom TypeHandler refusal must not call the remote statement"
            }

            statement.execute()
            val actual = withResultSet(statement) { resultSet ->
                check(resultSet.next())
                (1..16).map { column -> resultSet.getString(column) }
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
                Timestamp.from(instant).toLocalDateTime().toString().replace('T', ' '),
                "NULL",
                "left|right",
            )
            check(actual == expected) {
                "captured PreparedBinding adapter PostgreSQL round-trip mismatch"
            }
            check(typedNullSeen) { "captured adapter must exercise effective typed-null authority" }
            check(handlerIdentityMatched) { "resolved stock handler must match captured identity" }

            println(
                "ZMYBATIS_P3_CAPTURED_BINDINGS " +
                    "bindingCount=${bindings.size} " +
                    "additionalBindingCount=${additional.size} " +
                    "typedNull=true " +
                    "handlerIdentityMatched=true " +
                    "customHandlerRejected=true " +
                    "roundTrip=true " +
                    "forwardedSetterCount=${forwarded.distinct().size}",
            )
            CapturedBindingAdapterOutcome(
                bindingCount = bindings.size,
                additionalBindingCount = additional.size,
                typedNull = true,
                handlerIdentityMatched = true,
                customHandlerRejected = true,
                roundTrip = true,
            )
        }
    }

    private fun bindCapturedWithStockTypeHandler(
        registry: TypeHandlerRegistry,
        statement: PreparedStatement,
        binding: PreparedBinding,
    ): CapturedBindingResult {
        check(binding.metadata.parameterMode == "IN") {
            "captured prepared backend only accepts IN bindings"
        }
        if (!binding.metadata.typeHandlerIdentity.startsWith("org.apache.ibatis.type.")) {
            throw UnsupportedOperationException(
                "non-stock TypeHandler is outside the P3 captured-binding contract",
            )
        }

        val javaType = capturedJavaType(binding)
        val runtimeValue = capturedRuntimeValue(binding, javaType)
        val jdbcTypeIdentity = if (runtimeValue == null) {
            binding.metadata.effectiveJdbcTypeIdentity
        } else {
            binding.metadata.jdbcTypeIdentity
        }
        val jdbcType = jdbcTypeIdentity?.let(JdbcType::valueOf)

        @Suppress("UNCHECKED_CAST")
        val typedJavaClass = javaType as Class<Any>
        val handler: TypeHandler<*> = when {
            runtimeValue == null &&
                binding.metadata.typeHandlerIdentity == ObjectTypeHandler::class.java.name ->
                ObjectTypeHandler.INSTANCE
            jdbcType == null -> checkNotNull(registry.getTypeHandler(typedJavaClass)) {
                "stock MyBatis handler missing for captured type ${javaType.name}"
            }
            else -> checkNotNull(registry.getTypeHandler(typedJavaClass, jdbcType)) {
                "stock MyBatis handler missing for captured type ${javaType.name} / $jdbcType"
            }
        }
        check(handler.javaClass.name == binding.metadata.typeHandlerIdentity) {
            "resolved stock handler identity drifted from captured preparation metadata"
        }

        @Suppress("UNCHECKED_CAST")
        val untypedHandler = handler as TypeHandler<Any>
        untypedHandler.setParameter(
            statement,
            binding.index + 1,
            runtimeValue,
            jdbcType,
        )
        return CapturedBindingResult(
            typedNull = runtimeValue == null && jdbcType != null,
            handlerIdentityMatched = true,
        )
    }

    private fun capturedJavaType(binding: PreparedBinding): Class<*> =
        when (binding.metadata.mappingJavaTypeIdentity) {
            "java.lang.Byte" -> Byte::class.javaObjectType
            "java.lang.Short" -> Short::class.javaObjectType
            "java.lang.Integer" -> Int::class.javaObjectType
            "java.lang.Long" -> Long::class.javaObjectType
            "java.lang.Float" -> Float::class.javaObjectType
            "java.lang.Double" -> Double::class.javaObjectType
            "java.math.BigDecimal" -> BigDecimal::class.java
            "java.lang.Boolean" -> Boolean::class.javaObjectType
            "java.lang.String" -> String::class.java
            "[B", "byte[]" -> ByteArray::class.java
            "java.time.LocalDate" -> LocalDate::class.java
            "java.time.LocalTime" -> LocalTime::class.java
            "java.time.LocalDateTime" -> LocalDateTime::class.java
            "java.time.Instant" -> Instant::class.java
            "java.lang.Object" -> {
                check(binding.value == InputValue.NullValue) {
                    "Object mapping type is allowed only for captured null authority"
                }
                Object::class.java
            }
            else -> throw UnsupportedOperationException(
                "unsupported captured standard Java type: " +
                    binding.metadata.mappingJavaTypeIdentity,
            )
        }

    private fun capturedRuntimeValue(binding: PreparedBinding, javaType: Class<*>): Any? =
        when (val value = binding.value) {
            InputValue.NullValue -> null
            is InputValue.Text -> {
                check(javaType == String::class.java)
                value.value
            }
            is InputValue.BooleanValue -> {
                check(javaType == Boolean::class.javaObjectType)
                value.value
            }
            is InputValue.IntegerValue -> when (javaType) {
                Byte::class.javaObjectType -> value.value.byteValueExact()
                Short::class.javaObjectType -> value.value.shortValueExact()
                Int::class.javaObjectType -> value.value.intValueExact()
                Long::class.javaObjectType -> value.value.longValueExact()
                else -> error("integer InputValue does not match captured Java type")
            }
            is InputValue.DecimalValue -> when (javaType) {
                Float::class.javaObjectType -> value.value.toFloat().also {
                    check(it.isFinite()) { "captured float value overflow" }
                }
                Double::class.javaObjectType -> value.value.toDouble().also {
                    check(it.isFinite()) { "captured double value overflow" }
                }
                BigDecimal::class.java -> value.value
                else -> error("decimal InputValue does not match captured Java type")
            }
            is InputValue.ArrayValue -> {
                check(javaType == ByteArray::class.java)
                value.elements.map { element ->
                    val integer = element as? InputValue.IntegerValue
                        ?: error("captured byte[] element must retain integer core value")
                    integer.value.byteValueExact()
                }.toByteArray()
            }
            is InputValue.DateValue -> {
                check(javaType == LocalDate::class.java)
                value.value
            }
            is InputValue.TimeValue -> {
                check(javaType == LocalTime::class.java)
                value.value
            }
            is InputValue.DateTimeValue -> {
                check(javaType == LocalDateTime::class.java)
                value.value
            }
            is InputValue.InstantValue -> {
                check(javaType == Instant::class.java)
                value.value
            }
            else -> throw UnsupportedOperationException(
                "unsupported captured InputValue for P3.7: ${value.javaClass.name}",
            )
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
        val fileId = SourceFileId("fixture/P3CapturedBindingRuntimeMapper.java")
        val revision = SourceRevision("p3-captured-binding-runtime-r1")
        val sourceSnapshot = SourceSnapshot(
            fileId,
            revision,
            "authoritative-p3-captured-binding-runtime-source",
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
            "fixture.P3CapturedBindingRuntimeMapper",
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
            "P3.7 captured-binding invocation contract must be executable: " +
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
            "P3.7 captured-binding input environment must validate: $environment"
        }
        val request = MyBatisPreparationRequest.create(
            source = source,
            parameterContract = contract,
            inputEnvironment = environment.environment,
            effectiveDatabaseId = effectiveDatabaseId,
        )
        check(request is PreparationRequestResult.Ready) {
            "P3.7 captured-binding preparation request must be ready: $request"
        }
        return when (val prepared = MyBatisPreparationEngine.prepare(request.request)) {
            is PreparationResult.Success -> prepared.execution
            is PreparationResult.Failed -> error(
                "P3.7 captured-binding preparation failed: " +
                    "${prepared.failure.kind} / ${prepared.failure.code}",
            )
        }
    }

    private fun publicPreparedStatementFacade(
        remote: RemotePreparedStatement,
        forwarded: MutableList<String>,
    ): PreparedStatement {
        val remoteType = RemotePreparedStatement::class.java
        return Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
        ) { proxy, method, args ->
            if (method.declaringClass == Any::class.java) {
                return@newProxyInstance when (method.name) {
                    "toString" -> "P3PublicPreparedStatementFacade"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.singleOrNull()
                    else -> error("unsupported Object method: ${method.name}")
                }
            }

            val target = try {
                remoteType.getMethod(method.name, *method.parameterTypes)
            } catch (_: NoSuchMethodException) {
                throw UnsupportedOperationException(
                    "RemotePreparedStatement does not expose public exact signature " +
                        renderMethodSignature(method.name, method.parameterTypes),
                )
            }
            forwarded += renderMethodSignature(method.name, method.parameterTypes)
            try {
                target.invoke(remote, *(args ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as PreparedStatement
    }

    private fun bindWithStockTypeHandler(
        registry: TypeHandlerRegistry,
        statement: PreparedStatement,
        index: Int,
        binding: StockBinding,
    ) {
        @Suppress("UNCHECKED_CAST")
        val javaType = binding.javaType as Class<Any>
        val handler = checkNotNull(registry.getTypeHandler(javaType)) {
            "stock MyBatis handler missing for ${binding.javaType.name}"
        }
        @Suppress("UNCHECKED_CAST")
        val untypedHandler = handler as TypeHandler<Any>
        untypedHandler.setParameter(statement, index, binding.value, binding.jdbcType)
    }

    private fun renderMethodSignature(name: String, parameterTypes: Array<Class<*>>): String =
        "$name(${parameterTypes.joinToString(",") { it.name }})"

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

    private fun p4DbDataSource(dataSource: LocalDataSource): DbDataSource =
        checkNotNull(DbPsiFacade.getInstance(project).findDataSource(dataSource.uniqueId)) {
            "P4 runtime fixture must resolve to DbDataSource after datasource registration events"
        }

    private fun p4PreparedTarget(dataSource: DbDataSource): ResolvedExecutionTarget =
        ResolvedExecutionTarget(
            descriptor = ExecutionTargetDescriptor(
                targetId = ExecutionTargetId(
                    dataSourceId = StableDataSourceId(
                        checkNotNull(databaseToolsStableDataSourceId(dataSource)) {
                            "P4 runtime DbDataSource must expose stable identity"
                        },
                    ),
                    schema = ExplicitSchemaIdentity("public"),
                ),
                dataSourceDisplayName = "P4 runtime fixture",
            ),
            effectiveDatabaseId = MyBatisDatabaseId(ConventionalMyBatisDatabaseIds.POSTGRESQL),
        )

    private fun executeProductionPreparedBackend(
        dataSource: DbDataSource,
        plan: com.algorist.zMyBatis.core.execution.PreparedExecutionPlan,
        target: ResolvedExecutionTarget,
    ): DatabaseSessionPreparedOutcome {
        val sessionDataSource = checkNotNull(DbImplUtil.getMaybeLocalDataSource(dataSource)) {
            "P4 production backend must retain registered LocalDataSource session authority"
        }
        val console = JdbcConsole.newConsole(project)
            .fromDataSource(sessionDataSource)
            .forFile(
                LightVirtualFile(
                    "p4-production-backend.sql",
                    FileTypeManager.getInstance().getFileTypeByExtension("sql"),
                    "",
                ),
            )
            .build()
        try {
            val outcome = AtomicReference<DatabaseSessionPreparedOutcome?>()
            val completed = CountDownLatch(1)
            DatabaseSessionPreparedBackend(
                PreparedResultLimits(maxRows = 4, maxColumns = 32),
            ).execute(
                console = console,
                plan = plan,
                currentTarget = target,
                preExecutionCheck = { true },
                onCompleted = {
                    outcome.set(it)
                    completed.countDown()
                },
            )
            assertTrue(
                "P4 production prepared backend must complete through Database Tools session",
                completed.await(30, TimeUnit.SECONDS),
            )
            return checkNotNull(outcome.get()) {
                "P4 production prepared backend completed without an outcome"
            }
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

    private data class StockBinding(
        val javaType: Class<*>,
        val value: Any?,
        val jdbcType: JdbcType?,
    )

    private data class StockTypeHandlerFacadeOutcome(
        val bindingCount: Int,
        val roundTrip: Boolean,
        val forwardedSetterSignatures: List<String>,
    )

    private data class CapturedBindingResult(
        val typedNull: Boolean,
        val handlerIdentityMatched: Boolean,
    )

    private data class CapturedBindingAdapterOutcome(
        val bindingCount: Int,
        val additionalBindingCount: Int,
        val typedNull: Boolean,
        val handlerIdentityMatched: Boolean,
        val customHandlerRejected: Boolean,
        val roundTrip: Boolean,
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
