package com.termux.rust

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionStateStoreTest {
    @Test
    fun roundtrip_preserves_all_session_metadata() {
        val file = tempFile()
        val store = SessionStateStore(file)
        val sessions = listOf(
            PersistedSession(
                id = 3,
                executable = "/system/bin/sh",
                arguments = listOf("-c", "echo hi"),
                workingDirectory = "/data/data/com.termux/files/home",
                label = "build 构建",
                target = ExecutionTarget.TERMINAL_SESSION,
                terminalSize = TerminalDimensions(100, 40),
                lifecycle = PersistedLifecycle.CLOSED,
                termination = PersistedTermination(PersistedTerminationKind.EXITED, "7"),
                createdAtMs = 1_000L,
                closedAtMs = 2_000L,
            ),
            PersistedSession(
                id = 4,
                executable = "/system/bin/true",
                arguments = emptyList(),
                workingDirectory = null,
                label = null,
                target = ExecutionTarget.APP_SHELL,
                terminalSize = null,
                lifecycle = PersistedLifecycle.LIVE,
                termination = null,
                createdAtMs = 3_000L,
                closedAtMs = null,
            ),
        )

        store.save(sessions)

        assertEquals(sessions, SessionStateStore(file).load())
    }

    @Test
    fun save_is_atomic_and_leaves_no_partial_file() {
        val file = tempFile()
        val store = SessionStateStore(file)
        store.save(listOf(sampleSession(1)))

        assertTrue(file.isFile)
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
        assertEquals(1, SessionStateStore(file).load().size)
    }

    @Test
    fun corrupt_file_degrades_to_empty_without_throwing() {
        val file = tempFile()
        file.writeBytes(byteArrayOf(0, 1, 2, 3, 4))

        assertTrue(SessionStateStore(file).load().isEmpty())
    }

    @Test
    fun old_or_unknown_schema_version_degrades_to_empty() {
        val file = tempFile()
        file.writeText("TSES0\nsession\t1\tx\n")

        assertTrue(SessionStateStore(file).load().isEmpty())
    }

    @Test
    fun partially_written_file_is_rejected_as_a_whole() {
        val file = tempFile()
        val store = SessionStateStore(file)
        store.save(listOf(sampleSession(1), sampleSession(2)))
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size / 2))

        assertTrue(SessionStateStore(file).load().isEmpty())
    }

    @Test
    fun missing_file_loads_as_empty() {
        assertTrue(SessionStateStore(tempFile()).load().isEmpty())
    }

    @Test
    fun non_positive_and_overflow_ids_reject_the_whole_file() {
        listOf("0", "-5", "9999999999999999999999").forEach { badId ->
            val file = tempFile()
            SessionStateStore(file).save(listOf(sampleSession(1)))
            val content = file.readText().replace("session\t1\t", "session\t$badId\t")
            file.writeText(content)

            assertTrue(SessionStateStore(file).load().isEmpty(), "id $badId must reject")
        }
    }

    @Test
    fun duplicate_ids_reject_the_whole_file() {
        val file = tempFile()
        SessionStateStore(file).save(listOf(sampleSession(7), sampleSession(7)))

        assertTrue(SessionStateStore(file).load().isEmpty())
    }

    @Test
    fun nul_in_request_fields_rejects_the_whole_file() {
        val file = tempFile()
        val store = SessionStateStore(file)
        val evil = sampleSession(1).copy(executable = "/system/bin/sh\u0000x")
        // Bypass formatLine (which would encode it happily) by hand-crafting.
        store.save(listOf(sampleSession(2)))
        val good = file.readText()
        val evilLine = good.lines().first { it.startsWith("session") }
            .split("\t").toMutableList()
        val b64 = java.util.Base64.getEncoder().encodeToString(evil.executable.encodeToByteArray())
        evilLine[12] = b64
        file.writeText(good.lines().first() + "\n" + evilLine.joinToString("\t") + "\n")

        assertTrue(SessionStateStore(file).load().isEmpty())
    }

    @Test
    fun oversized_file_and_too_many_records_reject() {
        val big = tempFile()
        big.writeText("TSES1\n" + "x".repeat(300 * 1024))
        assertTrue(SessionStateStore(big).load().isEmpty())

        // 40 records exceeds the registry-capacity bound; craft the file
        // directly (save() itself enforces the same bound).
        val seed = tempFile()
        SessionStateStore(seed).save(listOf(sampleSession(1)))
        val line = seed.readLines().first { it.startsWith("session") }
        val storeFile = tempFile()
        storeFile.writeText("TSES1\n" + (1..40).joinToString("\n") { line })
        assertTrue(SessionStateStore(storeFile).load().isEmpty())
    }

    @Test
    fun save_reports_honest_sync_level_and_preserves_old_file_on_failure() {
        val file = tempFile()
        val store = SessionStateStore(file)
        store.save(listOf(sampleSession(1)))
        val healthyBefore = file.readText()

        // Sync level is reported, never silently upgraded.
        assertTrue(
            store.lastSaveSyncLevel == SaveSyncLevel.FULL ||
                store.lastSaveSyncLevel == SaveSyncLevel.RENAME_ONLY,
        )

        // Make the tmp path unusable: replace it with a directory.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.mkdirs()
        try {
            assertFailsWith<Exception> { store.save(listOf(sampleSession(2))) }
            assertEquals(healthyBefore, file.readText())
        } finally {
            tmp.deleteRecursively()
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
        File.createTempFile("session-state", ".bin").apply {
            delete()
            deleteOnExit()
        }
}
