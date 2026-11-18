package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.PreparedExecutionPlan
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.intellij.database.console.JdbcConsole
import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.database.remote.jdbc.RemotePreparedStatement
import com.intellij.database.remote.jdbc.RemoteResultSet
import com.intellij.openapi.progress.ProcessCanceledException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.apache.ibatis.type.TypeHandlerRegistry

private const val PLAN_TARGET_MISMATCH = "prepared-backend-plan-target-mismatch"
private const val BINDINGS_REQUIRED = "prepared-backend-bindings-required"
private const val AUTHORITY_INVALIDATED = "prepared-backend-authority-invalidated"
private const val CONSOLE_UNAVAILABLE = "prepared-backend-console-unavailable"
private const val BINDING_FAILED = "prepared-backend-binding-failed"
private const val RESULT_METADATA_UNSUPPORTED = "prepared-backend-result-metadata-unsupported"
private const val RESULT_LIMIT_EXCEEDED = "prepared-backend-result-limit-exceeded"
private const val NO_RESULT = "prepared-backend-no-result"
private const val DATABASE_ERROR = "prepared-backend-database-error"
private const val EXECUTION_FAILED = "prepared-backend-execution-failed"
private const val RESOURCE_CLOSE_FAILED = "prepared-backend-resource-close-failed"
private const val REQUEST_FAILED = "prepared-backend-request-failed"

internal data class PreparedResultLimits(
    val maxRows: Int,
    val maxColumns: Int,
) {
    init {
        require(maxRows > 0) { "prepared result maxRows must be positive" }
        require(maxColumns > 0) { "prepared result maxColumns must be positive" }
    }
}

internal data class PreparedResultColumn(
    val index: Int,
    val label: String?,
    val jdbcTypeName: String?,
) {
    init {
        require(index > 0) { "prepared result column index must be one-based" }
    }
}

internal sealed interface DatabaseSessionPreparedOutcome {
    data class Query(
        val columns: List<PreparedResultColumn>,
        val rows: List<List<String?>>,
    ) : DatabaseSessionPreparedOutcome

    data class Update(
        val updateCount: Int,
    ) : DatabaseSessionPreparedOutcome {
        init {
            require(updateCount >= 0) { "prepared update count must not be negative" }
        }
    }

    data object Cancelled : DatabaseSessionPreparedOutcome

    data class Failed(
        val failure: DatabaseSessionPreparedFailure,
    ) : DatabaseSessionPreparedOutcome
}

internal enum class DatabaseSessionPreparedFailureKind {
    PLAN_TARGET_MISMATCH,
    BINDINGS_REQUIRED,
    AUTHORITY_INVALIDATED,
    CONSOLE_UNAVAILABLE,
    BINDING_FAILED,
    RESULT_METADATA_UNSUPPORTED,
    RESULT_LIMIT_EXCEEDED,
    NO_RESULT,
    DATABASE_ERROR,
    EXECUTION_FAILED,
    RESOURCE_CLOSE_FAILED,
    REQUEST_FAILED,
}

internal data class DatabaseSessionPreparedFailure(
    val kind: DatabaseSessionPreparedFailureKind,
    val code: String,
    val bindingIndex: Int? = null,
    val diagnosticType: String? = null,
    val sqlState: String? = null,
    val vendorCode: Int? = null,
) {
    init {
        require(code.isNotBlank()) { "prepared backend failure code must not be blank" }
        require(bindingIndex == null || bindingIndex >= 0) {
            "prepared backend failure binding index must not be negative"
        }
        require(diagnosticType == null || diagnosticType.isNotBlank()) {
            "prepared backend failure diagnostic type must be null or non-blank"
        }
    }
}

internal sealed interface PreparedBackendCancellationResult {
    data object Requested : PreparedBackendCancellationResult
    data object AlreadyFinished : PreparedBackendCancellationResult

    data class Failed(
        val diagnosticType: String,
    ) : PreparedBackendCancellationResult {
        init {
            require(diagnosticType.isNotBlank()) { "cancellation diagnostic type must not be blank" }
        }
    }
}

internal interface PreparedBackendExecutionHandle {
    fun cancel(): PreparedBackendCancellationResult
}

internal class DatabaseSessionPreparedBackend(
    private val resultLimits: PreparedResultLimits,
    private val registryFactory: () -> TypeHandlerRegistry = { TypeHandlerRegistry() },
) {
    fun execute(
        console: JdbcConsole,
        plan: PreparedExecutionPlan,
        currentTarget: ResolvedExecutionTarget,
        preExecutionCheck: () -> Boolean,
        onCompleted: (DatabaseSessionPreparedOutcome) -> Unit,
    ): PreparedBackendExecutionHandle {
        val control = PreparedBackendCancellationControl()
        val completed = AtomicBoolean(false)
        fun complete(outcome: DatabaseSessionPreparedOutcome) {
            if (completed.compareAndSet(false, true)) {
                control.markFinished()
                onCompleted(outcome)
            }
        }

        validatePlan(plan, currentTarget)?.let {
            complete(DatabaseSessionPreparedOutcome.Failed(it))
            return control
        }
        if (!preExecutionCheck()) {
            complete(failed(DatabaseSessionPreparedFailureKind.AUTHORITY_INVALIDATED, AUTHORITY_INVALIDATED))
            return control
        }
        if (!console.isValid) {
            complete(failed(DatabaseSessionPreparedFailureKind.CONSOLE_UNAVAILABLE, CONSOLE_UNAVAILABLE))
            return control
        }

        val request = object : DataRequest.RawRequest(console) {
            override fun processRaw(
                context: DataRequest.Context,
                connection: DatabaseConnectionCore,
            ) {
                try {
                    val outcome = executeOnConnection(
                        connection = connection.remoteConnection,
                        plan = plan,
                        currentTarget = currentTarget,
                        preExecutionCheck = preExecutionCheck,
                        control = control,
                    )
                    complete(outcome)
                } catch (cancelled: ProcessCanceledException) {
                    control.markFinished()
                    throw cancelled
                } catch (fatal: Throwable) {
                    control.markFinished()
                    throw fatal
                }
            }
        }

        try {
            console.messageBus.dataProducer.processRequest(request)
        } catch (cancelled: ProcessCanceledException) {
            control.markFinished()
            throw cancelled
        } catch (failure: Exception) {
            complete(
                failed(
                    kind = DatabaseSessionPreparedFailureKind.REQUEST_FAILED,
                    code = REQUEST_FAILED,
                    diagnosticType = failure.javaClass.name,
                ),
            )
        } catch (fatal: Throwable) {
            control.markFinished()
            throw fatal
        }
        return control
    }

    internal fun executeOnConnection(
        connection: RemoteConnection,
        plan: PreparedExecutionPlan,
        currentTarget: ResolvedExecutionTarget,
        preExecutionCheck: () -> Boolean,
        control: PreparedBackendCancellationControl = PreparedBackendCancellationControl(),
    ): DatabaseSessionPreparedOutcome {
        validatePlan(plan, currentTarget)?.let {
            return DatabaseSessionPreparedOutcome.Failed(it)
        }
        if (control.isCancellationRequested()) return DatabaseSessionPreparedOutcome.Cancelled
        if (!preExecutionCheck()) {
            return failed(DatabaseSessionPreparedFailureKind.AUTHORITY_INVALIDATED, AUTHORITY_INVALIDATED)
        }

        var statement: RemotePreparedStatement? = null
        var outcome: DatabaseSessionPreparedOutcome? = null
        var escaped: Throwable? = null
        try {
            statement = connection.prepareStatement(plan.sqlWithPlaceholders)
            control.attach(statement)
            outcome = if (control.isCancellationRequested()) {
                DatabaseSessionPreparedOutcome.Cancelled
            } else {
                executePrepared(
                    statement = statement,
                    plan = plan,
                    preExecutionCheck = preExecutionCheck,
                    control = control,
                )
            }
        } catch (cancelled: ProcessCanceledException) {
            escaped = cancelled
        } catch (failure: SQLException) {
            outcome = if (control.cancelWasDispatched()) {
                DatabaseSessionPreparedOutcome.Cancelled
            } else {
                failed(
                    kind = DatabaseSessionPreparedFailureKind.DATABASE_ERROR,
                    code = DATABASE_ERROR,
                    diagnosticType = failure.javaClass.name,
                    sqlState = failure.sqlState,
                    vendorCode = failure.errorCode,
                )
            }
        } catch (failure: Exception) {
            outcome = if (control.cancelWasDispatched()) {
                DatabaseSessionPreparedOutcome.Cancelled
            } else {
                failed(
                    kind = DatabaseSessionPreparedFailureKind.EXECUTION_FAILED,
                    code = EXECUTION_FAILED,
                    diagnosticType = failure.javaClass.name,
                )
            }
        } catch (fatal: Throwable) {
            escaped = fatal
        }

        val attached = statement
        if (attached != null) {
            control.detach(attached)
        }
        val closeFailure = attached?.let { closeStatement(it) }
        control.markFinished()

        escaped?.let { throw it }
        val completed = checkNotNull(outcome) {
            "prepared backend execution completed without an outcome"
        }
        if (closeFailure != null && completed !is DatabaseSessionPreparedOutcome.Failed) {
            return failed(
                kind = DatabaseSessionPreparedFailureKind.RESOURCE_CLOSE_FAILED,
                code = RESOURCE_CLOSE_FAILED,
                diagnosticType = closeFailure.javaClass.name,
            )
        }
        return completed
    }

    private fun executePrepared(
        statement: RemotePreparedStatement,
        plan: PreparedExecutionPlan,
        preExecutionCheck: () -> Boolean,
        control: PreparedBackendCancellationControl,
    ): DatabaseSessionPreparedOutcome {
        val facade = PublicRemotePreparedStatementFacade.create(statement)
        val registry = registryFactory()
        for (binding in plan.orderedBindings) {
            when (val bound = StockMyBatisPreparedBindingBinder.bind(registry, facade, binding)) {
                PreparedBindingBindResult.Bound -> Unit
                is PreparedBindingBindResult.Failed ->
                    return failed(
                        kind = DatabaseSessionPreparedFailureKind.BINDING_FAILED,
                        code = BINDING_FAILED,
                        bindingIndex = bound.failure.bindingIndex,
                        diagnosticType = bound.failure.kind.name,
                    )
            }
        }

        if (control.isCancellationRequested()) return DatabaseSessionPreparedOutcome.Cancelled
        if (!preExecutionCheck()) {
            return failed(DatabaseSessionPreparedFailureKind.AUTHORITY_INVALIDATED, AUTHORITY_INVALIDATED)
        }

        statement.execute()

        val resultSet = statement.resultSet
        if (resultSet != null) {
            return readQuery(resultSet)
        }
        val updateCount = statement.updateCount
        if (updateCount >= 0) {
            return DatabaseSessionPreparedOutcome.Update(updateCount)
        }
        return failed(DatabaseSessionPreparedFailureKind.NO_RESULT, NO_RESULT)
    }

    private fun readQuery(resultSet: RemoteResultSet): DatabaseSessionPreparedOutcome {
        var outcome: DatabaseSessionPreparedOutcome? = null
        var escaped: Throwable? = null
        try {
            outcome = PublicRemoteResultSetAdapter.read(resultSet, resultLimits)
        } catch (cancelled: ProcessCanceledException) {
            escaped = cancelled
        } catch (failure: UnsupportedOperationException) {
            outcome = failed(
                kind = DatabaseSessionPreparedFailureKind.RESULT_METADATA_UNSUPPORTED,
                code = RESULT_METADATA_UNSUPPORTED,
                diagnosticType = failure.javaClass.name,
            )
        } catch (failure: SQLException) {
            outcome = failed(
                kind = DatabaseSessionPreparedFailureKind.DATABASE_ERROR,
                code = DATABASE_ERROR,
                diagnosticType = failure.javaClass.name,
                sqlState = failure.sqlState,
                vendorCode = failure.errorCode,
            )
        } catch (failure: Exception) {
            outcome = failed(
                kind = DatabaseSessionPreparedFailureKind.EXECUTION_FAILED,
                code = EXECUTION_FAILED,
                diagnosticType = failure.javaClass.name,
            )
        } catch (fatal: Throwable) {
            escaped = fatal
        }

        val closeFailure = closeResultSet(resultSet)
        escaped?.let { throw it }
        val completed = checkNotNull(outcome) {
            "prepared query read completed without an outcome"
        }
        if (closeFailure != null && completed !is DatabaseSessionPreparedOutcome.Failed) {
            return failed(
                kind = DatabaseSessionPreparedFailureKind.RESOURCE_CLOSE_FAILED,
                code = RESOURCE_CLOSE_FAILED,
                diagnosticType = closeFailure.javaClass.name,
            )
        }
        return completed
    }

    private fun validatePlan(
        plan: PreparedExecutionPlan,
        currentTarget: ResolvedExecutionTarget,
    ): DatabaseSessionPreparedFailure? {
        if (
            plan.executionTargetId != currentTarget.targetId ||
            plan.databaseId != currentTarget.effectiveDatabaseId?.value
        ) {
            return DatabaseSessionPreparedFailure(
                kind = DatabaseSessionPreparedFailureKind.PLAN_TARGET_MISMATCH,
                code = PLAN_TARGET_MISMATCH,
            )
        }
        if (plan.orderedBindings.isEmpty()) {
            return DatabaseSessionPreparedFailure(
                kind = DatabaseSessionPreparedFailureKind.BINDINGS_REQUIRED,
                code = BINDINGS_REQUIRED,
            )
        }
        return null
    }

    private fun closeStatement(statement: RemotePreparedStatement): Exception? =
        try {
            statement.close()
            null
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: Exception) {
            failure
        }

    private fun closeResultSet(resultSet: RemoteResultSet): Exception? =
        try {
            resultSet.close()
            null
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: Exception) {
            failure
        }

    private fun failed(
        kind: DatabaseSessionPreparedFailureKind,
        code: String,
        bindingIndex: Int? = null,
        diagnosticType: String? = null,
        sqlState: String? = null,
        vendorCode: Int? = null,
    ): DatabaseSessionPreparedOutcome.Failed =
        DatabaseSessionPreparedOutcome.Failed(
            DatabaseSessionPreparedFailure(
                kind = kind,
                code = code,
                bindingIndex = bindingIndex,
                diagnosticType = diagnosticType,
                sqlState = sqlState,
                vendorCode = vendorCode,
            ),
        )
}

internal class PreparedBackendCancellationControl : PreparedBackendExecutionHandle {
    private val requested = AtomicBoolean(false)
    private val dispatchStarted = AtomicBoolean(false)
    private val dispatchSucceeded = AtomicBoolean(false)
    private val dispatchFailureType = AtomicReference<String?>()
    private val finished = AtomicBoolean(false)
    private val statement = AtomicReference<RemotePreparedStatement?>()

    override fun cancel(): PreparedBackendCancellationResult {
        if (finished.get()) return PreparedBackendCancellationResult.AlreadyFinished
        requested.set(true)
        return dispatchIfPossible()
    }

    fun isCancellationRequested(): Boolean = requested.get()

    fun cancelWasDispatched(): Boolean = dispatchSucceeded.get()

    fun attach(candidate: RemotePreparedStatement) {
        check(statement.compareAndSet(null, candidate)) {
            "prepared backend statement must be attached at most once"
        }
        if (requested.get()) {
            dispatchIfPossible()
        }
    }

    fun detach(candidate: RemotePreparedStatement) {
        statement.compareAndSet(candidate, null)
    }

    fun markFinished() {
        finished.set(true)
        statement.set(null)
    }

    private fun dispatchIfPossible(): PreparedBackendCancellationResult {
        val current = statement.get() ?: return PreparedBackendCancellationResult.Requested
        if (!dispatchStarted.compareAndSet(false, true)) {
            return dispatchFailureType.get()?.let(PreparedBackendCancellationResult::Failed)
                ?: PreparedBackendCancellationResult.Requested
        }
        return try {
            current.cancel()
            dispatchSucceeded.set(true)
            PreparedBackendCancellationResult.Requested
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: Exception) {
            val diagnosticType = failure.javaClass.name
            dispatchFailureType.set(diagnosticType)
            PreparedBackendCancellationResult.Failed(diagnosticType)
        }
    }
}

private object PublicRemoteResultSetAdapter {
    private val metadataGetter: Method =
        RemoteResultSet::class.java.getMethod("getMetaData")

    private val metadataType: Class<*> = metadataGetter.returnType.also { type ->
        require(Modifier.isPublic(type.modifiers)) {
            "RemoteResultSet metadata contract must be public"
        }
        require(!type.name.contains(".impl.")) {
            "RemoteResultSet metadata contract must not be implementation-owned"
        }
    }

    private val columnCountMethod: Method =
        metadataType.getMethod("getColumnCount")

    private val columnLabelMethod: Method? =
        publicIndexedMethod("getColumnLabel") ?: publicIndexedMethod("getColumnName")

    private val columnTypeNameMethod: Method? =
        publicIndexedMethod("getColumnTypeName")

    fun read(
        resultSet: RemoteResultSet,
        limits: PreparedResultLimits,
    ): DatabaseSessionPreparedOutcome {
        val metadata = invoke(metadataGetter, resultSet)
            ?: throw UnsupportedOperationException("RemoteResultSet metadata is unavailable")
        val columnCount = invoke(columnCountMethod, metadata) as? Int
            ?: throw UnsupportedOperationException("RemoteResultSet column count is unavailable")
        if (columnCount < 0 || columnCount > limits.maxColumns) {
            return DatabaseSessionPreparedOutcome.Failed(
                DatabaseSessionPreparedFailure(
                    kind = DatabaseSessionPreparedFailureKind.RESULT_LIMIT_EXCEEDED,
                    code = RESULT_LIMIT_EXCEEDED,
                ),
            )
        }

        val columns = (1..columnCount).map { index ->
            PreparedResultColumn(
                index = index,
                label = columnLabelMethod
                    ?.let { invoke(it, metadata, index) as? String }
                    ?.takeIf { it.isNotBlank() },
                jdbcTypeName = columnTypeNameMethod
                    ?.let { invoke(it, metadata, index) as? String }
                    ?.takeIf { it.isNotBlank() },
            )
        }

        val rows = mutableListOf<List<String?>>()
        while (resultSet.next()) {
            if (rows.size >= limits.maxRows) {
                return DatabaseSessionPreparedOutcome.Failed(
                    DatabaseSessionPreparedFailure(
                        kind = DatabaseSessionPreparedFailureKind.RESULT_LIMIT_EXCEEDED,
                        code = RESULT_LIMIT_EXCEEDED,
                    ),
                )
            }
            rows += (1..columnCount).map { index -> resultSet.getString(index) }
        }
        return DatabaseSessionPreparedOutcome.Query(
            columns = columns.toList(),
            rows = rows.map { it.toList() },
        )
    }

    private fun publicIndexedMethod(name: String): Method? =
        try {
            metadataType.getMethod(name, Int::class.javaPrimitiveType)
        } catch (_: NoSuchMethodException) {
            null
        }

    private fun invoke(
        method: Method,
        receiver: Any,
        vararg args: Any,
    ): Any? =
        try {
            method.invoke(receiver, *args)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
}
