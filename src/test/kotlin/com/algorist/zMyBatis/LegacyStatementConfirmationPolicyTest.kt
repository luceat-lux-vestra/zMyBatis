package com.algorist.zMyBatis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyStatementConfirmationPolicyTest {

    @Test
    fun `xml mutation declarations require confirmation without classifying select as mutation`() {
        listOf("insert", "update", "delete", "INSERT", "UPDATE", "DELETE").forEach { tag ->
            assertTrue(
                tag,
                LegacyStatementConfirmationPolicy.requiresMutationConfirmation(
                    xmlTagName = tag,
                    annotationQualifiedName = null,
                ),
            )
        }

        assertFalse(
            LegacyStatementConfirmationPolicy.requiresMutationConfirmation(
                xmlTagName = "select",
                annotationQualifiedName = null,
            ),
        )
    }

    @Test
    fun `java mutation annotations require confirmation without classifying select as mutation`() {
        listOf(
            "org.apache.ibatis.annotations.Insert",
            "org.apache.ibatis.annotations.Update",
            "org.apache.ibatis.annotations.Delete",
        ).forEach { annotation ->
            assertTrue(
                annotation,
                LegacyStatementConfirmationPolicy.requiresMutationConfirmation(
                    xmlTagName = null,
                    annotationQualifiedName = annotation,
                ),
            )
        }

        assertFalse(
            LegacyStatementConfirmationPolicy.requiresMutationConfirmation(
                xmlTagName = null,
                annotationQualifiedName = "org.apache.ibatis.annotations.Select",
            ),
        )
    }

    @Test
    fun `missing or unrelated declaration evidence does not invent mutation semantics`() {
        assertFalse(
            LegacyStatementConfirmationPolicy.requiresMutationConfirmation(
                xmlTagName = null,
                annotationQualifiedName = null,
            ),
        )
        assertFalse(
            LegacyStatementConfirmationPolicy.requiresMutationConfirmation(
                xmlTagName = "script",
                annotationQualifiedName = "fixture.CustomStatement",
            ),
        )
    }
}
