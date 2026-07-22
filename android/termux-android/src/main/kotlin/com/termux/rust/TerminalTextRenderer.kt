package com.termux.rust

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan

/**
 * Maps a decoded [TerminalSnapshot] onto styled text for the placeholder
 * `TextView` renderer. Wide characters occupy their cell text once;
 * continuation cells contribute nothing. The cursor cell is highlighted
 * with a background span.
 */
object TerminalTextRenderer {
    fun render(snapshot: TerminalSnapshot): CharSequence {
        val builder = SpannableStringBuilder()
        snapshot.cells.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) builder.append('\n')
            // The cursor can rest on a continuation cell (e.g. right after a
            // wide character); highlight the owning wide cell's text in that
            // case so the cursor never disappears.
            var lastEmittableRange: IntRange? = null
            row.forEachIndexed { columnIndex, cell ->
                val isCursor = snapshot.cursor == TerminalPosition(columnIndex, rowIndex)
                if (cell.continuation || cell.text.isEmpty()) {
                    if (isCursor) {
                        lastEmittableRange?.let { range -> highlightCursor(builder, range) }
                    }
                    return@forEachIndexed
                }
                val start = builder.length
                builder.append(cell.text)
                val end = builder.length
                applyStyle(builder, start, end, cell.style)
                if (isCursor) {
                    highlightCursor(builder, start until end)
                }
                lastEmittableRange = start until end
            }
        }
        return builder
    }

    private fun highlightCursor(builder: SpannableStringBuilder, range: IntRange) {
        builder.setSpan(
            BackgroundColorSpan(Color.DKGRAY),
            range.first,
            range.last + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private fun applyStyle(
        builder: SpannableStringBuilder,
        start: Int,
        end: Int,
        style: TerminalStyle,
    ) {
        if (style.bold || style.italic) {
            val typeface = when {
                style.bold && style.italic -> Typeface.BOLD_ITALIC
                style.bold -> Typeface.BOLD
                else -> Typeface.ITALIC
            }
            builder.setSpan(
                StyleSpan(typeface),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (style.underline) {
            builder.setSpan(
                UnderlineSpan(),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (style.inverse) {
            builder.setSpan(
                BackgroundColorSpan(Color.LTGRAY),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
}
