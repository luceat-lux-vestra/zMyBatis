package com.algorist.zMyBatis

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.sql.SqlFileType

/**
 * SQL pretty-printer that delegates to IntelliJ's built-in [CodeStyleManager].
 *
 * Strategy:
 *  1. Create an in-memory SQL PsiFile from the raw SQL string.
 *  2. Run [CodeStyleManager.reformatText] on the whole file inside a write action.
 *  3. Return [PsiFile.text] — no Document lookup needed (lightweight PSI files
 *     created via [PsiFileFactory] have no backing Document, so [PsiDocumentManager.getDocument]
 *     returns null and must not be used here).
 *
 * The formatting respects the user's own SQL code-style settings
 * (Settings → Editor → Code Style → SQL).
 *
 * Falls back to the original SQL string if anything goes wrong so that
 * execution is never blocked by a formatting failure.
 */
object SqlFormatter {

    private val LOG = Logger.getInstance(SqlFormatter::class.java)

    /**
     * Formats [sql] using IntelliJ's SQL code-style settings.
     *
     * Must be called on the EDT (or inside a read/write action) because PSI
     * operations require the EDT.
     *
     * @param project  The current project (required for [PsiFileFactory] and [CodeStyleManager]).
     * @param sql      The raw resolved SQL string to format.
     * @return         The formatted SQL, or [sql] unchanged if formatting fails.
     */
    fun format(project: Project, sql: String): String {
        if (sql.isBlank()) return sql.trim()
        return try {
            formatInternal(project, sql)
        } catch (e: Throwable) {
            LOG.warn("zMyBatis: SQL formatting failed, returning original SQL. Reason: ${e.message}")
            sql
        }
    }

    private fun formatInternal(project: Project, sql: String): String {
        val psiFile = PsiFileFactory.getInstance(project)
            .createFileFromText("__zMyBatis_format__.sql", SqlFileType.INSTANCE, sql)

        WriteCommandAction.runWriteCommandAction(project) {
            // reformatText works directly on the PsiFile's AST — no Document required
            CodeStyleManager.getInstance(project)
                .reformatText(psiFile, 0, psiFile.textLength)
        }

        return psiFile.text
    }
}
