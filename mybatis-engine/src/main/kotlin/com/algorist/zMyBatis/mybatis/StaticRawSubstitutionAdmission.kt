package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputValue
import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationFailure
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.preparation.rawRequirements
import org.apache.ibatis.parsing.GenericTokenParser

/**
 * Admits the statically supported `${...}` subset without evaluating it.
 *
 * MyBatis renders `${...}` first and only then parses the rendered text for `#{...}` mappings. A raw
 * value must therefore be proven unable to create, remove, replace, escape, or mutate a source bound
 * token before MyBatis is allowed to evaluate the raw expression.
 *
 * This slice admits only an exact caller alias (`${alias}`). Nested/raw OGNL property traversal is
 * deliberately refused because the authoritative root RawText value does not prove the value that
 * MyBatis would obtain after evaluating `${alias.property}`.
 */
internal object StaticRawSubstitutionAdmission {
    private const val AUTHORITY_MISMATCH = "raw-interpolation-source-authority-mismatch"
    private const val EXPRESSION_UNSUPPORTED = "raw-interpolation-expression-unsupported"
    private const val BOUND_CONTEXT_UNSUPPORTED = "raw-interpolation-bound-context-unsupported"
    private const val TOKEN_TOPOLOGY_UNSUPPORTED = "raw-interpolation-mybatis-token-topology-unsupported"

    private val rawOpen = String(charArrayOf('$', '{'))
    private val boundOpen = String(charArrayOf('#', '{'))
    private val aliasName = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val supportedStringTypes = setOf("java.lang.String", "String")

    fun failureOrNull(
        script: String,
        request: MyBatisPreparationRequest,
    ): PreparationFailure? {
        val sourceExpressions = mutableListOf<String>()
        val rawSlotMarkers = mutableListOf<String>()
        var rawSlotIndex = 0
        val structuralSql = GenericTokenParser(rawOpen, "}") { content ->
            sourceExpressions += content.trim()
            val marker = "\u0000zmybatis_raw_slot_${rawSlotIndex++}\u0000"
            rawSlotMarkers += marker
            marker
        }.parse(script)

        val rawRequirements = request.parameterContract.rawRequirements()

        // Escaped or malformed `${...}` is intentionally outside the statically proven subset. The
        // stock token parser leaves a literal `${` behind in both cases.
        if (structuralSql.contains(rawOpen)) return authorityMismatch()
        if (sourceExpressions.isEmpty()) {
            return if (rawRequirements.isEmpty()) null else authorityMismatch()
        }
        if (rawRequirements.isEmpty()) return authorityMismatch()

        val authorities = mutableListOf<RawAuthority>()
        for (requirement in rawRequirements) {
            val placeholders = requirement.provenance.evidence.filterIsInstance<InputEvidence.Placeholder>()
            if (placeholders.any { it.kind != InputKind.RAW_INTERPOLATION }) return authorityMismatch()
            val rawPlaceholders = placeholders.filter { it.kind == InputKind.RAW_INTERPOLATION }
            if (rawPlaceholders.isEmpty()) return authorityMismatch()
            if (requirement.expectedType.javaTypeIdentity?.value?.trim() !in supportedStringTypes) {
                return unsupported(EXPRESSION_UNSUPPORTED)
            }
            rawPlaceholders.forEach { evidence ->
                authorities += RawAuthority(requirement.id, evidence.expression.trim())
            }
        }

        if (counts(sourceExpressions) != counts(authorities.map { it.expression })) {
            return authorityMismatch()
        }

        val aliasesByName = request.inputEnvironment.aliases.groupBy { it.name }
        val requirementByExpression = linkedMapOf<String, InputRequirementId>()
        for (expression in sourceExpressions.distinct()) {
            if (!aliasName.matches(expression)) return unsupported(EXPRESSION_UNSUPPORTED)

            val candidateIds = authorities
                .asSequence()
                .filter { it.expression == expression }
                .map { it.requirementId }
                .distinct()
                .toList()
            if (candidateIds.size != 1) return authorityMismatch()

            val requirementId = candidateIds.single()
            val aliases = aliasesByName[expression].orEmpty()
            if (aliases.size != 1 || aliases.single().requirementId != requirementId) return authorityMismatch()
            requirementByExpression[expression] = requirementId
        }

        // A raw slot already inside a source `#{...}` could change its property or mapping options and
        // is never admitted.
        val sourceBoundPayloads = boundPayloads(structuralSql)
        if (sourceBoundPayloads.any { payload -> rawSlotMarkers.any { marker -> payload.contains(marker) } }) {
            return unsupported(BOUND_CONTEXT_UNSUPPORTED)
        }

        val rawValuesByRequirement = linkedMapOf<InputRequirementId, String>()
        for (requirementId in requirementByExpression.values.toSet()) {
            val provided = request.inputEnvironment.value(requirementId) ?: return authorityMismatch()
            val raw = (provided.value as? InputValue.RawText)?.value ?: return authorityMismatch()
            rawValuesByRequirement[requirementId] = raw
        }

        // Give every source bound token a unique analysis-only identity while preserving its `#{...}`
        // opener. After raw substitution, MyBatis's own tokenizer must still observe exactly these
        // marker-bearing source tokens in the same order. A raw-created token is unmarked; a removed,
        // escaped, replaced, or truncated source token loses its marker, so either case fails closed.
        val expectedLabeledPayloads = mutableListOf<String>()
        var boundIndex = 0
        val labeledStructuralSql = GenericTokenParser(boundOpen, "}") { content ->
            val marker = "\u0001zmybatis_bound_${boundIndex++}\u0001"
            val labeledPayload = content + marker
            expectedLabeledPayloads += labeledPayload
            boundOpen + labeledPayload + "}"
        }.parse(structuralSql)

        // Preserve only characters that can affect GenericTokenParser's `#{...}` structure. All other
        // raw characters become inert sentinels. This is not SQL rendering or OGNL evaluation; it is a
        // structural proof over the same token parser MyBatis uses for parameter mappings.
        var projectedSql = labeledStructuralSql
        for (index in rawSlotMarkers.indices) {
            val expression = sourceExpressions[index]
            val requirementId = requirementByExpression[expression] ?: return authorityMismatch()
            val raw = rawValuesByRequirement[requirementId] ?: return authorityMismatch()
            projectedSql = projectedSql.replace(rawSlotMarkers[index], projectBoundTokenMetasyntax(raw))
        }
        if (boundPayloads(projectedSql) != expectedLabeledPayloads) {
            return unsupported(TOKEN_TOPOLOGY_UNSUPPORTED)
        }

        return null
    }

    private fun boundPayloads(sql: String): List<String> {
        val payloads = mutableListOf<String>()
        GenericTokenParser(boundOpen, "}") { content ->
            payloads += content
            "?"
        }.parse(sql)
        return payloads
    }

    private fun projectBoundTokenMetasyntax(raw: String): String = buildString(raw.length) {
        raw.forEach { character ->
            append(
                when (character) {
                    '#', '{', '}', '\\' -> character
                    else -> 'x'
                },
            )
        }
    }

    private fun counts(values: List<String>): Map<String, Int> = values.groupingBy { it }.eachCount()

    private fun authorityMismatch() = PreparationFailure(
        PreparationFailureKind.PREPARATION_INVARIANT,
        AUTHORITY_MISMATCH,
    )

    private fun unsupported(code: String) = PreparationFailure(
        PreparationFailureKind.UNSUPPORTED_SEMANTIC,
        code,
    )

    private data class RawAuthority(
        val requirementId: InputRequirementId,
        val expression: String,
    )
}
