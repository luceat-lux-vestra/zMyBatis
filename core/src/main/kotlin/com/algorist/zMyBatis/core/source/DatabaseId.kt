package com.algorist.zMyBatis.core.source

/**
 * Effective MyBatis database-id context proven from the selected Database Tools target.
 *
 * This value is semantic context only. It never decides whether a datasource itself is a valid
 * execution target.
 */
@JvmInline
value class MyBatisDatabaseId(val value: String) {
    init {
        require(value.isNotBlank()) { "MyBatis database id must not be blank" }
    }
}

/**
 * Deliberately narrow conventional mapping approved by #252.
 *
 * These identifiers are zMyBatis' documented fallback for stock MyBatis DB_VENDOR semantics with
 * no translation properties: VendorDatabaseIdProvider uses JDBC database product names. Other
 * identifiers are treated as potentially custom/provider-translated mappings.
 */
object ConventionalMyBatisDatabaseIds {
    const val POSTGRESQL = "PostgreSQL"
    const val ORACLE = "Oracle"

    val all: Set<String> = setOf(POSTGRESQL, ORACLE)
}

sealed interface DatabaseIdVariantSelection<out T> {
    data class Selected<T>(val variant: T) : DatabaseIdVariantSelection<T>
    data object Missing : DatabaseIdVariantSelection<Nothing>
    data object Ambiguous : DatabaseIdVariantSelection<Nothing>
    data object AuthorityUnavailable : DatabaseIdVariantSelection<Nothing>
    data class MappingUnproven(val declaredDatabaseIds: List<String>) : DatabaseIdVariantSelection<Nothing>
}

sealed interface DatabaseIdAuthorityValidation {
    data object Valid : DatabaseIdAuthorityValidation
    data object AuthorityUnavailable : DatabaseIdAuthorityValidation
    data class MappingUnproven(val declaredDatabaseIds: List<String>) : DatabaseIdAuthorityValidation
}

/**
 * Validates that database-id-dependent semantics have enough target/provider authority to delegate
 * selection to stock MyBatis without guessing.
 *
 * [provenDatabaseIds] describes database ids whose mapping is actually proven by the caller. The
 * current Database Tools fallback passes [ConventionalMyBatisDatabaseIds.all]; a future statically
 * reconstructed DatabaseIdProvider may pass its proven mapped ids instead.
 */
fun validateDatabaseIdAuthority(
    declaredDatabaseIds: Collection<String>,
    effectiveDatabaseId: MyBatisDatabaseId?,
    provenDatabaseIds: Set<String> = ConventionalMyBatisDatabaseIds.all,
): DatabaseIdAuthorityValidation {
    val specificIds = declaredDatabaseIds.toSet()
    if (specificIds.isEmpty()) return DatabaseIdAuthorityValidation.Valid
    if (effectiveDatabaseId == null) return DatabaseIdAuthorityValidation.AuthorityUnavailable

    val unproven = (specificIds - provenDatabaseIds).sorted()
    return if (unproven.isEmpty()) {
        DatabaseIdAuthorityValidation.Valid
    } else {
        DatabaseIdAuthorityValidation.MappingUnproven(unproven)
    }
}

/**
 * Applies stock-MyBatis variant precedence while remaining fail-closed around unknown custom
 * DatabaseIdProvider mappings.
 *
 * Unknown ids are rejected before conventional selection because they may be custom aliases for
 * the current target. Once every declared id is within the caller-proven set, an exact target-id
 * variant wins over the default and the default may serve another proven family.
 */
fun <T> selectDatabaseIdVariant(
    variants: List<T>,
    effectiveDatabaseId: MyBatisDatabaseId?,
    provenDatabaseIds: Set<String> = ConventionalMyBatisDatabaseIds.all,
    databaseIdOf: (T) -> String?,
): DatabaseIdVariantSelection<T> {
    if (variants.isEmpty()) return DatabaseIdVariantSelection.Missing

    val groups = variants.groupBy(databaseIdOf)
    if (groups.values.any { it.size != 1 }) return DatabaseIdVariantSelection.Ambiguous

    val defaultVariant = groups[null]?.singleOrNull()
    val specificIds = groups.keys.filterNotNull().toSet()
    if (specificIds.isEmpty()) {
        return defaultVariant?.let { DatabaseIdVariantSelection.Selected(it) }
            ?: DatabaseIdVariantSelection.Missing
    }

    when (val validation = validateDatabaseIdAuthority(specificIds, effectiveDatabaseId, provenDatabaseIds)) {
        DatabaseIdAuthorityValidation.Valid -> Unit
        DatabaseIdAuthorityValidation.AuthorityUnavailable ->
            return DatabaseIdVariantSelection.AuthorityUnavailable
        is DatabaseIdAuthorityValidation.MappingUnproven ->
            return DatabaseIdVariantSelection.MappingUnproven(validation.declaredDatabaseIds)
    }
    val effective = checkNotNull(effectiveDatabaseId)

    groups[effective.value]?.singleOrNull()?.let {
        return DatabaseIdVariantSelection.Selected(it)
    }

    return defaultVariant?.let { DatabaseIdVariantSelection.Selected(it) }
        ?: DatabaseIdVariantSelection.Missing
}
