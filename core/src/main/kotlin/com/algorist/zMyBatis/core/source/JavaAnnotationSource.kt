package com.algorist.zMyBatis.core.source

data class JavaMethodParameterMetadata(
    val index: Int,
    val sourceName: String?,
    val typeIdentity: JavaTypeIdentity,
    val myBatisParamAlias: String?,
) {
    init {
        require(index >= 0) { "Java parameter index must not be negative" }
        require(sourceName == null || sourceName.isNotBlank()) {
            "Java parameter source name must be null or non-blank"
        }
        require(myBatisParamAlias == null || myBatisParamAlias.isNotBlank()) {
            "MyBatis @Param alias must be null or non-blank"
        }
    }
}

class JavaAnnotationStatementVariant(
    val databaseId: String?,
    val statementKind: StatementKind,
    sqlSegments: List<String>,
) {
    private val sqlSegmentSnapshot = sqlSegments.toTypedArray()

    init {
        require(databaseId == null || databaseId.isNotEmpty()) {
            "Java annotation database id must be null or non-empty"
        }
        require(sqlSegmentSnapshot.isNotEmpty()) {
            "Java annotation variant requires at least one SQL segment"
        }
    }

    val sqlSegments: List<String>
        get() = sqlSegmentSnapshot.toList()

    override fun equals(other: Any?): Boolean =
        other is JavaAnnotationStatementVariant &&
            databaseId == other.databaseId &&
            statementKind == other.statementKind &&
            sqlSegmentSnapshot.contentEquals(other.sqlSegmentSnapshot)

    override fun hashCode(): Int {
        var result = databaseId.hashCode()
        result = 31 * result + statementKind.hashCode()
        result = 31 * result + sqlSegmentSnapshot.contentHashCode()
        return result
    }
}

/**
 * Target-independent Java source capture.
 *
 * All annotation variants remain source evidence here. Target-derived database-id authority is
 * applied only by [selectJavaAnnotationStatementVariant].
 */
class JavaAnnotationStatementVariantsCapture(
    val sourceGraph: StatementSourceGraph,
    variants: List<JavaAnnotationStatementVariant>,
    parameters: List<JavaMethodParameterMetadata>,
) {
    private val variantSnapshot = variants.toTypedArray()
    private val parameterSnapshot = parameters.toTypedArray()

    init {
        validateJavaCaptureIdentity(sourceGraph, parameterSnapshot)
        require(variantSnapshot.isNotEmpty()) {
            "Java annotation variant capture requires at least one statement variant"
        }
    }

    val variants: List<JavaAnnotationStatementVariant>
        get() = variantSnapshot.toList()

    val parameters: List<JavaMethodParameterMetadata>
        get() = parameterSnapshot.toList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JavaAnnotationStatementVariantsCapture) return false
        return sourceGraph == other.sourceGraph &&
            variantSnapshot.contentEquals(other.variantSnapshot) &&
            parameterSnapshot.contentEquals(other.parameterSnapshot)
    }

    override fun hashCode(): Int {
        var result = sourceGraph.hashCode()
        result = 31 * result + variantSnapshot.contentHashCode()
        result = 31 * result + parameterSnapshot.contentHashCode()
        return result
    }
}

sealed interface JavaAnnotationStatementSelectionResult {
    data class Selected(
        val capture: JavaAnnotationStatementCapture,
    ) : JavaAnnotationStatementSelectionResult

    data object Missing : JavaAnnotationStatementSelectionResult
    data object Ambiguous : JavaAnnotationStatementSelectionResult
    data object AuthorityUnavailable : JavaAnnotationStatementSelectionResult

    data class MappingUnproven(
        val declaredDatabaseIds: List<String>,
    ) : JavaAnnotationStatementSelectionResult
}

fun selectJavaAnnotationStatementVariant(
    capture: JavaAnnotationStatementVariantsCapture,
    effectiveDatabaseId: MyBatisDatabaseId?,
): JavaAnnotationStatementSelectionResult =
    when (
        val selection = selectDatabaseIdVariant(
            capture.variants,
            effectiveDatabaseId,
            databaseIdOf = JavaAnnotationStatementVariant::databaseId,
        )
    ) {
        is DatabaseIdVariantSelection.Selected ->
            JavaAnnotationStatementSelectionResult.Selected(
                JavaAnnotationStatementCapture(
                    sourceGraph = StatementSourceGraph(
                        rootStatement = capture.sourceGraph.rootStatement.copy(
                            kind = selection.variant.statementKind,
                        ),
                        sourceSnapshots = capture.sourceGraph.sourceSnapshots,
                        dependencies = capture.sourceGraph.dependencies,
                    ),
                    sqlSegments = selection.variant.sqlSegments,
                    parameters = capture.parameters,
                    effectiveDatabaseId = effectiveDatabaseId,
                ),
            )
        DatabaseIdVariantSelection.Missing -> JavaAnnotationStatementSelectionResult.Missing
        DatabaseIdVariantSelection.Ambiguous -> JavaAnnotationStatementSelectionResult.Ambiguous
        DatabaseIdVariantSelection.AuthorityUnavailable ->
            JavaAnnotationStatementSelectionResult.AuthorityUnavailable
        is DatabaseIdVariantSelection.MappingUnproven ->
            JavaAnnotationStatementSelectionResult.MappingUnproven(selection.declaredDatabaseIds)
    }

class JavaAnnotationStatementCapture(
    val sourceGraph: StatementSourceGraph,
    sqlSegments: List<String>,
    parameters: List<JavaMethodParameterMetadata>,
    val effectiveDatabaseId: MyBatisDatabaseId? = null,
) {
    private val sqlSegmentSnapshot = sqlSegments.toTypedArray()
    private val parameterSnapshot = parameters.toTypedArray()

    init {
        validateJavaCaptureIdentity(sourceGraph, parameterSnapshot)
        require(sqlSegmentSnapshot.isNotEmpty()) {
            "Java annotation capture requires at least one SQL segment"
        }
    }

    val sqlSegments: List<String>
        get() = sqlSegmentSnapshot.toList()

    val parameters: List<JavaMethodParameterMetadata>
        get() = parameterSnapshot.toList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JavaAnnotationStatementCapture) return false

        return sourceGraph == other.sourceGraph &&
            sqlSegmentSnapshot.contentEquals(other.sqlSegmentSnapshot) &&
            parameterSnapshot.contentEquals(other.parameterSnapshot) &&
            effectiveDatabaseId == other.effectiveDatabaseId
    }

    override fun hashCode(): Int {
        var result = sourceGraph.hashCode()
        result = 31 * result + sqlSegmentSnapshot.contentHashCode()
        result = 31 * result + parameterSnapshot.contentHashCode()
        result = 31 * result + effectiveDatabaseId.hashCode()
        return result
    }
}

private fun validateJavaCaptureIdentity(
    sourceGraph: StatementSourceGraph,
    parameters: Array<JavaMethodParameterMetadata>,
) {
    val statementId = sourceGraph.rootStatement.id
    require(statementId is JavaStatementId) {
        "Java annotation capture requires a JavaStatementId root"
    }
    require(parameters.indices.all { index -> parameters[index].index == index }) {
        "Java parameter metadata must be contiguous and declaration-ordered"
    }
    require(statementId.methodSignature.parameterTypeIdentities.size == parameters.size) {
        "Java parameter metadata must match the canonical method signature arity"
    }
    require(
        statementId.methodSignature.parameterTypeIdentities.indices.all { index ->
            statementId.methodSignature.parameterTypeIdentities[index] == parameters[index].typeIdentity
        },
    ) {
        "Java parameter metadata types must match the canonical method signature"
    }
}
