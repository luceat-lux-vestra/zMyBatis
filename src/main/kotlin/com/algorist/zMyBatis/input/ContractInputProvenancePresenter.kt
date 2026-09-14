package com.algorist.zMyBatis.input

import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.SourceEvidence

internal data class ContractInputProvenancePresentation(
    val summary: String,
    val details: String,
)

/**
 * Presentation-only formatting of already-proven input provenance.
 *
 * This adapter never infers input meaning or source identity. It only renders evidence that is
 * already carried by [InputProvenance], including source file/range information when available.
 */
internal object ContractInputProvenancePresenter {
    fun present(provenance: InputProvenance): ContractInputProvenancePresentation {
        val rendered = provenance.evidence.map(::render)
        val primary = rendered.firstOrNull { it.sourceBacked } ?: rendered.first()
        val summary = buildString {
            append(primary.text)
            if (rendered.size > 1) {
                append(" (+${rendered.size - 1} more)")
            }
        }
        return ContractInputProvenancePresentation(
            summary = summary,
            details = rendered.joinToString("; ") { it.text },
        )
    }

    private data class RenderedEvidence(
        val text: String,
        val sourceBacked: Boolean,
    )

    private fun render(evidence: InputEvidence): RenderedEvidence {
        val description = when (evidence) {
            is InputEvidence.MapperMethodParameter -> buildString {
                append("mapper parameter #${evidence.index}")
                evidence.sourceName?.let { append(" ($it)") }
                append(": ${evidence.typeIdentity.value}")
            }
            is InputEvidence.ExplicitParamAlias -> "@Param(\"${evidence.alias}\")"
            is InputEvidence.GeneratedAlias -> "generated alias ${evidence.alias} (${evidence.ruleId})"
            is InputEvidence.Placeholder -> "${evidence.kind}: ${evidence.expression}"
            is InputEvidence.OgnlExpression -> "OGNL: ${evidence.expression}"
            is InputEvidence.ForeachCollection -> "foreach collection: ${evidence.expression}"
            is InputEvidence.ForeachLocal -> "foreach ${evidence.role}: ${evidence.name}"
            is InputEvidence.BindLocal -> "bind ${evidence.name}: ${evidence.expression}"
        }
        val source = sourceOf(evidence)
        return RenderedEvidence(
            text = source?.let { "$description @ ${formatSource(it)}" } ?: description,
            sourceBacked = source != null,
        )
    }

    private fun sourceOf(evidence: InputEvidence): SourceEvidence? = when (evidence) {
        is InputEvidence.MapperMethodParameter -> evidence.source
        is InputEvidence.ExplicitParamAlias -> evidence.source
        is InputEvidence.GeneratedAlias -> null
        is InputEvidence.Placeholder -> evidence.source
        is InputEvidence.OgnlExpression -> evidence.source
        is InputEvidence.ForeachCollection -> evidence.source
        is InputEvidence.ForeachLocal -> evidence.source
        is InputEvidence.BindLocal -> evidence.source
    }

    private fun formatSource(source: SourceEvidence): String = buildString {
        append(source.sourceFileId.value)
        source.sourceRange?.let { range ->
            append("[${range.startOffset},${range.endOffsetExclusive})")
        }
    }
}
