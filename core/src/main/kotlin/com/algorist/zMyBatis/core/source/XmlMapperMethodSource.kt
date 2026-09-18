package com.algorist.zMyBatis.core.source

/**
 * Immutable Java mapper-method authority associated with one canonical XML mapper statement.
 *
 * The XML statement remains the canonical statement identity. This capture only supplies the exact
 * Java source revision/range and declaration-ordered parameter metadata that downstream #63 logic
 * may use as additional evidence. It carries no IntelliJ platform object.
 */
class XmlMapperMethodCapture(
    val statementId: XmlStatementId,
    val mapperSource: SourceSnapshot,
    val methodSourceRange: SourceRange,
    parameters: List<JavaMethodParameterMetadata>,
) {
    private val parameterSnapshot = parameters.toTypedArray()

    init {
        require(methodSourceRange.endOffsetExclusive <= mapperSource.content.length) {
            "XML mapper method range must fit the captured Java source"
        }
        require(parameterSnapshot.indices.all { index -> parameterSnapshot[index].index == index }) {
            "XML mapper method parameters must be contiguous and declaration-ordered"
        }
    }

    val parameters: List<JavaMethodParameterMetadata>
        get() = parameterSnapshot.toList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is XmlMapperMethodCapture) return false

        return statementId == other.statementId &&
            mapperSource == other.mapperSource &&
            methodSourceRange == other.methodSourceRange &&
            parameterSnapshot.contentEquals(other.parameterSnapshot)
    }

    override fun hashCode(): Int {
        var result = statementId.hashCode()
        result = 31 * result + mapperSource.hashCode()
        result = 31 * result + methodSourceRange.hashCode()
        result = 31 * result + parameterSnapshot.contentHashCode()
        return result
    }
}
