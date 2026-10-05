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
    ): Boolean {
        val xmlRequiresConfirmation =
            xmlTagName?.lowercase()?.let(MUTATION_XML_TAGS::contains) ?: false
        val annotationRequiresConfirmation =
            annotationQualifiedName?.let(MUTATION_ANNOTATIONS::contains) ?: false
        return xmlRequiresConfirmation || annotationRequiresConfirmation
    }
}
