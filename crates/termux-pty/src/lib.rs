use std::collections::BTreeMap;
use std::error::Error;
use std::io::{Read, Write};
use std::path::PathBuf;

use portable_pty::{
    Child, CommandBuilder, ExitStatus as NativeExitStatus, MasterPty, PtySize as NativePtySize,
    native_pty_system,
};

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

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PtyExitStatus {
    pub code: u32,
    pub signal: Option<String>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PtyCommand {
    pub program: PathBuf,
    pub args: Vec<String>,
    pub working_directory: Option<PathBuf>,
    pub environment: Option<BTreeMap<String, String>>,
}

impl PtyCommand {
    pub fn new(program: impl Into<PathBuf>) -> Self {
        Self {
            program: program.into(),
            args: Vec::new(),
            working_directory: None,
            environment: None,
        }
    }
}

impl PtyExitStatus {
    pub fn success(&self) -> bool {
        self.code == 0 && self.signal.is_none()
    }
}

impl From<NativeExitStatus> for PtyExitStatus {
    fn from(status: NativeExitStatus) -> Self {
        Self {
            code: status.exit_code(),
            signal: status.signal().map(str::to_owned),
        }
    }
}

pub trait PtySession: Read + Write {
    fn resize(&mut self, size: PtySize) -> PtyResult<()>;
    fn try_wait(&mut self) -> PtyResult<Option<PtyExitStatus>>;
    fn wait(&mut self) -> PtyResult<PtyExitStatus>;
    fn terminate(&mut self) -> PtyResult<PtyExitStatus>;
}

pub struct UnixPtySession {
    master: Box<dyn MasterPty + Send>,
    reader: Box<dyn Read + Send>,
    writer: Box<dyn Write + Send>,
    child: Box<dyn Child + Send + Sync>,
    exit_status: Option<PtyExitStatus>,
}

impl UnixPtySession {
    pub fn spawn(command: &str, args: &[&str], size: PtySize) -> PtyResult<Self> {
        let mut configured = PtyCommand::new(command);
        configured.args = args.iter().map(|argument| (*argument).to_owned()).collect();
        Self::spawn_command(&configured, size)
    }

    pub fn spawn_command(command: &PtyCommand, size: PtySize) -> PtyResult<Self> {
        let pair = native_pty_system().openpty(size.native())?;
        let mut builder = CommandBuilder::new(&command.program);
        for argument in &command.args {
            builder.arg(argument);
        }
        if let Some(directory) = &command.working_directory {
            builder.cwd(directory);
        }
        if let Some(environment) = &command.environment {
            builder.env_clear();
            for (name, value) in environment {
                builder.env(name, value);
            }
        }
        let child = pair.slave.spawn_command(builder)?;
        drop(pair.slave);
        let reader = pair.master.try_clone_reader()?;
        let writer = pair.master.take_writer()?;

        Ok(Self {
            master: pair.master,
            reader,
            writer,
            child,
            exit_status: None,
        })
    }

    fn record_exit(&mut self, status: NativeExitStatus) -> PtyExitStatus {
        let status = PtyExitStatus::from(status);
        self.exit_status = Some(status.clone());
        status
    }
}

impl PtySession for UnixPtySession {
    fn resize(&mut self, size: PtySize) -> PtyResult<()> {
        self.master.resize(size.native())?;
        Ok(())
    }

    fn try_wait(&mut self) -> PtyResult<Option<PtyExitStatus>> {
        if let Some(status) = &self.exit_status {
            return Ok(Some(status.clone()));
        }
        Ok(self
            .child
            .try_wait()?
            .map(|status| self.record_exit(status)))
    }

    fn wait(&mut self) -> PtyResult<PtyExitStatus> {
        if let Some(status) = &self.exit_status {
            return Ok(status.clone());
        }
        let status = self.child.wait()?;
        Ok(self.record_exit(status))
    }

    fn terminate(&mut self) -> PtyResult<PtyExitStatus> {
        if let Some(status) = &self.exit_status {
            return Ok(status.clone());
        }
        self.child.kill()?;
        self.wait()
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
    use std::collections::BTreeMap;
    use std::io::{Read, Write};
    use std::path::PathBuf;
    use std::thread;
    use std::time::Duration;

    use super::{PtyCommand, PtySession, PtySize, UnixPtySession};

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

    #[test]
    fn waits_for_and_caches_child_exit_status() {
        let mut session =
            UnixPtySession::spawn("/bin/sh", &["-c", "exit 7"], PtySize::new(80, 24)).unwrap();

        let status = session.wait().unwrap();

        assert_eq!(7, status.code);
        assert!(!status.success());
        assert_eq!(Some(status), session.try_wait().unwrap());
    }

    #[test]
    fn polls_a_naturally_exited_child() {
        let mut session =
            UnixPtySession::spawn("/bin/sh", &["-c", "exit 0"], PtySize::new(80, 24)).unwrap();

        for _ in 0..100 {
            if let Some(status) = session.try_wait().unwrap() {
                assert!(status.success());
                return;
            }
            thread::sleep(Duration::from_millis(10));
        }

        panic!("child did not exit within one second");
    }

    #[test]
    fn terminates_a_running_child_process() {
        let mut session =
            UnixPtySession::spawn("/bin/sh", &["-c", "sleep 10"], PtySize::new(80, 24)).unwrap();

        let status = session.terminate().unwrap();

        assert!(!status.success());
        assert_eq!(status, session.terminate().unwrap());
    }

    #[test]
    fn spawns_with_configured_working_directory_and_environment() {
        let command = PtyCommand {
            program: PathBuf::from("/bin/sh"),
            args: vec![
                "-c".to_string(),
                "printf '%s:%s' \"$PWD\" \"$ONLY_SET\"".to_string(),
            ],
            working_directory: Some(PathBuf::from("/tmp")),
            environment: Some(BTreeMap::from([(
                "ONLY_SET".to_string(),
                "value".to_string(),
            )])),
        };
        let mut session = UnixPtySession::spawn_command(&command, PtySize::new(80, 24)).unwrap();

        let mut output = String::new();
        session.read_to_string(&mut output).unwrap();

        let expected = format!(
            "{}:value",
            PathBuf::from("/tmp").canonicalize().unwrap().display()
        );
        assert_eq!(expected, output);
    }
}
