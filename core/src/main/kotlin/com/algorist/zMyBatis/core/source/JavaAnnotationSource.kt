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

class JavaAnnotationStatementCapture(
    val sourceGraph: StatementSourceGraph,
    sqlSegments: List<String>,
    parameters: List<JavaMethodParameterMetadata>,
) {
    private val sqlSegmentSnapshot = sqlSegments.toTypedArray()
    private val parameterSnapshot = parameters.toTypedArray()

    init {
        val statementId = sourceGraph.rootStatement.id
        require(statementId is JavaStatementId) {
            "Java annotation capture requires a JavaStatementId root"
        }
        require(sqlSegmentSnapshot.isNotEmpty()) {
            "Java annotation capture requires at least one SQL segment"
        }
        require(parameterSnapshot.indices.all { index -> parameterSnapshot[index].index == index }) {
            "Java parameter metadata must be contiguous and declaration-ordered"
        }
        require(statementId.methodSignature.parameterTypeIdentities.size == parameterSnapshot.size) {
            "Java parameter metadata must match the canonical method signature arity"
        }
        require(
            statementId.methodSignature.parameterTypeIdentities.indices.all { index ->
                statementId.methodSignature.parameterTypeIdentities[index] == parameterSnapshot[index].typeIdentity
            },
        ) {
            "Java parameter metadata types must match the canonical method signature"
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
            parameterSnapshot.contentEquals(other.parameterSnapshot)
    }

    override fun hashCode(): Int {
        var result = sourceGraph.hashCode()
        result = 31 * result + sqlSegmentSnapshot.contentHashCode()
        result = 31 * result + parameterSnapshot.contentHashCode()
        return result
    }
}
