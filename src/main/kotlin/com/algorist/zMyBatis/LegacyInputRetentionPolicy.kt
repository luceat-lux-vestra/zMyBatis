package com.algorist.zMyBatis

/**
 * Bounded retention policy for the still-shipping legacy parameter dialog.
 *
 * Raw `${...}` caller roots are never eligible for remembered-input persistence. This policy
 * intentionally does not classify input semantics itself; callers must provide the conservative
 * raw-root evidence derived from the current invocation source.
 */
internal object LegacyInputRetentionPolicy {
    fun retainableValues(
        rawValues: Map<String, String>,
        rawInterpolationParams: Set<String>,
    ): Map<String, String> = rawValues
        .filterKeys { it !in rawInterpolationParams }
}
