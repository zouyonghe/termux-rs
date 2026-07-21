use std::panic::{AssertUnwindSafe, catch_unwind};

use termux_core::emulator::Terminal;
use termux_core::terminal::Size;

pub const TERMUX_OK: i32 = 0;
pub const TERMUX_INVALID_ARGUMENT: i32 = -1;
pub const TERMUX_PANIC: i32 = -2;

/// Opaque terminal handle. Create with `termux_terminal_create` and release
/// exactly once with `termux_terminal_free`.
pub struct TermuxTerminal {
    terminal: Terminal,
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
        TERMUX_INVALID_ARGUMENT, TERMUX_OK, termux_terminal_create, termux_terminal_feed,
        termux_terminal_free, termux_terminal_render,
    };

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
}
