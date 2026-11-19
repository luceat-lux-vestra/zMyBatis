package com.algorist.zMyBatis.input

import com.algorist.zMyBatis.core.input.ExpectedInputType
import com.algorist.zMyBatis.core.input.InputEvidence
import com.algorist.zMyBatis.core.input.InputKind
import com.algorist.zMyBatis.core.input.InputProvenance
import com.algorist.zMyBatis.core.input.InputRequiredness
import com.algorist.zMyBatis.core.input.InputRequirement
import com.algorist.zMyBatis.core.input.InputRequirementId
import com.algorist.zMyBatis.core.input.InputShape
import com.algorist.zMyBatis.core.input.ParameterContract
import com.algorist.zMyBatis.core.input.SourceEvidence
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.XmlStatementId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class XmlParameterObjectInputEditorProjectFixtureTest : BasePlatformTestCase() {
    fun testFieldsAndJsonModesProduceOneCallerObjectAndSubmitThroughProductionPolicy() {
        val requirement = xmlObjectRequirement()
        val editor = XmlParameterObjectInputEditor(
            XmlParameterObjectInputSupport.hints(requirement.provenance),
        )

        assertEquals(listOf("user.id", "cust.name", "test"), editor.pathNamesForTest())
        assertTrue(
            editor.validationMessage().orEmpty().contains(
                "Required XML values are missing: user.id, cust.name, test",
            ),
        )

        editor.setPathValueForTest("user.id", "2")
        assertTrue(
            editor.validationMessage().orEmpty().contains(
                "Required XML values are missing: cust.name, test",
            ),
        )

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

        val contract = ParameterContract(
            statementId = XmlStatementId(FILE, "fixture.Mapper", "findByIdJSON"),
            requirements = listOf(requirement),
            aliases = emptyList(),
            internalBindings = emptyList(),
            blockingProblems = emptyList(),
            sourceRevisions = mapOf(FILE to REVISION),
        )
        val prepared = ContractInputSubmissionPolicy.prepare(
            contract = contract,
            drafts = listOf(
                ContractInputDraft(
                    requirementId = requirement.id,
                    text = editor.textForSubmission(),
                    supplied = true,
                    edited = editor.isExplicitlyEdited(),
                    retained = false,
                    rawConfirmed = false,
                ),
            ),
            acceptRetained = false,
        )
        assertTrue(
            "fully entered Fields values must submit through the production policy/adapter path: $prepared",
            prepared is ContractInputAdapterResult.Success,
        )

        val advanced = """{"user":{"id":9},"cust":{"name":"bar"},"test":11}"""
        editor.selectJsonForTest(advanced)

        assertNull(editor.validationMessage())
        assertEquals(advanced, editor.textForSubmission())
    }

    private fun xmlObjectRequirement(): InputRequirement {
        val source = SourceEvidence(FILE, REVISION, null)
        return InputRequirement(
            id = InputRequirementId("xml-parameter-object"),
            kind = InputKind.BOUND,
            expectedType = ExpectedInputType(InputShape.OBJECT),
            requiredness = InputRequiredness.REQUIRED,
            provenance = InputProvenance(
                listOf(
                    InputEvidence.XmlParameterObject(source),
                    InputEvidence.OgnlExpression("user.id == 1", source),
                    InputEvidence.OgnlExpression("cust.name == 'test'", source),
                    InputEvidence.Placeholder(InputKind.BOUND, "test", source),
                ),
            ),
        )
    }

    private companion object {
        val FILE = SourceFileId("fixture://historical-customer.xml")
        val REVISION = SourceRevision("fixture-revision")
    }
}
