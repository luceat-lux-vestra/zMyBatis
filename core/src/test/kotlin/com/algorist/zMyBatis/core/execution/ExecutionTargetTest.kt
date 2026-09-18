package com.algorist.zMyBatis.core.execution

import com.algorist.zMyBatis.core.materialization.TargetDialectIdentity
import com.algorist.zMyBatis.core.source.SourceFileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionTargetTest {
    @Test
    fun stableDatasourceAndExplicitSchemaDefineTargetIdentity() {
        val targetId = targetId("550e8400-e29b-41d4-a716-446655440000", "public")
        val beforeRename = ExecutionTargetDescriptor(targetId, "orders")
        val afterRename = ExecutionTargetDescriptor(targetId, "orders-renamed")

        assertEquals(beforeRename, afterRename)
        assertEquals(beforeRename.hashCode(), afterRename.hashCode())
        assertEquals(targetId, beforeRename.targetId)
        assertEquals(targetId, afterRename.targetId)
    }

    @Test
    fun sameDisplayNameCannotCollapseDistinctStableDatasourceIds() {
        val first = ExecutionTargetDescriptor(
            targetId("550e8400-e29b-41d4-a716-446655440000", "public"),
            "orders",
        )
        val second = ExecutionTargetDescriptor(
            targetId("550e8400-e29b-41d4-a716-446655440001", "public"),
            "orders",
        )

        assertNotEquals(first, second)
        assertNotEquals(first.targetId, second.targetId)
    }

    @Test
    fun datasourceAndSchemaIdentityRejectBlankValues() {
        assertThrows(IllegalArgumentException::class.java) {
            StableDataSourceId(" ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExplicitSchemaIdentity("")
        }
    }

    @Test
    fun sourceAssociationChangesWithoutRedefiningTargetIdentity() {
        val targetId = targetId("550e8400-e29b-41d4-a716-446655440000", "public")
        val first = SourceTargetAssociation(
            SourceFileId("file:///project/MapperA.xml"),
            targetId,
        )
        val second = SourceTargetAssociation(
            SourceFileId("file:///project/MapperB.xml"),
            targetId,
        )

        assertNotEquals(first, second)
        assertEquals(first.targetId, second.targetId)
        assertEquals(targetId, first.targetId)
    }

    @Test
    fun resolvedTargetAddsDialectWithoutChangingPersistedIdentity() {
        val descriptor = ExecutionTargetDescriptor(
            targetId("550e8400-e29b-41d4-a716-446655440000", "public"),
            "orders",
        )
        val resolved = ResolvedExecutionTarget(
            descriptor = descriptor,
            dialectIdentity = TargetDialectIdentity("postgresql-17"),
        )

        assertEquals(descriptor, resolved.descriptor)
        assertEquals(descriptor.targetId, resolved.targetId)
        assertEquals(TargetDialectIdentity("postgresql-17"), resolved.dialectIdentity)
    }

    @Test
    fun resolutionFailureVocabularyIsExplicitAndFailClosed() {
        assertEquals(
            setOf(
                TargetResolutionFailureKind.DATA_SOURCE_MISSING,
                TargetResolutionFailureKind.DATA_SOURCE_AMBIGUOUS,
                TargetResolutionFailureKind.SCHEMA_MISSING,
                TargetResolutionFailureKind.SCHEMA_AMBIGUOUS,
                TargetResolutionFailureKind.DEFAULT_SCHEMA_UNSUPPORTED,
                TargetResolutionFailureKind.DIALECT_UNKNOWN,
                TargetResolutionFailureKind.TARGET_STALE,
            ),
            TargetResolutionFailureKind.entries.toSet(),
        )

        TargetResolutionFailureKind.entries.forEach { kind ->
            val result: TargetResolution = TargetResolution.Failed(
                TargetResolutionFailure(kind, "target-resolution-${kind.name.lowercase()}"),
            )
            val failure = (result as TargetResolution.Failed).failure
            assertEquals(kind, failure.kind)
            assertFalse(failure.toString().contains("SELECT secret"))
            assertFalse(failure.toString().contains("JdbcConsole"))
        }
    }

    @Test
    fun descriptorDiagnosticsDoNotExposeMutableDisplayName() {
        val descriptor = ExecutionTargetDescriptor(
            targetId("550e8400-e29b-41d4-a716-446655440000", "public"),
            "production-secret-display-name",
        )

        assertFalse(descriptor.toString().contains("production-secret-display-name"))
        assertTrue(descriptor.toString().contains("<presentation>"))
    }

    @Test
    fun failureCodeMustBeNonBlank() {
        assertThrows(IllegalArgumentException::class.java) {
            TargetResolutionFailure(TargetResolutionFailureKind.TARGET_STALE, " ")
        }
    }

    private fun targetId(dataSourceId: String, schema: String) = ExecutionTargetId(
        dataSourceId = StableDataSourceId(dataSourceId),
        schema = ExplicitSchemaIdentity(schema),
    )
}
