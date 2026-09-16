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
    private val reservedContextNames = setOf("_parameter", "_databaseId")

    fun failureOrNull(
        script: String,
        request: MyBatisPreparationRequest,
    ): PreparationFailure? {
        val rawRequirements = request.parameterContract.rawRequirements()

        val rawSlotSentinels = unusedPrivateUseCharacters(listOf(script), count = 2)
            ?: return unsupported(TOKEN_TOPOLOGY_UNSUPPORTED)
        val rawSlotOpen = rawSlotSentinels[0].toString()
        val rawSlotClose = rawSlotSentinels[1].toString()
        val sourceExpressions = mutableListOf<String>()
        var rawSlotIndex = 0

        // Run the same raw-token parser MyBatis uses. Escaped or malformed `${...}` remains literal
        // source text and is not mistaken for an executable raw requirement. Recognized raw slots are
        // replaced by inert analysis sentinels so the later bound-token proof can preserve their exact
        // source positions without repeatedly rewriting the whole SQL string.
        val structuralRawSql = GenericTokenParser(rawOpen, "}") { content ->
            sourceExpressions += content.trim()
            rawSlotOpen + rawSlotIndex++ + rawSlotClose
        }.parse(script)

        if (sourceExpressions.isEmpty()) {
            return if (rawRequirements.isEmpty()) null else authorityMismatch()
        }
        if (rawRequirements.isEmpty()) return authorityMismatch()

        val authorities = mutableListOf<RawAuthority>()
        for ((requirementId, _, expectedType, _, provenance) in rawRequirements) {
            val placeholders = provenance.evidence.filterIsInstance<InputEvidence.Placeholder>()
            if (placeholders.any { it.kind != InputKind.RAW_INTERPOLATION }) return authorityMismatch()
            val rawPlaceholders = placeholders.filter { it.kind == InputKind.RAW_INTERPOLATION }
            if (rawPlaceholders.isEmpty()) return authorityMismatch()
            if (expectedType.javaTypeIdentity?.value?.trim() !in supportedStringTypes) {
                return unsupported(EXPRESSION_UNSUPPORTED)
            }
            rawPlaceholders.forEach { evidence ->
                authorities += RawAuthority(requirementId, evidence.expression.trim())
            }
        }

        if (counts(sourceExpressions) != counts(authorities.map { it.expression })) {
            return authorityMismatch()
        }

        val aliasesByName = request.inputEnvironment.aliases.groupBy { it.name }
        val requirementByExpression = linkedMapOf<String, InputRequirementId>()
        for (expression in sourceExpressions.distinct()) {
            if (!aliasName.matches(expression) || expression in reservedContextNames) {
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
            val aliases = aliasesByName[expression].orEmpty()
            if (aliases.size != 1 || aliases.single().requirementId != requirementId) return authorityMismatch()
            requirementByExpression[expression] = requirementId
        }

        val rawValuesByRequirement = linkedMapOf<InputRequirementId, String>()
        for (requirementId in requirementByExpression.values.toSet()) {
            val provided = request.inputEnvironment.value(requirementId) ?: return authorityMismatch()
            val raw = (provided.value as? InputValue.RawText)?.value ?: return authorityMismatch()
            rawValuesByRequirement[requirementId] = raw
        }

        // Select analysis characters that cannot be forged by either authoritative source text or a
        // raw value. Private-use characters are analysis-only and never reach MyBatis execution.
        val proofSentinels = unusedPrivateUseCharacters(
            listOf(script, structuralRawSql) + rawValuesByRequirement.values,
            count = 3,
        ) ?: return unsupported(TOKEN_TOPOLOGY_UNSUPPORTED)
        val escapedBoundOpen = proofSentinels[0].toString()
        val escapedBoundClose = proofSentinels[1].toString()
        val boundCanary = proofSentinels[2]

        // GenericTokenParser removes the escape backslash when it encounters an escaped opener/closer.
        // Protect those source forms before replacing authoritative source bindings with canaries, so
        // the final verification pass cannot mistake the parser's analysis output for a newly created
        // bound token.
        val protectedStructuralSql = structuralRawSql
            .replace("\\#{", escapedBoundOpen)
            .replace("\\}", escapedBoundClose)

        val expectedCanaryPayloads = mutableListOf<String>()
        var boundIndex = 0
        var rawInsideSourceBound = false
        val canaryStructuralSql = GenericTokenParser(boundOpen, "}") { content ->
            if (content.contains(rawSlotOpen)) rawInsideSourceBound = true
            val payload = "$boundCanary${boundIndex++}$boundCanary"
            expectedCanaryPayloads += payload
            "$boundOpen$payload}"
        }.parse(protectedStructuralSql)
        if (rawInsideSourceBound) return unsupported(BOUND_CONTEXT_UNSUPPORTED)

        // Substitute every recognized source raw slot exactly once in a single parser pass. Handler
        // output is not recursively tokenized, matching GenericTokenParser/MyBatis behavior even when
        // a raw value itself contains the analysis delimiter characters.
        var slotOccurrence = 0
        var slotFailure = false
        val projectedSql = GenericTokenParser(rawSlotOpen, rawSlotClose) { content ->
            val index = content.toIntOrNull()
            if (
                slotFailure ||
                index == null ||
                index != slotOccurrence ||
                index !in sourceExpressions.indices
            ) {
                slotFailure = true
                ""
            } else {
                val expression = sourceExpressions[index]
                val requirementId = requirementByExpression[expression]
                val raw = requirementId?.let(rawValuesByRequirement::get)
                if (raw == null) {
                    slotFailure = true
                    ""
                } else {
                    slotOccurrence++
                    projectBoundTokenMetasyntax(raw)
                }
            }
        }.parse(canaryStructuralSql)
        if (
            slotFailure ||
            slotOccurrence != sourceExpressions.size ||
            projectedSql.contains(rawSlotOpen)
        ) {
            return authorityMismatch()
        }

        // At this point every authoritative source binding has become a unique, unforgeable canary.
        // MyBatis's own bound-token parser must observe exactly those canaries, in order, and nothing
        // else. A raw-created token adds an unmarked payload; an escaped/deleted/mutated source token
        // removes or changes a canary, and a cross-boundary token changes the sequence as well.
        if (boundPayloads(projectedSql) != expectedCanaryPayloads) {
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

    private fun projectBoundTokenMetasyntax(raw: String): String = buildString {
        var inertRun = false
        raw.forEach { character ->
            if (character == '#' || character == '{' || character == '}' || character == '\\') {
                append(character)
                inertRun = false
            } else if (!inertRun) {
                append('x')
                inertRun = true
            }
        }
    }

    private fun unusedPrivateUseCharacters(
        forbidden: Collection<String>,
        count: Int,
    ): List<Char>? {
        val used = BooleanArray(PRIVATE_USE_END - PRIVATE_USE_START + 1)
        forbidden.forEach { text ->
            text.forEach { character ->
                val code = character.code
                if (code in PRIVATE_USE_START..PRIVATE_USE_END) {
                    used[code - PRIVATE_USE_START] = true
                }
            }
        }

        val result = ArrayList<Char>(count)
        for (offset in used.indices) {
            if (!used[offset]) {
                result += (PRIVATE_USE_START + offset).toChar()
                if (result.size == count) return result
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

    private const val PRIVATE_USE_START = 0xE000
    private const val PRIVATE_USE_END = 0xF8FF
}
