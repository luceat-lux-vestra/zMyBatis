package com.algorist.zMyBatis.execution

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class PreparedResultPresentationAdapterProjectFixtureTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testPresentationDispatchRunsOnEdtWithImmutableSnapshot() {
        val onEdt = AtomicBoolean(false)
        val observed = AtomicReference<PreparedResultPresentation.Query>()
        val presented = CompletableFuture<Unit>()
        val finished = CompletableFuture<Unit>()
        val ui = recordingUi(
            onQuery = { presentation ->
                onEdt.set(ApplicationManager.getApplication().isDispatchThread)
                observed.set(presentation)
                presented.complete(Unit)
            },
        )

        PreparedResultPresentationAdapter(project, ui).present(
            outcome = DatabaseSessionPreparedOutcome.Query(
                columns = listOf(
                    PreparedResultColumn(1, "answer", "BIGINT"),
                ),
                rows = listOf(listOf("42")),
            ),
            onFinished = { finished.complete(Unit) },
        )

        PlatformTestUtil.waitForFuture(presented, FIXTURE_TIMEOUT_MILLIS)
        PlatformTestUtil.waitForFuture(finished, FIXTURE_TIMEOUT_MILLIS)

        assertTrue("prepared result UI must dispatch on EDT", onEdt.get())
        assertEquals("42", observed.get().rows.single().single())
    }

    fun testInvocationInvalidationSuppressesAllPresentation() {
        val uiCalled = AtomicBoolean(false)
        val finished = CompletableFuture<Unit>()
        val ui = recordingUi(
            onQuery = { uiCalled.set(true) },
            onInfo = { _, _ -> uiCalled.set(true) },
            onError = { _, _ -> uiCalled.set(true) },
        )

        PreparedResultPresentationAdapter(project, ui).present(
            outcome = DatabaseSessionPreparedOutcome.Update(1),
            isInvocationActive = { false },
            onFinished = { finished.complete(Unit) },
        )

        PlatformTestUtil.waitForFuture(finished, FIXTURE_TIMEOUT_MILLIS)
        assertFalse(
            "invalidated invocation must not display a stale prepared result",
            uiCalled.get(),
        )
    }

    fun testTypedFailureDispatchUsesOnlyBoundedPresentationText() {
        val observed = CompletableFuture<Pair<String, String>>()
        val finished = CompletableFuture<Unit>()
        val ui = recordingUi(
            onError = { title, message -> observed.complete(title to message) },
        )

        PreparedResultPresentationAdapter(project, ui).present(
            outcome = DatabaseSessionPreparedOutcome.Failed(
                DatabaseSessionPreparedFailure(
                    kind = DatabaseSessionPreparedFailureKind.DATABASE_ERROR,
                    code = "prepared-backend-database-error",
                    diagnosticType = "fixture.SecretRuntimeType",
                    sqlState = "22012",
                ),
            ),
            onFinished = { finished.complete(Unit) },
        )

        val presentation = PlatformTestUtil.waitForFuture(observed, FIXTURE_TIMEOUT_MILLIS)
        PlatformTestUtil.waitForFuture(finished, FIXTURE_TIMEOUT_MILLIS)

        assertEquals("zMyBatis: Database Execution Failed", presentation.first)
        assertTrue(presentation.second.contains("prepared-backend-database-error"))
        assertTrue(presentation.second.contains("SQLSTATE=22012"))
        assertFalse(presentation.second.contains("SecretRuntimeType"))
    }

    private fun recordingUi(
        onQuery: (PreparedResultPresentation.Query) -> Unit = {},
        onInfo: (String, String) -> Unit = { _, _ -> },
        onError: (String, String) -> Unit = { _, _ -> },
    ): PreparedResultUi =
        object : PreparedResultUi {
            override fun showQuery(presentation: PreparedResultPresentation.Query) {
                onQuery(presentation)
            }

            override fun showInfo(title: String, message: String) {
                onInfo(title, message)
            }

            override fun showError(title: String, message: String) {
                onError(title, message)
            }
        }

    companion object {
        private const val FIXTURE_TIMEOUT_MILLIS = 10_000L
    }
}
