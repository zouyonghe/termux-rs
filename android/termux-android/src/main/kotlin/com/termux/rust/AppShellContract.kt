package com.termux.rust

private val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val PROTECTED_ENVIRONMENT = setOf(
    "HOME",
    "LANG",
    "LC_ALL",
    "PATH",
    "PREFIX",
    "TERM",
    "TERMUX_APP_PACKAGE",
    "TERMUX_APP_PACKAGE_MANAGER",
    "TERMUX_APP_PACKAGE_VARIANT",
    "TMPDIR",
)

internal data class CallerIdentity(
    val uid: Int,
    val packageName: String,
) {
    init {
        require(uid >= 0) { "caller uid must be non-negative" }
        require(packageName.isNotBlank() && '\u0000' !in packageName) { "invalid caller package" }
    }
}

internal sealed interface RequestOrigin {
    /**
     * External RUN_COMMAND request. [caller] is nullable on purpose: a
     * started-service Intent cannot reliably attribute the sender UID
     * (`Binder.getCallingUid()` resolves to self there), so provenance only
     * ever carries verifiable facts. When null, the identity boundary is the
     * manifest signature permission alone — no package is fabricated.
     */
    data class ExternalRunCommand(val caller: CallerIdentity?) : RequestOrigin
    data object Internal : RequestOrigin
}

internal enum class ExecutionTarget {
    TERMINAL_SESSION,
    APP_SHELL,
}

internal enum class SessionExecutionMode(
    val acceptsInput: Boolean,
    val acceptsResize: Boolean,
    val binderAttachable: Boolean,
) {
    INTERACTIVE_PTY(acceptsInput = true, acceptsResize = true, binderAttachable = true),
    NONINTERACTIVE_APP_SHELL(acceptsInput = false, acceptsResize = false, binderAttachable = false),
}

internal data class TerminalDimensions(val columns: Int, val rows: Int) {
    init {
        require(columns > 0 && rows > 0) { "terminal dimensions must be positive" }
    }
}

internal sealed interface ExecutionTimeout {
    data object Unlimited : ExecutionTimeout

    data class After(val milliseconds: Long) : ExecutionTimeout {
        init {
            require(milliseconds > 0) { "execution timeout must be positive" }
        }
    }
}

/**
 * Typed request accepted by the non-exported TermuxService boundary. External
 * Intent parsing and caller authorization happen before this type is created.
 */
internal class AppExecutionRequest(
    val origin: RequestOrigin,
    val executable: String,
    arguments: List<String> = emptyList(),
    stdin: ByteArray? = null,
    val workingDirectory: String? = null,
    val target: ExecutionTarget,
    val label: String? = null,
    environment: Map<String, String> = emptyMap(),
    val terminalSize: TerminalDimensions? = null,
    val timeout: ExecutionTimeout = ExecutionTimeout.Unlimited,
) {
    val arguments: List<String> = arguments.toList()
    private val stdinBytes: ByteArray? = stdin?.copyOf()
    val stdin: ByteArray? get() = stdinBytes?.copyOf()
    val environment: Map<String, String> = environment.toMap()

    val sessionMode: SessionExecutionMode = when (target) {
        ExecutionTarget.TERMINAL_SESSION -> SessionExecutionMode.INTERACTIVE_PTY
        ExecutionTarget.APP_SHELL -> SessionExecutionMode.NONINTERACTIVE_APP_SHELL
    }

    init {
        require(executable.isNotEmpty() && '\u0000' !in executable) { "invalid executable" }
        require(arguments.none { '\u0000' in it }) { "arguments must not contain NUL" }
        require(workingDirectory?.contains('\u0000') != true) { "working directory must not contain NUL" }
        require(environment.keys.all(ENVIRONMENT_NAME::matches)) { "invalid environment name" }
        require(environment.keys.none(PROTECTED_ENVIRONMENT::contains)) { "protected environment override" }
        require(environment.values.none { '\u0000' in it }) { "environment values must not contain NUL" }
        require(label?.contains('\u0000') != true) { "label must not contain NUL" }
        when (target) {
            ExecutionTarget.TERMINAL_SESSION -> requireNotNull(terminalSize) {
                "terminal sessions require dimensions"
            }
            ExecutionTarget.APP_SHELL -> require(terminalSize == null) {
                "app-shell requests must not include terminal dimensions"
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is AppExecutionRequest &&
            origin == other.origin &&
            executable == other.executable &&
            arguments == other.arguments &&
            stdinBytes.contentEqualsNullable(other.stdinBytes) &&
            workingDirectory == other.workingDirectory &&
            target == other.target &&
            label == other.label &&
            environment == other.environment &&
            terminalSize == other.terminalSize &&
            timeout == other.timeout

    override fun hashCode(): Int {
        var result = origin.hashCode()
        result = 31 * result + executable.hashCode()
        result = 31 * result + arguments.hashCode()
        result = 31 * result + (stdinBytes?.contentHashCode() ?: 0)
        result = 31 * result + (workingDirectory?.hashCode() ?: 0)
        result = 31 * result + target.hashCode()
        result = 31 * result + (label?.hashCode() ?: 0)
        result = 31 * result + environment.hashCode()
        result = 31 * result + (terminalSize?.hashCode() ?: 0)
        return 31 * result + timeout.hashCode()
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
    this == null -> other == null
    other == null -> false
    else -> contentEquals(other)
}

@JvmInline
internal value class SessionId(val value: Long) {
    init {
        require(value > 0) { "session id must be positive" }
    }
}

internal enum class SessionLifecycle {
    STARTING,
    RUNNING,
    CANCELLING,
    EXITED,
    CLOSED;

    fun canTransitionTo(next: SessionLifecycle): Boolean = next in when (this) {
        STARTING -> setOf(RUNNING, CANCELLING, EXITED)
        RUNNING -> setOf(CANCELLING, EXITED)
        CANCELLING -> setOf(EXITED)
        EXITED -> setOf(CLOSED)
        CLOSED -> emptySet()
    }

    fun cancellationDisposition(): CancellationDisposition = when (this) {
        STARTING, RUNNING -> CancellationDisposition.REQUEST_TERMINATION
        CANCELLING -> CancellationDisposition.ALREADY_REQUESTED
        EXITED, CLOSED -> CancellationDisposition.ALREADY_TERMINAL
    }
}

internal enum class CancellationDisposition {
    REQUEST_TERMINATION,
    ALREADY_REQUESTED,
    ALREADY_TERMINAL,
}

internal enum class CancellationReason {
    USER_REQUEST,
    TIMEOUT,
    SERVICE_SHUTDOWN,
}

internal sealed interface SessionTermination {
    data class ProcessExited(val code: Int, val signal: String? = null) : SessionTermination
    data class Cancelled(val reason: CancellationReason) : SessionTermination

    data class TimedOut(val timeoutMilliseconds: Long) : SessionTermination {
        init {
            require(timeoutMilliseconds > 0) { "timeout must be positive" }
        }
    }

    data class Failed(val error: AppShellError) : SessionTermination
}

internal enum class AppShellErrorCode {
    INVALID_REQUEST,
    UNAUTHORIZED_CALLER,
    BOOTSTRAP_UNAVAILABLE,
    SESSION_NOT_FOUND,
    SESSION_LIMIT_REACHED,
    NATIVE_FAILURE,
    INTERNAL_FAILURE,
}

internal data class AppShellError(
    val code: AppShellErrorCode,
    val retryable: Boolean,
)

internal sealed interface AppShellResult<out T> {
    data class Success<T>(val value: T) : AppShellResult<T>
    data class Failure(val error: AppShellError) : AppShellResult<Nothing>
}

internal data class SessionSnapshot(
    val id: SessionId,
    val label: String?,
    val target: ExecutionTarget,
    val lifecycle: SessionLifecycle,
    val termination: SessionTermination? = null,
) {
    init {
        val terminal = lifecycle == SessionLifecycle.EXITED || lifecycle == SessionLifecycle.CLOSED
        require(terminal == (termination != null)) {
            "terminal states EXITED/CLOSED require a retained result; live states must not have one"
        }
    }
}

/**
 * Immutable keyboard/input payload crossing the Binder boundary. Constructing
 * and reading both defensive-copy, so a caller mutating its buffer after
 * enqueue (or a reader mutating an extracted copy) can never corrupt the
 * queued value.
 */
internal class SessionInput private constructor(
    private val bytes: ByteArray,
) {
    val size: Int get() = bytes.size

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is SessionInput && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        fun of(bytes: ByteArray): SessionInput = SessionInput(bytes.copyOf())
    }
}

/**
 * Latest rendered terminal frame (TRS1 snapshot bytes) cached by the service
 * owner thread. Reads are served from the in-memory cache only and hand out
 * independent copies; they never block and never touch the Rust FFI.
 */
internal class CachedSessionFrame(
    val sessionId: SessionId,
    val version: Long,
    payload: ByteArray,
) {
    init {
        require(version >= 0) { "frame version must be non-negative" }
    }

    private val frameBytes = payload.copyOf()
    val payload: ByteArray get() = frameBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is CachedSessionFrame &&
            sessionId == other.sessionId &&
            version == other.version &&
            frameBytes.contentEquals(other.frameBytes)

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + version.hashCode()
        return 31 * result + frameBytes.contentHashCode()
    }
}

/**
 * Binder-facing typed API. Implementations allocate IDs and enqueue work, but
 * never perform Rust FFI on the Binder caller thread.
 */
internal interface TermuxServiceApi {
    fun createSession(request: AppExecutionRequest): AppShellResult<SessionId>
    fun sessions(): AppShellResult<List<SessionSnapshot>>
    fun session(id: SessionId): AppShellResult<SessionSnapshot>

    /**
     * Enqueues input for the session. Terminal-state semantics: once a
     * session is CANCELLING/EXITED/CLOSED the payload is silently dropped and
     * the call still succeeds — idempotent no-op, matching cancel/close.
     */
    fun writeInput(id: SessionId, input: SessionInput): AppShellResult<Unit>

    /**
     * Enqueues a resize. Terminal-state semantics match [writeInput]:
     * dropped with Success on CANCELLING/EXITED/CLOSED sessions.
     */
    fun resize(id: SessionId, dimensions: TerminalDimensions): AppShellResult<Unit>
    fun cancel(id: SessionId, reason: CancellationReason): AppShellResult<Unit>
    fun close(id: SessionId): AppShellResult<Unit>

    /**
     * Returns the newest cached frame for [id], or `Success(null)` when no
     * frame newer than [afterVersion] exists. Served from the service-owned
     * memory cache only: nonblocking, and no FFI on the caller thread. This
     * is how an Activity renders once session ownership lives in the service.
     *
     * Activity read pattern: poll [cachedFrame] for rendering and [session]
     * for lifecycle/termination (exit banner); frames carry no termination
     * signal by design.
     */
    fun cachedFrame(id: SessionId, afterVersion: Long? = null): AppShellResult<CachedSessionFrame?>
}

internal enum class AppShellThread {
    SERVICE_OWNER,
}

internal enum class AppShellComponent {
    RUN_COMMAND_SERVICE,
    TERMUX_SERVICE,
}

internal object AppShellThreadContract {
    val ffiOwner = AppShellThread.SERVICE_OWNER
    const val binderCallsMayRunFfi = false
    const val cachedFrameReadsMayRunFfi = false
    const val activityUnbindCancelsSessions = false
}

internal object AppShellBoundaryContract {
    val externalAdapter = AppShellComponent.RUN_COMMAND_SERVICE
    val executionOwner = AppShellComponent.TERMUX_SERVICE
    const val requiresSignaturePermission = true
    const val requiresCallerVerification = true
    const val requiresIntentSchemaValidation = true
    const val runCommandServiceMayCallFfi = false
}

internal object SessionRegistryContract {
    val owner = AppShellComponent.TERMUX_SERVICE
}

internal object ForegroundNotificationContract {
    val owner = AppShellComponent.TERMUX_SERVICE
}

internal enum class TimeoutClockStart {
    SESSION_ID_ALLOCATED,
}

internal object ExecutionTimeoutContract {
    val clockStart = TimeoutClockStart.SESSION_ID_ALLOCATED
    const val timeoutRequestsCancellation = true
}

internal data class RegistrySummary(
    val starting: Int = 0,
    val running: Int = 0,
    val cancelling: Int = 0,
    val exited: Int = 0,
) {
    init {
        require(starting >= 0 && running >= 0 && cancelling >= 0 && exited >= 0) {
            "registry counts must be non-negative"
        }
    }

    val requiresForeground: Boolean
        get() = starting > 0 || running > 0 || cancelling > 0
}
