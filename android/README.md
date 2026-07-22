# Android Terminal (Vertical Slice)

The `termux-android` library is the first usable Android terminal path on top
of the Rust core: one debug `TerminalActivity` opens a Rust-backed PTY shell,
renders terminal output, forwards keyboard input, resizes with the surface,
and terminates the session cleanly on lifecycle exit.

This is a **single-session demo**, not the production Termux app. See
[Known limitations](#known-limitations) before building on it.

## Prerequisites

| Requirement | Version / note |
| --- | --- |
| Android SDK | API 24+ platform; `compileSdk 36`, `minSdk 24` |
| Android NDK | r29 (`29.0.14206865` verified) via `ANDROID_NDK_HOME` |
| Rust target | `aarch64-linux-android` (`rustup target add aarch64-linux-android`) |
| ABI | `arm64-v8a` only |
| JDK | **17** for `testDebugUnitTest` (Robolectric 4.15.1 cannot read JDK 24+ class files); any JDK 17+ works for `assembleDebug`/`connectedDebugAndroidTest` |
| Emulator | API 24+ `arm64-v8a` for instrumented tests (API 32 verified) |

## Build and test

```sh
# Host-side Rust checks (no Android SDK needed)
cargo test --workspace

# Android library + Rust JNI cross-build
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
cd android
gradle :termux-android:assembleDebug

# JVM unit tests (codec, renderer, session controller/supervisor) — JDK 17
JAVA_HOME=/path/to/jdk-17 gradle :termux-android:testDebugUnitTest

# Instrumented tests on a running emulator (incl. full E2E journey)
gradle :termux-android:connectedDebugAndroidTest
```

`scripts/build-android-jni.sh` cross-compiles `termux-ffi`, links its static
Rust library with the C JNI shim (`src/main/cpp/bootstrap_jni.c`), and writes
`libtermux_ffi.so` under the Gradle build directory. No generated native
artifact is checked in.

## Data flow

```text
Keyboard ──▶ TerminalActivity.writeKey
                │  UTF-8 bytes (xterm-style escapes for arrows/Ctrl)
                ▼
        TerminalSessionSupervisor ── main-thread confined boundary
                │  writeInput / pumpFrame / resize / terminate
                ▼
        TerminalSessionController ── typed handle + status mapping
                │  JNI (bootstrap_jni.c)
                ▼
        termux-ffi (C ABI) ── TermuxTerminalSession
                │  write→PTY, pump←ByteQueue, render→TRS1 snapshot
                ▼
        termux-core Terminal (emulator) ◀── termux-pty UnixPtySession ◀── /system/bin/sh

Render path: PTY reader thread ─▶ ByteQueue (64 KB) ─▶ pumpOutput ─▶
Terminal.feed_bytes ─▶ render_terminal (TRS1 encode) ─▶ JNI ─▶
TerminalSnapshotCodec.decode ─▶ TerminalTextRenderer ─▶ TextView
```

All FFI session calls are serialized on the Android main thread; the Rust FFI
requires exclusive session access. The only background thread is the Rust PTY
reader, which never touches the emulator — it only fills the byte queue.

## Session lifecycle contract

- **Ownership**: `TerminalActivity` owns one `TerminalSessionSupervisor`
  (states RUNNING → EXITED → CLOSED). A future `TermuxService` handoff must go
  through this boundary; no Binder/foreground-service migration exists yet.
- **Pause/resume**: `onStop` stops the refresh loop (idempotent); the session
  and Rust reader thread stay alive in background. `onStart` restarts
  rendering. Repeated events never stack callbacks.
- **Child exit**: `pumpOutput` reports `SESSION_OUTPUT_CLOSED`; the supervisor
  polls `tryWait` until the exit code arrives, then freezes input/resize and
  appends `[process exited with code N]` to the UI. The final frame stays
  visible.
- **Explicit close**: `close()` terminates a running child exactly once
  (SIGKILL + wait) before freeing the native handle; repeated
  `terminate()`/`close()` are idempotent. As a safety net, the Rust
  `TermuxTerminalSession` `Drop` reaps the child, so no path leaves a zombie.
- **Resize**: surface measurement (`paint.measureText("M")` × `lineHeight`)
  derives columns/rows; `session.resize` fires only on change and propagates
  to the PTY (SIGWINCH) and emulator.

## TRS1 snapshot format

Platform-neutral, little-endian, emitted by
`termux_terminal_(session_)render`:

| Field | Type | Notes |
| --- | --- | --- |
| magic | 4 bytes | `TRS1` |
| version | u64 | bumps per feed/resize; UI skips stale frames |
| columns, rows | u32 ×2 | grid dimensions |
| cursor column, row | u32 ×2 | |
| per row | u8 wrapped flag, then per cell: u8 flags, u8 width, u16 text length, UTF-8 bytes | flags: 1=continuation, 2=bold, 4=italic, 8=underline, 16=inverse |

Bounds: per-cell text is truncated at a UTF-8 boundary to fit the u16 length
field (may split a combining sequence — display artifact only); the Kotlin
codec rejects truncated/oversized input with `IllegalArgumentException`.
Colors are not yet encoded (style flags only).

## Known limitations

- **Single session, demo shell**: hardcoded `/system/bin/sh`, no session
  switcher, no bootstrap/packages, no Intent/plugin/notification handling.
  Production Termux behaviors are out of scope for this slice.
- **Background output stall**: the Rust reader drains the PTY into a bounded
  64 KB queue. While backgrounded (no pump), a child writing more than 64 KB
  blocks until the app returns to foreground.
- **Placeholder renderer**: a monospaced `TextView`, not a grid canvas. Wide
  characters rely on font metrics, no color palette (bold/italic/underline/
  inverse only), no scrollback view, 16 ms polling instead of event-driven
  redraw.
- **Keyboard coverage**: printable chars, Enter/Delete, arrows, Ctrl+C only;
  no IME composition, function keys, or modifier combos.
- **Tests**: JVM unit tests require JDK 17 (Robolectric constraint above);
  instrumented tests verified on API 32 emulator, claimed compatible with
  API 24+ without per-API device matrix.

## Next phase entry points

- `TermuxService` handoff behind `TerminalSessionSupervisor` (Binder,
  foreground service, multi-session registry)
- Grid/canvas renderer consuming `TerminalSnapshot` directly (colors,
  scrollback, event-driven invalidation — extend TRS1 with a version bump)
- Bootstrap installer wiring (`BootstrapController` + `termux-ffi` bootstrap
  state machine) and package-managed environment
- CI gate for Android/NDK builds and connected tests
