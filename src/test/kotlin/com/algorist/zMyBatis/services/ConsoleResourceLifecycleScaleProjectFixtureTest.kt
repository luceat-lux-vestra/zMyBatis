package com.algorist.zMyBatis.services

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.materialization.MaterializationResult
import com.algorist.zMyBatis.core.materialization.MaterializedExecution
import com.algorist.zMyBatis.core.materialization.TargetBoundZeroBindingExecutionMaterializer
import com.algorist.zMyBatis.core.preparation.PreparationMetadata
import com.algorist.zMyBatis.core.preparation.PreparedExecution
import com.algorist.zMyBatis.core.source.SourceFileId
import com.algorist.zMyBatis.core.source.SourceRevision
import com.algorist.zMyBatis.core.source.StatementKind
import com.algorist.zMyBatis.core.source.XmlStatementId
import com.algorist.zMyBatis.execution.DatabaseToolsConsoleAcquisitionFailure
import com.algorist.zMyBatis.execution.DatabaseToolsConsoleAdapter
import com.algorist.zMyBatis.execution.DatabaseToolsSqlExecutionFailure
import com.intellij.database.console.JdbcConsole
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.DbImplUtil
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Scale evidence for the shipping console boundary after P5 UX parity recovery.
 *
 * zMyBatis reuses Database Tools-owned consoles when available and creates one only for a
 * no-candidate flow or explicit user choice. These fixtures prove repeated borrowing, bounded
 * ownership of created consoles, lease cleanup, and zero persistent editor/tab growth.
 */
class ConsoleResourceLifecycleScaleProjectFixtureTest : BasePlatformTestCase() {

    fun testRepeatedBorrowedConsoleCompletionKeepsOnePlatformConsoleAndNoLeaseStranded() {
        val cache = ConsoleCacheService.getInstance(project)
        val dataSource = registeredDbDataSource(
            name = "zMyBatis existing-console scale",
            url = "jdbc:h2:mem:zmybatis_existing_console_scale",
        )
        val console = newConsole(dataSource, "ResourceReuseMapper.xml")
        val lifetime = Disposer.newCheckedDisposable(console)
        val adapter = DatabaseToolsConsoleAdapter.getInstance(project)
        val key = "vfs:file:///tmp/zmybatis/ResourceReuseMapper.xml"
        val identities = mutableSetOf<Int>()

        try {
            repeat(BORROW_ITERATIONS) {
                var failure: DatabaseToolsConsoleAcquisitionFailure? = null
                var delivered = false

                adapter.acquireAndDeliverConsole(
                    dataSource = dataSource,
                    schema = null,
                    resourceKey = key,
                    onConsoleReady = { borrowed, release ->
                        delivered = true
                        assertSame(console, borrowed)
                        identities += System.identityHashCode(borrowed)
                        release()
                    },
                    onFailure = { failure = it },
                )

                assertTrue(delivered)
                assertNull(failure)
                assertFalse(lifetime.isDisposed)
                assertNull("shipping borrow must never populate the zMyBatis console cache", cache.get(key))
                assertTrue("terminal completion must release the keyed lease", cache.beginReuseLease(key))
                cache.endReuseLease(key)
            }

            assertEquals(1, identities.size)
            println(
                "ZMYBATIS_RESOURCE_LIFECYCLE phase=repeated-completion " +
                    "policy=BORROW_EXISTING iterations=$BORROW_ITERATIONS " +
                    "distinct_consoles=1 cache_retained=0 lease_stranded=false " +
                    "platform_console_live=true",
            )
        } finally {
            if (!lifetime.isDisposed) Disposer.dispose(console)
        }
    }

    fun testRepeatedMaterializedDeliveryUsesTransientEditorsWithoutOpeningPersistentEditor() {
        val cache = ConsoleCacheService.getInstance(project)
        val dataSource = registeredDbDataSource(
            name = "zMyBatis existing-console materialized scale",
            url = "jdbc:h2:mem:zmybatis_existing_console_materialized",
        )
        val console = newConsole(dataSource, "ResourceMaterializedMapper.xml")
        val lifetime = Disposer.newCheckedDisposable(console)
        val adapter = DatabaseToolsConsoleAdapter.getInstance(project)
        val key = "vfs:file:///tmp/zmybatis/ResourceMaterializedMapper.xml"
        val target = targetId("resource-materialized-existing", "public")
        val baselineEditors = EditorFactory.getInstance().getEditors(console.document, project).size
        var terminalFailures = 0

        try {
            repeat(MATERIALIZED_DELIVERY_ITERATIONS) { iteration ->
                var acquisitionFailure: DatabaseToolsConsoleAcquisitionFailure? = null

                adapter.acquireAndDeliverConsole(
                    dataSource = dataSource,
                    schema = null,
                    resourceKey = key,
                    onConsoleReady = { borrowed, release ->
                        assertSame(console, borrowed)
                        val artifact = materialized(
                            sql = "SELECT ${iteration + 1} AS resource_probe",
                            target = target,
                        )
                        var invocationChecks = 0
                        var executed = false
                        var deliveryFailure: DatabaseToolsSqlExecutionFailure? = null
                        var finished = 0

                        adapter.executeMaterialized(
                            console = borrowed,
                            artifact = artifact,
                            currentTarget = { resolvedTarget(target) },
                            isInvocationActive = {
                                invocationChecks += 1
                                invocationChecks < LATE_INVALIDATION_CHECK
                            },
                            areSourceRevisionsCurrent = { it == artifact.sourceRevisions },
                            onExecuted = { executed = true },
                            onFailure = { deliveryFailure = it },
                            onFinished = {
                                finished += 1
                                release()
                            },
                        )

                        assertEquals(DatabaseToolsSqlExecutionFailure.InvocationInvalidated, deliveryFailure)
                        assertFalse("late invalidation must stop native query invocation", executed)
                        assertEquals(1, finished)
                        assertEquals("failed delivery must restore the borrowed console document", "", console.document.text)
                        assertEquals(
                            "transient execution editor must be released after terminal delivery",
                            baselineEditors,
                            EditorFactory.getInstance().getEditors(console.document, project).size,
                        )
                        terminalFailures += 1
                    },
                    onFailure = { acquisitionFailure = it },
                )

                assertNull(acquisitionFailure)
                assertTrue(cache.beginReuseLease(key))
                cache.endReuseLease(key)
                assertFalse(lifetime.isDisposed)
            }

            println(
                "ZMYBATIS_RESOURCE_LIFECYCLE phase=materialized-delivery-terminal " +
                    "policy=BORROW_EXISTING iterations=$MATERIALIZED_DELIVERY_ITERATIONS " +
                    "distinct_consoles=1 persistent_editor_delta=0 " +
                    "terminal_failures=$terminalFailures native_invocations=0 " +
                    "lease_stranded=false document_restored=true platform_console_live=true",
            )
        } finally {
            if (!lifetime.isDisposed) Disposer.dispose(console)
        }
    }

    fun testCancellationAndCreatedConsoleDoNotStrandOwnership() {
        val cache = ConsoleCacheService.getInstance(project)
        val dataSource = registeredDbDataSource(
            name = "zMyBatis borrowed-console terminal paths",
            url = "jdbc:h2:mem:zmybatis_borrowed_terminal",
        )
        val console = newConsole(dataSource, "ResourceCancellationMapper.xml")
        val lifetime = Disposer.newCheckedDisposable(console)
        val adapter = DatabaseToolsConsoleAdapter.getInstance(project)
        val cancellationKey = "vfs:file:///tmp/zmybatis/ResourceCancellationMapper.xml"

        try {
            try {
                adapter.acquireAndDeliverConsole(
                    dataSource = dataSource,
                    schema = null,
                    resourceKey = cancellationKey,
                    onConsoleReady = { _, _ -> throw ProcessCanceledException() },
                    onFailure = { fail("cancellation must not become an acquisition failure") },
                )
                fail("ProcessCanceledException must propagate")
            } catch (_: ProcessCanceledException) {
                // Expected platform cancellation.
            }

            assertTrue(cache.beginReuseLease(cancellationKey))
            cache.endReuseLease(cancellationKey)
            assertFalse("cancellation must not dispose the borrowed console", lifetime.isDisposed)

            val missingDataSource = registeredDbDataSource(
                name = "zMyBatis missing-console terminal path",
                url = "jdbc:h2:mem:zmybatis_missing_terminal",
            )
            val missingKey = "vfs:file:///tmp/zmybatis/NoConsoleMapper.xml"
            var failure: DatabaseToolsConsoleAcquisitionFailure? = null
            var created: JdbcConsole? = null
            adapter.acquireAndDeliverConsole(
                dataSource = missingDataSource,
                schema = null,
                resourceKey = missingKey,
                onConsoleReady = { console, release ->
                    created = console
                    release()
                },
                onFailure = { failure = it },
            )

            assertNull(failure)
            assertNotNull("no-candidate flow must create a console", created)
            assertEquals("NoConsoleMapper.xml", created!!.file.virtualFile?.name)
            assertTrue(cache.beginReuseLease(missingKey))
            cache.endReuseLease(missingKey)
            if (created!!.isValid) {
                Disposer.dispose(created!!)
            }

            println(
                "ZMYBATIS_RESOURCE_LIFECYCLE phase=terminal-cleanup " +
                    "policy=REUSE_CHOOSE_CREATE cancellation_disposed=false " +
                    "missing_console_created=true lease_stranded=false",
            )
        } finally {
            if (!lifetime.isDisposed) Disposer.dispose(console)
        }
    }

    private fun materialized(
        sql: String,
        target: ExecutionTargetId,
    ): MaterializedExecution {
        val file = SourceFileId("vfs:/ResourceLifecycle.xml")
        val prepared = PreparedExecution(
            statementId = XmlStatementId(file, "fixture.ResourceLifecycleMapper", "probe"),
            statementKind = StatementKind.SELECT,
            sourceRevisions = mapOf(file to SourceRevision("resource-lifecycle-r1")),
            sqlWithPlaceholders = sql,
            orderedBindings = emptyList(),
            rawInterpolations = emptyList(),
            preparationMetadata = PreparationMetadata(
                engineIdentity = "org.mybatis:mybatis",
                engineVersion = "3.5.19",
                languageDriverIdentity = "org.apache.ibatis.scripting.xmltags.XMLLanguageDriver",
            ),
        )
        val result = TargetBoundZeroBindingExecutionMaterializer(resolvedTarget(target))
            .materialize(prepared)
        return (result as MaterializationResult.Success).execution
    }

    private fun resolvedTarget(target: ExecutionTargetId): ResolvedExecutionTarget =
        ResolvedExecutionTarget(
            ExecutionTargetDescriptor(target, "fixture"),
            effectiveDatabaseId = null,
        )

    private fun targetId(
        dataSourceId: String,
        schema: String,
    ): ExecutionTargetId =
        ExecutionTargetId(
            StableDataSourceId(dataSourceId),
            ExplicitSchemaIdentity(schema),
        )

    private fun registeredDbDataSource(
        name: String,
        url: String,
    ): DbDataSource {
        val local = LocalDataSource().apply {
            this.name = name
            driverClass = "org.h2.Driver"
            this.url = url
            isAutoSynchronize = false
        }
        val manager = LocalDataSourceManager.getInstance(project)
        manager.addDataSource(local)
        Disposer.register(testRootDisposable) { manager.removeDataSource(local) }

        return requireNotNull(
            DbPsiFacade.getInstance(project).dataSources.firstOrNull { candidate ->
                DbImplUtil.getMaybeLocalDataSource(candidate) === local
            },
        ) {
            "registered LocalDataSource must be exposed as DbDataSource"
        }
    }

    private fun newConsole(
        dataSource: DbDataSource,
        fileName: String,
    ): JdbcConsole =
        JdbcConsole.newConsole(project)
            .fromDataSource(dataSource)
            .forFile(
                LightVirtualFile(
                    fileName,
                    com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                        .getFileTypeByExtension("sql"),
                    "",
                ),
            )
            .build()

    companion object {
        private const val BORROW_ITERATIONS = 48
        private const val MATERIALIZED_DELIVERY_ITERATIONS = 12
        private const val LATE_INVALIDATION_CHECK = 4
    }
}
