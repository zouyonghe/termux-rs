package com.termux.rust

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalSnapshotCodecTest {
    @Test
    fun decodes_empty_snapshot() {
        val snapshot = TerminalSnapshotCodec.decode(
            fixture(version = 7, columns = 2, rows = 1, cursorColumn = 0, cursorRow = 0) {
                row(false) {
                    cell(" ", width = 1)
                    cell(" ", width = 1)
                }
            },
        )

        assertEquals(7, snapshot.version)
        assertEquals(2, snapshot.columns)
        assertEquals(1, snapshot.rows)
        assertEquals(TerminalPosition(0, 0), snapshot.cursor)
        assertEquals(" ", snapshot.cells[0][0].text)
    }

    @Test
    fun decodes_styles_wide_cells_and_continuations() {
        val snapshot = TerminalSnapshotCodec.decode(
            fixture(version = 9, columns = 2, rows = 1, cursorColumn = 1, cursorRow = 0) {
                row(true) {
                    cell("中", width = 2, flags = 2 or 4 or 8 or 16)
                    cell("", width = 0, flags = 1)
                }
            },
        )

        val wide = snapshot.cells[0][0]
        assertEquals("中", wide.text)
        assertEquals(2, wide.width)
        assertTrue(wide.style.bold)
        assertTrue(wide.style.italic)
        assertTrue(wide.style.underline)
        assertTrue(wide.style.inverse)
        assertFalse(wide.continuation)
        assertTrue(snapshot.cells[0][1].continuation)
        assertTrue(snapshot.wrappedRows[0])
    }

    @Test
    fun rejects_truncated_snapshot() {
        val valid = fixture(version = 1, columns = 1, rows = 1, cursorColumn = 0, cursorRow = 0) {
            row(false) { cell("x", width = 1) }
        }

        assertFailsWith<IllegalArgumentException> {
            TerminalSnapshotCodec.decode(valid.copyOf(valid.size - 1))
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalSnapshotCodec.decode(valid.copyOf(28))
        }
    }

    @Test
    fun rejects_cell_text_length_beyond_remaining_bytes() {
        val bytes = fixture(version = 1, columns = 1, rows = 1, cursorColumn = 0, cursorRow = 0) {
            row(false) { cell("x", width = 1) }
        }
        // Inflate the declared cell text length past the actual payload.
        bytes[bytes.size - 3] = 127

        assertFailsWith<IllegalArgumentException> {
            TerminalSnapshotCodec.decode(bytes)
        }
    }

    private fun fixture(
        version: Long,
        columns: Int,
        rows: Int,
        cursorColumn: Int,
        cursorRow: Int,
        writeRows: FixtureWriter.() -> Unit,
    ): ByteArray {
        val writer = FixtureWriter()
        writer.writeRows()
        return ByteBuffer.allocate(28 + writer.bytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("TRS1".encodeToByteArray())
            .putLong(version)
            .putInt(columns)
            .putInt(rows)
            .putInt(cursorColumn)
            .putInt(cursorRow)
            .put(writer.bytes.toByteArray())
            .array()
    }

    private class FixtureWriter {
        val bytes = mutableListOf<Byte>()

        fun row(wrapped: Boolean, writeCells: FixtureWriter.() -> Unit) {
            bytes += if (wrapped) 1 else 0
            writeCells()
        }

        fun cell(text: String, width: Int, flags: Int = 0) {
            val encoded = text.encodeToByteArray()
            bytes += flags.toByte()
            bytes += width.toByte()
            bytes += (encoded.size and 0xff).toByte()
            bytes += (encoded.size ushr 8).toByte()
            bytes += encoded.toList()
        }
    }
}
