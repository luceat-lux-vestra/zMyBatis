package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.execution.TargetResolutionFailure
import com.algorist.zMyBatis.core.execution.TargetResolutionFailureKind
import com.algorist.zMyBatis.core.materialization.TargetDialectIdentity
import com.intellij.database.model.DasNamespace
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.DasUtil
import com.intellij.database.util.DbImplUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project

internal data class DataSourceCandidate<D>(
    val stableId: String?,
    val resource: D,
)

internal data class SchemaCandidate<S>(
    val name: String,
    val resource: S,
)

internal sealed interface ExactTargetSelectionResult<out D, out S> {
    data class Success<D, S>(
        val resolvedTarget: ResolvedExecutionTarget,
        val dataSource: D,
        val schema: S,
    ) : ExactTargetSelectionResult<D, S>

    data class Failed(
        val failure: TargetResolutionFailure,
    ) : ExactTargetSelectionResult<Nothing, Nothing>
}

internal object ExactTargetSelectionPolicy {
    fun <D, S> resolve(
        descriptor: ExecutionTargetDescriptor,
        dataSources: List<DataSourceCandidate<D>>,
        schemas: (D) -> List<SchemaCandidate<S>>,
        dialectIdentity: (D) -> TargetDialectIdentity?,
    ): ExactTargetSelectionResult<D, S> {
        val targetDataSourceId = descriptor.targetId.dataSourceId.value
        val matchingDataSources = dataSources.filter { it.stableId == targetDataSourceId }

        val dataSource = when (matchingDataSources.size) {
            0 -> return failed(
                TargetResolutionFailureKind.DATA_SOURCE_MISSING,
                "target-resolution-data-source-missing",
            )
            1 -> matchingDataSources.single().resource
            else -> return failed(
                TargetResolutionFailureKind.DATA_SOURCE_AMBIGUOUS,
                "target-resolution-data-source-ambiguous",
            )
        }

        val targetSchema = descriptor.targetId.schema.value
        val matchingSchemas = schemas(dataSource).filter { it.name == targetSchema }
        val schema = when (matchingSchemas.size) {
            0 -> return failed(
                TargetResolutionFailureKind.SCHEMA_MISSING,
                "target-resolution-schema-missing",
            )
            1 -> matchingSchemas.single().resource
            else -> return failed(
                TargetResolutionFailureKind.SCHEMA_AMBIGUOUS,
                "target-resolution-schema-ambiguous",
            )
        }

        val dialect = dialectIdentity(dataSource)
            ?: return failed(
                TargetResolutionFailureKind.DIALECT_UNKNOWN,
                "target-resolution-dialect-unknown",
            )

        return ExactTargetSelectionResult.Success(
            resolvedTarget = ResolvedExecutionTarget(
                descriptor = descriptor,
                dialectIdentity = dialect,
            ),
            dataSource = dataSource,
            schema = schema,
        )
    }

    private fun failed(
        kind: TargetResolutionFailureKind,
        code: String,
    ): ExactTargetSelectionResult.Failed =
        ExactTargetSelectionResult.Failed(TargetResolutionFailure(kind, code))
}

internal fun interface DatabaseToolsDialectIdentityProvider {
    fun resolve(dataSource: DbDataSource): TargetDialectIdentity?
}

internal object UnknownDatabaseToolsDialectIdentityProvider : DatabaseToolsDialectIdentityProvider {
    override fun resolve(dataSource: DbDataSource): TargetDialectIdentity? = null
}

internal sealed interface DatabaseToolsTargetResolution {
    data class Success(
        val resolvedTarget: ResolvedExecutionTarget,
        val dataSource: DbDataSource,
        val schema: DasNamespace,
    ) : DatabaseToolsTargetResolution

    data class Failed(
        val failure: TargetResolutionFailure,
    ) : DatabaseToolsTargetResolution
}

/**
 * Resolves persisted target identity to exact live Database Tools resources.
 *
 * This adapter performs no console creation, document mutation, materialization, or query
 * invocation. Dialect identity must be supplied by an explicit provider; the default provider
 * deliberately fails closed until a maintained public-API classifier is proven.
 */
@Suppress("unused") // #167 establishes the adapter; orchestration wiring is a later #65 slice.
internal class DatabaseToolsTargetResolver(
    private val project: Project,
    private val dialectIdentityProvider: DatabaseToolsDialectIdentityProvider =
        UnknownDatabaseToolsDialectIdentityProvider,
) {
    fun resolve(descriptor: ExecutionTargetDescriptor): DatabaseToolsTargetResolution {
        val candidates = DbPsiFacade.getInstance(project).dataSources.map { dataSource ->
            DataSourceCandidate(
                stableId = stableDataSourceId(dataSource),
                resource = dataSource,
            )
        }

        return when (
            val selected = ExactTargetSelectionPolicy.resolve(
                descriptor = descriptor,
                dataSources = candidates,
                schemas = { dataSource ->
                    DasUtil.getSchemas(dataSource)
                        .map { schema -> SchemaCandidate(schema.name, schema) }
                        .toList()
                },
                dialectIdentity = dialectIdentityProvider::resolve,
            )
        ) {
            is ExactTargetSelectionResult.Success -> DatabaseToolsTargetResolution.Success(
                resolvedTarget = selected.resolvedTarget,
                dataSource = selected.dataSource,
                schema = selected.schema,
            )
            is ExactTargetSelectionResult.Failed -> DatabaseToolsTargetResolution.Failed(selected.failure)
        }
    }

    private fun stableDataSourceId(dataSource: DbDataSource): String? = try {
        DbImplUtil.getMaybeLocalDataSource(dataSource)
            ?.uniqueId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    } catch (ex: ProcessCanceledException) {
        throw ex
    } catch (_: Exception) {
        null
    }
}
