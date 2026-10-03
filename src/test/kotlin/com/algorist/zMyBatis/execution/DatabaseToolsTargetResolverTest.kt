package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.execution.TargetResolutionFailureKind
import com.algorist.zMyBatis.core.source.ConventionalMyBatisDatabaseIds
import com.intellij.database.Dbms
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseToolsTargetResolverTest {
    @Test
    fun databaseToolsDbmsProducesSemanticDatabaseIdWithoutBecomingTargetAdmission() {
        assertEquals(
            ConventionalMyBatisDatabaseIds.POSTGRESQL,
            classifyDatabaseToolsDatabaseId(Dbms.POSTGRES)?.value,
        )
        assertEquals(
            ConventionalMyBatisDatabaseIds.ORACLE,
            classifyDatabaseToolsDatabaseId(Dbms.ORACLE)?.value,
        )
        assertEquals(null, classifyDatabaseToolsDatabaseId(Dbms.MYSQL))
        assertEquals(null, classifyDatabaseToolsDatabaseId(Dbms.UNKNOWN))
        assertEquals(null, classifyDatabaseToolsDatabaseId(null))
    }

    @Test
    fun databaseIdLookupPreservesCancellationAndOrdinaryFailureBecomesUnknownContext() {
        val cancellation = ProcessCanceledException()
        try {
            resolveDatabaseToolsDatabaseId { throw cancellation }
            throw AssertionError("ProcessCanceledException must escape database-id lookup")
        } catch (actual: ProcessCanceledException) {
            assertSame(cancellation, actual)
        }

        assertEquals(
            null,
            resolveDatabaseToolsDatabaseId { throw IllegalStateException("metadata unavailable") },
        )
    }

    @Test
    fun uniqueDatasourceAndSchemaResolveExactResourcesWithoutDbmsClassification() {
        val descriptor = descriptor("ds-1", "public", "renamed-display")
        val ds = Resource("datasource-1")
        val schema = Resource("schema-public")

        val result = ExactTargetSelectionPolicy.resolve(
            descriptor = descriptor,
            dataSources = listOf(
                DataSourceCandidate("ds-1", ds),
                DataSourceCandidate("ds-2", Resource("other")),
            ),
            schemas = {
                listOf(
                    SchemaCandidate("public", schema),
                    SchemaCandidate("audit", Resource("schema-audit")),
                )
            },
        ) as ExactTargetSelectionResult.Success

        assertSame(ds, result.dataSource)
        assertSame(schema, result.schema)
        assertEquals(descriptor, result.resolvedTarget.descriptor)
        assertEquals(descriptor.targetId, result.resolvedTarget.targetId)
    }

    @Test
    fun missingDatasourceFailsBeforeSchemaLookup() {
        var schemaCalls = 0
        val result = ExactTargetSelectionPolicy.resolve(
            descriptor = descriptor("wanted", "public"),
            dataSources = listOf(
                DataSourceCandidate("other", Resource("other")),
                DataSourceCandidate(null, Resource("unproven-id")),
            ),
            schemas = {
                schemaCalls++
                emptyList<SchemaCandidate<Resource>>()
            },
        )

        assertFailure(result, TargetResolutionFailureKind.DATA_SOURCE_MISSING)
        assertEquals(0, schemaCalls)
    }

    @Test
    fun duplicateStableDatasourceIdFailsAmbiguousWithoutNameFallback() {
        var schemaCalls = 0
        val result = ExactTargetSelectionPolicy.resolve(
            descriptor = descriptor("ds-1", "public", "display-name-is-not-authority"),
            dataSources = listOf(
                DataSourceCandidate("ds-1", Resource("first")),
                DataSourceCandidate("ds-1", Resource("second")),
            ),
            schemas = {
                schemaCalls++
                listOf(SchemaCandidate("public", Resource("schema")))
            },
        )

        assertFailure(result, TargetResolutionFailureKind.DATA_SOURCE_AMBIGUOUS)
        assertEquals(0, schemaCalls)
    }

    @Test
    fun missingSchemaFailsClosed() {
        val result = ExactTargetSelectionPolicy.resolve(
            descriptor = descriptor("ds-1", "public"),
            dataSources = listOf(DataSourceCandidate("ds-1", Resource("ds"))),
            schemas = { listOf(SchemaCandidate("other", Resource("other-schema"))) },
        )

        assertFailure(result, TargetResolutionFailureKind.SCHEMA_MISSING)
    }

    @Test
    fun duplicateExactSchemaFailsAmbiguous() {
        val result = ExactTargetSelectionPolicy.resolve(
            descriptor = descriptor("ds-1", "public"),
            dataSources = listOf(DataSourceCandidate("ds-1", Resource("ds"))),
            schemas = {
                listOf(
                    SchemaCandidate("public", Resource("schema-1")),
                    SchemaCandidate("public", Resource("schema-2")),
                )
            },
        )

        assertFailure(result, TargetResolutionFailureKind.SCHEMA_AMBIGUOUS)
    }

    @Test
    fun schemaMatchingIsExactAndCaseSensitive() {
        val result = ExactTargetSelectionPolicy.resolve(
            descriptor = descriptor("ds-1", "Public"),
            dataSources = listOf(DataSourceCandidate("ds-1", Resource("ds"))),
            schemas = { listOf(SchemaCandidate("public", Resource("schema"))) },
        )

        assertFailure(result, TargetResolutionFailureKind.SCHEMA_MISSING)
    }

    @Test
    fun displayNameChangesCannotAffectSelection() {
        val dataSources = listOf(DataSourceCandidate("ds-1", Resource("ds")))
        val schemas: (Resource) -> List<SchemaCandidate<Resource>> = {
            listOf(SchemaCandidate("public", Resource("schema")))
        }

        val before = ExactTargetSelectionPolicy.resolve(
            descriptor("ds-1", "public", "old-name"),
            dataSources,
            schemas,
        ) as ExactTargetSelectionResult.Success
        val after = ExactTargetSelectionPolicy.resolve(
            descriptor("ds-1", "public", "new-name"),
            dataSources,
            schemas,
        ) as ExactTargetSelectionResult.Success

        assertSame(before.dataSource, after.dataSource)
        assertEquals(before.resolvedTarget.targetId, after.resolvedTarget.targetId)
    }

    @Test
    fun failureDiagnosticsContainNoResourcesOrSql() {
        val secretResource = Resource("jdbc-console-secret")
        val result = ExactTargetSelectionPolicy.resolve(
            descriptor = descriptor("missing", "public"),
            dataSources = listOf(DataSourceCandidate("other", secretResource)),
            schemas = { emptyList<SchemaCandidate<Resource>>() },
        )

        val failure = (result as ExactTargetSelectionResult.Failed).failure
        assertTrue(failure.code.startsWith("target-resolution-"))
        assertTrue(!failure.toString().contains("jdbc-console-secret"))
        assertTrue(!failure.toString().contains("SELECT"))
    }

    private fun descriptor(
        dataSourceId: String,
        schema: String,
        displayName: String? = "display",
    ) = ExecutionTargetDescriptor(
        targetId = ExecutionTargetId(
            dataSourceId = StableDataSourceId(dataSourceId),
            schema = ExplicitSchemaIdentity(schema),
        ),
        dataSourceDisplayName = displayName,
    )

    private fun assertFailure(
        result: ExactTargetSelectionResult<Resource, Resource>,
        expectedKind: TargetResolutionFailureKind,
    ) {
        val failure = (result as ExactTargetSelectionResult.Failed).failure
        assertEquals(expectedKind, failure.kind)
    }

    private data class Resource(val id: String)
}
