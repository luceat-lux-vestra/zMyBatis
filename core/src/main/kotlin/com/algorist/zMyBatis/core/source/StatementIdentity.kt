package com.algorist.zMyBatis.core.source

@JvmInline
value class SourceFileId(val value: String) {
    init {
        require(value.isNotBlank()) { "source file id must not be blank" }
    }
}

@JvmInline
value class SourceRevision(val value: String) {
    init {
        require(value.isNotBlank()) { "source revision must not be blank" }
    }
}

@JvmInline
value class JavaTypeIdentity(val value: String) {
    init {
        require(value.isNotBlank()) { "Java type identity must not be blank" }
    }
}

sealed interface StatementId {
    val sourceFileId: SourceFileId
}

data class XmlStatementId(
    override val sourceFileId: SourceFileId,
    val namespace: String,
    val statementId: String,
) : StatementId {
    init {
        require(namespace.isNotBlank()) { "XML mapper namespace must not be blank" }
        require(statementId.isNotBlank()) { "XML statement id must not be blank" }
    }
}

class MethodSignature(
    val name: String,
    parameterTypeIdentities: List<JavaTypeIdentity>,
) {
    private val parameterTypeSnapshot = parameterTypeIdentities.toTypedArray()

    init {
        require(name.isNotBlank()) { "Java method name must not be blank" }
    }

    val parameterTypeIdentities: List<JavaTypeIdentity>
        get() = parameterTypeSnapshot.toList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MethodSignature) return false

        return name == other.name && parameterTypeSnapshot.contentEquals(other.parameterTypeSnapshot)
    }

    override fun hashCode(): Int = 31 * name.hashCode() + parameterTypeSnapshot.contentHashCode()

    override fun toString(): String =
        "$name(${parameterTypeSnapshot.joinToString(separator = ",") { it.value }})"
}

data class JavaStatementId(
    override val sourceFileId: SourceFileId,
    val qualifiedMapperType: String,
    val methodSignature: MethodSignature,
) : StatementId {
    init {
        require(qualifiedMapperType.isNotBlank()) { "qualified Java mapper type must not be blank" }
    }
}
