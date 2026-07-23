package com.termux.rust

import java.io.File
import java.io.FileOutputStream
import java.util.Base64

internal enum class PersistedLifecycle { LIVE, CLOSED }

internal enum class PersistedTerminationKind { EXITED, CANCELLED, TIMEOUT, FAILED }

internal data class PersistedTermination(
    val kind: PersistedTerminationKind,
    val value: String,
)

/**
 * Metadata of one session that is SAFE to rebuild after a service restart.
 * Never contains live handles, pending input, or anything that would let a
 * dead native child appear alive.
 */
internal data class PersistedSession(
    val id: Long,
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: String?,
    val label: String?,
    val target: ExecutionTarget,
    val terminalSize: TerminalDimensions?,
    val lifecycle: PersistedLifecycle,
    val termination: PersistedTermination?,
    val createdAtMs: Long,
    val closedAtMs: Long?,
)

/** Durability actually achieved by a save. */
internal enum class SaveSyncLevel {
    /** Payload fsync + atomic rename + directory fsync. */
    FULL,

    /** Payload fsync + atomic rename; directory fsync unavailable or failed.
     *  Durability of the rename itself is NOT guaranteed — never claim it. */
    RENAME_ONLY,
}

/**
 * Versioned, bounded, atomically replaced session-metadata store.
 *
 * Two validation layers, deliberately distinct:
 * 1. `load` is **all-or-nothing**: any size/record/field/id/request/
 *    termination/duplicate-id violation degrades the WHOLE file to empty.
 *    A file we cannot fully trust is treated as absent.
 * 2. `SessionRegistry.restoreEntry` is **defense-in-depth skip**: if a
 *    record somehow passes load yet violates the live contract, only that
 *    record is dropped. It exists so a contract change can never crash the
 *    service; it is not the primary corruption path.
 *
 * - load is all-or-nothing: any size/record/field/id/request/termination/
 *   duplicate-id violation degrades the whole file to empty (never throws)
 * - save writes a same-directory tmp file, fsyncs the payload, renames
 *   atomically, then attempts a directory fsync on a best-effort basis and
 *   reports the achieved [SaveSyncLevel] honestly
 * - on any save failure the previous healthy file is preserved and the tmp
 *   file is removed
 */
internal open class SessionStateStore(
    private val file: File,
) {
    /** Outcome of the last save (tests observe degraded durability here). */
    @Volatile
    var lastSaveSyncLevel: SaveSyncLevel? = null
        private set

    open fun load(): List<PersistedSession> = runCatching {
        if (!file.isFile) return emptyList()
        if (file.length() > MAX_FILE_BYTES) return emptyList()
        val lines = file.readText(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        if (lines.isEmpty() || lines[0] != MAGIC) return emptyList()
        val records = lines.drop(1)
        require(records.size <= MAX_RECORDS) { "too many records" }
        val seen = mutableSetOf<Long>()
        records.map { line ->
            val session = parseLine(line)
            require(seen.add(session.id)) { "duplicate session id" }
            session
        }
    }.getOrNull() ?: emptyList()

    open fun save(sessions: List<PersistedSession>): SaveSyncLevel {
        require(sessions.size <= MAX_RECORDS) { "too many sessions to persist" }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        val body = buildString {
            appendLine(MAGIC)
            sessions.forEach { appendLine(formatLine(it)) }
        }
        try {
            FileOutputStream(tmp).use { out ->
                out.write(body.encodeToByteArray())
                out.fd.sync()
            }
        } catch (error: Throwable) {
            tmp.delete()
            throw error
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IllegalStateException("cannot publish session state file")
        }
        lastSaveSyncLevel = syncDirectoryBestEffort()
        return lastSaveSyncLevel!!
    }

    /** Directory fsync for rename durability. Opening a directory read-only
     *  works on Linux/Android; anywhere it fails we degrade explicitly and
     *  never claim full durability. */
    private fun syncDirectoryBestEffort(): SaveSyncLevel =
        try {
            FileOutputStream(file.parentFile).use { it.fd.sync() }
            SaveSyncLevel.FULL
        } catch (error: Throwable) {
            SaveSyncLevel.RENAME_ONLY
        }

    private fun formatLine(session: PersistedSession): String = buildList {
        add("session")
        add(session.id.toString())
        add(session.target.name)
        add(session.lifecycle.name)
        add(session.terminalSize?.columns?.toString() ?: MISSING)
        add(session.terminalSize?.rows?.toString() ?: MISSING)
        add(session.createdAtMs.toString())
        add(session.closedAtMs?.toString() ?: MISSING)
        add(session.termination?.kind?.name ?: MISSING)
        add(session.termination?.value ?: MISSING)
        add(session.label.b64() ?: MISSING)
        add(session.workingDirectory.b64() ?: MISSING)
        add(session.executable.b64()!!)
        add(session.arguments.joinToString(US).b64()!!)
    }.joinToString("\t")

    private fun parseLine(line: String): PersistedSession {
        require(line.length <= MAX_LINE_CHARS) { "line too long" }
        val fields = line.split("\t")
        require(fields.size == 14) { "bad field count" }
        require(fields[0] == "session") { "bad record tag" }
        val id = fields[1].toLong()
        require(id > 0) { "session id must be positive" }
        val columns = fields[4].missingToNull()?.toInt()
        val rows = fields[5].missingToNull()?.toInt()
        val lifecycle = PersistedLifecycle.valueOf(fields[3])
        val termination = fields[8].missingToNull()?.let { kind ->
            val value = fields[9]
            when (PersistedTerminationKind.valueOf(kind)) {
                PersistedTerminationKind.EXITED -> {
                    val code = value.toInt()
                    require(code in 0..255) { "exit code out of range" }
                    PersistedTermination(PersistedTerminationKind.EXITED, value)
                }
                PersistedTerminationKind.CANCELLED -> {
                    CancellationReason.valueOf(value)
                    PersistedTermination(PersistedTerminationKind.CANCELLED, value)
                }
                PersistedTerminationKind.TIMEOUT -> {
                    require(value.toLong() > 0) { "timeout must be positive" }
                    PersistedTermination(PersistedTerminationKind.TIMEOUT, value)
                }
                PersistedTerminationKind.FAILED -> {
                    AppShellErrorCode.valueOf(value)
                    PersistedTermination(PersistedTerminationKind.FAILED, value)
                }
            }
        }
        // CLOSED sessions must carry a terminal result (contract invariant).
        require(lifecycle != PersistedLifecycle.CLOSED || termination != null) {
            "closed session without termination"
        }
        val executable = requireNotNull(fields[12].unb64())
        require(executable.isNotEmpty() && '\u0000' !in executable) { "invalid executable" }
        val arguments = requireNotNull(fields[13].unb64())
            .split(US)
            .filter { it.isNotEmpty() }
        require(arguments.none { '\u0000' in it }) { "argument must not contain NUL" }
        val workingDirectory = fields[11].unb64()
        require(workingDirectory == null || '\u0000' !in workingDirectory) { "cwd must not contain NUL" }
        val label = fields[10].unb64()
        require(label == null || '\u0000' !in label) { "label must not contain NUL" }
        require(executable.length <= MAX_FIELD_CHARS) { "executable too long" }
        return PersistedSession(
            id = id,
            target = ExecutionTarget.valueOf(fields[2]),
            lifecycle = lifecycle,
            terminalSize = if (columns != null && rows != null) TerminalDimensions(columns, rows) else null,
            createdAtMs = fields[6].toLong(),
            closedAtMs = fields[7].missingToNull()?.toLong(),
            termination = termination,
            label = label,
            workingDirectory = workingDirectory,
            executable = executable,
            arguments = arguments,
        )
    }

    private fun String?.b64(): String? =
        this?.let { Base64.getEncoder().encodeToString(it.encodeToByteArray()) }

    private fun String.unb64(): String? {
        if (this == MISSING) return null
        require(length <= MAX_FIELD_CHARS * 2) { "field too long" }
        return String(Base64.getDecoder().decode(this))
    }

    private fun String.missingToNull(): String? = if (this == MISSING) null else this

    private companion object {
        const val MAGIC = "TSES1"
        const val MISSING = "-"
        const val US = "\u001f"
        const val MAX_FILE_BYTES = 256L * 1024
        const val MAX_RECORDS = 32 // never exceed registry capacity
        const val MAX_LINE_CHARS = 64 * 1024
        const val MAX_FIELD_CHARS = 16 * 1024
    }
}
