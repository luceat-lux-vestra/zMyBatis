package com.algorist.zMyBatis.input

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class XmlParameterObjectInputEditorProjectFixtureTest : BasePlatformTestCase() {
    fun testFieldsAndJsonModesProduceOneCallerObject() {
        val editor = XmlParameterObjectInputEditor(
            listOf(
                XmlParameterObjectPathHint("user.id", "1"),
                XmlParameterObjectPathHint("cust.name", "\"test\""),
                XmlParameterObjectPathHint("test", null),
            ),
        )

        assertEquals(listOf("user.id", "cust.name", "test"), editor.pathNamesForTest())
        assertNotNull(editor.validationMessage())

        editor.setPathValueForTest("user.id", "2")
        editor.setPathValueForTest("cust.name", "foo")
        editor.setPathValueForTest("test", "7")

        assertNull(editor.validationMessage())
        assertTrue(editor.isExplicitlyEdited())
        assertEquals(
            """
            {
              "user": {
                "id": 2
              },
              "cust": {
                "name": "foo"
              },
              "test": 7
            }
            """.trimIndent(),
            editor.textForSubmission(),
        )

        val advanced = """{"user":{"id":9},"cust":{"name":"bar"},"test":11}"""
        editor.selectJsonForTest(advanced)

        assertNull(editor.validationMessage())
        assertEquals(advanced, editor.textForSubmission())
    }
}
