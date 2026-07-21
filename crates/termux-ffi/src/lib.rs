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
        let size = Size::new(columns, rows);
        let mut pty = UnixPtySession::spawn(
            command,
            &arguments,
            PtySize::new(columns as u16, rows as u16),
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
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
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

fn render_terminal(terminal: &Terminal) -> Vec<u8> {
    (0..terminal.screen().size().rows)
        .filter_map(|row| terminal.screen().row_text(row))
        .collect::<Vec<_>>()
        .join("\n")
        .into_bytes()
}

#[cfg(test)]
mod tests {
    use super::{
        TERMUX_BOOTSTRAP_FAILED, TERMUX_BOOTSTRAP_INSTALLED, TERMUX_BOOTSTRAP_INSTALLING,
        TERMUX_INVALID_ARGUMENT, TERMUX_OK, termux_bootstrap_begin, termux_bootstrap_complete,
        termux_bootstrap_create, termux_bootstrap_free, termux_bootstrap_state,
        termux_terminal_create, termux_terminal_feed, termux_terminal_free, termux_terminal_render,
        termux_terminal_session_create, termux_terminal_session_feed_output,
        termux_terminal_session_free, termux_terminal_session_pump_output,
        termux_terminal_session_render, termux_terminal_session_resize,
        termux_terminal_session_terminate, termux_terminal_session_try_wait,
        termux_terminal_session_write_input,
    };
    use std::ffi::CString;
    use std::thread;
    use std::time::Duration;

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
        assert_eq!(b"hi  \n    ", rendered.as_slice());

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
        assert_eq!(b"hi  \n    ", rendered.as_slice());
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

        assert!(rendered.starts_with(b"bridged"));
        unsafe { termux_terminal_session_free(handle) };
    }
}
