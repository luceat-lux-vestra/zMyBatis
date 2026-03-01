package com.algorist.zMyBatis

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReferenceExpression

/**
 * Extracts the raw SQL string from a MyBatis statement annotation
 * (@Select, @Insert, @Update, @Delete) including multi-line string arrays
 * and resolved constant field references.
 */
internal object AnnotationSqlExtractor {

    @Suppress("ReturnCount")
    fun extract(annotation: PsiAnnotation?): String? {
        if (annotation == null) return null
        val valueAttr = annotation.findAttributeValue("value") ?: return null
        val resolved = resolveExpression(valueAttr)
        return when (resolved) {
            is PsiArrayInitializerMemberValue -> extractFromArray(resolved)
            is PsiLiteralExpression -> resolved.value?.toString()
            else -> fallbackText(resolved)
        }
    }

    private fun resolveExpression(element: PsiElement): PsiElement {
        if (element is PsiReferenceExpression) {
            val resolved = element.resolve()
            if (resolved is PsiField && resolved.hasInitializer()) {
                return resolved.initializer ?: element
            }
        }
        return element
    }

    private fun extractFromArray(arrayValue: PsiArrayInitializerMemberValue): String =
        arrayValue.initializers.joinToString(" ") { expression ->
            val resolved = resolveExpression(expression)
            (resolved as? PsiLiteralExpression)?.value?.toString() ?: ""
        }

    private fun fallbackText(element: PsiElement): String {
        val text = element.text
        return if (text.startsWith("\"") && text.endsWith("\"")) {
            text.substring(1, text.length - 1)
        } else {
            text
        }
    }
}

