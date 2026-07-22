package com.termux.rust

import java.io.ByteArrayOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

/** Test-only zip fixtures with unix mode bits (S_IFMT|perms: 0o100644
 *  regular, 0o120777 symlink). Entry paths are prefix-relative, matching the
 *  real Termux bootstrap layout (bin/, lib/, ... under the prefix root). */
internal fun goodZip(entries: Int = 2): ByteArray = buildZip {
    if (entries <= 2) {
        entry("bin/tool", 0b100000111101101, "#!/system/bin/sh\necho installed\n")
        entry("lib/data.txt", 0b100000100100100, "payload")
    } else {
        repeat(entries) { index ->
            entry("lib/file-$index.txt", 0b100000100100100, "data-$index")
        }
    }
}

internal fun evilZip(
    absoluteEntry: Boolean = false,
    parentEntry: Boolean = false,
    symlinkEntry: Boolean = false,
    duplicateEntry: Boolean = false,
    deviceEntry: Boolean = false,
    nulEntry: Boolean = false,
): ByteArray = buildZip {
    when {
        absoluteEntry -> entry("/data/data/com.termux/escape.txt", 0b100000100100100, "x")
        parentEntry -> entry("bin/../../escape.txt", 0b100000100100100, "x")
        symlinkEntry -> entry("bin/link", 0b1010000111111111, "target")
        duplicateEntry -> {
            entry("lib/dup.txt", 0b100000100100100, "one")
            entry("lib/dup.txt", 0b100000100100100, "two")
        }
        deviceEntry -> entry("dev/null0", 0b011000100100100, "")
        nulEntry -> entry("bad\u0000name", 0b100000100100100, "x")
    }
}

internal fun conflictZip(fileFirst: Boolean): ByteArray = buildZip {
    if (fileFirst) {
        // `a` as a file, then `a/b` beneath it.
        entry("a", 0b100000100100100, "x")
        entry("a/b", 0b100000100100100, "y")
    } else {
        // `a/` as a directory, then `a` as a file.
        entry("a/", 0b010000011101101, "")
        entry("a", 0b100000100100100, "x")
    }
}

private fun buildZip(build: ZipBuilder.() -> Unit): ByteArray {
    val output = ByteArrayOutputStream()
    ZipArchiveOutputStream(output).use { zip ->
        val builder = ZipBuilder(zip)
        builder.build()
    }
    return output.toByteArray()
}

private class ZipBuilder(private val zip: ZipArchiveOutputStream) {
    fun entry(name: String, mode: Int, content: String) {
        val entry = ZipArchiveEntry(name)
        entry.unixMode = mode
        val bytes = content.encodeToByteArray()
        entry.size = bytes.size.toLong()
        zip.putArchiveEntry(entry)
        zip.write(bytes)
        zip.closeArchiveEntry()
    }
}
