package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterializedDeliveryValidityTest {
    private val expected = targetId("datasource-a", "public")
    private val revisions = mapOf(SourceFileId("vfs:/Mapper.xml") to SourceRevision("r1"))

    @Test
    fun exactLiveTargetAndCurrentSourceAreAdmitted() {
        assertTrue(
            materializedDeliveryValid(
                boundTarget = expected,
                sourceRevisions = revisions,
                currentTarget = { resolved(expected) },
                isInvocationActive = { true },
                areSourceRevisionsCurrent = { it == revisions },
            ),
        )
    }

    @Test
    fun unboundArtifactShortCircuitsEveryMutableCheck() {
        var invocationChecks = 0
        var sourceChecks = 0
        var targetLookups = 0

        assertFalse(
            materializedDeliveryValid(
                boundTarget = null,
                sourceRevisions = revisions,
                currentTarget = { targetLookups++; resolved(expected) },
                isInvocationActive = { invocationChecks++; true },
                areSourceRevisionsCurrent = { sourceChecks++; true },
            ),
        )

        assertEquals(0, invocationChecks)
        assertEquals(0, sourceChecks)
        assertEquals(0, targetLookups)
    }

    @Test
    fun cancelledInvocationAndStaleSourceShortCircuitTargetResolution() {
        var sourceChecks = 0
        var targetLookups = 0
        assertFalse(
            materializedDeliveryValid(
                boundTarget = expected,
                sourceRevisions = revisions,
                currentTarget = { targetLookups++; resolved(expected) },
                isInvocationActive = { false },
                areSourceRevisionsCurrent = { sourceChecks++; true },
            ),
        )
        assertEquals(0, sourceChecks)
        assertEquals(0, targetLookups)

        assertFalse(
            materializedDeliveryValid(
                boundTarget = expected,
                sourceRevisions = revisions,
                currentTarget = { targetLookups++; resolved(expected) },
                isInvocationActive = { true },
                areSourceRevisionsCurrent = { false },
            ),
        )
        assertEquals(0, targetLookups)
    }

    @Test
    fun missingWrongDatasourceAndWrongSchemaAreRefused() {
        assertFalse(validWithTarget(null))
        assertFalse(validWithTarget(resolved(targetId("datasource-b", "public"))))
        assertFalse(validWithTarget(resolved(targetId("datasource-a", "other"))))
    }

    @Test
    fun exactArtifactSourceRevisionSnapshotIsGivenToEverySourceCheck() {
        val seen = mutableListOf<Map<SourceFileId, SourceRevision>>()

        repeat(2) {
            assertTrue(
                materializedDeliveryValid(
                    boundTarget = expected,
                    sourceRevisions = revisions,
                    currentTarget = { resolved(expected) },
                    isInvocationActive = { true },
                    areSourceRevisionsCurrent = {
                        seen += it
                        true
                    },
                ),
            )
        }

        assertEquals(listOf(revisions, revisions), seen)
    }

    @Test
    fun targetIsFreshlyRevalidatedOnEveryBoundary() {
        var lookups = 0
        val resolve = {
            lookups++
            if (lookups == 1) resolved(expected) else null
        }

        assertTrue(
            materializedDeliveryValid(
                expected,
                revisions,
                resolve,
                { true },
                { true },
            ),
        )
        assertFalse(
            materializedDeliveryValid(
                expected,
                revisions,
                resolve,
                { true },
                { true },
            ),
        )
        assertEquals(2, lookups)
    }

    private fun validWithTarget(target: ResolvedExecutionTarget?): Boolean =
        materializedDeliveryValid(
            boundTarget = expected,
            sourceRevisions = revisions,
            currentTarget = { target },
            isInvocationActive = { true },
            areSourceRevisionsCurrent = { true },
        )

    private fun targetId(dataSourceId: String, schema: String): ExecutionTargetId =
        ExecutionTargetId(
            StableDataSourceId(dataSourceId),
            ExplicitSchemaIdentity(schema),
        )

    private fun resolved(targetId: ExecutionTargetId): ResolvedExecutionTarget =
        ResolvedExecutionTarget(
            ExecutionTargetDescriptor(targetId, "fixture"),
            effectiveDatabaseId = null,
        )
}
