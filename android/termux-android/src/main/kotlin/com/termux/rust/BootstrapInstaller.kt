package com.termux.rust

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import org.apache.commons.compress.archivers.zip.ZipFile

/** Stable failure categories for bootstrap installation. */
internal enum class BootstrapFailure {
    /** No bundled payload is available (repo/device has no archive). */
    MISSING,
    /** Payload is not a readable zip or fails its checksum. */
    CORRUPT,
    /** Payload entries violate the extraction policy. */
    UNSUPPORTED,
    /** Filesystem I/O failed. */
    STORAGE,
}

internal class BootstrapInstallException(
    val failure: BootstrapFailure,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("$failure: $message", cause)

internal enum class BootstrapInstallState {
    NOT_INSTALLED,
    INSTALLING,
    READY,
    FAILED,
}

internal data class BootstrapInstallLimits(
    val maxEntries: Int = 20_000,
    val maxTotalBytes: Long = 768L * 1024 * 1024,
)

/** Source of the bundled bootstrap payload. */
internal interface BootstrapPayloadSource {
    val version: String
    val expectedSha256: String?

    /** Opens the payload, or returns null when none is bundled. */
    fun open(): InputStream?
}

/** Test fixture source backed by an in-memory zip. */
internal class ZipPayloadSource(
    private val bytes: ByteArray,
    override val version: String = "test-1",
    override val expectedSha256: String? = bytes.sha256Hex(),
) : BootstrapPayloadSource {
    override fun open(): InputStream = bytes.inputStream()
}

/** Test source with metadata only: proves restart recognition uses the
 *  version/checksum metadata, not the payload bytes. */
internal class MetadataOnlyPayloadSource(
    override val version: String,
    override val expectedSha256: String?,
) : BootstrapPayloadSource {
    override fun open(): InputStream? = null
}

internal object MissingPayloadSource : BootstrapPayloadSource {
    override val version = "none"
    override val expectedSha256: String? = null
    override fun open(): InputStream? = null
}

/**
 * Installs a verified bundled payload into an app-private root.
 *
 * Protocol:
 * - **single-flight across instances**: an in-JVM lock plus an app-private
 *   lock file (`root/.bootstrap.lock`) serialize every installer instance;
 *   marker/recovery state is re-checked inside the lock
 * - **crash recovery**: an interrupted publish is healed before any new
 *   work — `usr.old` with a valid marker is restored when `usr` is missing,
 *   and a stale `usr.old` is removed only once the new `usr` is healthy
 * - **verified payload**: the source must carry a non-empty version and a
 *   64-hex SHA-256; an unverifiable payload is never installed and a marker
 *   is never trusted without matching metadata
 * - **staging + atomic publish**: extract to `staging/` under strict entry
 *   policy, then swap via rename; the previous healthy install survives
 *   every failure and every crash point
 *
 * All I/O happens on the calling thread — callers must keep this off the
 * main/Binder/FFI-owner threads (a dedicated executor is fine).
 */
internal class BootstrapInstaller(
    private val root: File,
    private val payload: BootstrapPayloadSource,
    private val limits: BootstrapInstallLimits = BootstrapInstallLimits(),
) {
    private val liveDir = File(root, "usr")
    private val stagingDir = File(root, "staging")
    private val backupDir = File(root, "usr.old")
    private val lockFile = File(root, LOCK_NAME)
    private val payloadFile = File(root, PAYLOAD_STAGING_NAME)

    @Volatile
    private var currentState = BootstrapInstallState.NOT_INSTALLED

    /**
     * READY is always proven by the on-disk marker — an in-process cache
     * never masks a deleted or corrupted marker.
     */
    @Synchronized
    fun state(): BootstrapInstallState =
        if (markerValid(liveDir)) {
            BootstrapInstallState.READY
        } else if (currentState == BootstrapInstallState.READY) {
            BootstrapInstallState.NOT_INSTALLED.also { currentState = it }
        } else {
            currentState
        }

    fun installIfNeeded() = withInstallLock {
        recoverInterruptedPublish()
        if (markerValid(liveDir)) {
            backupDir.deleteRecursively()
            currentState = BootstrapInstallState.READY
            return@withInstallLock
        }
        currentState = BootstrapInstallState.INSTALLING
        try {
            val stream = payload.open()
                ?: throw BootstrapInstallException(
                    BootstrapFailure.MISSING,
                    "no bootstrap payload bundled for this build",
                )
            val expected = payload.expectedSha256
                ?: throw BootstrapInstallException(
                    BootstrapFailure.CORRUPT,
                    "payload carries no checksum metadata; refusing unverified install",
                )
            stream.use { input ->
                stagePayload(input, expected)
                extract()
                payloadFile.delete()
                writeMarker(expected)
                publish()
            }
            currentState = BootstrapInstallState.READY
        } catch (error: BootstrapInstallException) {
            cleanupFailedInstall()
            currentState = BootstrapInstallState.FAILED
            throw error
        } catch (error: Throwable) {
            cleanupFailedInstall()
            currentState = BootstrapInstallState.FAILED
            throw BootstrapInstallException(BootstrapFailure.STORAGE, "I/O failed", error)
        }
    }

    @Synchronized
    fun cleanupFailedInstall() {
        stagingDir.deleteRecursively()
        payloadFile.delete()
    }

    /** Whether [executable] lives under the installed prefix and therefore
     *  requires a READY bootstrap. Absolute system paths (e.g. /system/bin)
     *  never require it. */
    fun requiresBootstrapPath(executable: String): Boolean =
        executable.startsWith(liveDir.absolutePath + "/")

    // --- locking ---------------------------------------------------------------

    private fun <T> withInstallLock(block: () -> T): T {
        val jvmLock = JVM_LOCKS.getOrPut(root.canonicalPath) { ReentrantLock() }
        jvmLock.lock()
        try {
            lockFile.parentFile?.mkdirs()
            val channel = try {
                RandomAccessFile(lockFile, "rw").channel
            } catch (error: Throwable) {
                throw BootstrapInstallException(BootstrapFailure.STORAGE, "cannot open install lock", error)
            }
            channel.use { held ->
                return try {
                    held.lock().use { block() }
                } catch (error: OverlappingFileLockException) {
                    // Same-JVM overlap: the ReentrantLock above already
                    // serialized us, so proceeding is safe.
                    block()
                }
            }
        } finally {
            jvmLock.unlock()
        }
    }

    // --- crash recovery ---------------------------------------------------------

    /** Heals an interrupted publish. `usr.old` is only ever removed once a
     *  healthy `usr` exists, so the last good install survives any crash. */
    private fun recoverInterruptedPublish() {
        val liveOk = markerValid(liveDir)
        val backupOk = backupDir.isDirectory && markerValid(backupDir)
        if (!liveOk && backupOk) {
            liveDir.deleteRecursively()
            if (!backupDir.renameTo(liveDir)) {
                throw BootstrapInstallException(BootstrapFailure.STORAGE, "cannot restore previous install")
            }
        }
        if (markerValid(liveDir) && backupDir.exists()) {
            backupDir.deleteRecursively()
        }
        stagingDir.deleteRecursively()
    }

    // --- staging ----------------------------------------------------------------

    private fun stagePayload(input: InputStream, expected: String) {
        payloadFile.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        payloadFile.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        val actual = digest.digest().toHex()
        if (!expected.equals(actual, ignoreCase = true)) {
            payloadFile.delete()
            throw BootstrapInstallException(
                BootstrapFailure.CORRUPT,
                "checksum mismatch: expected $expected got $actual",
            )
        }
    }

    // --- extraction policy ---------------------------------------------------

    private fun extract() {
        stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs()) {
            throw BootstrapInstallException(BootstrapFailure.STORAGE, "cannot create staging dir")
        }
        val canonicalRoot = stagingDir.canonicalFile
        val seen = mutableSetOf<String>()
        var entryCount = 0
        var totalBytes = 0L

        val zip = try {
            ZipFile.builder().setFile(payloadFile).get()
        } catch (error: Throwable) {
            throw BootstrapInstallException(BootstrapFailure.CORRUPT, "payload is not a zip archive", error)
        }
        zip.use { archive ->
            val entries = archive.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount += 1
                if (entryCount > limits.maxEntries) {
                    throw BootstrapInstallException(BootstrapFailure.UNSUPPORTED, "too many entries")
                }
                val name = entry.name
                if (name.isEmpty() || name.startsWith('/') || '\u0000' in name ||
                    name.split('/').any { it == ".." }
                ) {
                    throw BootstrapInstallException(BootstrapFailure.UNSUPPORTED, "unsafe path: $name")
                }
                if (!seen.add(name)) {
                    throw BootstrapInstallException(BootstrapFailure.UNSUPPORTED, "duplicate entry: $name")
                }
                val mode = entry.unixMode
                val type = mode ushr 12
                if (mode != 0 && type != REGULAR_FILE && type != DIRECTORY) {
                    throw BootstrapInstallException(
                        BootstrapFailure.UNSUPPORTED,
                        "special entry (symlink/device) rejected: $name",
                    )
                }
                val target = File(stagingDir, name)
                if (!target.canonicalFile.toPath().startsWith(canonicalRoot.toPath())) {
                    throw BootstrapInstallException(BootstrapFailure.UNSUPPORTED, "escape attempt: $name")
                }
                if (entry.isDirectory || name.endsWith('/')) {
                    if (target.exists() && !target.isDirectory) {
                        throw BootstrapInstallException(
                            BootstrapFailure.UNSUPPORTED,
                            "directory conflicts with file: $name",
                        )
                    }
                    if (!target.isDirectory && !target.mkdirs()) {
                        throw BootstrapInstallException(BootstrapFailure.STORAGE, "mkdir failed: $name")
                    }
                    continue
                }
                requireFileAncestors(target, canonicalRoot, name)
                if (target.isDirectory) {
                    throw BootstrapInstallException(
                        BootstrapFailure.UNSUPPORTED,
                        "file conflicts with directory: $name",
                    )
                }
                archive.getInputStream(entry).use { entryInput ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = entryInput.read(buffer)
                            if (read < 0) break
                            totalBytes += read
                            if (totalBytes > limits.maxTotalBytes) {
                                throw BootstrapInstallException(BootstrapFailure.UNSUPPORTED, "payload too large")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                applyMode(target, name, mode)
            }
        }
        if (entryCount == 0) {
            throw BootstrapInstallException(BootstrapFailure.CORRUPT, "payload is not a zip archive")
        }
    }

    /** Every existing ancestor of [target] up to the staging root must be a
     *  directory; `a` as file followed by `a/b` is a policy violation, not
     *  an incidental I/O error. */
    private fun requireFileAncestors(target: File, canonicalRoot: File, name: String) {
        // Canonicalize: on some filesystems the staging root itself sits
        // behind a symlink, so raw paths would never prefix-match.
        var parent = target.canonicalFile.parentFile
        while (parent != null && parent.toPath().startsWith(canonicalRoot.toPath()) && parent != canonicalRoot) {
            if (parent.exists() && !parent.isDirectory) {
                throw BootstrapInstallException(
                    BootstrapFailure.UNSUPPORTED,
                    "entry conflicts with non-directory ancestor: $name",
                )
            }
            parent = parent.parentFile
        }
        target.parentFile?.let {
            if (!it.isDirectory && !it.mkdirs()) {
                throw BootstrapInstallException(BootstrapFailure.STORAGE, "mkdir failed for: $name")
            }
        }
    }

    private fun applyMode(target: File, name: String, mode: Int) {
        val executable = (mode and 0b001001000) != 0 || name.startsWith("bin/") || name.contains("/bin/")
        val ok = target.setReadable(true, true) &&
            target.setWritable(true, true) &&
            target.setExecutable(executable, true)
        if (!ok) {
            throw BootstrapInstallException(BootstrapFailure.STORAGE, "cannot set permissions on: $name")
        }
    }

    // --- marker + publish ----------------------------------------------------

    private fun writeMarker(sha256: String) {
        File(stagingDir, MARKER_NAME).writeText(
            "version=${payload.version}\nsha256=$sha256\n",
        )
    }

    private fun markerValid(dir: File): Boolean {
        val marker = File(dir, MARKER_NAME)
        if (!dir.isDirectory || !marker.isFile) return false
        val lines = marker.readLines().associate {
            val parts = it.split('=', limit = 2)
            parts[0] to parts.getOrElse(1) { "" }
        }
        val expected = payload.expectedSha256 ?: return false
        return lines["version"] == payload.version &&
            !lines["version"].isNullOrEmpty() &&
            lines["sha256"].equals(expected, ignoreCase = true)
    }

    /** Swap staging into place. Never deletes the previous install first;
     *  recovery at next start heals any crash point. */
    private fun publish() {
        val hadLive = liveDir.exists()
        if (hadLive && !liveDir.renameTo(backupDir)) {
            throw BootstrapInstallException(BootstrapFailure.STORAGE, "cannot move old install aside")
        }
        if (!stagingDir.renameTo(liveDir)) {
            if (hadLive) backupDir.renameTo(liveDir)
            throw BootstrapInstallException(BootstrapFailure.STORAGE, "cannot publish staging")
        }
        if (markerValid(liveDir)) {
            backupDir.deleteRecursively()
        }
    }

    private companion object {
        const val MARKER_NAME = ".bootstrap-meta"
        const val PAYLOAD_STAGING_NAME = "bootstrap-payload.zip"
        const val LOCK_NAME = ".bootstrap.lock"
        const val REGULAR_FILE = 0b1000
        const val DIRECTORY = 0b0100

        private val JVM_LOCKS = ConcurrentHashMap<String, ReentrantLock>()
    }
}

private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).toHex()

private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }
