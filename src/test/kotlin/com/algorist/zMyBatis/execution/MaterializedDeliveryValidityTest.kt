package com.algorist.zMyBatis.execution

import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterializedDeliveryValidityTest {
    private val expected = ExecutionTargetId(StableDataSourceId("datasource-a"), ExplicitSchemaIdentity("public"))

    @Test
    fun exactLiveTargetAndInvocationAdmitted() {
        assertTrue(materializedDeliveryValid(expected, { expected }, { true }))
    }

    @Test
    fun unboundArtifactMustNotQueryTarget() {
        var targetLookups = 0
        assertFalse(materializedDeliveryValid(null, { targetLookups++; expected }, { true }))
        assertEquals(0, targetLookups)
    }

    @Test
    fun missingWrongDatasourceAndWrongSchemaRefused() {
        assertFalse(materializedDeliveryValid(expected, { null }, { true }))
        assertFalse(materializedDeliveryValid(expected, {
            ExecutionTargetId(StableDataSourceId("datasource-b"), ExplicitSchemaIdentity("public"))
        }, { true }))
        assertFalse(materializedDeliveryValid(expected, {
            ExecutionTargetId(StableDataSourceId("datasource-a"), ExplicitSchemaIdentity("other"))
        }, { true }))
    }

    @Test
    fun cancellationOrStaleSourceNeverResolvesTarget() {
        var targetLookups = 0
        assertFalse(materializedDeliveryValid(expected, { targetLookups++; expected }, { false }))
        assertEquals(0, targetLookups)
    }

    @Test
    fun targetIsFreshlyRevalidatedOnEveryBoundary() {
        var lookups = 0
        val resolve = {
            lookups++
            if (lookups == 1) expected else null
        }
        assertTrue(materializedDeliveryValid(expected, resolve, { true }))
        assertFalse(materializedDeliveryValid(expected, resolve, { true }))
        assertEquals(2, lookups)
    }
}
