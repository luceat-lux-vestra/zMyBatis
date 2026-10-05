package com.algorist.zMyBatis

import com.algorist.zMyBatis.settings.ParameterHistoryService
import com.algorist.zMyBatis.settings.ZMyBatisSettings
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class LegacyInputRetentionProjectFixtureTest : BasePlatformTestCase() {
    fun testDialogPurgesStoredRawHistoryWithoutDroppingBoundHistory() {
        val settings = ZMyBatisSettings.getInstance()
        val originalRetention = settings.rememberLastInputs
        val history = ParameterHistoryService.getInstance(project)
        val statementKey = "/fixture/UserMapper.xml::find"
        history.save(
            statementKey,
            linkedMapOf(
                "id" to "7",
                "orderBy" to "created_at DESC",
            ),
        )
        settings.rememberLastInputs = true

        val dialog = ParameterInputDialog(
            project = project,
            paramNames = listOf("id", "orderBy"),
            statementKey = statementKey,
            rawInterpolationParams = setOf("orderBy"),
        )

        try {
            assertEquals(
                mapOf("id" to "7"),
                history.load(statementKey),
            )
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
            history.save(statementKey, emptyMap())
            settings.rememberLastInputs = originalRetention
        }
    }

    fun testDisabledRetentionDoesNotMutateStoredHistoryOnDialogLoad() {
        val settings = ZMyBatisSettings.getInstance()
        val originalRetention = settings.rememberLastInputs
        val history = ParameterHistoryService.getInstance(project)
        val statementKey = "/fixture/UserMapper.xml::disabled"
        val stored = linkedMapOf(
            "id" to "7",
            "orderBy" to "created_at DESC",
        )
        history.save(statementKey, stored)
        settings.rememberLastInputs = false

        val dialog = ParameterInputDialog(
            project = project,
            paramNames = listOf("id", "orderBy"),
            statementKey = statementKey,
            rawInterpolationParams = setOf("orderBy"),
        )

        try {
            assertEquals(stored, history.load(statementKey))
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
            history.save(statementKey, emptyMap())
            settings.rememberLastInputs = originalRetention
        }
    }
}
