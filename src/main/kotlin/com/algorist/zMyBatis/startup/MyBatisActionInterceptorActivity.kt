package com.algorist.zMyBatis.startup

import com.algorist.zMyBatis.services.ConsoleCacheService
import com.algorist.zMyBatis.services.PersistedConsoleSession
import com.intellij.database.console.JdbcConsole
import com.intellij.database.util.DasUtil
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.LightVirtualFile

class MyBatisActionInterceptorActivity : ProjectActivity {

    companion object {
        private val LOG = Logger.getInstance(MyBatisActionInterceptorActivity::class.java)
    }

    override suspend fun execute(project: Project) {
        LOG.info("zMyBatis: startup activity running")

        // Register before crossing the EDT scheduling boundary so project-close cannot race past
        // the shutdown marker.
        ProjectManager.getInstance().addProjectManagerListener(project, object : ProjectManagerListener {
            override fun projectClosing(closingProject: Project) {
                if (closingProject === project) {
                    LOG.info("zMyBatis: project closing — marking shutdown for session preservation")
                    ConsoleCacheService.getInstance(project).markShuttingDown()
                }
            }
        })

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val cache = ConsoleCacheService.getInstance(project)
            if (cache.isShuttingDown()) return@invokeLater
            restoreSessionsIntoCache(project, cache)
        }
    }

    private fun restoreSessionsIntoCache(project: Project, cache: ConsoleCacheService) {
        if (project.isDisposed || cache.isShuttingDown()) return
        val sessions = cache.pruneStaleIndex()
        if (sessions.isEmpty()) return
        LOG.info("zMyBatis: restoring ${sessions.size} saved session(s) on startup")

        val sqlFileType = FileTypeManager.getInstance().getFileTypeByExtension("sql")
        for (session in sessions) {
            if (project.isDisposed || cache.isShuttingDown()) return
            restoreOneSession(project, cache, session, sqlFileType)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun restoreOneSession(
        project: Project,
        cache: ConsoleCacheService,
        session: PersistedConsoleSession,
        sqlFileType: com.intellij.openapi.fileTypes.FileType
    ) {
        if (project.isDisposed || cache.isShuttingDown()) return
        val mapperKey = session.mapperKey
        if (cache.get(mapperKey) != null) return

        val mapperFile = VirtualFileManager.getInstance().findFileByUrl(mapperKey)
        if (mapperFile == null || !mapperFile.isValid) {
            LOG.info("zMyBatis: mapper '$mapperKey' no longer exists — removing stale session")
            cache.clearSession(mapperKey)
            return
        }

        val dataSource = cache.findDataSourceById(session.dataSourceId)
        if (dataSource == null) {
            LOG.info(
                "zMyBatis: datasource '${session.dataSourceName}' (${session.dataSourceId}) " +
                    "cannot be resolved exactly — removing stale session"
            )
            cache.clearSession(mapperKey)
            return
        }
        if (dataSource.name != session.dataSourceName) {
            LOG.info(
                "zMyBatis: datasource '${session.dataSourceName}' was renamed to '${dataSource.name}'; " +
                    "restoring by stable id ${session.dataSourceId}"
            )
        }

        if (project.isDisposed || cache.isShuttingDown()) return
        var console: JdbcConsole? = null
        try {
            val consoleName = mapperFile.name + " - zMyBatis"
            val lightFile = LightVirtualFile(consoleName, sqlFileType, "")
            console = JdbcConsole.newConsole(project)
                .fromDataSource(dataSource)
                .forFile(lightFile)
                .build()

            if (session.schemaName.isNotBlank()) {
                val schemas = DasUtil.getSchemas(dataSource)
                    .toList()
                    .filter { it.name == session.schemaName }
                if (schemas.size != 1) {
                    LOG.warn(
                        "zMyBatis: schema '${session.schemaName}' is ${if (schemas.isEmpty()) "missing" else "ambiguous"} " +
                            "for datasource id ${session.dataSourceId} — removing stale session"
                    )
                    cache.clearSession(mapperKey)
                    Disposer.dispose(console)
                    return
                }

                val schema = schemas.single()
                val kind = DasUtil.getKind(schema)
                val path = ObjectPath.create(schema.name, kind)
                console.switchSchema(SearchPath.of(path), false)
            }

            // Startup restoration only reconstructs console state. It never injects or executes SQL.
            // put() owns the final lifecycle linearization: if shutdown won the race, it disposes
            // this console and refuses registration/persistence.
            cache.put(
                mapperKey = mapperKey,
                console = console,
                dataSourceId = session.dataSourceId,
                dataSourceName = dataSource.name,
                schemaName = session.schemaName
            )
            if (cache.get(mapperKey) !== console) {
                LOG.warn("zMyBatis: restored console was not live after cache registration for $mapperKey")
                Disposer.dispose(console)
                console = null
                return
            }

            console = null // ownership transferred to the cache/platform lifecycle
            LOG.info(
                "zMyBatis: session restored for $mapperKey " +
                    "(ds=${dataSource.name}, dsId=${session.dataSourceId}, schema=${session.schemaName})"
            )
        } catch (ex: Throwable) {
            console?.let { Disposer.dispose(it) }
            LOG.warn("zMyBatis: failed to restore session for $mapperKey; leaving it un-restored", ex)
        }
    }
}
