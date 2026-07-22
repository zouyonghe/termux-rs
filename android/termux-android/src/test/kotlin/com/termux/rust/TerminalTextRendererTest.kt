package com.termux.rust

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalTextRendererTest {
    @Test
    fun renders_empty_snapshot_as_blank_rows() {
        val snapshot = snapshot(columns = 2, rows = 2) { row, column -> cell(" ") }

        val rendered = TerminalTextRenderer.render(snapshot)

        assertEquals("  \n  ", rendered.toString())
    }

    @Test
    fun applies_bold_italic_underline_and_inverse_spans() {
        val styled = TerminalStyle(bold = true, italic = true, underline = true, inverse = true)
        val snapshot = snapshot(columns = 1, rows = 1) { _, _ -> cell("x", style = styled) }

        val rendered = TerminalTextRenderer.render(snapshot) as Spanned

        val styleSpans = rendered.getSpans(0, 1, StyleSpan::class.java)
        assertEquals(Typeface.BOLD_ITALIC, styleSpans.single().style)
        assertEquals(1, rendered.getSpans(0, 1, UnderlineSpan::class.java).size)
        val inverse = rendered.getSpans(0, 1, BackgroundColorSpan::class.java)
        assertTrue(inverse.any { it.backgroundColor == Color.LTGRAY })
    }

    @Test
    fun applies_bold_and_italic_independently() {
        val bold = TerminalStyle(bold = true, italic = false, underline = false, inverse = false)
        val italic = TerminalStyle(bold = false, italic = true, underline = false, inverse = false)
        val snapshot = snapshot(columns = 2, rows = 1) { _, column ->
            if (column == 0) cell("b", style = bold) else cell("i", style = italic)
        }

        val rendered = TerminalTextRenderer.render(snapshot) as Spanned

        assertEquals(Typeface.BOLD, rendered.getSpans(0, 1, StyleSpan::class.java).single().style)
        assertEquals(Typeface.ITALIC, rendered.getSpans(1, 2, StyleSpan::class.java).single().style)
    }

    @Test
    fun highlights_cursor_cell() {
        val snapshot = snapshot(columns = 2, rows = 1, cursor = TerminalPosition(1, 0)) { _, column ->
            cell(if (column == 0) "a" else " ")
        }

        val rendered = TerminalTextRenderer.render(snapshot) as Spanned

        val cursorSpans = rendered.getSpans(0, rendered.length, BackgroundColorSpan::class.java)
            .filter { it.backgroundColor == Color.DKGRAY }
        assertEquals(1, cursorSpans.size)
        assertEquals(1, rendered.getSpanStart(cursorSpans.single()))
        assertEquals(2, rendered.getSpanEnd(cursorSpans.single()))
    }

    @Test
    fun renders_wide_character_once_and_skips_continuation_cell() {
        val snapshot = snapshot(columns = 2, rows = 1) { _, column ->
            if (column == 0) cell("中", width = 2) else cell("", width = 0, continuation = true)
        }

        val rendered = TerminalTextRenderer.render(snapshot)

        assertEquals("中", rendered.toString())
    }

    @Test
    fun cursor_on_continuation_cell_highlights_owning_wide_cell() {
        val snapshot = snapshot(columns = 3, rows = 1, cursor = TerminalPosition(1, 0)) { _, column ->
            when (column) {
                0 -> cell("中", width = 2)
                1 -> cell("", width = 0, continuation = true)
                else -> cell(" ")
            }
        }

        val rendered = TerminalTextRenderer.render(snapshot) as Spanned

        val cursorSpans = rendered.getSpans(0, rendered.length, BackgroundColorSpan::class.java)
            .filter { it.backgroundColor == Color.DKGRAY }
        assertEquals(1, cursorSpans.size)
        assertEquals(0, rendered.getSpanStart(cursorSpans.single()))
        assertEquals(1, rendered.getSpanEnd(cursorSpans.single()))
    }

    @Test
    fun reflows_rows_to_new_dimensions_after_resize() {
        val before = snapshot(columns = 4, rows = 1) { _, column -> cell("abcd"[column].toString()) }
        val after = snapshot(columns = 2, rows = 2) { row, column ->
            cell(if (row == 0) "ab"[column].toString() else "cd"[column].toString())
        }

        assertEquals("abcd", TerminalTextRenderer.render(before).toString())
        assertEquals("ab\ncd", TerminalTextRenderer.render(after).toString())
    }

    private fun cell(
        text: String,
        width: Int = 1,
        continuation: Boolean = false,
        style: TerminalStyle = TerminalStyle(false, false, false, false),
    ) = TerminalCell(text, width, continuation, style)

    private fun snapshot(
        columns: Int,
        rows: Int,
        cursor: TerminalPosition = TerminalPosition(0, 0),
        cellAt: (Int, Int) -> TerminalCell,
    ) = TerminalSnapshot(
        version = 1,
        columns = columns,
        rows = rows,
        cursor = cursor,
        cells = (0 until rows).map { row -> (0 until columns).map { column -> cellAt(row, column) } },
        wrappedRows = List(rows) { false },
    )

    @Test
    fun render_is_char_sequence() {
        val snapshot = snapshot(columns = 1, rows = 1) { _, _ -> cell("x") }
        assertIs<Spanned>(TerminalTextRenderer.render(snapshot))
    }
}
