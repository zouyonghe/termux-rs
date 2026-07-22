package com.termux.rust

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end verification of the complete Android Rust terminal path on an
 * API 24+ arm64 emulator: launch, deterministic command execution, output
 * observation, keyboard input, resize, and clean process termination.
 * Independent of user files, package manager, Intent callers, and
 * notifications.
 */
@RunWith(AndroidJUnit4::class)
class TerminalE2eInstrumentedTest {
    @Test
    fun full_terminal_session_journey() {
        ActivityScenario.launch(TerminalActivity::class.java).use { scenario ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()

            // 1. Deterministic command: arithmetic result "42" can only come
            //    from actual shell execution, not from command echo.
            instrumentation.sendStringSync("echo \$((40+2))\n")
            awaitText(scenario) { it.contains("\n42") }

            // 2. Resize: shrink the surface and verify the native terminal
            //    follows measured dimensions.
            val initial = awaitDimensions(scenario) { cols, rows, snapshot ->
                snapshot.columns == cols && snapshot.rows == rows
            }
            // Guard against the pre-layout flake: never shrink a zero-sized surface.
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

            // 3. Input + execution still work after the resize.
            instrumentation.sendStringSync("echo \$((100+23))\n")
            awaitText(scenario) { it.contains("\n123") }

            // 4. Clean process termination with exit code surfaced to UI.
            instrumentation.sendStringSync("exit 0\n")
            awaitText(scenario) { it.contains("process exited with code 0") }
            scenario.onActivity { activity ->
                assertEquals(0, activity.childExitCode)
            }
        } // 5. Destroy: supervisor close is idempotent, no crash or leak.
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
                val snapshot = activity.cachedSnapshotForTest()
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
