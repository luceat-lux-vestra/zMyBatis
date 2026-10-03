package com.algorist.zMyBatis.services

import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleCacheOwnedResourceDisposalPolicyTest {

    @Test
    fun `cleanup skips disposed resources and attempts every live resource`() {
        val disposed = setOf("already-disposed")
        val attempted = mutableListOf<String>()

        disposeOwnedResourcesPreservingFailureSemantics(
            resources = listOf("live-a", "already-disposed", "live-b"),
            isDisposed = { it in disposed },
            disposeResource = { attempted += it },
        )

        assertEquals(listOf("live-a", "live-b"), attempted)
    }

    @Test
    fun `ordinary cleanup failures do not stop later resources and remain suppressed`() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        val attempted = mutableListOf<String>()

        val thrown = captureFailure {
            disposeOwnedResourcesPreservingFailureSemantics(
                resources = listOf("a", "b", "c"),
                isDisposed = { false },
                disposeResource = {
                    attempted += it
                    when (it) {
                        "a" -> throw first
                        "b" -> throw second
                    }
                },
            )
        }

        assertSame(first, thrown)
        assertEquals(listOf("a", "b", "c"), attempted)
        assertTrue(first.suppressed.contains(second))
    }

    @Test
    fun `cancellation outranks an earlier ordinary cleanup failure`() {
        val ordinary = IllegalStateException("ordinary")
        val cancellation = ProcessCanceledException()
        val attempted = mutableListOf<String>()

        val thrown = captureFailure {
            disposeOwnedResourcesPreservingFailureSemantics(
                resources = listOf("ordinary", "cancel", "tail"),
                isDisposed = { false },
                disposeResource = {
                    attempted += it
                    when (it) {
                        "ordinary" -> throw ordinary
                        "cancel" -> throw cancellation
                    }
                },
            )
        }

        assertSame(cancellation, thrown)
        assertEquals(listOf("ordinary", "cancel", "tail"), attempted)
        assertTrue(cancellation.suppressed.contains(ordinary))
    }

    @Test
    fun `fatal cleanup failure outranks an earlier ordinary failure and keeps cleaning`() {
        val ordinary = IllegalStateException("ordinary")
        val fatal = AssertionError("fatal")
        val attempted = mutableListOf<String>()

        val thrown = captureFailure {
            disposeOwnedResourcesPreservingFailureSemantics(
                resources = listOf("ordinary", "fatal", "tail"),
                isDisposed = { false },
                disposeResource = {
                    attempted += it
                    when (it) {
                        "ordinary" -> throw ordinary
                        "fatal" -> throw fatal
                    }
                },
            )
        }

        assertSame(fatal, thrown)
        assertEquals(listOf("ordinary", "fatal", "tail"), attempted)
        assertTrue(fatal.suppressed.contains(ordinary))
    }

    private fun captureFailure(block: () -> Unit): Throwable =
        try {
            block()
            throw AssertionError("expected cleanup failure")
        } catch (failure: Throwable) {
            failure
        }
}
