package com.termux.rust

import android.content.Intent

/**
 * Validates and converts RUN_COMMAND Intents into immutable
 * [AppExecutionRequest] values. Pure parsing: no registry, no FFI, no caller
 * fabrication, and every entry point is guarded — a malformed/unparcelable
 * Intent yields INVALID_REQUEST instead of crashing the service.
 *
 * Two inbound schemas are accepted and mapped onto the same request:
 *
 * 1. **Upstream Termux RUN_COMMAND** (verified against termux-app master,
 *    `termux-shared .../TermuxConstants.java` v0.53.0 and
 *    `ExecutionCommand.Runner`):
 *    - action `com.termux.RUN_COMMAND`
 *    - `com.termux.RUN_COMMAND_PATH` (String, absolute path, required)
 *    - `com.termux.RUN_COMMAND_ARGUMENTS` (String[])
 *    - `com.termux.RUN_COMMAND_STDIN` (String)
 *    - `com.termux.RUN_COMMAND_WORKDIR` (String)
 *    - `com.termux.RUN_COMMAND_RUNNER` ("app-shell" | "terminal-session")
 *    - `com.termux.RUN_COMMAND_BACKGROUND` (boolean, deprecated alias for
 *      runner=app-shell)
 *    - `com.termux.RUN_COMMAND_COMMAND_LABEL` (String)
 *    Phase-1 boundary: only app-shell/background runners are accepted;
 *    `terminal-session` requests and result callbacks
 *    (`RUN_COMMAND_PENDING_INTENT`, `RUN_COMMAND_RESULT_*`,
 *    `RUN_COMMAND_SESSION_ACTION`) are out of scope — see the bd follow-up
 *    linked from termux-rs-8f7.5.
 *
 * 2. **Own schema** (`com.termux.rust.*`) as before, additionally supporting
 *    environment and timeout extras the upstream protocol lacks.
 *
 * The caller-identity boundary for both is the manifest signature permission;
 * `Binder.getCallingUid()` in `onStartCommand` resolves to self and is never
 * used as proof of origin.
 */
internal object RunCommandIntentParser {
    // Own schema.
    const val ACTION_RUN_COMMAND = "com.termux.rust.RUN_COMMAND"
    const val EXTRA_PATH = "com.termux.rust.extra.path"
    const val EXTRA_ARGUMENTS = "com.termux.rust.extra.arguments"
    const val EXTRA_WORKING_DIRECTORY = "com.termux.rust.extra.working_directory"
    const val EXTRA_ENVIRONMENT = "com.termux.rust.extra.environment"
    const val EXTRA_TIMEOUT_MS = "com.termux.rust.extra.timeout_ms"
    const val EXTRA_LABEL = "com.termux.rust.extra.label"

    // Upstream Termux schema (termux-app TermuxConstants v0.53.0).
    const val UPSTREAM_ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val UPSTREAM_EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val UPSTREAM_EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val UPSTREAM_EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
    const val UPSTREAM_EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val UPSTREAM_EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val UPSTREAM_EXTRA_RUNNER = "com.termux.RUN_COMMAND_RUNNER"
    const val UPSTREAM_EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val UPSTREAM_RUNNER_APP_SHELL = "app-shell"
    const val UPSTREAM_RUNNER_TERMINAL_SESSION = "terminal-session"

    private val ENVIRONMENT_ENTRY = Regex("^([A-Za-z_][A-Za-z0-9_]*)=(.*)$")

    fun parse(intent: Intent?): AppShellResult<AppExecutionRequest> =
        try {
            when (intent?.action) {
                ACTION_RUN_COMMAND -> parseOwn(intent)
                UPSTREAM_ACTION_RUN_COMMAND -> parseUpstream(intent)
                else -> invalid("unsupported or missing action")
            }
        } catch (error: RuntimeException) {
            // Hostile or unparcelable extras must never crash the service.
            invalid("malformed intent extras")
        }

    private fun parseOwn(intent: Intent): AppShellResult<AppExecutionRequest> {
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrEmpty() || !path.startsWith('/') || '\u0000' in path) {
            return invalid("path must be an absolute, NUL-free string")
        }
        val arguments = intent.getStringArrayExtra(EXTRA_ARGUMENTS)?.toList() ?: emptyList()
        val workingDirectory = intent.getStringExtra(EXTRA_WORKING_DIRECTORY)?.let { directory ->
            if (directory.isEmpty() || !directory.startsWith('/') || '\u0000' in directory) {
                return invalid("working directory must be absolute")
            }
            directory
        }
        val environment = mutableMapOf<String, String>()
        intent.getStringArrayExtra(EXTRA_ENVIRONMENT)?.forEach { entry ->
            val match = ENVIRONMENT_ENTRY.matchEntire(entry)
                ?: return invalid("environment entries must be NAME=value")
            environment[match.groupValues[1]] = match.groupValues[2]
        }
        val timeoutMs = intent.getIntExtra(EXTRA_TIMEOUT_MS, 0)
        if (timeoutMs < 0) {
            return invalid("timeout must be positive")
        }

        return buildRequest(
            executable = path,
            arguments = arguments,
            stdin = null,
            workingDirectory = workingDirectory,
            environment = environment,
            label = intent.getStringExtra(EXTRA_LABEL),
            timeout = if (timeoutMs > 0) ExecutionTimeout.After(timeoutMs.toLong()) else ExecutionTimeout.Unlimited,
        )
    }

    private fun parseUpstream(intent: Intent): AppShellResult<AppExecutionRequest> {
        // Phase-1 runner boundary: app-shell only (background commands).
        val runner = intent.getStringExtra(UPSTREAM_EXTRA_RUNNER)
        val background = intent.getBooleanExtra(UPSTREAM_EXTRA_BACKGROUND, true)
        val isAppShell = when {
            runner == UPSTREAM_RUNNER_APP_SHELL -> true
            runner == UPSTREAM_RUNNER_TERMINAL_SESSION -> false
            runner != null -> return invalid("unknown RUN_COMMAND_RUNNER")
            else -> background
        }
        if (!isAppShell) {
            return invalid("terminal-session runner is out of phase-1 scope")
        }

        val path = intent.getStringExtra(UPSTREAM_EXTRA_COMMAND_PATH)
        if (path.isNullOrEmpty() || !path.startsWith('/') || '\u0000' in path) {
            return invalid("RUN_COMMAND_PATH must be an absolute, NUL-free string")
        }
        val arguments = intent.getStringArrayExtra(UPSTREAM_EXTRA_ARGUMENTS)?.toList() ?: emptyList()
        val workingDirectory = intent.getStringExtra(UPSTREAM_EXTRA_WORKDIR)?.let { directory ->
            if (directory.isEmpty() || !directory.startsWith('/') || '\u0000' in directory) {
                return invalid("RUN_COMMAND_WORKDIR must be absolute")
            }
            directory
        }
        val stdin = intent.getStringExtra(UPSTREAM_EXTRA_STDIN)

        return buildRequest(
            executable = path,
            arguments = arguments,
            stdin = stdin?.encodeToByteArray(),
            workingDirectory = workingDirectory,
            environment = emptyMap(),
            label = intent.getStringExtra(UPSTREAM_EXTRA_COMMAND_LABEL),
            timeout = ExecutionTimeout.Unlimited,
        )
    }

    private fun buildRequest(
        executable: String,
        arguments: List<String>,
        stdin: ByteArray?,
        workingDirectory: String?,
        environment: Map<String, String>,
        label: String?,
        timeout: ExecutionTimeout,
    ): AppShellResult<AppExecutionRequest> =
        try {
            AppShellResult.Success(
                AppExecutionRequest(
                    // Started-service protocol: caller is not attributable;
                    // the signature permission is the identity boundary.
                    origin = RequestOrigin.ExternalRunCommand(caller = null),
                    executable = executable,
                    arguments = arguments,
                    stdin = stdin,
                    workingDirectory = workingDirectory,
                    target = ExecutionTarget.APP_SHELL,
                    label = label,
                    environment = environment,
                    timeout = timeout,
                ),
            )
        } catch (error: IllegalArgumentException) {
            invalid(error.message ?: "request rejected by contract")
        }

    private fun invalid(reason: String): AppShellResult.Failure =
        AppShellResult.Failure(AppShellError(AppShellErrorCode.INVALID_REQUEST, retryable = false))
}
