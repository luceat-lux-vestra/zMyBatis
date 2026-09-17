package com.algorist.zMyBatis.mybatis

import org.junit.Assert.assertEquals
import org.junit.Test

class IsolatedOgnlExactRootBatchAdmissionTest {
    @Test
    fun singleExactRootPropertyRemainsAvailableForFocusedAdmissionChecks() {
        assertEquals(
            IsolatedOgnlAstAdmission.Result.Admitted,
            IsolatedOgnlAstAdmission.inspectExactRootProperty("ids", "ids"),
        )
    }

    @Test
    fun multipleExactRootPropertiesAreAdmittedInOneBatch() {
        assertEquals(
            IsolatedOgnlAstAdmission.Result.Admitted,
            IsolatedOgnlAstAdmission.inspectExactRootProperties(
                listOf("ids", "otherIds", "entries"),
            ),
        )
    }

    @Test
    fun batchFailureIdentifiesTheExpressionThatIsNotAPropertyRoot() {
        assertEquals(
            IsolatedOgnlAstAdmission.Result.Unsupported(
                nodeType = "ASTProperty[exact-root-required]",
                expression = "true",
            ),
            IsolatedOgnlAstAdmission.inspectExactRootProperties(
                listOf("ids", "true", "entries"),
            ),
        )
    }

    @Test
    fun ordinaryDynamicAdmissionKeepsItsPreexistingFailureShape() {
        val oversized = "a".repeat(65_537)
        assertEquals(
            IsolatedOgnlAstAdmission.Result.Unsupported("OGNL[expression-length]"),
            IsolatedOgnlAstAdmission.inspect(listOf(oversized), setOf("a")),
        )
    }
}
