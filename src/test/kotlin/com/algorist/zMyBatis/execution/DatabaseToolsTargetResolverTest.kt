package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.execution.TargetResolutionFailureKind
import com.algorist.zMyBatis.core.materialization.TargetDialectIdentity
import com.intellij.database.Dbms
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseToolsTargetResolverTest {
    @Test
    fun uniqueDatasourceSchemaAndDialectResolveExactResources() {
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
            dialectIdentity = { TargetDialectIdentity("postgresql-maintained") },
        ) as ExactTargetSelectionResult.Success

        assertSame(ds, result.dataSource)
        assertSame(schema, result.schema)
        assertEquals(descriptor, result.resolvedTarget.descriptor)
        assertEquals(descriptor.targetId, result.resolvedTarget.targetId)
        assertEquals(
            TargetDialectIdentity("postgresql-maintained"),
            result.resolvedTarget.dialectIdentity,
        )
    }

    @Test
    fun maintainedDatabaseToolsDbmsClassifierAdmitsExactPostgresOnly() {
        assertEquals(
            TargetDialectIdentity("postgresql"),
            classifyDatabaseToolsDbms(Dbms.POSTGRES),
        )
        assertEquals(null, classifyDatabaseToolsDbms(Dbms.GREENPLUM))
        assertEquals(null, classifyDatabaseToolsDbms(Dbms.MYSQL))
        assertEquals(null, classifyDatabaseToolsDbms(Dbms.UNKNOWN))
        assertEquals(null, classifyDatabaseToolsDbms(null))
    }

    @Test
    fun databaseToolsDbmsLookupPreservesProcessCancellation() {
        val cancellation = ProcessCanceledException()

        try {
            resolveDatabaseToolsDialectIdentity { throw cancellation }
            throw AssertionError("ProcessCanceledException must escape DBMS classification")
        } catch (ex: ProcessCanceledException) {
            assertSame(cancellation, ex)
        }
    }

    @Test
    fun ordinaryDatabaseToolsDbmsLookupFailureFailsClosed() {
        val result = resolveDatabaseToolsDialectIdentity {
            throw IllegalStateException("unavailable")
        }

        assertEquals(null, result)
    }

    @Test
    fun missingDatasourceFailsWithoutConsultingSchemaOrDialect() {
        var schemaCalls = 0
        var dialectCalls = 0

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
            dialectIdentity = {
                dialectCalls++
                TargetDialectIdentity("should-not-run")
            },
        )

        assertFailure(result, TargetResolutionFailureKind.DATA_SOURCE_MISSING)
        assertEquals(0, schemaCalls)
        assertEquals(0, dialectCalls)
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
            dialectIdentity = { TargetDialectIdentity("should-not-run") },
        )

        assertFailure(result, TargetResolutionFailureKind.DATA_SOURCE_AMBIGUOUS)
        assertEquals(0, schemaCalls)
    }

    @Test
    fun missingSchemaFailsBeforeDialectResolution() {
        var dialectCalls = 0

        val result = ExactTargetSelectionPolicy.resolve(
            descriptor = descriptor("ds-1", "public"),
            dataSources = listOf(DataSourceCandidate("ds-1", Resource("ds"))),
            schemas = { listOf(SchemaCandidate("other", Resource("other-schema"))) },
            dialectIdentity = {
                dialectCalls++
                TargetDialectIdentity("should-not-run")
            },
        )

        assertFailure(result, TargetResolutionFailureKind.SCHEMA_MISSING)
        assertEquals(0, dialectCalls)
    }

    @Test
    fun duplicateExactSchemaFailsAmbiguous() {
        var dialectCalls = 0

        val result = ExactTargetSelectionPolicy.resolve(
            descriptor = descriptor("ds-1", "public"),
            dataSources = listOf(DataSourceCandidate("ds-1", Resource("ds"))),
            schemas = {
                listOf(
                    SchemaCandidate("public", Resource("schema-1")),
                    SchemaCandidate("public", Resource("schema-2")),
                )
            },
            dialectIdentity = {
                dialectCalls++
                TargetDialectIdentity("should-not-run")
            },
        )

        assertFailure(result, TargetResolutionFailureKind.SCHEMA_AMBIGUOUS)
        assertEquals(0, dialectCalls)
    }

    @Test
    fun schemaMatchingIsExactAndCaseSensitive() {
        val result = ExactTargetSelectionPolicy.resolve(
            descriptor = descriptor("ds-1", "Public"),
            dataSources = listOf(DataSourceCandidate("ds-1", Resource("ds"))),
            schemas = { listOf(SchemaCandidate("public", Resource("schema"))) },
            dialectIdentity = { TargetDialectIdentity("should-not-run") },
        )

        assertFailure(result, TargetResolutionFailureKind.SCHEMA_MISSING)
    }

    @Test
    fun unknownDialectFailsAfterExactTargetIsProven() {
        val result = ExactTargetSelectionPolicy.resolve(
            descriptor = descriptor("ds-1", "public"),
            dataSources = listOf(DataSourceCandidate("ds-1", Resource("ds"))),
            schemas = { listOf(SchemaCandidate("public", Resource("schema"))) },
            dialectIdentity = { null },
        )

        val failure = (result as ExactTargetSelectionResult.Failed).failure
        assertEquals(TargetResolutionFailureKind.DIALECT_UNKNOWN, failure.kind)
        assertEquals("target-resolution-dialect-unknown", failure.code)
    }

    @Test
    fun displayNameChangesCannotAffectSelection() {
        val dataSources = listOf(DataSourceCandidate("ds-1", Resource("ds")))
        val schemas: (Resource) -> List<SchemaCandidate<Resource>> = {
            listOf(SchemaCandidate("public", Resource("schema")))
        }
        val dialect: (Resource) -> TargetDialectIdentity? = {
            TargetDialectIdentity("dialect")
        }

        val before = ExactTargetSelectionPolicy.resolve(
            descriptor("ds-1", "public", "old-name"),
            dataSources,
            schemas,
            dialect,
        ) as ExactTargetSelectionResult.Success
        val after = ExactTargetSelectionPolicy.resolve(
            descriptor("ds-1", "public", "new-name"),
            dataSources,
            schemas,
            dialect,
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
            dialectIdentity = { null },
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
