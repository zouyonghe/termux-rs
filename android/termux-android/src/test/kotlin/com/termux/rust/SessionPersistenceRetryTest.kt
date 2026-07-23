package com.termux.rust

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionPersistenceRetryTest {
    @Test
    fun transient_failure_is_retried_and_lands_without_new_state_change() {
        val file = tempFile()
        val store = FlakyStore(file, failuresBeforeSuccess = 1)
        val worker = SessionPersistenceWorker(store)

        worker.submitLatest(listOf(sampleSession(1)))
        // No further submissions: the bounded backoff retry must still land
        // the snapshot.
        assertTrue(worker.shutdown(5_000))

        assertEquals(2, store.saveCalls)
        assertEquals(listOf(sampleSession(1)), SessionStateStore(file).load())
    }

    @Test
    fun newer_snapshot_replaces_a_failed_older_one_during_backoff() {
        val file = tempFile()
        val store = FlakyStore(file, failuresBeforeSuccess = 1)
        val worker = SessionPersistenceWorker(store)

        worker.submitLatest(listOf(sampleSession(1)))
        awaitTrue("first save attempt failed") { store.saveCalls >= 1 }
        worker.submitLatest(listOf(sampleSession(2), sampleSession(3)))
        assertTrue(worker.shutdown(5_000))

        // The failed old snapshot must never win: final state is the newest.
        assertEquals(listOf(sampleSession(2), sampleSession(3)), SessionStateStore(file).load())
    }

    @Test
    fun shutdown_flushes_a_snapshot_sitting_in_retry_backoff() {
        val file = tempFile()
        val store = FlakyStore(file, failuresBeforeSuccess = 3)
        val worker = SessionPersistenceWorker(store)

        worker.submitLatest(listOf(sampleSession(4)))
        awaitTrue("failure streak started") { store.saveCalls >= 1 }
        // Shutdown must not wait out the backoff to persist the final state.
        val flushed = worker.shutdown(5_000)

        assertEquals(listOf(sampleSession(4)), SessionStateStore(file).load())
    }

    private fun awaitTrue(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out: $description")
    }

    private class FlakyStore(
        file: File,
        private val failuresBeforeSuccess: Int,
    ) : SessionStateStore(file) {
        @Volatile
        var saveCalls = 0

        override fun save(sessions: List<PersistedSession>): SaveSyncLevel {
            saveCalls += 1
            if (saveCalls <= failuresBeforeSuccess) {
                throw IllegalStateException("injected transient failure")
            }
            return super.save(sessions)
        }
    }

    private fun sampleSession(id: Long) = PersistedSession(
        id = id,
        executable = "/system/bin/sh",
        arguments = emptyList(),
        workingDirectory = null,
        label = null,
        target = ExecutionTarget.TERMINAL_SESSION,
        terminalSize = TerminalDimensions(80, 24),
        lifecycle = PersistedLifecycle.LIVE,
        termination = null,
        createdAtMs = 0L,
        closedAtMs = null,
    )

    private fun tempFile(): File =
        File.createTempFile("session-retry", ".bin").apply {
            delete()
            deleteOnExit()
        }
}
