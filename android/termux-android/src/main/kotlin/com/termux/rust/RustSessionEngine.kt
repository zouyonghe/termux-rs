package com.termux.rust

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [SessionEngine] backed by the real Rust session through the existing
 * controller/JNI path. Constructed lazily on the service owner thread by the
 * registry; every native error is mapped to [SessionEngineException] so the
 * registry can fail the session deterministically.
 *
 * When [packageName] is provided, the child receives the deterministic
 * required shell environment (HOME/PREFIX/PATH/TMPDIR/TERM/locale/package
 * metadata from the Rust runtime) merged with the request's extras —
 * protected variables always come from the runtime and can never be
 * replaced by request overrides.
 *
 * APP_SHELL (noninteractive) requests currently spawn a headless PTY with
 * default 80x24 dimensions — pipe-based spawns are a later milestone.
 */
internal class RustSessionEngine(
    request: AppExecutionRequest,
    sessionId: SessionId,
    bridge: NativeTerminalSessionBridge = JniTerminalSessionBridge,
    packageName: String? = null,
) : SessionEngine {
    private val controller: TerminalSessionController = run {
        val size = request.terminalSize ?: TerminalDimensions(80, 24)
        try {
            if (packageName == null) {
                TerminalSessionController(request.executable, request.arguments, size.columns, size.rows, bridge)
            } else {
                val inherited = System.getenv().map { "${it.key}=${it.value}" }
                val required = bridge.runtimeEnvironment(packageName)
                val extras = request.environment.map { "${it.key}=${it.value}" }
                TerminalSessionController(
                    request.executable,
                    request.arguments,
                    size.columns,
                    size.rows,
                    bridge,
                    environment = inherited + required + extras,
                )
            }
        } catch (error: Throwable) {
            throw mapError(error)
        }
    }

    override fun writeInput(input: SessionInput) = mapErrors { controller.writeInput(input.copyBytes()) }

    override fun resize(dimensions: TerminalDimensions) =
        mapErrors { controller.resize(dimensions.columns, dimensions.rows) }

    override fun terminate() = mapErrors { controller.terminate() }

    override fun pollTermination(): SessionTermination? =
        mapErrors { controller.pollExitCode() }?.let { SessionTermination.ProcessExited(it) }

    override fun renderFrame(): Pair<Long, ByteArray>? {
        // Drain pending PTY output into the emulator before rendering, so
        // frames reflect child output produced since the last drive.
        val pumpStatus = mapErrors { controller.pumpOutput() }
        if (pumpStatus < 0) {
            throw mapError(
                IllegalStateException("native pump failed with status $pumpStatus"),
            )
        }
        val frame = mapErrors { controller.render() }
        if (frame.size < TRS1_HEADER_BYTES) return null
        val version = ByteBuffer.wrap(frame, TRS1_VERSION_OFFSET, Long.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .long
        return version to frame
    }

    override fun close() = mapErrors { controller.close() }

    private fun <T> mapErrors(block: () -> T): T =
        try {
            block()
        } catch (error: Throwable) {
            throw mapError(error)
        }

    private fun mapError(error: Throwable): SessionEngineException =
        (error as? SessionEngineException)
            ?: SessionEngineException(
                AppShellError(AppShellErrorCode.NATIVE_FAILURE, retryable = false),
                error,
            )

    private companion object {
        const val TRS1_HEADER_BYTES = 28
        const val TRS1_VERSION_OFFSET = 4
    }
}
