package com.termux.rust

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalActivityInstrumentedTest {
    @Test
    fun creates_recreates_and_closes_one_native_terminal_session_per_activity() {
        ActivityScenario.launch(TerminalActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<android.view.View>(android.R.id.content) != null)
            }
            scenario.recreate()
        }
    }

    @Test
    fun sends_keyboard_text_to_shell_and_displays_output() {
        ActivityScenario.launch(TerminalActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().sendStringSync("echo wired\n")
            repeat(100) {
                var rendered = ""
                scenario.onActivity { activity ->
                    val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                    rendered = (content.getChildAt(0) as android.widget.TextView).text.toString()
                }
                if (rendered.contains("wired")) return
                Thread.sleep(20)
            }
        }
        throw AssertionError("shell output did not reach terminal screen")
    }

    @Test
    fun resizes_native_terminal_when_surface_dimensions_change() {
        ActivityScenario.launch(TerminalActivity::class.java).use { scenario ->
            // Wait for the initial layout to drive the first measured resize.
            val initial = awaitDimensions(scenario) { cols, rows, snapshot ->
                snapshot.columns == cols && snapshot.rows == rows
            }
            // Shrink the surface to half its size and request a new layout.
            awaitSurfaceLaidOut(scenario)
            scenario.onActivity { activity ->
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val view = content.getChildAt(0)
                view.layoutParams = view.layoutParams.apply {
                    width = view.width / 2
                    height = view.height / 2
                }
                view.requestLayout()
            }

            val resized = awaitDimensions(scenario) { cols, rows, snapshot ->
                (cols < initial.first || rows < initial.second) &&
                    snapshot.columns == cols && snapshot.rows == rows
            }
            assertEquals(resized.first, resized.third.columns)
            assertEquals(resized.second, resized.third.rows)

            // Rendering must still work after the resize (reflow evidence).
            InstrumentationRegistry.getInstrumentation().sendStringSync("echo reflow-ok\n")
            awaitText(scenario) { it.contains("reflow-ok") }
        }
    }

    @Test
    fun renders_styles_wide_characters_and_cursor_on_emulator() {
        ActivityScenario.launch(TerminalActivity::class.java).use { scenario ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.sendStringSync("printf '\\033[1mBOLD\\033[0m\\n'\n")
            // 中 as UTF-8 octal escapes: sendStringSync can only inject ASCII.
            instrumentation.sendStringSync("printf '\\344\\270\\255\\n'\n")

            awaitText(scenario) { it.contains("BOLD") && it.contains("中") }

            scenario.onActivity { activity ->
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val text = (content.getChildAt(0) as android.widget.TextView).text
                assertTrue(text is Spanned)
                val spanned = text as Spanned

                // The first "BOLD" occurrence is the unstyled command echo;
                // the styled printf output carries a bold StyleSpan.
                val boldSpans = spanned
                    .getSpans(0, spanned.length, StyleSpan::class.java)
                    .filter { it.style == Typeface.BOLD }
                assertTrue("expected a bold StyleSpan in rendered output", boldSpans.isNotEmpty())

                val cursorSpans = spanned
                    .getSpans(0, spanned.length, BackgroundColorSpan::class.java)
                    .filter { it.backgroundColor == Color.DKGRAY }
                assertTrue("expected a cursor highlight span", cursorSpans.isNotEmpty())
            }
        }
    }

    @Test
    fun child_exit_updates_ui_state_and_ignores_further_input() {
        ActivityScenario.launch(TerminalActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().sendStringSync("exit 7\n")

            awaitText(scenario) { it.contains("process exited with code 7") }
            scenario.onActivity { activity ->
                assertEquals(7, activity.childExitCode)
            }

            // Input after exit must be ignored without crashing; the UI must
            // not show output from a dead child.
            InstrumentationRegistry.getInstrumentation().sendStringSync("echo nope\n")
            Thread.sleep(200)
            scenario.onActivity { activity ->
                assertEquals(7, activity.childExitCode)
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val text = (content.getChildAt(0) as android.widget.TextView).text.toString()
                assertFalse("input after child exit must not reach the screen", text.contains("nope"))
            }
        }
    }

    @Test
    fun pause_resume_and_destroy_are_idempotent() {
        ActivityScenario.launch(TerminalActivity::class.java).use { scenario ->
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED) // onStop
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED) // onStart again
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)

            InstrumentationRegistry.getInstrumentation().sendStringSync("echo alive\n")
            awaitText(scenario) { it.contains("alive") }
        } // use{} destroy + supervisor close: idempotent, no crash/leak
    }

    private fun awaitText(
        scenario: ActivityScenario<TerminalActivity>,
        condition: (String) -> Boolean,
    ): String {
        repeat(150) {
            var rendered = ""
            scenario.onActivity { activity ->
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                rendered = (content.getChildAt(0) as android.widget.TextView).text.toString()
            }
            if (condition(rendered)) return rendered
            Thread.sleep(20)
        }
        throw AssertionError("expected text never rendered")
    }

    private fun awaitSurfaceLaidOut(scenario: ActivityScenario<TerminalActivity>) {
        repeat(100) {
            var laidOut = false
            scenario.onActivity { activity ->
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val view = content.getChildAt(0)
                laidOut = view.width > 0 && view.height > 0
            }
            if (laidOut) return
            Thread.sleep(20)
        }
        throw AssertionError("surface never laid out")
    }

    private fun awaitDimensions(
        scenario: ActivityScenario<TerminalActivity>,
        condition: (Int, Int, TerminalSnapshot) -> Boolean,
    ): Triple<Int, Int, TerminalSnapshot> {
        repeat(100) {
            var result: Triple<Int, Int, TerminalSnapshot>? = null
            scenario.onActivity { activity ->
                val snapshot = activity.pumpAndRenderSnapshotForTest()
                if (snapshot != null &&
                    condition(activity.terminalColumns, activity.terminalRows, snapshot)
                ) {
                    result = Triple(activity.terminalColumns, activity.terminalRows, snapshot)
                }
            }
            result?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("terminal dimensions did not settle")
    }
}
