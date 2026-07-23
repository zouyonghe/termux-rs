package com.termux.rust

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppShellContractTest {
    @Test
    fun external_request_preserves_validated_caller_and_execution_fields() {
        val caller = CallerIdentity(uid = 10042, packageName = "com.example.client")
        val request = AppExecutionRequest(
            origin = RequestOrigin.ExternalRunCommand(caller),
            executable = "/data/data/com.termux/files/usr/bin/bash",
            arguments = listOf("-lc", "printf ok"),
            stdin = "input\n".encodeToByteArray(),
            workingDirectory = "/data/data/com.termux/files/home",
            target = ExecutionTarget.TERMINAL_SESSION,
            label = "external command",
            environment = mapOf("EDITOR" to "vi"),
            terminalSize = TerminalDimensions(columns = 80, rows = 24),
            timeout = ExecutionTimeout.After(5_000),
        )

        assertEquals(caller, (request.origin as RequestOrigin.ExternalRunCommand).caller)
        assertEquals(listOf("-lc", "printf ok"), request.arguments)
        assertEquals("EDITOR", request.environment.keys.single())
        assertTrue(request.stdin!!.contentEquals("input\n".encodeToByteArray()))
        assertEquals(SessionExecutionMode.INTERACTIVE_PTY, request.sessionMode)
        assertTrue(request.sessionMode.acceptsInput)
        assertTrue(request.sessionMode.acceptsResize)
        assertTrue(request.sessionMode.binderAttachable)
    }

    @Test
    fun app_shell_requests_are_noninteractive_and_have_no_terminal_size() {
        val request = AppExecutionRequest(
            origin = RequestOrigin.Internal,
            executable = "/system/bin/echo",
            arguments = listOf("ok"),
            target = ExecutionTarget.APP_SHELL,
        )

        assertEquals(SessionExecutionMode.NONINTERACTIVE_APP_SHELL, request.sessionMode)
        assertFalse(request.sessionMode.acceptsInput)
        assertFalse(request.sessionMode.acceptsResize)
        assertFalse(request.sessionMode.binderAttachable)
        assertNull(request.terminalSize)
        assertEquals(ExecutionTimeout.Unlimited, request.timeout)
    }

    @Test
    fun typed_request_is_an_immutable_value_after_boundary_validation() {
        val arguments = mutableListOf("first")
        val stdin = byteArrayOf(1, 2)
        val environment = mutableMapOf("EDITOR" to "vi")
        val request = AppExecutionRequest(
            origin = RequestOrigin.Internal,
            executable = "/system/bin/sh",
            arguments = arguments,
            stdin = stdin,
            target = ExecutionTarget.TERMINAL_SESSION,
            environment = environment,
            terminalSize = TerminalDimensions(80, 24),
        )

        arguments += "mutated"
        stdin[0] = 9
        environment["EDITOR"] = "mutated"

        assertEquals(listOf("first"), request.arguments)
        assertTrue(request.stdin!!.contentEquals(byteArrayOf(1, 2)))
        assertEquals(mapOf("EDITOR" to "vi"), request.environment)
        assertEquals(
            request,
            AppExecutionRequest(
                origin = RequestOrigin.Internal,
                executable = "/system/bin/sh",
                arguments = listOf("first"),
                stdin = byteArrayOf(1, 2),
                target = ExecutionTarget.TERMINAL_SESSION,
                environment = mapOf("EDITOR" to "vi"),
                terminalSize = TerminalDimensions(80, 24),
            ),
        )
    }

    @Test
    fun typed_requests_reject_values_that_must_not_cross_into_rust() {
        assertFailsWith<IllegalArgumentException> {
            terminalRequest(executable = "")
        }
        assertFailsWith<IllegalArgumentException> {
            terminalRequest(arguments = listOf("bad\u0000argument"))
        }
        assertFailsWith<IllegalArgumentException> {
            terminalRequest(environment = mapOf("PATH" to "/untrusted"))
        }
        assertFailsWith<IllegalArgumentException> {
            terminalRequest(environment = mapOf("TERM" to "dumb"))
        }
        assertFailsWith<IllegalArgumentException> {
            terminalRequest(environment = mapOf("LC_ALL" to "C"))
        }
        assertFailsWith<IllegalArgumentException> {
            terminalRequest(environment = mapOf("BAD-NAME" to "value"))
        }
        assertFailsWith<IllegalArgumentException> {
            terminalRequest(timeout = ExecutionTimeout.After(0))
        }
        assertFailsWith<IllegalArgumentException> {
            AppExecutionRequest(
                origin = RequestOrigin.Internal,
                executable = "/system/bin/true",
                target = ExecutionTarget.APP_SHELL,
                terminalSize = TerminalDimensions(80, 24),
            )
        }
    }

    @Test
    fun binder_contract_is_nonblocking_and_ffi_is_service_owner_confined() {
        assertEquals(AppShellThread.SERVICE_OWNER, AppShellThreadContract.ffiOwner)
        assertFalse(AppShellThreadContract.binderCallsMayRunFfi)
        assertFalse(AppShellThreadContract.activityUnbindCancelsSessions)
        assertEquals(AppShellComponent.TERMUX_SERVICE, SessionRegistryContract.owner)
        assertEquals(AppShellComponent.TERMUX_SERVICE, ForegroundNotificationContract.owner)
        assertEquals(AppShellComponent.RUN_COMMAND_SERVICE, AppShellBoundaryContract.externalAdapter)
        assertEquals(AppShellComponent.TERMUX_SERVICE, AppShellBoundaryContract.executionOwner)
        assertTrue(AppShellBoundaryContract.requiresSignaturePermission)
        assertTrue(AppShellBoundaryContract.requiresCallerVerification)
        assertTrue(AppShellBoundaryContract.requiresIntentSchemaValidation)
        assertFalse(AppShellBoundaryContract.runCommandServiceMayCallFfi)

        val api = RecordingTermuxServiceApi()
        val request = terminalRequest()
        val created = api.createSession(request)

        assertIs<AppShellResult.Success<SessionId>>(created)
        assertEquals(request, api.lastRequest)
        assertEquals(SessionId(1), created.value)
    }

    @Test
    fun session_lifecycle_allows_only_owned_registry_transitions() {
        assertTrue(SessionLifecycle.STARTING.canTransitionTo(SessionLifecycle.RUNNING))
        assertTrue(SessionLifecycle.RUNNING.canTransitionTo(SessionLifecycle.CANCELLING))
        assertTrue(SessionLifecycle.CANCELLING.canTransitionTo(SessionLifecycle.EXITED))
        assertTrue(SessionLifecycle.EXITED.canTransitionTo(SessionLifecycle.CLOSED))

        assertFalse(SessionLifecycle.RUNNING.canTransitionTo(SessionLifecycle.STARTING))
        assertFalse(SessionLifecycle.EXITED.canTransitionTo(SessionLifecycle.RUNNING))
        assertFalse(SessionLifecycle.CLOSED.canTransitionTo(SessionLifecycle.RUNNING))
    }

    @Test
    fun cancellation_is_idempotent_and_terminal_results_are_explicit() {
        assertEquals(TimeoutClockStart.SESSION_ID_ALLOCATED, ExecutionTimeoutContract.clockStart)
        assertTrue(ExecutionTimeoutContract.timeoutRequestsCancellation)
        assertEquals(CancellationDisposition.REQUEST_TERMINATION, SessionLifecycle.RUNNING.cancellationDisposition())
        assertEquals(CancellationDisposition.ALREADY_REQUESTED, SessionLifecycle.CANCELLING.cancellationDisposition())
        assertEquals(CancellationDisposition.ALREADY_TERMINAL, SessionLifecycle.EXITED.cancellationDisposition())
        assertEquals(CancellationDisposition.ALREADY_TERMINAL, SessionLifecycle.CLOSED.cancellationDisposition())

        assertEquals(SessionTermination.ProcessExited(code = 7, signal = null), SessionTermination.ProcessExited(7))
        assertEquals(SessionTermination.Cancelled(CancellationReason.USER_REQUEST), SessionTermination.Cancelled(CancellationReason.USER_REQUEST))
        assertEquals(SessionTermination.TimedOut(5_000), SessionTermination.TimedOut(5_000))
        assertTrue(AppShellError(AppShellErrorCode.NATIVE_FAILURE, retryable = true).retryable)
    }

    @Test
    fun foreground_notification_tracks_only_live_or_starting_work() {
        assertFalse(RegistrySummary().requiresForeground)
        assertTrue(RegistrySummary(starting = 1).requiresForeground)
        assertTrue(RegistrySummary(running = 2).requiresForeground)
        assertTrue(RegistrySummary(cancelling = 1, exited = 4).requiresForeground)
        assertFalse(RegistrySummary(exited = 4).requiresForeground)
    }

    @Test
    fun session_input_payloads_are_immutable_values() {
        val raw = byteArrayOf(1, 2, 3)
        val input = SessionInput.of(raw)

        // Caller mutation after enqueue must not leak into the payload.
        raw[0] = 99
        assertTrue(input.copyBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(3, input.size)

        // Mutating an extracted copy must not leak back either.
        val extracted = input.copyBytes()
        extracted[1] = 99
        assertTrue(input.copyBytes().contentEquals(byteArrayOf(1, 2, 3)))

        // Value equality is content-based.
        assertEquals(input, SessionInput.of(byteArrayOf(1, 2, 3)))
        assertEquals(input.hashCode(), SessionInput.of(byteArrayOf(1, 2, 3)).hashCode())

        // The service API consumes the immutable payload, not raw arrays.
        val api = RecordingTermuxServiceApi()
        api.writeInput(SessionId(1), SessionInput.of(byteArrayOf(7)))
        assertEquals(SessionInput.of(byteArrayOf(7)), api.lastInput)
    }

    @Test
    fun cached_frames_are_nonblocking_immutable_values() {
        // The Binder caller thread never performs FFI to read a frame.
        assertFalse(AppShellThreadContract.cachedFrameReadsMayRunFfi)

        val api = RecordingTermuxServiceApi()
        val frameBytes = byteArrayOf(1, 2, 3, 4)
        api.cannedFrame = CachedSessionFrame(SessionId(1), version = 9, payload = frameBytes)
        frameBytes[0] = 99 // constructor must defensive-copy

        val frame = api.cachedFrame(SessionId(1), afterVersion = 8)
        assertIs<AppShellResult.Success<CachedSessionFrame?>>(frame)
        val value = frame.value!!
        assertEquals(9, value.version)
        assertTrue(value.payload.contentEquals(byteArrayOf(1, 2, 3, 4)))

        // Reads hand out independent copies; mutating one cannot corrupt the cache.
        value.payload[0] = 99
        val reread = (api.cachedFrame(SessionId(1), afterVersion = 8)
            as AppShellResult.Success).value!!
        assertTrue(reread.payload.contentEquals(byteArrayOf(1, 2, 3, 4)))

        // No newer frame yet: callers get an empty result, never a block.
        val stale = api.cachedFrame(SessionId(1), afterVersion = 9)
        assertIs<AppShellResult.Success<CachedSessionFrame?>>(stale)
        assertNull(stale.value)
    }

    @Test
    fun closed_sessions_retain_their_terminal_result() {
        val termination = SessionTermination.ProcessExited(code = 0)

        // Both terminal states must retain the result.
        assertEquals(
            termination,
            SessionSnapshot(SessionId(1), null, ExecutionTarget.TERMINAL_SESSION, SessionLifecycle.EXITED, termination).termination,
        )
        assertEquals(
            termination,
            SessionSnapshot(SessionId(1), null, ExecutionTarget.TERMINAL_SESSION, SessionLifecycle.CLOSED, termination).termination,
        )

        // CLOSED without a terminal result is rejected, just like EXITED.
        assertFailsWith<IllegalArgumentException> {
            SessionSnapshot(SessionId(1), null, ExecutionTarget.TERMINAL_SESSION, SessionLifecycle.CLOSED)
        }
        assertFailsWith<IllegalArgumentException> {
            SessionSnapshot(SessionId(1), null, ExecutionTarget.TERMINAL_SESSION, SessionLifecycle.EXITED)
        }

        // Live sessions still must not carry one.
        assertFailsWith<IllegalArgumentException> {
            SessionSnapshot(SessionId(1), null, ExecutionTarget.TERMINAL_SESSION, SessionLifecycle.RUNNING, termination)
        }
    }

    private fun terminalRequest(
        executable: String = "/system/bin/sh",
        arguments: List<String> = emptyList(),
        environment: Map<String, String> = emptyMap(),
        timeout: ExecutionTimeout = ExecutionTimeout.Unlimited,
    ) = AppExecutionRequest(
        origin = RequestOrigin.Internal,
        executable = executable,
        arguments = arguments,
        target = ExecutionTarget.TERMINAL_SESSION,
        environment = environment,
        terminalSize = TerminalDimensions(80, 24),
        timeout = timeout,
    )

    private class RecordingTermuxServiceApi : TermuxServiceApi {
        var lastRequest: AppExecutionRequest? = null
        var lastInput: SessionInput? = null
        var cannedFrame: CachedSessionFrame? = null

        override fun createSession(request: AppExecutionRequest): AppShellResult<SessionId> {
            lastRequest = request
            return AppShellResult.Success(SessionId(1))
        }

        override fun sessions(): AppShellResult<List<SessionSnapshot>> = AppShellResult.Success(emptyList())
        override fun session(id: SessionId): AppShellResult<SessionSnapshot> = notFound()
        override fun writeInput(id: SessionId, input: SessionInput): AppShellResult<Unit> {
            lastInput = input
            return AppShellResult.Success(Unit)
        }
        override fun resize(id: SessionId, dimensions: TerminalDimensions): AppShellResult<Unit> = notFound()
        override fun cancel(id: SessionId, reason: CancellationReason): AppShellResult<Unit> = notFound()
        override fun close(id: SessionId): AppShellResult<Unit> = notFound()
        override fun cachedFrame(id: SessionId, afterVersion: Long?): AppShellResult<CachedSessionFrame?> {
            val frame = cannedFrame ?: return notFound()
            return AppShellResult.Success(if (afterVersion == null || frame.version > afterVersion) frame else null)
        }

        private fun <T> notFound(): AppShellResult<T> = AppShellResult.Failure(
            AppShellError(AppShellErrorCode.SESSION_NOT_FOUND, retryable = false),
        )
    }
}
