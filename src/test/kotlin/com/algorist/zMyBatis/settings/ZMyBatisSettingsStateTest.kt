package com.algorist.zMyBatis.settings

import org.junit.Assert.assertFalse
import org.junit.Test

class ZMyBatisSettingsStateTest {
    @Test
    fun `remembered parameter inputs are opt in by default`() {
        assertFalse(ZMyBatisSettings.State().rememberLastInputs)
    }
}
