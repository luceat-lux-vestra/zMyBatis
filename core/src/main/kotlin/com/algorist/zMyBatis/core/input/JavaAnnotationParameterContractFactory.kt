package com.algorist.zMyBatis.core.input

import com.algorist.zMyBatis.core.source.JavaAnnotationStatementCapture
import com.algorist.zMyBatis.core.source.JavaMethodParameterMetadata
import com.algorist.zMyBatis.core.source.JavaStatementId
import com.algorist.zMyBatis.core.source.JavaTypeIdentity
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision

/**
 * Builds the statically provable Java direct-annotation subset of the #63 input contract.
 *
 * This factory deliberately refuses dynamic `<script>` input discovery, generated MyBatis aliases,
 * source-name aliases, single-parameter shortcuts, dependency-backed SQL segments, escaped tokens,
 * and complex OGNL. Those semantics require richer source provenance and the later #63/#64 MyBatis
 * boundary rather than a second approximate evaluator in :core.
 */
object JavaAnnotationParameterContractFactory {
    private const val DYNAMIC_SCRIPT_PROBLEM = "java-annotation-dynamic-script-input-discovery-unsupported"
    private const val DEPENDENT_SEGMENT_PROVENANCE_PROBLEM =
        "java-annotation-dependent-segment-provenance-unsupported"
    private const val MALFORMED_PLACEHOLDER_PROBLEM = "java-annotation-malformed-placeholder"
    private const val COMPLEX_PLACEHOLDER_PROBLEM = "java-annotation-complex-placeholder-expression"
    private const val ESCAPED_PLACEHOLDER_PROBLEM = "java-annotation-escaped-placeholder-unsupported"
    private const val UNRESOLVED_ROOT_PROBLEM = "java-annotation-unresolved-explicit-param-root"
    private const val DUPLICATE_ALIAS_PROBLEM = "java-annotation-duplicate-explicit-param-alias"
    private const val RESERVED_ALIAS_PROBLEM = "java-annotation-reserved-internal-alias"
    private const val MIXED_KIND_PROBLEM = "java-annotation-mixed-raw-bound-input"
    private const val UNPROVEN_SHAPE_PROBLEM = "java-annotation-unproven-parameter-shape"
    private const val RAW_NON_STRING_PROBLEM = "java-annotation-raw-non-string-parameter"
    private const val SCALAR_NESTED_PATH_PROBLEM = "java-annotation-scalar-nested-placeholder-path"

    private val simplePath = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")
    private val reservedInternalAliases = setOf("_parameter", "_databaseId")

    fun build(capture: JavaAnnotationStatementCapture): ParameterContract {
        val statement = capture.sourceGraph.rootStatement
        val statementId = statement.id as JavaStatementId
        val sourceRevisions = capture.sourceGraph.sourceSnapshots.associate { it.fileId to it.revision }
        val rootRevision = sourceRevisions.getValue(statementId.sourceFileId)
        val statementSource = SourceEvidence(statementId.sourceFileId, rootRevision, statement.sourceRange)

        if (capture.sourceGraph.dependencies.isNotEmpty()) {
            return blockedContract(
                statementId,
                sourceRevisions,
                InputContractProblemKind.UNSUPPORTED,
                DEPENDENT_SEGMENT_PROVENANCE_PROBLEM,
            )
        }

        if (capture.sqlSegments.any(::containsDynamicScript)) {
            return blockedContract(
                statementId,
                sourceRevisions,
                InputContractProblemKind.UNSUPPORTED,
                DYNAMIC_SCRIPT_PROBLEM,
            )
        }

        val scan = scan(capture.sqlSegments)
        if (scan.failures.isNotEmpty()) {
            return ParameterContract(
                statementId = statementId,
                requirements = emptyList(),
                aliases = emptyList(),
                internalBindings = emptyList(),
                blockingProblems = scan.failures.map { failure ->
                    InputContractProblem(
                        kind = if (failure.problemCode == MALFORMED_PLACEHOLDER_PROBLEM) {
                            InputContractProblemKind.UNKNOWN
                        } else {
                            InputContractProblemKind.UNSUPPORTED
                        },
                        code = failure.problemCode,
                        requirementId = null,
                        provenance = failure.expression?.let { expression ->
                            InputProvenance(
                                listOf(InputEvidence.Placeholder(failure.kind, expression, statementSource)),
                            )
                        },
                    )
                },
                sourceRevisions = sourceRevisions,
            )
        }

        val parametersByAlias = capture.parameters
            .mapNotNull { parameter -> parameter.myBatisParamAlias?.let { alias -> alias to parameter } }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })

        val problems = mutableListOf<InputContractProblem>()
        val usesByParameter = linkedMapOf<Int, MutableList<PlaceholderUse>>()

        scan.uses.forEach { use ->
            val root = use.expression.substringBefore('.')
            if (root in reservedInternalAliases) {
                problems += problemForUse(
                    InputContractProblemKind.UNSUPPORTED,
                    RESERVED_ALIAS_PROBLEM,
                    use,
                    statementSource,
                )
                return@forEach
            }

            val candidates = parametersByAlias[root].orEmpty()
            when (candidates.size) {
                0 -> problems += problemForUse(
                    InputContractProblemKind.UNKNOWN,
                    UNRESOLVED_ROOT_PROBLEM,
                    use,
                    statementSource,
                )
                1 -> usesByParameter.getOrPut(candidates.single().index) { mutableListOf() } += use
                else -> problems += problemForUse(
                    InputContractProblemKind.AMBIGUOUS,
                    DUPLICATE_ALIAS_PROBLEM,
                    use,
                    statementSource,
                )
            }
        }

        val requirements = mutableListOf<InputRequirement>()
        val aliases = mutableListOf<InputAlias>()

        capture.parameters.forEach { parameter ->
            val uses = usesByParameter[parameter.index].orEmpty()
            if (uses.isEmpty()) return@forEach

            val alias = parameter.myBatisParamAlias
                ?: error("placeholder use can only be resolved through an explicit @Param alias")
            if (parametersByAlias.getValue(alias).size != 1) return@forEach

            val evidence = buildEvidence(parameter, uses, statementSource)
            val provenance = InputProvenance(evidence)
            val kinds = uses.mapTo(linkedSetOf()) { it.kind }
            if (kinds.size != 1) {
                problems += InputContractProblem(
                    InputContractProblemKind.AMBIGUOUS,
                    MIXED_KIND_PROBLEM,
                    null,
                    provenance,
                )
                return@forEach
            }

            val kind = kinds.single()
            val requirementId = InputRequirementId("java-param:${parameter.index}")
            val expectedType = JavaParameterTypeContract.expectedType(parameter.typeIdentity, kind)
            val requirement = InputRequirement(
                id = requirementId,
                kind = kind,
                expectedType = expectedType,
                requiredness = InputRequiredness.REQUIRED,
                provenance = provenance,
            )
            requirements += requirement
            aliases += InputAlias(
                name = alias,
                requirementId = requirementId,
                kind = InputAliasKind.EXPLICIT_PARAM,
                provenance = InputProvenance(
                    listOf(
                        InputEvidence.MapperMethodParameter(
                            parameter.index,
                            parameter.sourceName,
                            parameter.typeIdentity,
                            statementSource,
                        ),
                        InputEvidence.ExplicitParamAlias(parameter.index, alias, statementSource),
                    ),
                ),
            )

            if (kind == InputKind.RAW_INTERPOLATION && !JavaParameterTypeContract.isString(parameter.typeIdentity)) {
                problems += InputContractProblem(
                    InputContractProblemKind.UNSUPPORTED,
                    RAW_NON_STRING_PROBLEM,
                    requirementId,
                    provenance,
                )
            }
            if (expectedType.shape == InputShape.UNKNOWN) {
                problems += InputContractProblem(
                    InputContractProblemKind.UNSUPPORTED,
                    UNPROVEN_SHAPE_PROBLEM,
                    requirementId,
                    provenance,
                )
            }
            if (
                kind == InputKind.BOUND &&
                expectedType.shape in setOf(InputShape.SCALAR, InputShape.TEMPORAL) &&
                uses.any { '.' in it.expression }
            ) {
                problems += InputContractProblem(
                    InputContractProblemKind.UNSUPPORTED,
                    SCALAR_NESTED_PATH_PROBLEM,
                    requirementId,
                    provenance,
                )
            }
        }

        return ParameterContract(
            statementId = statementId,
            requirements = requirements,
            aliases = aliases,
            internalBindings = emptyList(),
            blockingProblems = problems,
            sourceRevisions = sourceRevisions,
        )
    }

    private fun buildEvidence(
        parameter: JavaMethodParameterMetadata,
        uses: List<PlaceholderUse>,
        source: SourceEvidence,
    ): List<InputEvidence> = buildList {
        add(
            InputEvidence.MapperMethodParameter(
                parameter.index,
                parameter.sourceName,
                parameter.typeIdentity,
                source,
            ),
        )
        parameter.myBatisParamAlias?.let { alias ->
            add(InputEvidence.ExplicitParamAlias(parameter.index, alias, source))
        }
        uses.forEach { use ->
            add(InputEvidence.Placeholder(use.kind, use.expression, source))
        }
    }

    private fun scan(segments: List<String>): PlaceholderScan {
        val uses = mutableListOf<PlaceholderUse>()
        val failures = mutableListOf<PlaceholderScanFailure>()

        segments.forEach { segment ->
            var cursor = 0
            while (cursor < segment.length - 1) {
                val kind = when {
                    segment[cursor] == '#' && segment[cursor + 1] == '{' -> InputKind.BOUND
                    segment[cursor] == '$' && segment[cursor + 1] == '{' -> InputKind.RAW_INTERPOLATION
                    else -> {
                        cursor++
                        continue
                    }
                }
                val escaped = cursor > 0 && segment[cursor - 1] == '\\'
                val end = segment.indexOf('}', startIndex = cursor + 2)
                if (end < 0) {
                    failures += PlaceholderScanFailure(kind, null, MALFORMED_PLACEHOLDER_PROBLEM)
                    break
                }

                val payload = segment.substring(cursor + 2, end).trim()
                val expression = if (kind == InputKind.BOUND) payload.substringBefore(',').trim() else payload
                when {
                    escaped -> failures += PlaceholderScanFailure(
                        kind,
                        expression.takeIf(String::isNotEmpty),
                        ESCAPED_PLACEHOLDER_PROBLEM,
                    )
                    expression.isEmpty() || !simplePath.matches(expression) -> failures += PlaceholderScanFailure(
                        kind,
                        expression.takeIf(String::isNotEmpty),
                        COMPLEX_PLACEHOLDER_PROBLEM,
                    )
                    else -> uses += PlaceholderUse(kind, expression)
                }
                cursor = end + 1
            }
        }
        return PlaceholderScan(uses, failures)
    }

    private fun containsDynamicScript(sql: String): Boolean = sql.indexOf("<script", ignoreCase = true) >= 0

    private fun problemForUse(
        kind: InputContractProblemKind,
        code: String,
        use: PlaceholderUse,
        source: SourceEvidence,
    ) = InputContractProblem(
        kind,
        code,
        null,
        InputProvenance(listOf(InputEvidence.Placeholder(use.kind, use.expression, source))),
    )

    private fun blockedContract(
        statementId: JavaStatementId,
        sourceRevisions: Map<SourceFileId, SourceRevision>,
        kind: InputContractProblemKind,
        code: String,
    ) = ParameterContract(
        statementId,
        requirements = emptyList(),
        aliases = emptyList(),
        internalBindings = emptyList(),
        blockingProblems = listOf(InputContractProblem(kind, code, null, null)),
        sourceRevisions = sourceRevisions,
    )

    private data class PlaceholderUse(val kind: InputKind, val expression: String)

    private data class PlaceholderScanFailure(
        val kind: InputKind,
        val expression: String?,
        val problemCode: String,
    )

    private data class PlaceholderScan(
        val uses: List<PlaceholderUse>,
        val failures: List<PlaceholderScanFailure>,
    )
}
