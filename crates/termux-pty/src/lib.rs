use std::error::Error;
use std::io::{Read, Write};

use portable_pty::{Child, CommandBuilder, MasterPty, PtySize as NativePtySize, native_pty_system};

pub type PtyResult<T> = Result<T, Box<dyn Error + Send + Sync>>;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PtySize {
    pub columns: u16,
    pub rows: u16,
    pub pixel_width: u16,
    pub pixel_height: u16,
}

impl PtySize {
    pub const fn new(columns: u16, rows: u16) -> Self {
        Self {
            columns,
            rows,
            pixel_width: 0,
            pixel_height: 0,
        }
    }

    fn native(self) -> NativePtySize {
        NativePtySize {
            rows: self.rows,
            cols: self.columns,
            pixel_width: self.pixel_width,
            pixel_height: self.pixel_height,
        }
    }
}

pub trait PtySession: Read + Write {
    fn resize(&mut self, size: PtySize) -> PtyResult<()>;
}

pub struct UnixPtySession {
    master: Box<dyn MasterPty + Send>,
    reader: Box<dyn Read + Send>,
    writer: Box<dyn Write + Send>,
    _child: Box<dyn Child + Send + Sync>,
}

impl UnixPtySession {
    pub fn spawn(command: &str, args: &[&str], size: PtySize) -> PtyResult<Self> {
        let pair = native_pty_system().openpty(size.native())?;
        let mut builder = CommandBuilder::new(command);
        for argument in args {
            builder.arg(argument);
        }
        let child = pair.slave.spawn_command(builder)?;
        drop(pair.slave);
        let reader = pair.master.try_clone_reader()?;
        let writer = pair.master.take_writer()?;

        Ok(Self {
            master: pair.master,
            reader,
            writer,
            _child: child,
        })
    }
}

impl PtySession for UnixPtySession {
    fn resize(&mut self, size: PtySize) -> PtyResult<()> {
        self.master.resize(size.native())?;
        Ok(())
    }
}

impl Read for UnixPtySession {
    fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
        self.reader.read(buffer)
    }
}

impl Write for UnixPtySession {
    fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
        self.writer.write(buffer)
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.writer.flush()
    }
}

#[cfg(test)]
#[cfg(unix)]
mod tests {
    use std::io::{Read, Write};

    use super::{PtySession, PtySize, UnixPtySession};

    #[test]
    fn spawns_reads_and_resizes_unix_pty_session() {
        let mut session =
            UnixPtySession::spawn("/bin/sh", &["-c", "printf termux-rs"], PtySize::new(80, 24))
                .unwrap();
        session.resize(PtySize::new(100, 40)).unwrap();

        let mut output = String::new();
        session.read_to_string(&mut output).unwrap();

        assert_eq!("termux-rs", output);
    }

    #[test]
    fn writes_to_unix_pty_session() {
        let mut session = UnixPtySession::spawn(
            "/bin/sh",
            &["-c", "read line; printf '<%s>' \"$line\""],
            PtySize::new(80, 24),
        )
        .unwrap();

        session.write_all(b"hello\n").unwrap();
        session.flush().unwrap();
        let mut output = String::new();
        session.read_to_string(&mut output).unwrap();

        assert!(output.contains("<hello>"));
    }
}
