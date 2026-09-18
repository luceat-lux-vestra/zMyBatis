package com.algorist.zMyBatis.core.input

import com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.StatementSourceGraph
import com.algorist.zMyBatis.core.source.XmlMapperMethodCapture
import com.algorist.zMyBatis.core.source.XmlStatementId

/**
 * Widens the proven XML placeholder contract only when matching authoritative mapper-method
 * metadata proves an explicit @Param alias. Source names and generated MyBatis aliases are not
 * caller authority in this slice.
 */
object XmlMapperMethodParameterContractFactory {
    private const val CALLER_AUTHORITY_PROBLEM = "xml-caller-input-authority-unproven"
    private const val AUTHORITY_MISMATCH_PROBLEM = "xml-mapper-method-authority-mismatch"
    private const val SOURCE_REVISION_CONFLICT_PROBLEM = "xml-mapper-method-source-revision-conflict"
    private const val DUPLICATE_ALIAS_PROBLEM = "xml-duplicate-explicit-param-alias"
    private const val UNPROVEN_SHAPE_PROBLEM = "xml-unproven-parameter-shape"
    private const val RAW_NON_STRING_PROBLEM = "xml-raw-non-string-parameter"

    fun build(
        graph: StatementSourceGraph,
        mapperMethod: XmlMapperMethodCapture,
    ): ParameterContract {
        val baseline = XmlStatementParameterContractFactory.build(graph)
        val authorityProblems = baseline.blockingProblems.filter { it.code == CALLER_AUTHORITY_PROBLEM }
        if (authorityProblems.isEmpty()) {
            return baseline
        }

        val statementId = baseline.statementId as XmlStatementId
        val mergedRevisions = mergeSourceRevisions(
            baseline.sourceRevisions,
            mapperMethod,
        ) ?: return blockedFromBaseline(
            baseline = baseline,
            code = SOURCE_REVISION_CONFLICT_PROBLEM,
        )

        if (mapperMethod.statementId != statementId) {
            return ParameterContract(
                statementId = statementId,
                requirements = emptyList(),
                aliases = emptyList(),
                internalBindings = baseline.internalBindings,
                blockingProblems = baseline.blockingProblems
                    .filterNot { it.code == CALLER_AUTHORITY_PROBLEM } +
                    InputContractProblem(
                        kind = InputContractProblemKind.UNKNOWN,
                        code = AUTHORITY_MISMATCH_PROBLEM,
                        requirementId = null,
                        provenance = null,
                    ),
                sourceRevisions = mergedRevisions,
            )
        }

        val mapperSource = SourceEvidence(
            sourceFileId = mapperMethod.mapperSource.fileId,
            sourceRevision = mapperMethod.mapperSource.revision,
            sourceRange = mapperMethod.methodSourceRange,
        )
        val parametersByAlias = mapperMethod.parameters
            .mapNotNull { parameter -> parameter.myBatisParamAlias?.let { alias -> alias to parameter } }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })

        val requirements = mutableListOf<InputRequirement>()
        val aliases = mutableListOf<InputAlias>()
        val problems = baseline.blockingProblems
            .filterNot { it.code == CALLER_AUTHORITY_PROBLEM }
            .toMutableList()

        authorityProblems.forEach { authorityProblem ->
            val placeholders = authorityProblem.provenance
                ?.evidence
                ?.filterIsInstance<InputEvidence.Placeholder>()
                .orEmpty()
            val root = placeholders.map { it.expression }.distinct().singleOrNull()
                ?: run {
                    problems += authorityProblem
                    return@forEach
                }
            val kind = placeholders.map { it.kind }.distinct().singleOrNull()
                ?: run {
                    problems += authorityProblem
                    return@forEach
                }

            val candidates = parametersByAlias[root].orEmpty()
            when (candidates.size) {
                0 -> problems += authorityProblem
                1 -> {
                    val parameter = candidates.single()
                    val requirementId = InputRequirementId("xml-java-param:${parameter.index}")
                    val evidence = mapperEvidence(parameter, mapperSource) + placeholders
                    val provenance = InputProvenance(evidence)
                    val expectedType = JavaParameterTypeContract.expectedType(parameter.typeIdentity, kind)

                    requirements += InputRequirement(
                        id = requirementId,
                        kind = kind,
                        expectedType = expectedType,
                        requiredness = InputRequiredness.REQUIRED,
                        provenance = provenance,
                    )
                    aliases += InputAlias(
                        name = root,
                        requirementId = requirementId,
                        kind = InputAliasKind.EXPLICIT_PARAM,
                        provenance = InputProvenance(mapperEvidence(parameter, mapperSource)),
                    )

                    if (
                        kind == InputKind.RAW_INTERPOLATION &&
                        !JavaParameterTypeContract.isString(parameter.typeIdentity)
                    ) {
                        problems += InputContractProblem(
                            kind = InputContractProblemKind.UNSUPPORTED,
                            code = RAW_NON_STRING_PROBLEM,
                            requirementId = requirementId,
                            provenance = provenance,
                        )
                    }
                    if (expectedType.shape == InputShape.UNKNOWN) {
                        problems += InputContractProblem(
                            kind = InputContractProblemKind.UNSUPPORTED,
                            code = UNPROVEN_SHAPE_PROBLEM,
                            requirementId = requirementId,
                            provenance = provenance,
                        )
                    }
                }
                else -> {
                    val evidence = buildList {
                        candidates.forEach { parameter ->
                            addAll(mapperEvidence(parameter, mapperSource))
                        }
                        addAll(placeholders)
                    }
                    problems += InputContractProblem(
                        kind = InputContractProblemKind.AMBIGUOUS,
                        code = DUPLICATE_ALIAS_PROBLEM,
                        requirementId = null,
                        provenance = InputProvenance(evidence),
                    )
                }
            }
        }

        return ParameterContract(
            statementId = statementId,
            requirements = requirements,
            aliases = aliases,
            internalBindings = baseline.internalBindings,
            blockingProblems = problems,
            sourceRevisions = mergedRevisions,
        )
    }

    private fun mapperEvidence(
        parameter: JavaMethodParameterMetadata,
        source: SourceEvidence,
    ): List<InputEvidence> = listOf(
        InputEvidence.MapperMethodParameter(
            index = parameter.index,
            sourceName = parameter.sourceName,
            typeIdentity = parameter.typeIdentity,
            source = source,
        ),
        InputEvidence.ExplicitParamAlias(
            parameterIndex = parameter.index,
            alias = requireNotNull(parameter.myBatisParamAlias),
            source = source,
        ),
    )

    private fun mergeSourceRevisions(
        baseline: Map<SourceFileId, SourceRevision>,
        mapperMethod: XmlMapperMethodCapture,
    ): Map<SourceFileId, SourceRevision>? {
        val existing = baseline[mapperMethod.mapperSource.fileId]
        if (existing != null && existing != mapperMethod.mapperSource.revision) {
            return null
        }
        return baseline + (mapperMethod.mapperSource.fileId to mapperMethod.mapperSource.revision)
    }

    private fun blockedFromBaseline(
        baseline: ParameterContract,
        code: String,
    ): ParameterContract = ParameterContract(
        statementId = baseline.statementId,
        requirements = emptyList(),
        aliases = emptyList(),
        internalBindings = baseline.internalBindings,
        blockingProblems = baseline.blockingProblems
            .filterNot { it.code == CALLER_AUTHORITY_PROBLEM } +
            InputContractProblem(
                kind = InputContractProblemKind.UNKNOWN,
                code = code,
                requirementId = null,
                provenance = null,
            ),
        sourceRevisions = baseline.sourceRevisions,
    )
}
