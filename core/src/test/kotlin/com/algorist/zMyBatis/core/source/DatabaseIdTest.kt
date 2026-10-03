package com.algorist.zMyBatis.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseIdTest {
    @Test
    fun provenCustomProviderIdsCanDriveSelectionWithoutChangingTargetValidityPolicy() {
        data class Variant(val databaseId: String?)

        val selection = selectDatabaseIdVariant(
            variants = listOf(
                Variant(null),
                Variant("pg"),
                Variant("ora"),
            ),
            effectiveDatabaseId = MyBatisDatabaseId("pg"),
            databaseIdOf = Variant::databaseId,
            provenDatabaseIds = setOf("pg", "ora"),
        )

        assertTrue(selection is DatabaseIdVariantSelection.Selected)
        assertEquals(
            "pg",
            (selection as DatabaseIdVariantSelection.Selected).variant.databaseId,
        )
    }

    @Test
    fun unknownAliasRemainsUnprovenUnderConventionalFallback() {
        val validation = validateDatabaseIdAuthority(
            declaredDatabaseIds = listOf("PostgreSQL", "pg"),
            effectiveDatabaseId = MyBatisDatabaseId(ConventionalMyBatisDatabaseIds.POSTGRESQL),
        )

        assertTrue(validation is DatabaseIdAuthorityValidation.MappingUnproven)
        assertEquals(
            listOf("pg"),
            (validation as DatabaseIdAuthorityValidation.MappingUnproven).declaredDatabaseIds,
        )
    }
}
