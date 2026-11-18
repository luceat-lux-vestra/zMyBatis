package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlan
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlanResult
import com.algorist.zMyBatis.core.execution.PreparedExecutionPlanner
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.preparation.PreparationMetadata
import com.algorist.zMyBatis.core.preparation.PreparedBinding
import com.algorist.zMyBatis.core.preparation.PreparedBindingMetadata
import com.algorist.zMyBatis.core.preparation.PreparedBindingOrigin
import com.algorist.zMyBatis.core.preparation.PreparedExecution
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.StatementKind
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.database.remote.jdbc.RemotePreparedStatement
import com.intellij.database.remote.jdbc.RemoteResultSet
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseSessionPreparedBackendTest {
    @Test
    fun queryUsesStockBindingAndReturnsBoundedStructuredRowsWithOwnedClose() {
        val statementClosed = AtomicBoolean(false)
        val resultClosed = AtomicBoolean(false)
        val setterCalls = mutableListOf<String>()
        val resultSet = resultSet(
            rows = listOf(listOf("41", "alpha")),
            labels = listOf("value", "label"),
            typeNames = listOf("int8", "text"),
            closed = resultClosed,
        )
        val statement = statement(
            resultSet = resultSet,
            updateCount = -1,
            closed = statementClosed,
            setterCalls = setterCalls,
        )
        val connection = connection(statement)
        val backend = DatabaseSessionPreparedBackend(PreparedResultLimits(maxRows = 10, maxColumns = 10))

        val outcome = backend.executeOnConnection(
            connection = connection,
            plan = plan(),
            currentTarget = target(),
            preExecutionCheck = { true },
        )

        assertTrue(outcome is DatabaseSessionPreparedOutcome.Query)
        outcome as DatabaseSessionPreparedOutcome.Query
        assertEquals(listOf("value", "label"), outcome.columns.map { it.label })
        assertEquals(listOf("int8", "text"), outcome.columns.map { it.jdbcTypeName })
        assertEquals(listOf(listOf("41", "alpha")), outcome.rows)
        assertEquals(listOf("setLong"), setterCalls)
        assertTrue(resultClosed.get())
        assertTrue(statementClosed.get())
    }

    @Test
    fun dmlReturnsUpdateCountAndClosesStatement() {
        val closed = AtomicBoolean(false)
        val statement = statement(
            resultSet = null,
            updateCount = 1,
            closed = closed,
            setterCalls = mutableListOf(),
        )
        val backend = DatabaseSessionPreparedBackend(PreparedResultLimits(maxRows = 10, maxColumns = 10))

        val outcome = backend.executeOnConnection(
            connection = connection(statement),
            plan = plan(statementKind = StatementKind.UPDATE),
            currentTarget = target(),
            preExecutionCheck = { true },
        )

        assertEquals(DatabaseSessionPreparedOutcome.Update(1), outcome)
        assertTrue(closed.get())
    }

    @Test
    fun targetDriftFailsBeforeRemotePreparation() {
        val prepareCalls = AtomicInteger(0)
        val connection = Proxy.newProxyInstance(
            RemoteConnection::class.java.classLoader,
            arrayOf(RemoteConnection::class.java),
        ) { _, method, _ ->
            if (method.name == "prepareStatement") prepareCalls.incrementAndGet()
            defaultValue(method.returnType)
        } as RemoteConnection
        val backend = DatabaseSessionPreparedBackend(PreparedResultLimits(maxRows = 10, maxColumns = 10))

        val outcome = backend.executeOnConnection(
            connection = connection,
            plan = plan(),
            currentTarget = target(dataSourceId = "different"),
            preExecutionCheck = { true },
        )

        assertTrue(outcome is DatabaseSessionPreparedOutcome.Failed)
        assertEquals(
            DatabaseSessionPreparedFailureKind.PLAN_TARGET_MISMATCH,
            (outcome as DatabaseSessionPreparedOutcome.Failed).failure.kind,
        )
        assertEquals(0, prepareCalls.get())
    }

    @Test
    fun cancellationBeforePreparationExecutesNoSql() {
        val prepareCalls = AtomicInteger(0)
        val connection = Proxy.newProxyInstance(
            RemoteConnection::class.java.classLoader,
            arrayOf(RemoteConnection::class.java),
        ) { _, method, _ ->
            if (method.name == "prepareStatement") prepareCalls.incrementAndGet()
            defaultValue(method.returnType)
        } as RemoteConnection
        val control = PreparedBackendCancellationControl()
        assertSame(PreparedBackendCancellationResult.Requested, control.cancel())

        val outcome = DatabaseSessionPreparedBackend(
            PreparedResultLimits(maxRows = 10, maxColumns = 10),
        ).executeOnConnection(
            connection = connection,
            plan = plan(),
            currentTarget = target(),
            preExecutionCheck = { true },
            control = control,
        )

        assertSame(DatabaseSessionPreparedOutcome.Cancelled, outcome)
        assertEquals(0, prepareCalls.get())
    }

    @Test
    fun rowLimitFailsClosedAndStillClosesResources() {
        val statementClosed = AtomicBoolean(false)
        val resultClosed = AtomicBoolean(false)
        val resultSet = resultSet(
            rows = listOf(listOf("1"), listOf("2")),
            labels = listOf("value"),
            typeNames = listOf("int8"),
            closed = resultClosed,
        )
        val statement = statement(
            resultSet = resultSet,
            updateCount = -1,
            closed = statementClosed,
            setterCalls = mutableListOf(),
        )

        val outcome = DatabaseSessionPreparedBackend(
            PreparedResultLimits(maxRows = 1, maxColumns = 10),
        ).executeOnConnection(
            connection = connection(statement),
            plan = plan(),
            currentTarget = target(),
            preExecutionCheck = { true },
        )

        assertTrue(outcome is DatabaseSessionPreparedOutcome.Failed)
        assertEquals(
            DatabaseSessionPreparedFailureKind.RESULT_LIMIT_EXCEEDED,
            (outcome as DatabaseSessionPreparedOutcome.Failed).failure.kind,
        )
        assertTrue(resultClosed.get())
        assertTrue(statementClosed.get())
    }

    private fun connection(statement: RemotePreparedStatement): RemoteConnection =
        Proxy.newProxyInstance(
            RemoteConnection::class.java.classLoader,
            arrayOf(RemoteConnection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "prepareStatement" -> statement
                else -> defaultValue(method.returnType)
            }
        } as RemoteConnection

    private fun statement(
        resultSet: RemoteResultSet?,
        updateCount: Int,
        closed: AtomicBoolean,
        setterCalls: MutableList<String>,
    ): RemotePreparedStatement =
        Proxy.newProxyInstance(
            RemotePreparedStatement::class.java.classLoader,
            arrayOf(RemotePreparedStatement::class.java),
        ) { _, method, _ ->
            when {
                method.name.startsWith("set") -> {
                    setterCalls += method.name
                    defaultValue(method.returnType)
                }
                method.name == "execute" -> resultSet != null
                method.name == "getResultSet" -> resultSet
                method.name == "getUpdateCount" -> updateCount
                method.name == "close" -> {
                    closed.set(true)
                    null
                }
                else -> defaultValue(method.returnType)
            }
        } as RemotePreparedStatement

    private fun resultSet(
        rows: List<List<String?>>,
        labels: List<String>,
        typeNames: List<String>,
        closed: AtomicBoolean,
    ): RemoteResultSet {
        val metadataMethod = RemoteResultSet::class.java.getMethod("getMetaData")
        val metadataType = metadataMethod.returnType
        val metadata = Proxy.newProxyInstance(
            metadataType.classLoader,
            arrayOf(metadataType),
        ) { _, method, args ->
            val index = (args?.firstOrNull() as? Int)?.minus(1)
            when (method.name) {
                "getColumnCount" -> labels.size
                "getColumnLabel", "getColumnName" -> labels[index!!]
                "getColumnTypeName" -> typeNames[index!!]
                else -> defaultValue(method.returnType)
            }
        }
        var cursor = -1
        return Proxy.newProxyInstance(
            RemoteResultSet::class.java.classLoader,
            arrayOf(RemoteResultSet::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getMetaData" -> metadata
                "next" -> {
                    cursor++
                    cursor < rows.size
                }
                "getString" -> rows[cursor][(args!!.first() as Int) - 1]
                "close" -> {
                    closed.set(true)
                    null
                }
                else -> defaultValue(method.returnType)
            }
        } as RemoteResultSet
    }

    private fun plan(
        statementKind: StatementKind = StatementKind.SELECT,
    ): PreparedExecutionPlan {
        val prepared = PreparedExecution(
            statementId = statementId(),
            statementKind = statementKind,
            sourceRevisions = mapOf(
                SourceFileId("fixture/Mapper.java") to SourceRevision("r1"),
            ),
            sqlWithPlaceholders = "select ?",
            orderedBindings = listOf(binding()),
            rawInterpolations = emptyList(),
            preparationMetadata = PreparationMetadata(
                engineIdentity = "org.mybatis",
                engineVersion = "3.6.0",
                languageDriverIdentity = "org.apache.ibatis.scripting.xmltags.XMLLanguageDriver",
                databaseId = null,
            ),
        )
        return when (val result = PreparedExecutionPlanner.freeze(prepared, target())) {
            is PreparedExecutionPlanResult.Ready -> result.plan
            is PreparedExecutionPlanResult.Failed ->
                throw AssertionError("unexpected plan failure: ${result.failure}")
        }
    }

    private fun binding(): PreparedBinding {
        val source = SourceEvidence(
            SourceFileId("fixture/Mapper.java"),
            SourceRevision("r1"),
            SourceRange(0, 1),
        )
        return PreparedBinding(
            index = 0,
            property = "value",
            value = InputValue.IntegerValue(BigInteger.valueOf(41)),
            origin = PreparedBindingOrigin.CallerInput(
                InputRequirementId("method-param:0"),
                InputProvenance(
                    listOf(InputEvidence.Placeholder(InputKind.BOUND, "value", source)),
                ),
            ),
            metadata = PreparedBindingMetadata(
                declaredJavaTypeIdentity = JavaTypeIdentity("java.lang.Long"),
                mappingJavaTypeIdentity = "java.lang.Long",
                jdbcTypeIdentity = "BIGINT",
                effectiveJdbcTypeIdentity = "BIGINT",
                typeHandlerIdentity = "org.apache.ibatis.type.LongTypeHandler",
                parameterMode = "IN",
                numericScale = null,
            ),
        )
    }

    private fun statementId(): JavaStatementId =
        JavaStatementId(
            sourceFileId = SourceFileId("fixture/Mapper.java"),
            qualifiedMapperType = "fixture.Mapper",
            methodSignature = MethodSignature(
                name = "query",
                parameterTypeIdentities = listOf(JavaTypeIdentity("java.lang.Long")),
            ),
        )

    private fun target(dataSourceId: String = "fixture-ds"): ResolvedExecutionTarget =
        ResolvedExecutionTarget(
            descriptor = ExecutionTargetDescriptor(
                targetId = ExecutionTargetId(
                    StableDataSourceId(dataSourceId),
                    ExplicitSchemaIdentity("public"),
                ),
                dataSourceDisplayName = "fixture",
            ),
            effectiveDatabaseId = null,
        )

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }
}
