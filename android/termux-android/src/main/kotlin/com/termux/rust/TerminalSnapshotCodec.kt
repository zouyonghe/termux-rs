package com.termux.rust

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TerminalPosition(val column: Int, val row: Int)

data class TerminalStyle(
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val inverse: Boolean,
)

data class TerminalCell(
    val text: String,
    val width: Int,
    val continuation: Boolean,
    val style: TerminalStyle,
)

data class TerminalSnapshot(
    val version: Long,
    val columns: Int,
    val rows: Int,
    val cursor: TerminalPosition,
    val cells: List<List<TerminalCell>>,
    val wrappedRows: List<Boolean>,
)

/**
 * Decodes the platform-neutral `TRS1` binary snapshot emitted by
 * `termux_terminal_session_render` / `termux_terminal_render`:
 *
 * - 4 bytes magic `TRS1`
 * - `u64` snapshot version (little-endian)
 * - `u32` column count, `u32` row count
 * - `u32` cursor column, `u32` cursor row
 * - per row: `u8` wrapped flag, then per cell: `u8` flags (bit 0
 *   continuation, 1 bold, 2 italic, 3 underline, 4 inverse), `u8` width,
 *   `u16` UTF-8 text length, text bytes
 */
object TerminalSnapshotCodec {
    private const val FLAG_CONTINUATION = 1
    private const val FLAG_BOLD = 2
    private const val FLAG_ITALIC = 4
    private const val FLAG_UNDERLINE = 8
    private const val FLAG_INVERSE = 16
    private const val HEADER_BYTES = 28
    private const val CELL_HEADER_BYTES = 4

    fun decode(bytes: ByteArray): TerminalSnapshot {
        require(bytes.size >= HEADER_BYTES) { "snapshot too short: ${bytes.size} bytes" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buffer.get(it) }
        require(magic.contentEquals("TRS1".encodeToByteArray())) { "bad snapshot magic" }

        val version = buffer.long
        val columns = buffer.int
        val rows = buffer.int
        val cursor = TerminalPosition(buffer.int, buffer.int)
        require(columns >= 0 && rows >= 0) { "negative snapshot dimensions" }

        val cells = ArrayList<List<TerminalCell>>(rows)
        val wrappedRows = ArrayList<Boolean>(rows)
        repeat(rows) { rowIndex ->
            require(buffer.hasRemaining()) { "truncated snapshot: missing row $rowIndex" }
            wrappedRows += buffer.get().toInt() != 0
            val row = ArrayList<TerminalCell>(columns)
            repeat(columns) { columnIndex ->
                require(buffer.remaining() >= CELL_HEADER_BYTES) {
                    "truncated snapshot: missing cell header at row $rowIndex column $columnIndex"
                }
                val flags = buffer.get().toInt() and 0xff
                val width = buffer.get().toInt() and 0xff
                val length = buffer.short.toInt() and 0xffff
                require(buffer.remaining() >= length) {
                    "truncated snapshot: cell text of $length bytes at row $rowIndex column $columnIndex"
                }
                val textBytes = ByteArray(length).also { buffer.get(it) }
                row += TerminalCell(
                    text = String(textBytes, Charsets.UTF_8),
                    width = width,
                    continuation = flags and FLAG_CONTINUATION != 0,
                    style = TerminalStyle(
                        bold = flags and FLAG_BOLD != 0,
                        italic = flags and FLAG_ITALIC != 0,
                        underline = flags and FLAG_UNDERLINE != 0,
                        inverse = flags and FLAG_INVERSE != 0,
                    ),
                )
            }
            cells += row
        }
        require(!buffer.hasRemaining()) { "trailing snapshot bytes" }

        return TerminalSnapshot(version, columns, rows, cursor, cells, wrappedRows)
    }
}
