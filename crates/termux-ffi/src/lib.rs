use std::io::Write;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Arc;
use std::thread;

use termux_core::emulator::Terminal;
use termux_core::queue::{ByteQueue, QueueRead};
use termux_core::session::TerminalSessionClient;
use termux_core::terminal::Size;
use termux_pty::{PtySession, PtySize, UnixPtySession};
use termux_runtime::{BootstrapInstaller, BootstrapState, BootstrapStorage};

pub const TERMUX_OK: i32 = 0;
pub const TERMUX_INVALID_ARGUMENT: i32 = -1;
pub const TERMUX_PANIC: i32 = -2;
pub const TERMUX_IO_ERROR: i32 = -3;
pub const TERMUX_SESSION_RUNNING: i32 = 1;
pub const TERMUX_SESSION_OUTPUT_CLOSED: i32 = 2;
pub const TERMUX_BOOTSTRAP_NOT_INSTALLED: i32 = 0;
pub const TERMUX_BOOTSTRAP_INSTALLING: i32 = 1;
pub const TERMUX_BOOTSTRAP_INSTALLED: i32 = 2;
pub const TERMUX_BOOTSTRAP_FAILED: i32 = 3;

/// Opaque terminal handle. Create with `termux_terminal_create` and release
/// exactly once with `termux_terminal_free`.
pub struct TermuxTerminal {
    terminal: Terminal,
}

pub struct TermuxTerminalSession {
    terminal: Terminal,
    pty: UnixPtySession,
    output: Arc<ByteQueue>,
}

impl Drop for TermuxTerminalSession {
    /// Reap the child if it is still running so freeing a session handle
    /// never leaves a zombie behind. `terminate` is a no-op when the child
    /// already exited (the exit status is cached).
    fn drop(&mut self) {
        let _ = self.pty.terminate();
    }
}

struct NoopSessionClient;

impl TerminalSessionClient for NoopSessionClient {
    fn text_changed(&mut self) {}
    fn title_changed(&mut self) {}
    fn session_finished(&mut self) {}
    fn resized(&mut self, _: Size) {}
    fn cursor_state_changed(&mut self, _: bool) {}
}

/// # Safety
/// `command` and every argument must be valid NUL-terminated UTF-8 C strings.
/// `arguments` must contain `argument_count` readable pointers when non-null.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn termux_terminal_session_create(
    command: *const std::ffi::c_char,
    arguments: *const *const std::ffi::c_char,
    argument_count: usize,
    columns: usize,
    rows: usize,
) -> *mut TermuxTerminalSession {
    if command.is_null()
        || columns == 0
        || rows == 0
        || columns > u16::MAX as usize
        || rows > u16::MAX as usize
        || (arguments.is_null() && argument_count != 0)
    {
        return std::ptr::null_mut();
    }
    catch_unwind(AssertUnwindSafe(|| {
        let command = unsafe { std::ffi::CStr::from_ptr(command) }.to_str().ok()?;
        let arguments = if argument_count == 0 {
            &[]
        } else {
            unsafe { std::slice::from_raw_parts(arguments, argument_count) }
        };
        let arguments = arguments
            .iter()
            .map(|argument| {
                if argument.is_null() {
                    None
                } else {
                    unsafe { std::ffi::CStr::from_ptr(*argument) }.to_str().ok()
                }
            })
            .collect::<Option<Vec<_>>>()?;
        create_session_with_environment(
            command,
            arguments.iter().map(|value| (*value).to_string()).collect(),
            None,
            Size::new(columns, rows),
        )
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}

/// Creates a session with an explicit process environment (deterministic
/// required variables merged by the caller; protected values always win).
///
/// # Safety
/// Same contract as [`termux_terminal_session_create`]; `environment` must
/// contain `environment_count` readable pointers to NUL-terminated `K=V`
/// strings when non-null.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn termux_terminal_session_create_with_env(
    command: *const std::ffi::c_char,
    arguments: *const *const std::ffi::c_char,
    argument_count: usize,
    environment: *const *const std::ffi::c_char,
    environment_count: usize,
    columns: usize,
    rows: usize,
) -> *mut TermuxTerminalSession {
    if command.is_null()
        || columns == 0
        || rows == 0
        || columns > u16::MAX as usize
        || rows > u16::MAX as usize
        || (arguments.is_null() && argument_count != 0)
        || (environment.is_null() && environment_count != 0)
    {
        return std::ptr::null_mut();
    }
    catch_unwind(AssertUnwindSafe(|| {
        let command = unsafe { std::ffi::CStr::from_ptr(command) }.to_str().ok()?;
        let arguments = if argument_count == 0 {
            &[]
        } else {
            unsafe { std::slice::from_raw_parts(arguments, argument_count) }
        };
        let arguments = arguments
            .iter()
            .map(|argument| {
                if argument.is_null() {
                    None
                } else {
                    unsafe { std::ffi::CStr::from_ptr(*argument) }.to_str().ok()
                }
            })
            .collect::<Option<Vec<_>>>()?;
        let environment = if environment_count == 0 {
            &[]
        } else {
            unsafe { std::slice::from_raw_parts(environment, environment_count) }
        };
        let environment = environment
            .iter()
            .map(|entry| {
                if entry.is_null() {
                    return None;
                }
                let entry = unsafe { std::ffi::CStr::from_ptr(*entry) }.to_str().ok()?;
                let (name, value) = entry.split_once('=')?;
                Some((name.to_string(), value.to_string()))
            })
            .collect::<Option<std::collections::BTreeMap<_, _>>>()?;
        create_session_with_environment(
            command,
            arguments.iter().map(|value| (*value).to_string()).collect(),
            Some(environment),
            Size::new(columns, rows),
        )
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}

/// Serializes the deterministic required shell environment for `package_name`
/// as NUL-separated `K=V` pairs. Returns the required byte count when
/// `buffer` is null and `capacity` is zero, 0 on invalid input or insufficient
/// capacity.
///
/// # Safety
/// `package_name` must be a valid NUL-terminated UTF-8 C string; non-null
/// `buffer` must be writable for `capacity` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn termux_runtime_environment(
    package_name: *const std::ffi::c_char,
    buffer: *mut u8,
    capacity: usize,
) -> usize {
    if package_name.is_null() || (buffer.is_null() && capacity != 0) {
        return 0;
    }
    catch_unwind(AssertUnwindSafe(|| {
        let package_name = unsafe { std::ffi::CStr::from_ptr(package_name) }
            .to_str()
            .ok()?;
        let paths = termux_runtime::TermuxPaths::new(package_name).ok()?;
        let serialized = paths
            .environment()
            .into_iter()
            .map(|(name, value)| format!("{name}={value}"))
            .collect::<Vec<_>>()
            .join("\0");
        if buffer.is_null() && capacity == 0 {
            return Some(serialized.len());
        }
        if capacity < serialized.len() {
            return None;
        }
        unsafe { std::ptr::copy_nonoverlapping(serialized.as_ptr(), buffer, serialized.len()) };
        Some(serialized.len())
    }))
    .ok()
    .flatten()
    .unwrap_or(0)
}

fn create_session_with_environment(
    command: &str,
    arguments: Vec<String>,
    environment: Option<std::collections::BTreeMap<String, String>>,
    size: Size,
) -> Option<*mut TermuxTerminalSession> {
    let pty_command = termux_pty::PtyCommand {
        program: command.into(),
        args: arguments,
        working_directory: None,
        environment,
    };
    let mut pty = UnixPtySession::spawn_command(
        &pty_command,
        PtySize::new(size.columns as u16, size.rows as u16),
    )
    .ok()?;
    let reader = pty.take_reader().ok()?;
    let output = Arc::new(ByteQueue::new(64 * 1024));
    let queue = Arc::clone(&output);
    thread::spawn(move || {
        let mut reader = reader;
        let mut buffer = [0; 4096];
        while let Ok(count) = reader.read(&mut buffer) {
            if count == 0 || queue.write(&buffer[..count]).is_err() {
                break;
            }
        }
        queue.close();
    });
    Some(Box::into_raw(Box::new(TermuxTerminalSession {
        terminal: Terminal::new(size),
        pty,
        output,
    })))
}

/// # Safety
/// `handle` must be a live session with exclusive access. Non-empty `data`
/// must reference readable memory for `length` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn termux_terminal_session_feed_output(
    handle: *mut TermuxTerminalSession,
    data: *const u8,
    length: usize,
) -> i32 {
    if handle.is_null() || (data.is_null() && length != 0) {
        return TERMUX_INVALID_ARGUMENT;
    }
    catch_unwind(AssertUnwindSafe(|| {
        let data = if length == 0 {
            &[]
        } else {
            unsafe { std::slice::from_raw_parts(data, length) }
        };
        unsafe { &mut *handle }.terminal.feed_bytes(data);
        TERMUX_OK
    }))
    .unwrap_or(TERMUX_PANIC)
}

/// Drains available PTY output into the emulator without blocking.
///
/// # Safety
/// `handle` must be a live session with exclusive access.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn termux_terminal_session_pump_output(
    handle: *mut TermuxTerminalSession,
) -> i32 {
    if handle.is_null() {
        return TERMUX_INVALID_ARGUMENT;
    }
    catch_unwind(AssertUnwindSafe(|| {
        let session = unsafe { &mut *handle };
        let mut buffer = [0; 4096];
        match session.output.read(&mut buffer, false) {
            QueueRead::Data(count) => {
                if count > 0 {
                    session.terminal.feed_bytes(&buffer[..count]);
                }
                TERMUX_OK
            }
            QueueRead::Empty => TERMUX_SESSION_RUNNING,
            QueueRead::Closed => TERMUX_SESSION_OUTPUT_CLOSED,
        }
    }))
    .unwrap_or(TERMUX_PANIC)
}

/// # Safety
/// `handle` must be a live session with exclusive access. Non-empty `data`
/// must reference readable memory for `length` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn termux_terminal_session_write_input(
    handle: *mut TermuxTerminalSession,
    data: *const u8,
    length: usize,
) -> i32 {
    if handle.is_null() || (data.is_null() && length != 0) {
        return TERMUX_INVALID_ARGUMENT;
    }
    catch_unwind(AssertUnwindSafe(|| {
        let data = if length == 0 {
            &[]
        } else {
            unsafe { std::slice::from_raw_parts(data, length) }
        };
        let session = unsafe { &mut *handle };
        session
            .pty
            .write_all(data)
            .and_then(|()| session.pty.flush())
            .map_or(TERMUX_IO_ERROR, |_| TERMUX_OK)
    }))
    .unwrap_or(TERMUX_PANIC)
}

/// # Safety
/// `handle` must remain valid. Non-null `buffer` must be writable for
/// `capacity` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn termux_terminal_session_render(
    handle: *const TermuxTerminalSession,
    buffer: *mut u8,
    capacity: usize,
) -> usize {
    if handle.is_null() || (buffer.is_null() && capacity != 0) {
        return 0;
    }
    catch_unwind(AssertUnwindSafe(|| {
        let rendered = render_terminal(&unsafe { &*handle }.terminal);
        if buffer.is_null() && capacity == 0 {
            return rendered.len();
        }
        if capacity < rendered.len() {
            return 0;
        }
        unsafe { std::ptr::copy_nonoverlapping(rendered.as_ptr(), buffer, rendered.len()) };
        rendered.len()
    }))
    .unwrap_or(0)
}

/// # Safety
/// `handle` must be a live session with exclusive access.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn termux_terminal_session_resize(
    handle: *mut TermuxTerminalSession,
    columns: usize,
    rows: usize,
) -> i32 {
    if handle.is_null()
        || columns == 0
        || rows == 0
        || columns > u16::MAX as usize
        || rows > u16::MAX as usize
    {
        return TERMUX_INVALID_ARGUMENT;
    }
    catch_unwind(AssertUnwindSafe(|| {
        let session = unsafe { &mut *handle };
        session
            .pty
            .resize(PtySize::new(columns as u16, rows as u16))
            .map_err(|_| TERMUX_IO_ERROR)?;
        session
            .terminal
            .resize_with_client(Size::new(columns, rows), &mut NoopSessionClient);
        Ok::<_, i32>(TERMUX_OK)
    }))
    .unwrap_or(Err(TERMUX_PANIC))
    .unwrap_or_else(|status| status)
}

/// # Safety
/// `handle` must be a live session with exclusive access and `exit_code` must
/// point to writable memory.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn termux_terminal_session_try_wait(
    handle: *mut TermuxTerminalSession,
    exit_code: *mut u32,
) -> i32 {
    if handle.is_null() || exit_code.is_null() {
        return TERMUX_INVALID_ARGUMENT;
    }
    catch_unwind(AssertUnwindSafe(|| {
        match unsafe { &mut *handle }.pty.try_wait() {
            Ok(Some(status)) => {
                unsafe { *exit_code = status.code };
                TERMUX_OK
            }
            Ok(None) => TERMUX_SESSION_RUNNING,
            Err(_) => TERMUX_IO_ERROR,
        }
    }))
    .unwrap_or(TERMUX_PANIC)
}

/// # Safety
/// `handle` must be a live session with exclusive access.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn termux_terminal_session_terminate(
    handle: *mut TermuxTerminalSession,
) -> i32 {
    if handle.is_null() {
        return TERMUX_INVALID_ARGUMENT;
    }
    catch_unwind(AssertUnwindSafe(|| {
        unsafe { &mut *handle }
            .pty
            .terminate()
            .map_or(TERMUX_IO_ERROR, |_| TERMUX_OK)
    }))
    .unwrap_or(TERMUX_PANIC)
}

/// # Safety
/// `handle` must be null or an owned session handle not previously freed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn termux_terminal_session_free(handle: *mut TermuxTerminalSession) {
    if !handle.is_null() {
        let _ = catch_unwind(AssertUnwindSafe(|| unsafe { drop(Box::from_raw(handle)) }));
    }
}

/// Opaque bootstrap state handle. Create with `termux_bootstrap_create` and
/// release exactly once with `termux_bootstrap_free`.
pub struct TermuxBootstrap {
    installer: BootstrapInstaller,
}

#[derive(Clone, Copy)]
struct BootstrapOutcomeStorage {
    installed: bool,
    prepare_succeeded: bool,
    install_succeeded: bool,
}

impl BootstrapStorage for BootstrapOutcomeStorage {
    fn is_installed(&mut self) -> Result<bool, String> {
        Ok(self.installed)
    }

    fn prepare_install(&mut self) -> Result<(), String> {
        self.prepare_succeeded
            .then_some(())
            .ok_or_else(|| "Android bootstrap preparation failed".to_string())
    }

    fn install(&mut self) -> Result<(), String> {
        self.install_succeeded
            .then_some(())
            .ok_or_else(|| "Android bootstrap installation failed".to_string())
    }

    fn cleanup_failed_install(&mut self) {}
}

#[unsafe(no_mangle)]
pub extern "C" fn termux_bootstrap_create() -> *mut TermuxBootstrap {
    catch_unwind(AssertUnwindSafe(|| {
        Box::into_raw(Box::new(TermuxBootstrap {
            installer: BootstrapInstaller::new(),
        }))
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// Applies Kotlin's installed and prepare outcomes to a valid bootstrap handle.
///
/// # Safety
/// `handle` must originate from `termux_bootstrap_create` and be exclusively
/// owned for this call. Both outcome values must be zero or one.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn termux_bootstrap_begin(
    handle: *mut TermuxBootstrap,
    installed: i32,
    prepare_succeeded: i32,
) -> i32 {
    let (Some(installed), Some(prepare_succeeded)) =
        (as_bool(installed), as_bool(prepare_succeeded))
    else {
        return TERMUX_INVALID_ARGUMENT;
    };
    if handle.is_null() {
        return TERMUX_INVALID_ARGUMENT;
    }

    catch_unwind(AssertUnwindSafe(|| {
        let bootstrap = unsafe { &mut *handle };
        bootstrap.installer.begin(&mut BootstrapOutcomeStorage {
            installed,
            prepare_succeeded,
            install_succeeded: false,
        });
        TERMUX_OK
    }))
    .unwrap_or(TERMUX_PANIC)
}

/// Applies Kotlin's install outcome to a bootstrap handle in the installing state.
///
/// # Safety
/// `handle` must originate from `termux_bootstrap_create` and be exclusively
/// owned for this call. `install_succeeded` must be zero or one.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn termux_bootstrap_complete(
    handle: *mut TermuxBootstrap,
    install_succeeded: i32,
) -> i32 {
    let Some(install_succeeded) = as_bool(install_succeeded) else {
        return TERMUX_INVALID_ARGUMENT;
    };
    if handle.is_null() {
        return TERMUX_INVALID_ARGUMENT;
    }

    catch_unwind(AssertUnwindSafe(|| {
        let bootstrap = unsafe { &mut *handle };
        if !matches!(bootstrap.installer.state(), BootstrapState::Installing) {
            return TERMUX_INVALID_ARGUMENT;
        }
        bootstrap.installer.complete(&mut BootstrapOutcomeStorage {
            installed: false,
            prepare_succeeded: false,
            install_succeeded,
        });
        TERMUX_OK
    }))
    .unwrap_or(TERMUX_PANIC)
}

/// Returns a `TERMUX_BOOTSTRAP_*` state or `TERMUX_INVALID_ARGUMENT`.
///
/// # Safety
/// `handle` must originate from `termux_bootstrap_create` and remain valid for
/// this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn termux_bootstrap_state(handle: *const TermuxBootstrap) -> i32 {
    if handle.is_null() {
        return TERMUX_INVALID_ARGUMENT;
    }

    catch_unwind(AssertUnwindSafe(|| {
        let bootstrap = unsafe { &*handle };
        match bootstrap.installer.state() {
            BootstrapState::NotInstalled => TERMUX_BOOTSTRAP_NOT_INSTALLED,
            BootstrapState::Installing => TERMUX_BOOTSTRAP_INSTALLING,
            BootstrapState::Installed => TERMUX_BOOTSTRAP_INSTALLED,
            BootstrapState::Failed(_) => TERMUX_BOOTSTRAP_FAILED,
        }
    }))
    .unwrap_or(TERMUX_PANIC)
}

/// Releases a handle created by `termux_bootstrap_create`. Passing null is a no-op.
///
/// # Safety
/// The caller must pass either null or an owned handle returned by
/// `termux_bootstrap_create` that has not already been freed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn termux_bootstrap_free(handle: *mut TermuxBootstrap) {
    if handle.is_null() {
        return;
    }

    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        drop(Box::from_raw(handle));
    }));
}

fn as_bool(value: i32) -> Option<bool> {
    match value {
        0 => Some(false),
        1 => Some(true),
        _ => None,
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn termux_terminal_create(columns: usize, rows: usize) -> *mut TermuxTerminal {
    if columns == 0 || rows == 0 {
        return std::ptr::null_mut();
    }

    catch_unwind(AssertUnwindSafe(|| {
        Box::into_raw(Box::new(TermuxTerminal {
            terminal: Terminal::new(Size::new(columns, rows)),
        }))
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// Feeds input bytes into a valid terminal handle.
///
/// `handle` must originate from `termux_terminal_create`. `data` may be null
/// only when `length` is zero.
///
/// # Safety
/// The caller must retain exclusive ownership of `handle` for this call. When
/// `length` is nonzero, `data` must reference readable memory for `length`
/// bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn termux_terminal_feed(
    handle: *mut TermuxTerminal,
    data: *const u8,
    length: usize,
) -> i32 {
    if handle.is_null() || (data.is_null() && length != 0) {
        return TERMUX_INVALID_ARGUMENT;
    }

    catch_unwind(AssertUnwindSafe(|| {
        let terminal = unsafe { &mut *handle };
        let data = if length == 0 {
            &[]
        } else {
            unsafe { std::slice::from_raw_parts(data, length) }
        };
        terminal.terminal.feed_bytes(data);
        TERMUX_OK
    }))
    .unwrap_or(TERMUX_PANIC)
}

/// Returns required render byte count when `buffer` is null and `capacity` is
/// zero. Otherwise writes the whole render result and returns its byte count.
/// Returns zero on invalid input, insufficient capacity, or panic.
///
/// # Safety
/// `handle` must remain valid for the call. When `buffer` is non-null, it must
/// reference writable memory for `capacity` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn termux_terminal_render(
    handle: *const TermuxTerminal,
    buffer: *mut u8,
    capacity: usize,
) -> usize {
    if handle.is_null() || (buffer.is_null() && capacity != 0) {
        return 0;
    }

    catch_unwind(AssertUnwindSafe(|| {
        let terminal = unsafe { &*handle };
        let rendered = render_terminal(&terminal.terminal);
        if buffer.is_null() && capacity == 0 {
            return rendered.len();
        }
        if capacity < rendered.len() {
            return 0;
        }
        unsafe { std::ptr::copy_nonoverlapping(rendered.as_ptr(), buffer, rendered.len()) };
        rendered.len()
    }))
    .unwrap_or(0)
}

/// Releases a handle created by `termux_terminal_create`. Passing null is a
/// no-op. Passing a stale, foreign, or already-freed handle is undefined.
///
/// # Safety
/// The caller must pass either null or an owned handle returned by
/// `termux_terminal_create` that has not already been freed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn termux_terminal_free(handle: *mut TermuxTerminal) {
    if handle.is_null() {
        return;
    }

    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        drop(Box::from_raw(handle));
    }));
}

const SNAPSHOT_FLAG_CONTINUATION: u8 = 1;
const SNAPSHOT_FLAG_BOLD: u8 = 2;
const SNAPSHOT_FLAG_ITALIC: u8 = 4;
const SNAPSHOT_FLAG_UNDERLINE: u8 = 8;
const SNAPSHOT_FLAG_INVERSE: u8 = 16;

/// Encodes the terminal render snapshot in the platform-neutral `TRS1`
/// binary format consumed by the Android renderer:
///
/// - 4 bytes magic `TRS1`
/// - `u64` snapshot version (little-endian)
/// - `u32` column count, `u32` row count (little-endian)
/// - `u32` cursor column, `u32` cursor row (little-endian)
/// - per row: `u8` wrapped flag, then per cell: `u8` style/continuation
///   flags, `u8` cell width, `u16` UTF-8 text length, text bytes
fn render_terminal(terminal: &Terminal) -> Vec<u8> {
    let snapshot = terminal.snapshot();
    let mut rendered = Vec::new();
    rendered.extend_from_slice(b"TRS1");
    rendered.extend_from_slice(&snapshot.version.to_le_bytes());
    rendered.extend_from_slice(&(snapshot.size.columns as u32).to_le_bytes());
    rendered.extend_from_slice(&(snapshot.size.rows as u32).to_le_bytes());
    rendered.extend_from_slice(&(snapshot.cursor.column as u32).to_le_bytes());
    rendered.extend_from_slice(&(snapshot.cursor.row as u32).to_le_bytes());
    for row in &snapshot.rows {
        rendered.push(u8::from(row.wrapped));
        for cell in &row.cells {
            let mut flags = 0;
            if cell.continuation {
                flags |= SNAPSHOT_FLAG_CONTINUATION;
            }
            if cell.style.bold {
                flags |= SNAPSHOT_FLAG_BOLD;
            }
            if cell.style.italic {
                flags |= SNAPSHOT_FLAG_ITALIC;
            }
            if cell.style.underline {
                flags |= SNAPSHOT_FLAG_UNDERLINE;
            }
            if cell.style.inverse {
                flags |= SNAPSHOT_FLAG_INVERSE;
            }
            rendered.push(flags);
            rendered.push(cell.width as u8);
            let text = cell.text.as_deref().unwrap_or_default();
            // The protocol stores cell text length as `u16`; bound oversized
            // combining sequences at a UTF-8 boundary so the payload always
            // matches its declared length.
            let bounded = if text.len() > u16::MAX as usize {
                let mut end = u16::MAX as usize;
                while !text.is_char_boundary(end) {
                    end -= 1;
                }
                &text[..end]
            } else {
                text
            };
            rendered.extend_from_slice(&(bounded.len() as u16).to_le_bytes());
            rendered.extend_from_slice(bounded.as_bytes());
        }
    }
    rendered
}

#[cfg(test)]
mod tests {
    use super::{Size, Terminal};
    use super::{
        TERMUX_BOOTSTRAP_FAILED, TERMUX_BOOTSTRAP_INSTALLED, TERMUX_BOOTSTRAP_INSTALLING,
        TERMUX_INVALID_ARGUMENT, TERMUX_OK, termux_bootstrap_begin, termux_bootstrap_complete,
        termux_bootstrap_create, termux_bootstrap_free, termux_bootstrap_state,
        termux_runtime_environment, termux_terminal_create, termux_terminal_feed,
        termux_terminal_free, termux_terminal_render, termux_terminal_session_create,
        termux_terminal_session_create_with_env, termux_terminal_session_feed_output,
        termux_terminal_session_free, termux_terminal_session_pump_output,
        termux_terminal_session_render, termux_terminal_session_resize,
        termux_terminal_session_terminate, termux_terminal_session_try_wait,
        termux_terminal_session_write_input,
    };
    use std::ffi::CString;
    use std::thread;
    use std::time::Duration;

    struct SnapshotCell {
        flags: u8,
        width: u8,
        text: String,
    }

    struct SnapshotRow {
        wrapped: bool,
        cells: Vec<SnapshotCell>,
    }

    struct Snapshot {
        version: u64,
        columns: u32,
        rows: u32,
        cursor: (u32, u32),
        grid: Vec<SnapshotRow>,
    }

    impl Snapshot {
        fn decode(rendered: &[u8]) -> Self {
            assert_eq!(b"TRS1", rendered.get(..4).unwrap_or_default());
            let version = u64::from_le_bytes(rendered[4..12].try_into().unwrap());
            let columns = u32::from_le_bytes(rendered[12..16].try_into().unwrap());
            let rows = u32::from_le_bytes(rendered[16..20].try_into().unwrap());
            let cursor = (
                u32::from_le_bytes(rendered[20..24].try_into().unwrap()),
                u32::from_le_bytes(rendered[24..28].try_into().unwrap()),
            );
            let mut offset = 28;
            let grid = (0..rows)
                .map(|_| {
                    let wrapped = rendered[offset] != 0;
                    offset += 1;
                    let cells = (0..columns)
                        .map(|_| {
                            let flags = rendered[offset];
                            let width = rendered[offset + 1];
                            let length = u16::from_le_bytes(
                                rendered[offset + 2..offset + 4].try_into().unwrap(),
                            ) as usize;
                            offset += 4;
                            let text =
                                String::from_utf8(rendered[offset..offset + length].to_vec())
                                    .unwrap();
                            offset += length;
                            SnapshotCell { flags, width, text }
                        })
                        .collect();
                    SnapshotRow { wrapped, cells }
                })
                .collect();
            assert_eq!(rendered.len(), offset);
            Self {
                version,
                columns,
                rows,
                cursor,
                grid,
            }
        }

        fn row_text(&self, row: usize) -> String {
            self.grid[row]
                .cells
                .iter()
                .map(|cell| cell.text.as_str())
                .collect()
        }
    }

    fn assert_snapshot(rendered: &[u8], columns: u32, rows: u32, text: &str) {
        let snapshot = Snapshot::decode(rendered);
        assert!(snapshot.version > 0);
        assert_eq!(columns, snapshot.columns);
        assert_eq!(rows, snapshot.rows);
        assert!(snapshot.cursor.0 < columns);
        assert!(snapshot.cursor.1 < rows);
        assert!((0..rows as usize).any(|row| snapshot.row_text(row).contains(text)));
    }

    #[test]
    fn creates_feeds_renders_and_frees_a_terminal() {
        let handle = termux_terminal_create(4, 2);
        assert!(!handle.is_null());

        assert_eq!(TERMUX_OK, unsafe {
            termux_terminal_feed(handle, b"hi".as_ptr(), 2)
        });
        let required = unsafe { termux_terminal_render(handle, std::ptr::null_mut(), 0) };
        let mut rendered = vec![0; required];
        assert_eq!(required, unsafe {
            termux_terminal_render(handle, rendered.as_mut_ptr(), rendered.len())
        });
        assert_snapshot(&rendered, 4, 2, "hi");

        unsafe { termux_terminal_free(handle) };
    }

    #[test]
    fn rejects_invalid_boundary_arguments_without_panicking() {
        assert!(termux_terminal_create(0, 2).is_null());
        assert_eq!(TERMUX_INVALID_ARGUMENT, unsafe {
            termux_terminal_feed(std::ptr::null_mut(), std::ptr::null(), 1)
        });
    }

    #[test]
    fn bootstrap_ffi_transitions_from_missing_to_installed() {
        let handle = termux_bootstrap_create();
        assert!(!handle.is_null());

        assert_eq!(TERMUX_OK, unsafe { termux_bootstrap_begin(handle, 0, 1) });
        assert_eq!(TERMUX_BOOTSTRAP_INSTALLING, unsafe {
            termux_bootstrap_state(handle)
        });
        assert_eq!(TERMUX_OK, unsafe { termux_bootstrap_complete(handle, 1) });
        assert_eq!(TERMUX_BOOTSTRAP_INSTALLED, unsafe {
            termux_bootstrap_state(handle)
        });

        unsafe { termux_bootstrap_free(handle) };
    }

    #[test]
    fn bootstrap_ffi_keeps_an_installed_handle_idempotent() {
        let handle = termux_bootstrap_create();

        assert_eq!(TERMUX_OK, unsafe { termux_bootstrap_begin(handle, 1, 0) });
        assert_eq!(TERMUX_BOOTSTRAP_INSTALLED, unsafe {
            termux_bootstrap_state(handle)
        });
        assert_eq!(TERMUX_OK, unsafe { termux_bootstrap_begin(handle, 0, 0) });
        assert_eq!(TERMUX_BOOTSTRAP_INSTALLED, unsafe {
            termux_bootstrap_state(handle)
        });

        unsafe { termux_bootstrap_free(handle) };
    }

    #[test]
    fn bootstrap_ffi_rejects_invalid_values_and_transitions() {
        let handle = termux_bootstrap_create();

        assert_eq!(TERMUX_INVALID_ARGUMENT, unsafe {
            termux_bootstrap_begin(std::ptr::null_mut(), 0, 1)
        });
        assert_eq!(TERMUX_INVALID_ARGUMENT, unsafe {
            termux_bootstrap_begin(handle, 2, 1)
        });
        assert_eq!(TERMUX_INVALID_ARGUMENT, unsafe {
            termux_bootstrap_complete(handle, 1)
        });
        assert_eq!(TERMUX_INVALID_ARGUMENT, unsafe {
            termux_bootstrap_state(std::ptr::null())
        });

        assert_eq!(TERMUX_OK, unsafe { termux_bootstrap_begin(handle, 0, 0) });
        assert_eq!(TERMUX_BOOTSTRAP_FAILED, unsafe {
            termux_bootstrap_state(handle)
        });
        unsafe { termux_bootstrap_free(handle) };
    }

    #[test]
    fn terminal_session_ffi_feeds_renders_resizes_and_terminates() {
        let command = CString::new("/bin/sh").unwrap();
        let argument = CString::new("-c").unwrap();
        let script = CString::new("read line; sleep 10").unwrap();
        let arguments = [argument.as_ptr(), script.as_ptr()];
        let handle = unsafe {
            termux_terminal_session_create(
                command.as_ptr(),
                arguments.as_ptr(),
                arguments.len(),
                4,
                2,
            )
        };
        assert!(!handle.is_null());

        assert_eq!(TERMUX_OK, unsafe {
            termux_terminal_session_feed_output(handle, b"hi".as_ptr(), 2)
        });
        let required = unsafe { termux_terminal_session_render(handle, std::ptr::null_mut(), 0) };
        let mut rendered = vec![0; required];
        assert_eq!(required, unsafe {
            termux_terminal_session_render(handle, rendered.as_mut_ptr(), rendered.len())
        });
        assert_snapshot(&rendered, 4, 2, "hi");
        assert_eq!(TERMUX_OK, unsafe {
            termux_terminal_session_resize(handle, 6, 3)
        });
        assert_eq!(TERMUX_OK, unsafe {
            termux_terminal_session_write_input(handle, b"input\n".as_ptr(), 6)
        });
        assert_eq!(TERMUX_OK, unsafe {
            termux_terminal_session_terminate(handle)
        });
        let mut code = 0;
        assert_eq!(TERMUX_OK, unsafe {
            termux_terminal_session_try_wait(handle, &mut code)
        });

        unsafe { termux_terminal_session_free(handle) };
    }

    #[test]
    fn terminal_session_ffi_rejects_invalid_arguments() {
        assert!(
            unsafe { termux_terminal_session_create(std::ptr::null(), std::ptr::null(), 0, 4, 2) }
                .is_null()
        );
        assert_eq!(TERMUX_INVALID_ARGUMENT, unsafe {
            termux_terminal_session_feed_output(std::ptr::null_mut(), std::ptr::null(), 1)
        });
        assert_eq!(TERMUX_INVALID_ARGUMENT, unsafe {
            termux_terminal_session_resize(std::ptr::null_mut(), 4, 2)
        });
        assert_eq!(TERMUX_INVALID_ARGUMENT, unsafe {
            termux_terminal_session_try_wait(std::ptr::null_mut(), std::ptr::null_mut())
        });
    }

    #[test]
    fn terminal_session_pumps_pty_output_into_emulator() {
        let command = CString::new("/bin/sh").unwrap();
        let argument = CString::new("-c").unwrap();
        let script = CString::new("printf bridged").unwrap();
        let arguments = [argument.as_ptr(), script.as_ptr()];
        let handle = unsafe {
            termux_terminal_session_create(
                command.as_ptr(),
                arguments.as_ptr(),
                arguments.len(),
                8,
                1,
            )
        };

        for _ in 0..100 {
            let status = unsafe { termux_terminal_session_pump_output(handle) };
            if status == TERMUX_OK {
                break;
            }
            thread::sleep(Duration::from_millis(10));
        }
        let required = unsafe { termux_terminal_session_render(handle, std::ptr::null_mut(), 0) };
        let mut rendered = vec![0; required];
        unsafe { termux_terminal_session_render(handle, rendered.as_mut_ptr(), rendered.len()) };

        let snapshot = Snapshot::decode(&rendered);
        assert!(snapshot.row_text(0).starts_with("bridged"));
        unsafe { termux_terminal_session_free(handle) };
    }

    #[test]
    fn render_encodes_styles_wide_cells_and_continuations() {
        let handle = termux_terminal_create(4, 1);
        assert!(!handle.is_null());

        assert_eq!(TERMUX_OK, unsafe {
            termux_terminal_feed(handle, "\x1b[1m中".as_ptr(), "\x1b[1m中".len())
        });
        let required = unsafe { termux_terminal_render(handle, std::ptr::null_mut(), 0) };
        let mut rendered = vec![0; required];
        assert_eq!(required, unsafe {
            termux_terminal_render(handle, rendered.as_mut_ptr(), rendered.len())
        });

        let snapshot = Snapshot::decode(&rendered);
        assert!(!snapshot.grid[0].wrapped);
        let wide = &snapshot.grid[0].cells[0];
        assert_eq!("中", wide.text);
        assert_eq!(2, wide.width);
        assert_eq!(
            super::SNAPSHOT_FLAG_BOLD,
            wide.flags & super::SNAPSHOT_FLAG_BOLD
        );
        assert_eq!(0, wide.flags & super::SNAPSHOT_FLAG_CONTINUATION);
        let continuation = &snapshot.grid[0].cells[1];
        assert_ne!(0, continuation.flags & super::SNAPSHOT_FLAG_CONTINUATION);
        assert_eq!(0, continuation.width);

        unsafe { termux_terminal_free(handle) };
    }

    #[test]
    fn render_bounds_oversized_cell_text_without_malforming_snapshot() {
        let mut terminal = Terminal::new(Size::new(4, 1));
        let mut input = String::from("e");
        input.push_str(&"\u{301}".repeat(40_000));
        assert!(input.len() > u16::MAX as usize);
        terminal.feed_bytes(input.as_bytes());

        let rendered = super::render_terminal(&terminal);
        let snapshot = Snapshot::decode(&rendered);
        let cell = &snapshot.grid[0].cells[0];
        assert!(cell.text.len() <= u16::MAX as usize);
        assert!(cell.text.starts_with('e'));
    }

    #[test]
    fn freeing_a_running_session_reaps_the_child_via_drop() {
        let command = CString::new("/bin/sh").unwrap();
        let argument = CString::new("-c").unwrap();
        let script = CString::new("sleep 30").unwrap();
        let arguments = [argument.as_ptr(), script.as_ptr()];
        let handle = unsafe {
            termux_terminal_session_create(command.as_ptr(), arguments.as_ptr(), 2, 4, 2)
        };
        assert!(!handle.is_null());

        // Drop kills and waits for the child; without it this call would
        // leave a zombie and the test suite would hang on process cleanup.
        let started = std::time::Instant::now();
        unsafe { termux_terminal_session_free(handle) };
        assert!(started.elapsed() < Duration::from_secs(5));
    }

    #[test]
    fn create_with_env_spawns_child_with_given_environment() {
        let command = CString::new("/bin/sh").unwrap();
        let argument = CString::new("-c").unwrap();
        let script = CString::new("printf env-ok-$MARKER").unwrap();
        let arguments = [argument.as_ptr(), script.as_ptr()];
        let marker = CString::new("MARKER=child-sees-this").unwrap();
        let environment = [marker.as_ptr()];
        let handle = unsafe {
            termux_terminal_session_create_with_env(
                command.as_ptr(),
                arguments.as_ptr(),
                arguments.len(),
                environment.as_ptr(),
                environment.len(),
                40,
                2,
            )
        };
        assert!(!handle.is_null());

        let mut found = false;
        let mut last_text = String::new();
        for _ in 0..200 {
            unsafe { termux_terminal_session_pump_output(handle) };
            let required =
                unsafe { termux_terminal_session_render(handle, std::ptr::null_mut(), 0) };
            if required == 0 {
                thread::sleep(Duration::from_millis(10));
                continue;
            }
            let mut rendered = vec![0; required];
            unsafe {
                termux_terminal_session_render(handle, rendered.as_mut_ptr(), rendered.len())
            };
            let snapshot = Snapshot::decode(&rendered);
            last_text = (0..snapshot.rows as usize)
                .map(|row| snapshot.row_text(row))
                .collect::<Vec<_>>()
                .join("\n");
            if last_text.contains("env-ok-child-sees-this") {
                found = true;
                break;
            }
            thread::sleep(Duration::from_millis(10));
        }
        assert!(
            found,
            "child did not see MARKER in its environment; screen was:\n{last_text}"
        );
        unsafe { termux_terminal_session_free(handle) };
    }

    #[test]
    fn create_with_env_rejects_malformed_entries() {
        let command = CString::new("/bin/true").unwrap();
        let malformed = CString::new("MISSING_EQUALS").unwrap();
        let environment = [malformed.as_ptr()];

        let handle = unsafe {
            termux_terminal_session_create_with_env(
                command.as_ptr(),
                std::ptr::null(),
                0,
                environment.as_ptr(),
                environment.len(),
                40,
                2,
            )
        };

        assert!(handle.is_null());
    }

    #[test]
    fn runtime_environment_serializes_required_variables() {
        let package = CString::new("com.termux.test").unwrap();
        let required =
            unsafe { termux_runtime_environment(package.as_ptr(), std::ptr::null_mut(), 0) };
        assert!(required > 0);
        let mut buffer = vec![0; required];
        assert_eq!(required, unsafe {
            termux_runtime_environment(package.as_ptr(), buffer.as_mut_ptr(), buffer.len())
        });
        let serialized = String::from_utf8(buffer).unwrap();
        let entries: std::collections::BTreeMap<_, _> = serialized
            .split('\0')
            .map(|entry| entry.split_once('=').unwrap())
            .collect();

        assert_eq!("/data/data/com.termux.test/files/home", entries["HOME"]);
        assert_eq!("/data/data/com.termux.test/files/usr", entries["PREFIX"]);
        assert_eq!("/data/data/com.termux.test/files/usr/bin", entries["PATH"]);
        assert_eq!(
            "/data/data/com.termux.test/files/usr/tmp",
            entries["TMPDIR"]
        );
        assert_eq!("xterm-256color", entries["TERM"]);
        assert_eq!("C.UTF-8", entries["LANG"]);
        assert_eq!("C.UTF-8", entries["LC_ALL"]);
        assert_eq!("com.termux.test", entries["TERMUX_APP_PACKAGE"]);
        assert_eq!("apt", entries["TERMUX_APP_PACKAGE_MANAGER"]);
        assert_eq!("apt-android-7", entries["TERMUX_APP_PACKAGE_VARIANT"]);
    }
}
