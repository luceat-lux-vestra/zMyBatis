package com.algorist.zMyBatis.services

import com.algorist.zMyBatis.core.execution.ExecutionTargetDescriptor
import com.algorist.zMyBatis.core.execution.ExecutionTargetId
import com.algorist.zMyBatis.core.execution.ExplicitSchemaIdentity
import com.algorist.zMyBatis.core.execution.ResolvedExecutionTarget
import com.algorist.zMyBatis.core.execution.StableDataSourceId
import com.algorist.zMyBatis.core.materialization.MaterializationResult
import com.algorist.zMyBatis.core.materialization.MaterializedExecution
import com.algorist.zMyBatis.core.materialization.TargetBoundZeroBindingExecutionMaterializer
import com.algorist.zMyBatis.core.materialization.ZeroBindingExecutionMaterializer
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
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ConsoleCacheServiceLifecycleTest : BasePlatformTestCase() {

    fun testRealConsoleDisposalInvalidatesLifetimeCacheAndExecution() {
        val cache = newIsolatedCache()
        val console = newConsole()
        val lifetime = Disposer.newCheckedDisposable(console)
        val key = "file:///tmp/zmybatis/DisposedConsole.xml"
        try {
            assertTrue("a newly created console must be valid", console.isValid)
            assertTrue(cache.putEphemeral(key, console))
            assertSame(console, cache.get(key))

            Disposer.dispose(console)

            assertTrue(lifetime.isDisposed)
            assertFalse(console.isValid)
            assertNull(cache.get(key))
            var failure: DatabaseToolsSqlExecutionFailure? = null
            var executed = false
            DatabaseToolsConsoleAdapter.getInstance(project).executeSql(
                console, "SELECT 1", { true }, { executed = true }, { failure = it },
            )
            assertEquals(DatabaseToolsSqlExecutionFailure.ConsoleUnavailable, failure)
            assertFalse("a disposed console must never execute", executed)
        } finally {
            if (!lifetime.isDisposed) Disposer.dispose(console)
        }
    }

    fun testInvalidConsoleStillDisposesOwnedChildrenWhenEvicted() {
        val cache = newIsolatedCache()
        val console = newConsole()
        val lifetime = Disposer.newCheckedDisposable(console)
        val key = "file:///tmp/zmybatis/InvalidConsole.xml"
        try {
            assertTrue(console.isValid)
            assertTrue(cache.putEphemeral(key, console))
            // The platform invalidates the parent before disposing its children.
            console.beforeTreeDispose()
            assertFalse(console.isValid)
            assertFalse(lifetime.isDisposed)

            cache.evict(key)

            assertTrue("invalidity must not skip actual resource cleanup", lifetime.isDisposed)
            assertNull(cache.get(key))
        } finally {
            if (!lifetime.isDisposed) Disposer.dispose(console)
        }
    }

    fun testMaterializedDeliveryRejectsUnboundArtifactBeforeMutableChecks() {
        val console = newConsole()
        val artifact = materialized("SELECT 417", target = null)
        var invocationChecks = 0
        var sourceChecks = 0
        var targetLookups = 0
        var executed = false
        var failure: DatabaseToolsSqlExecutionFailure? = null

        DatabaseToolsConsoleAdapter.getInstance(project).executeMaterialized(
            console = console,
            artifact = artifact,
            currentTarget = { targetLookups++; resolvedTarget(targetId("ds-a", "public")) },
            isInvocationActive = { invocationChecks++; true },
            areSourceRevisionsCurrent = { sourceChecks++; true },
            onExecuted = { executed = true },
            onFailure = { failure = it },
        )

        assertEquals(DatabaseToolsSqlExecutionFailure.InvocationInvalidated, failure)
        assertFalse(executed)
        assertEquals(0, invocationChecks)
        assertEquals(0, sourceChecks)
        assertEquals(0, targetLookups)
    }

    fun testMaterializedDeliveryRejectsStaleSourceBeforeTargetLookup() {
        val console = newConsole()
        val target = targetId("ds-a", "public")
        val artifact = materialized("SELECT 417", target)
        var targetLookups = 0
        var executed = false
        var failure: DatabaseToolsSqlExecutionFailure? = null

        DatabaseToolsConsoleAdapter.getInstance(project).executeMaterialized(
            console = console,
            artifact = artifact,
            currentTarget = { targetLookups++; resolvedTarget(target) },
            isInvocationActive = { true },
            areSourceRevisionsCurrent = { revisions -> revisions != artifact.sourceRevisions },
            onExecuted = { executed = true },
            onFailure = { failure = it },
        )

        assertEquals(DatabaseToolsSqlExecutionFailure.InvocationInvalidated, failure)
        assertFalse(executed)
        assertEquals(0, targetLookups)
    }

    fun testMaterializedDeliveryRejectsTargetDriftBeforeNativeExecution() {
        val console = newConsole()
        val target = targetId("ds-a", "public")
        val artifact = materialized("SELECT 417", target)
        var executed = false
        var failure: DatabaseToolsSqlExecutionFailure? = null

        DatabaseToolsConsoleAdapter.getInstance(project).executeMaterialized(
            console = console,
            artifact = artifact,
            currentTarget = { resolvedTarget(targetId("ds-b", "public")) },
            isInvocationActive = { true },
            areSourceRevisionsCurrent = { it == artifact.sourceRevisions },
            onExecuted = { executed = true },
            onFailure = { failure = it },
        )

        assertEquals(DatabaseToolsSqlExecutionFailure.InvocationInvalidated, failure)
        assertFalse(executed)
    }

    fun testMaterializedDeliveryPropagatesCancellationWithoutFailureCallback() {
        val console = newConsole()
        val target = targetId("ds-a", "public")
        val artifact = materialized("SELECT 417", target)
        var executed = false
        var failure: DatabaseToolsSqlExecutionFailure? = null
        var finished = 0

        try {
            DatabaseToolsConsoleAdapter.getInstance(project).executeMaterialized(
                console = console,
                artifact = artifact,
                currentTarget = { resolvedTarget(target) },
                isInvocationActive = { throw ProcessCanceledException() },
                areSourceRevisionsCurrent = { true },
                onExecuted = { executed = true },
                onFailure = { failure = it },
                onFinished = { finished++ },
            )
            fail("ProcessCanceledException must propagate")
        } catch (_: ProcessCanceledException) {
            // Expected: cancellation retains platform severity and is never converted to a refusal.
        }

        assertFalse(executed)
        assertNull(failure)
        assertEquals("cancellation must release terminal ownership exactly once", 1, finished)
    }

    fun testMaterializedDeliveryRejectsDisposedRealConsole() {
        val console = newConsole()
        val lifetime = Disposer.newCheckedDisposable(console)
        val target = targetId("ds-a", "public")
        val artifact = materialized("SELECT 417", target)
        var executed = false
        var failure: DatabaseToolsSqlExecutionFailure? = null
        Disposer.dispose(console)

        DatabaseToolsConsoleAdapter.getInstance(project).executeMaterialized(
            console = console,
            artifact = artifact,
            currentTarget = { resolvedTarget(target) },
            isInvocationActive = { true },
            areSourceRevisionsCurrent = { it == artifact.sourceRevisions },
            onExecuted = { executed = true },
            onFailure = { failure = it },
        )

        assertTrue(lifetime.isDisposed)
        assertEquals(DatabaseToolsSqlExecutionFailure.ConsoleUnavailable, failure)
        assertFalse(executed)
    }

    fun testMaterializedDeliveryInjectsExactArtifactSqlAndLateInvalidationRestoresConsole() {
        val console = newConsole()
        val target = targetId("ds-a", "public")
        val exactSql = "SELECT 417 AS EXACT_ARTIFACT"
        val artifact = materialized(exactSql, target)
        val editor = EditorFactory.getInstance().createEditor(console.document, project) as EditorEx
        try {
            var invocationChecks = 0
            var observedExactSql = false
            var executed = false
            var failure: DatabaseToolsSqlExecutionFailure? = null

            DatabaseToolsConsoleAdapter.getInstance(project).executeMaterialized(
                console = console,
                artifact = artifact,
                currentTarget = { resolvedTarget(target) },
                isInvocationActive = {
                    invocationChecks++
                    if (invocationChecks == 4) {
                        observedExactSql = console.document.text == exactSql
                        false
                    } else {
                        true
                    }
                },
                areSourceRevisionsCurrent = { it == artifact.sourceRevisions },
                onExecuted = { executed = true },
                onFailure = { failure = it },
            )

            assertTrue("artifact executionSql must be injected unchanged before native dispatch", observedExactSql)
            assertEquals(DatabaseToolsSqlExecutionFailure.InvocationInvalidated, failure)
            assertFalse("late invalidation must prevent native Database Tools invocation", executed)
            assertEquals("", console.document.text)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    fun testAdapterReuseReturnsSameConsoleAndRejectsOverlapUntilRelease() {
        val cache = ConsoleCacheService.getInstance(project)
        val dataSource = registeredDbDataSource(
            name = "zMyBatis reuse fixture",
            url = "jdbc:h2:mem:zmybatis_reuse",
        )

        val key = "file:///tmp/zmybatis/ReuseMapper.xml"
        val adapter = DatabaseToolsConsoleAdapter.getInstance(project)
        var first: JdbcConsole? = null
        var firstRelease: (() -> Unit)? = null
        var failure: DatabaseToolsConsoleAcquisitionFailure? = null

        try {
            adapter.acquireAndDeliverConsole(
                dataSource = dataSource,
                schema = null,
                resourceKey = key,
                forceNew = false,
                onConsoleReady = { console, release ->
                    first = console
                    firstRelease = release
                },
                onFailure = { failure = it },
            )
            assertNotNull(first)
            assertNull(failure)

            var overlapping: JdbcConsole? = null
            adapter.acquireAndDeliverConsole(
                dataSource = dataSource,
                schema = null,
                resourceKey = key,
                forceNew = false,
                onConsoleReady = { console, release ->
                    overlapping = console
                    release()
                },
                onFailure = { failure = it },
            )

            assertNull("same-source overlapping REUSE must not share the active console", overlapping)
            assertSame(DatabaseToolsConsoleAcquisitionFailure.ReuseBusy, failure)

            firstRelease!!.invoke()
            failure = null

            var reused: JdbcConsole? = null
            adapter.acquireAndDeliverConsole(
                dataSource = dataSource,
                schema = null,
                resourceKey = key,
                forceNew = false,
                onConsoleReady = { console, release ->
                    reused = console
                    release()
                },
                onFailure = { failure = it },
            )

            assertNull(failure)
            assertSame("released matching REUSE resource must be reused", first, reused)
        } finally {
            firstRelease?.invoke()
            cache.evict(key)
        }
    }

    fun testAdapterNewEachReturnsDistinctUncachedResources() {
        val cache = ConsoleCacheService.getInstance(project)
        val dataSource = registeredDbDataSource(
            name = "zMyBatis new-each fixture",
            url = "jdbc:h2:mem:zmybatis_new_each",
        )

        val key = "file:///tmp/zmybatis/NewEachMapper.xml"
        val adapter = DatabaseToolsConsoleAdapter.getInstance(project)
        val consoles = mutableListOf<JdbcConsole>()
        var failure: DatabaseToolsConsoleAcquisitionFailure? = null

        try {
            repeat(2) {
                adapter.acquireAndDeliverConsole(
                    dataSource = dataSource,
                    schema = null,
                    resourceKey = key,
                    forceNew = true,
                    onConsoleReady = { console, release ->
                        consoles += console
                        release()
                    },
                    onFailure = { failure = it },
                )
            }

            assertNull(failure)
            assertEquals(2, consoles.size)
            assertNotSame("NEW_EACH must acquire a distinct console resource", consoles[0], consoles[1])
            assertNull("NEW_EACH must not populate the REUSE cache", cache.get(key))
        } finally {
            consoles.forEach { console ->
                val lifetime = Disposer.newCheckedDisposable(console)
                if (!lifetime.isDisposed) Disposer.dispose(console)
            }
            cache.evict(key)
        }
    }

    fun testCacheTargetMismatchEvictsInsteadOfReusingWrongTarget() {
        val cache = newIsolatedCache()
        val console = newConsole()
        val lifetime = Disposer.newCheckedDisposable(console)
        val key = "file:///tmp/zmybatis/TargetMismatch.xml"
        val targetA = targetId("ds-a", "public")
        val targetB = targetId("ds-b", "public")

        assertTrue(cache.putEphemeral(key, console, targetA))
        assertNull(cache.get(key, targetB))
        assertTrue("wrong-target cache entry must be disposed after eviction", lifetime.isDisposed)
    }

    fun testReuseLeaseIsKeyScopedAndReleasable() {
        val cache = ConsoleCacheService.getInstance(project)
        val mapperKey = "file:///tmp/zmybatis/Mapper.xml"
        val otherKey = "file:///tmp/zmybatis/OtherMapper.xml"

        assertTrue(cache.beginReuseLease(mapperKey))
        assertFalse("same source must not have overlapping REUSE ownership", cache.beginReuseLease(mapperKey))
        assertTrue("unrelated sources must remain independently usable", cache.beginReuseLease(otherKey))
        cache.endReuseLease(mapperKey)
        assertTrue(cache.beginReuseLease(mapperKey))
        cache.endReuseLease(mapperKey)
        cache.endReuseLease(otherKey)
    }

    fun testCacheTargetIdentityPolicyRejectsCrossTargetAndNullMismatch() {
        val targetA = targetId("ds-a", "public")
        val targetB = targetId("ds-b", "public")

        assertTrue(consoleCacheTargetIdentityMatches(targetA, targetA))
        assertFalse(consoleCacheTargetIdentityMatches(targetA, targetB))
        assertFalse(consoleCacheTargetIdentityMatches(targetA, null))
        assertFalse(consoleCacheTargetIdentityMatches(null, targetA))
        assertTrue(
            "null-to-null is the deliberate non-persisted in-process path only",
            consoleCacheTargetIdentityMatches(null, null),
        )
    }

    fun testShutdownGateRejectsNewSelection() {
        val cache = newIsolatedCache()
        val mapperKey = "file:///tmp/zmybatis/ClosingMapper.xml"

        assertFalse(cache.isShuttingDown())
        assertTrue(cache.beginReuseLease(mapperKey))
        cache.endReuseLease(mapperKey)

        cache.markShuttingDown()

        assertTrue(cache.isShuttingDown())
        assertFalse(cache.beginReuseLease(mapperKey))
    }

    fun testProjectClosingHookEntersSameShutdownGate() {
        val cache = newIsolatedCache()

        assertFalse(cache.isShuttingDown())

        cache.handleProjectClosing(project)

        assertTrue(cache.isShuttingDown())
        assertFalse(cache.beginReuseLease("file:///tmp/zmybatis/AfterCloseMapper.xml"))
    }


    private fun materialized(
        sql: String,
        target: ExecutionTargetId?,
    ): MaterializedExecution {
        val file = SourceFileId("vfs:/Artifact.xml")
        val prepared = PreparedExecution(
            statementId = XmlStatementId(file, "fixture.Mapper", "ping"),
            statementKind = StatementKind.SELECT,
            sourceRevisions = mapOf(file to SourceRevision("artifact-r1")),
            sqlWithPlaceholders = sql,
            orderedBindings = emptyList(),
            rawInterpolations = emptyList(),
            preparationMetadata = PreparationMetadata(
                engineIdentity = "org.mybatis:mybatis",
                engineVersion = "3.5.19",
                languageDriverIdentity = "org.apache.ibatis.scripting.xmltags.XMLLanguageDriver",
            ),
        )
        val result = if (target == null) {
            ZeroBindingExecutionMaterializer.materialize(prepared)
        } else {
            TargetBoundZeroBindingExecutionMaterializer(resolvedTarget(target)).materialize(prepared)
        }
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

    private fun newIsolatedCache(): ConsoleCacheService =
        ConsoleCacheService(project).also { Disposer.register(testRootDisposable, it) }

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
            }
        ) {
            "registered LocalDataSource must be exposed as DbDataSource"
        }
    }

    private fun newConsole(): JdbcConsole {
        // Console validity includes registered datasource authority, even without a connection.
        val dataSource = LocalDataSource().apply {
            name = "zMyBatis lifetime fixture"
            driverClass = "org.h2.Driver"
            url = "jdbc:h2:mem:zmybatis_lifetime"
            isAutoSynchronize = false
        }
        val manager = LocalDataSourceManager.getInstance(project)
        manager.addDataSource(dataSource)
        Disposer.register(testRootDisposable) { manager.removeDataSource(dataSource) }
        return JdbcConsole.newConsole(project)
            .fromDataSource(dataSource)
            .forFile(LightVirtualFile("lifetime.sql", FileTypeManager.getInstance().getFileTypeByExtension("sql"), ""))
            .build()
    }
}
