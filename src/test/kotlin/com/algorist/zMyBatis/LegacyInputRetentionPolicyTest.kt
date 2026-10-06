package com.algorist.zMyBatis

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyInputRetentionPolicyTest {
    @Test
    fun `raw interpolation values are removed while bound values remain`() {
        val retained = LegacyInputRetentionPolicy.retainableValues(
            rawValues = linkedMapOf(
                "id" to "7",
                "orderBy" to "created_at DESC",
            ),
            rawInterpolationParams = setOf("orderBy"),
        )

        assertEquals(mapOf("id" to "7"), retained)
    }

    @Test
    fun `mixed raw and bound use of one root is never retained`() {
        val retained = LegacyInputRetentionPolicy.retainableValues(
            rawValues = mapOf("shared" to "unsafe"),
            rawInterpolationParams = setOf("shared"),
        )

        assertEquals(emptyMap<String, String>(), retained)
    }

    @Test
    fun `bound-only legacy values retain existing behavior`() {
        val rawValues = linkedMapOf("id" to "7", "status" to "ACTIVE")

        assertEquals(
            rawValues,
            LegacyInputRetentionPolicy.retainableValues(rawValues, emptySet()),
        )
    }
}
