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
 * MyBatis renders `${...}` first and only then parses the rendered text for `#{...}` mappings.  A raw
 * value must therefore be proven unable to synthesize a new bound token or mutate an existing bound
 * token before MyBatis is allowed to evaluate the raw expression.
 */
internal object StaticRawSubstitutionAdmission {
    private const val AUTHORITY_MISMATCH = "raw-interpolation-source-authority-mismatch"
    private const val EXPRESSION_UNSUPPORTED = "raw-interpolation-expression-unsupported"
    private const val BOUND_CONTEXT_UNSUPPORTED = "raw-interpolation-bound-context-unsupported"
    private const val TOKEN_SYNTHESIS_UNSUPPORTED = "raw-interpolation-mybatis-token-synthesis-unsupported"

    private val rawOpen = String(charArrayOf('$', '{'))
    private val boundOpen = String(charArrayOf('#', '{'))
    private val simplePath = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")
    private val supportedStringTypes = setOf("java.lang.String", "String")

    fun failureOrNull(
        script: String,
        request: MyBatisPreparationRequest,
    ): PreparationFailure? {
        val sourceExpressions = mutableListOf<String>()
        val slotMarkers = mutableListOf<String>()
        var slotIndex = 0
        val structuralSql = GenericTokenParser(rawOpen, "}") { content ->
            sourceExpressions += content.trim()
            val marker = "\u0000zmybatis_raw_slot_${slotIndex++}\u0000"
            slotMarkers += marker
            marker
        }.parse(script)

        val rawRequirements = request.parameterContract.rawRequirements()

        // Escaped or malformed `${...}` is intentionally outside the statically proven subset.  The
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

        val aliasesByName = request.inputEnvironment.aliases.associateBy { it.name }
        val requirementByExpression = linkedMapOf<String, InputRequirementId>()
        for (expression in sourceExpressions.distinct()) {
            if (!simplePath.matches(expression) || expression.split('.').any { it == "class" }) {
                return unsupported(EXPRESSION_UNSUPPORTED)
            }
            val candidateIds = authorities
                .asSequence()
                .filter { it.expression == expression }
                .map { it.requirementId }
                .distinct()
                .toList()
            if (candidateIds.size != 1) return authorityMismatch()

            val requirementId = candidateIds.single()
            val root = expression.substringBefore('.')
            val alias = aliasesByName[root] ?: return authorityMismatch()
            if (alias.requirementId != requirementId) return authorityMismatch()
            requirementByExpression[expression] = requirementId
        }

        // Reuse MyBatis's own bound-token tokenizer over a non-evaluated structural skeleton.  If a
        // raw slot is already inside a source `#{...}`, its value could inject typeHandler/javaType
        // options into that mapping and is therefore not admitted.
        var rawInsideBoundToken = false
        GenericTokenParser(boundOpen, "}") { content ->
            if (slotMarkers.any(content::contains)) rawInsideBoundToken = true
            ""
        }.parse(structuralSql)
        if (rawInsideBoundToken) return unsupported(BOUND_CONTEXT_UNSUPPORTED)

        for (requirementId in requirementByExpression.values.toSet()) {
            val provided = request.inputEnvironment.value(requirementId) ?: return authorityMismatch()
            val raw = (provided.value as? InputValue.RawText)?.value ?: return authorityMismatch()

            // A non-empty replacement that contains neither half of the `#{` opener cannot create a
            // new bound token across either substitution boundary.  Existing source bound tokens were
            // handled above, so MyBatis cannot discover a new runtime-class-bearing mapping later.
            if (raw.isEmpty() || '#' in raw || '{' in raw) {
                return unsupported(TOKEN_SYNTHESIS_UNSUPPORTED)
            }
        }

        return null
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
