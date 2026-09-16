package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.input.ExpectedInputType
import com.algorist.zMyBatis.core.input.ForeachLocalRole
import com.algorist.zMyBatis.core.input.InputAlias
import com.algorist.zMyBatis.core.input.InputAliasKind
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputNullability
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequiredness
import com.algorist.zMyBatis.core.input.InputRequirement
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputShape
import com.algorist.zMyBatis.core.input.InternalBinding
import com.algorist.zMyBatis.core.input.InternalBindingKind
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.MethodSignature
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRange
import com.algorist.zMyBatis.core.source.SourceRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeachProvenanceAdmissionTest {
    @Test
    fun missingItemAuthorityFailsBeforeEvaluation() {
        val result = ForeachProvenanceAdmission.inspect(
            foreachScript(),
            contract(locals = emptyList()),
        )

        assertFailure(
            result,
            PreparationFailureKind.BINDING_RESOLUTION,
            "mybatis-foreach-local-provenance-missing",
            "item",
        )
    }

    @Test
    fun ambiguousLocalAuthorityFailsBeforeEvaluation() {
        val result = ForeachProvenanceAdmission.inspect(
            foreachScript(),
            contract(
                locals = listOf(
                    LocalSpec("item", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM),
                    LocalSpec("item", InternalBindingKind.FOREACH_INDEX, ForeachLocalRole.INDEX),
                ),
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.BINDING_RESOLUTION,
            "mybatis-foreach-local-provenance-ambiguous",
            "item",
        )
    }

    @Test
    fun bindAuthorityCannotShadowForeachLocal() {
        val result = ForeachProvenanceAdmission.inspect(
            foreachScript(),
            contract(
                locals = listOf(LocalSpec("item", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM)),
                bindNames = listOf("item"),
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "mybatis-foreach-local-shadowing-unsupported",
            "item",
        )
    }

    @Test
    fun callerAliasCannotOccupyGeneratedForeachNamespace() {
        val result = ForeachProvenanceAdmission.inspect(
            foreachScript(),
            contract(
                locals = listOf(LocalSpec("item", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM)),
                extraAliases = listOf("__frch_forged_0"),
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "mybatis-foreach-generated-namespace-collision",
            null,
        )
    }

    @Test
    fun complexCollectionOgnlIsOutsideThisSlice() {
        val result = ForeachProvenanceAdmission.inspect(
            "<script><foreach collection=\"ids.size\" item=\"item\">#{item}</foreach></script>",
            contract(locals = listOf(LocalSpec("item", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM))),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "mybatis-foreach-collection-expression-unsupported",
            "ids.size",
        )
    }

    @Test
    fun explicitlyBlankIndexIsNotTreatedAsAbsent() {
        val result = ForeachProvenanceAdmission.inspect(
            "<script><foreach collection=\"ids\" item=\"item\" index=\"\">#{item}</foreach></script>",
            contract(locals = listOf(LocalSpec("item", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM))),
        )

        assertFailure(
            result,
            PreparationFailureKind.UNSUPPORTED_SEMANTIC,
            "mybatis-foreach-local-identifier-unsupported",
            "",
        )
    }

    @Test
    fun staleExtraForeachAuthorityCannotSurviveSourceParityCheck() {
        val result = ForeachProvenanceAdmission.inspect(
            foreachScript(),
            contract(
                locals = listOf(
                    LocalSpec("item", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM),
                    LocalSpec("stale", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.ITEM),
                ),
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.BINDING_RESOLUTION,
            "mybatis-foreach-source-contract-mismatch",
            null,
        )
    }

    @Test
    fun wrongRoleEvidenceCannotAuthorizeAnItem() {
        val result = ForeachProvenanceAdmission.inspect(
            foreachScript(),
            contract(
                locals = listOf(LocalSpec("item", InternalBindingKind.FOREACH_ITEM, ForeachLocalRole.INDEX)),
            ),
        )

        assertFailure(
            result,
            PreparationFailureKind.BINDING_RESOLUTION,
            "mybatis-foreach-source-contract-mismatch",
            "item",
        )
    }

    private fun foreachScript() =
        "<script><foreach collection=\"ids\" item=\"item\">#{item}</foreach></script>"

    private fun contract(
        locals: List<LocalSpec>,
        bindNames: List<String> = emptyList(),
        extraAliases: List<String> = emptyList(),
    ): ParameterContract {
        val fileId = SourceFileId("fixture/ForeachAdmission.java")
        val revision = SourceRevision("foreach-admission-revision")
        val source = SourceEvidence(fileId, revision, SourceRange(0, 10))
        val allAliases = listOf("ids") + extraAliases
        val requirements = allAliases.mapIndexed { index, alias ->
            InputRequirement(
                id = InputRequirementId("foreach-admission:$index"),
                kind = InputKind.BOUND,
                expectedType = ExpectedInputType(
                    shape = InputShape.LIST,
                    javaTypeIdentity = JavaTypeIdentity("java.util.List<java.lang.Long>"),
                    nullability = InputNullability.NON_NULL,
                ),
                requiredness = InputRequiredness.REQUIRED,
                provenance = InputProvenance(
                    buildList {
                        add(InputEvidence.ExplicitParamAlias(index, alias, source))
                        add(InputEvidence.OgnlExpression(alias, source))
                        if (alias == "ids") add(InputEvidence.ForeachCollection("ids", source))
                    },
                ),
            )
        }
        val aliases = allAliases.mapIndexed { index, alias ->
            InputAlias(
                name = alias,
                requirementId = requirements[index].id,
                kind = InputAliasKind.EXPLICIT_PARAM,
                provenance = requirements[index].provenance,
            )
        }
        val internalBindings = buildList {
            locals.forEach { local ->
                add(
                    InternalBinding(
                        name = local.name,
                        kind = local.kind,
                        provenance = InputProvenance(
                            listOf(InputEvidence.ForeachLocal(local.name, local.role, source)),
                        ),
                    ),
                )
            }
            bindNames.forEach { name ->
                add(
                    InternalBinding(
                        name = name,
                        kind = InternalBindingKind.BIND,
                        provenance = InputProvenance(
                            listOf(InputEvidence.BindLocal(name, "ids", source)),
                        ),
                    ),
                )
            }
        }
        val typeIdentities = allAliases.map { JavaTypeIdentity("java.util.List<java.lang.Long>") }
        val statementId = JavaStatementId(
            fileId,
            "fixture.ForeachAdmission",
            MethodSignature("query", typeIdentities),
        )
        return ParameterContract(
            statementId = statementId,
            requirements = requirements,
            aliases = aliases,
            internalBindings = internalBindings,
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(fileId to revision),
        )
    }

    private fun assertFailure(
        result: ForeachProvenanceAdmission.Result,
        kind: PreparationFailureKind,
        code: String,
        property: String?,
    ) {
        assertTrue(result is ForeachProvenanceAdmission.Result.Failed)
        result as ForeachProvenanceAdmission.Result.Failed
        assertEquals(kind, result.failure.kind)
        assertEquals(code, result.failure.code)
        assertEquals(property, result.failure.bindingProperty)
    }

    private data class LocalSpec(
        val name: String,
        val kind: InternalBindingKind,
        val role: ForeachLocalRole,
    )
}
