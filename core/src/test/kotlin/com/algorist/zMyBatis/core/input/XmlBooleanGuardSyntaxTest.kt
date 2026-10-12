package com.algorist.zMyBatis.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlBooleanGuardSyntaxTest {
    @Test
    fun exactBooleanShapesHaveOneCallerAliasAndMappingPolarity() {
        val accepted = listOf(
            Triple("enabled", true, false),
            Triple("!enabled", false, true),
            Triple("enabled == true", true, true),
            Triple("enabled == false", false, true),
            Triple("enabled != false", true, true),
            Triple("enabled != true", false, true),
        )
        for ((expression, activeWhenTrue, restricted) in accepted) {
            val syntax = requireNotNull(XmlBooleanGuardSyntax.parse(expression))
            assertEquals(expression, "enabled", syntax.callerAlias)
            assertEquals(expression, restricted, syntax.requiresSingleSelect)
            assertEquals(expression, activeWhenTrue, syntax.isActive(true))
            assertEquals(expression, !activeWhenTrue, syntax.isActive(false))
        }
    }

    @Test
    fun grammarDoesNotGuessOtherOgnlShapes() {
        for (expression in listOf(
            "", " true", "enabled ", "! enabled", "!!enabled",
            "enabled==true", "enabled  == true", "enabled ==  true",
            "enabled !=false", "enabled <> true", "enabled eq true",
            "enabled == null", "enabled == TRUE", "enabled == 1",
            "enabled.value == true", "enabled || true",
            "!enabled == false", "(enabled)", "enabled.equals(true)",
            "enabled != true || false", "enabled != true != false",
            "false == enabled", "enabled\n== false",
        )) {
            assertNull(expression, XmlBooleanGuardSyntax.parse(expression))
        }
        assertFalse(requireNotNull(XmlBooleanGuardSyntax.parse("flag")).requiresSingleSelect)
        assertTrue(requireNotNull(XmlBooleanGuardSyntax.parse("!flag")).requiresSingleSelect)
    }
}
