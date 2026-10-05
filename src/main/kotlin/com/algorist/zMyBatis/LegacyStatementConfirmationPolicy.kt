package com.algorist.zMyBatis

internal object LegacyStatementConfirmationPolicy {

    private val MUTATION_XML_TAGS = setOf("insert", "update", "delete")
    private val MUTATION_ANNOTATIONS = setOf(
        "org.apache.ibatis.annotations.Insert",
        "org.apache.ibatis.annotations.Update",
        "org.apache.ibatis.annotations.Delete",
    )

    fun requiresMutationConfirmation(
        xmlTagName: String?,
        annotationQualifiedName: String?,
    ): Boolean =
        xmlTagName?.lowercase() in MUTATION_XML_TAGS ||
            annotationQualifiedName in MUTATION_ANNOTATIONS
}
