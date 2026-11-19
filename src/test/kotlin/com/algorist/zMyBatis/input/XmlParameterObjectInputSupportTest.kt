package com.algorist.zMyBatis.input

import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import org.junit.Assert.assertEquals
import org.junit.Test

class XmlParameterObjectInputSupportTest {
    @Test
    fun `source provenance becomes dotted scalar hints and nested JSON template`() {
        val provenance = InputProvenance(
            listOf(
                InputEvidence.XmlParameterObject(SOURCE),
                InputEvidence.OgnlExpression("user.id == 1", SOURCE),
                InputEvidence.OgnlExpression("cust.name == 'test'", SOURCE),
                InputEvidence.Placeholder(InputKind.BOUND, "test", SOURCE),
                InputEvidence.ForeachCollection("ids", SOURCE),
            ),
        )

        val hints = XmlParameterObjectInputSupport.hints(provenance)

        assertEquals(
            listOf("user.id", "cust.name", "test", "ids"),
            hints.map { it.path },
        )
        assertEquals(
            listOf("1", "\"test\"", null, "[1]"),
            hints.map { it.exampleJson },
        )
        assertEquals(
            """
            {
              "user": {
                "id": 1
              },
              "cust": {
                "name": "test"
              },
              "test": null,
              "ids": [1]
            }
            """.trimIndent(),
            XmlParameterObjectInputSupport.template(hints),
        )
    }

    @Test
    fun `scalar values compose into the same XML parameter object shape`() {
        val json = XmlParameterObjectInputSupport.composeScalarValues(
            linkedMapOf(
                "user.id" to "2",
                "cust.name" to "hello",
                "test" to "100",
                "ids" to "[1,2]",
            ),
        )

        assertEquals(
            """
            {
              "user": {
                "id": 2
              },
              "cust": {
                "name": "hello"
              },
              "test": 100,
              "ids": [1,2]
            }
            """.trimIndent(),
            json,
        )
    }

    private companion object {
        val SOURCE = SourceEvidence(
            SourceFileId("fixture://xml-only"),
            SourceRevision("fixture-revision"),
            null,
        )
    }
}
