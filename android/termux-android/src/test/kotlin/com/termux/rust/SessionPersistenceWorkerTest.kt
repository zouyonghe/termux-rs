package com.termux.rust

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionPersistenceWorkerTest {
    @Test
    fun submit_latest_coalesces_to_only_the_newest_snapshot() {
        val file = tempFile()
        val worker = SessionPersistenceWorker(SessionStateStore(file))

        repeat(20) { index ->
            worker.submitLatest(listOf(sampleSession(index.toLong())))
        }
        assertTrue(worker.shutdown(5_000))

        val loaded = SessionStateStore(file).load()
        assertEquals(1, loaded.size)
        // Whatever intermediate states were dropped, the FINAL write is the
        // newest snapshot, never a stale one.
        assertTrue(loaded.single().id >= 0)
        assertEquals(sampleSession(19).id, loaded.single().id)
    }

    @Test
    fun shutdown_flushes_pending_write_within_bound() {
        val file = tempFile()
        val worker = SessionPersistenceWorker(SessionStateStore(file))

        worker.submitLatest(listOf(sampleSession(3)))
        assertTrue(worker.shutdown(5_000))

        assertEquals(listOf(sampleSession(3)), SessionStateStore(file).load())
    }

    @Test
    fun load_async_runs_off_the_caller_thread() {
        val file = tempFile()
        SessionStateStore(file).save(listOf(sampleSession(1)))
        val worker = SessionPersistenceWorker(SessionStateStore(file))
        val caller = Thread.currentThread().name

        val latch = CountDownLatch(1)
        var loaded = emptyList<PersistedSession>()
        var workerThread = ""
        worker.loadAsync { records ->
            loaded = records
            workerThread = Thread.currentThread().name
            latch.countDown()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, loaded.size)
        assertTrue(workerThread.startsWith("termux-persistence"))
        assertTrue(workerThread != caller)
        worker.shutdownNow()
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
        File.createTempFile("session-worker", ".bin").apply {
            delete()
            deleteOnExit()
        }
}
