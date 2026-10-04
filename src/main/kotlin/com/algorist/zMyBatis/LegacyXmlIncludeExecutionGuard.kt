package com.algorist.zMyBatis

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

/**
 * Temporary shipping-path guard for mapper dependencies that the legacy evaluator cannot resolve.
 *
 * Leap #62/#64 owns authoritative <sql>/<include> graph resolution. Until that architecture is
 * allowed to replace the shipping action, a real <include> inside the selected XML statement must
 * fail closed rather than reach legacy unknown-tag stripping.
 */
internal object LegacyXmlIncludeExecutionGuard {

    fun containsIncludeDependency(statementTag: XmlTag): Boolean =
        PsiTreeUtil.findChildrenOfType(statementTag, XmlTag::class.java)
            .any { it.localName == "include" }
}
