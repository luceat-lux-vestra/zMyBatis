package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.PreparationMetadata
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.preparation.PreparedExecution
import com.algorist.zMyBatis.core.preparation.PreparedRawInterpolation

object MyBatisPreparationEngine {
    private const val ENGINE_ID = "org.mybatis:mybatis"
    private const val ENGINE_VERSION = "3.6.0"
    private const val UNSUPPORTED_SOURCE = "preparation-source-kind-unsupported"
    private const val RAW_INPUT_MISSING = "raw-interpolation-input-missing"
    private const val EMPTY_SQL = "mybatis-prepared-sql-empty"
    private const val INVARIANT_FAILURE = "mybatis-preparation-invariant-failure"

    private val rawInterpolationExpression = Regex("""\$\{([^}]*)}""")

    fun prepare(request: MyBatisPreparationRequest): PreparationResult {
        val source = request.source as? PreparationSource.JavaAnnotation
            ?: return failed(PreparationFailureKind.UNSUPPORTED_SEMANTIC, UNSUPPORTED_SOURCE)

        val script = source.capture.sqlSegments.joinToString(separator = " ").trim()
        if (containsDynamicScript(script)) {
            DynamicOgnlSafetyAdmission.failureForScript(script)?.let {
                return PreparationResult.Failed(it)
            }
        } else {
            val rawExpressions = rawInterpolationExpression.findAll(script)
                .map { it.groupValues[1].trim() }
                .filter(String::isNotEmpty)
                .toList()
            DynamicOgnlSafetyAdmission.failureForExpressions(rawExpressions)?.let {
                return PreparationResult.Failed(it)
            }
        }

        val payload = when (val result = MyBatisInvocationParameterObjectFactory.create(request)) {
            is MyBatisInvocationParameterObjectFactory.Result.Success -> result.payload
            is MyBatisInvocationParameterObjectFactory.Result.Failed ->
                return PreparationResult.Failed(result.failure)
        }

        val boundSql = when (
            val isolated = IsolatedDynamicMyBatisPreparation.prepareInvocation(
                script = script,
                payload = payload,
                databaseId = request.effectiveDatabaseId?.value,
            )
        ) {
            is IsolatedDynamicMyBatisPreparation.Result.Ready -> isolated.boundSql
            is IsolatedDynamicMyBatisPreparation.Result.Failed ->
                return PreparationResult.Failed(isolated.failure)
        }

        if (boundSql.sql.isBlank()) {
            return failed(PreparationFailureKind.PREPARATION_INVARIANT, EMPTY_SQL)
        }

        val bindings = when (
            val captured = MyBatisInvocationBindingCapture.capture(
                snapshots = boundSql.mappings,
                request = request,
                payload = payload,
            )
        ) {
            is MyBatisInvocationBindingCapture.Result.Ready -> captured.bindings
            is MyBatisInvocationBindingCapture.Result.Failed ->
                return PreparationResult.Failed(captured.failure)
        }

        val rawInterpolations = mutableListOf<PreparedRawInterpolation>()
        for (requirement in request.parameterContract.requirements.filter { it.usesRawInterpolation }) {
            val provided = request.inputEnvironment.value(requirement.id)
                ?: return failed(PreparationFailureKind.PREPARATION_INVARIANT, RAW_INPUT_MISSING)
            rawInterpolations += PreparedRawInterpolation(
                requirementId = requirement.id,
                provenance = requirement.provenance,
                origin = provided.origin,
            )
        }

        return try {
            PreparationResult.Success(
                PreparedExecution(
                    statementId = request.statementId,
                    statementKind = request.statementKind,
                    sourceRevisions = request.sourceRevisions,
                    sqlWithPlaceholders = boundSql.sql,
                    orderedBindings = bindings,
                    rawInterpolations = rawInterpolations,
                    preparationMetadata = PreparationMetadata(
                        engineIdentity = ENGINE_ID,
                        engineVersion = ENGINE_VERSION,
                        languageDriverIdentity = boundSql.languageDriverIdentity,
                        databaseId = request.effectiveDatabaseId?.value,
                    ),
                ),
            )
        } catch (failure: IllegalArgumentException) {
            failed(
                PreparationFailureKind.PREPARATION_INVARIANT,
                INVARIANT_FAILURE,
                diagnosticType = failure.javaClass.name,
            )
        }
    }

    private fun containsDynamicScript(sql: String): Boolean = sql.startsWith("<script>")

    private fun failed(
        kind: PreparationFailureKind,
        code: String,
        diagnosticType: String? = null,
    ) = PreparationResult.Failed(PreparationFailure(kind, code, diagnosticType = diagnosticType))
}
