package com.algorist.zMyBatis.startup

import com.algorist.zMyBatis.services.ConsoleCacheService
import com.intellij.database.console.JdbcConsole
import com.intellij.database.util.DasUtil
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.testFramework.LightVirtualFile

class MyBatisActionInterceptorActivity : ProjectActivity {

    companion object {
        private val LOG = Logger.getInstance(MyBatisActionInterceptorActivity::class.java)

        private val TARGET_ACTION_IDS = listOf(
            "Console.Jdbc.ExplainPlan",
            "Console.Jdbc.ExplainPlan.Raw",
            "Console.Jdbc.ExplainAnalyse",
            "Console.Jdbc.ExplainAnalyse.Raw",
            "Console.Jdbc.Execute",
            "Console.TableResult.ShowDumpDialogAction"
        )

        /** Replaces each target action with a [MyBatisActionWrapper]. Idempotent. */
        private fun registerActionWrappers() {
            val actionManager = ActionManager.getInstance()
            for (id in TARGET_ACTION_IDS) {
                val current = actionManager.getAction(id) ?: continue
                if (current is MyBatisActionWrapper) continue   // already wrapped
                actionManager.replaceAction(id, MyBatisActionWrapper(current))
                LOG.info("zMyBatis: replaced action '$id' with MyBatisActionWrapper")
            }
        }
    }

    override suspend fun execute(project: Project) {
        LOG.info("zMyBatis: action-wrapper interception active")

        // Replace target DB actions with our wrapper on the EDT (ActionManager requires EDT).
        ApplicationManager.getApplication().invokeAndWait {
            registerActionWrappers()
        }

        // Register a project-close listener so ConsoleCacheService knows
        // when the project is about to close (before JdbcConsoles are disposed).
        // This is more reliable than checking app.isDisposeInProgress in the sentinel callback,
        // because JdbcConsoles can be disposed before our service's dispose() is called.
        ProjectManager.getInstance().addProjectManagerListener(project, object : ProjectManagerListener {
            override fun projectClosing(closingProject: Project) {
                if (closingProject === project) {
                    LOG.info("zMyBatis: project closing — marking shutdown for session preservation")
                    ConsoleCacheService.getInstance(project).markShuttingDown()
                }
            }
        })

        // After restart: silently re-create consoles from saved session data.
        // LightVirtualFile consoles are not persisted by IntelliJ, so we re-create them
        // from the (dsName, searchPath) we saved in PropertiesComponent at creation time.
        ApplicationManager.getApplication().invokeLater {
            restoreSessionsIntoCache(project)
        }
    }

    private fun restoreSessionsIntoCache(project: Project) {
        val fileKeys = ConsoleCacheService.allSavedFileKeys()
        if (fileKeys.isEmpty()) return
        LOG.info("zMyBatis: restoring ${fileKeys.size} saved session(s) on startup")

        val cache = ConsoleCacheService.getInstance(project)
        val sqlFileType = FileTypeManager.getInstance().getFileTypeByExtension("sql")

        for (fileKey in fileKeys) {
            try {
                if (cache.get(fileKey) != null) continue   // already live

                val (dsName, searchPathStr) = ConsoleCacheService.loadSession(fileKey) ?: continue
                val ds = ConsoleCacheService.findDataSourceByName(project, dsName)
                if (ds == null) {
                    LOG.info("zMyBatis: DS '$dsName' not found for $fileKey, skipping restore")
                    continue
                }

                val consoleName = fileKey.substringAfterLast('/').substringAfterLast('\\')
                val lightFile = LightVirtualFile(consoleName, sqlFileType, "")

                val console = JdbcConsole.newConsole(project)
                    .fromDataSource(ds)
                    .forFile(lightFile)
                    .build()

                if (searchPathStr.isNotBlank()) {
                    try {
                        val schema = DasUtil.getSchemas(ds).firstOrNull { it.name == searchPathStr }
                        if (schema != null) {
                            val kind = DasUtil.getKind(schema)
                            val path = ObjectPath.create(schema.name, kind)
                            console.switchSchema(SearchPath.of(path), false)
                        }
                    } catch (_: Throwable) { /* best-effort */ }
                }

                cache.put(fileKey, console)
                LOG.info("zMyBatis: session restored for $fileKey (ds=$dsName)")
            } catch (ex: Throwable) {
                LOG.warn("zMyBatis: failed to restore session for $fileKey: ${ex.message}")
            }
        }
    }
}
