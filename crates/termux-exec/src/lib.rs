use std::error::Error;
use std::fmt;
use std::io::Write;

use termux_pty::{PtyCommand, PtySize, UnixPtySession};
use termux_runtime::{ExecutionRequest, ExecutionRequestError, RunnerKind, TermuxPaths};

pub type ExecutionResult<T> = Result<T, UnixExecutionError>;

#[derive(Debug)]
pub enum UnixExecutionError {
    Request(ExecutionRequestError),
    UnsupportedRunner(RunnerKind),
    Pty(Box<dyn Error + Send + Sync>),
}

impl fmt::Display for UnixExecutionError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Request(error) => write!(formatter, "invalid execution request: {error}"),
            Self::UnsupportedRunner(runner) => {
                write!(
                    formatter,
                    "runner is not supported by Unix PTY adapter: {runner:?}"
                )
            }
            Self::Pty(error) => write!(formatter, "Unix PTY execution failed: {error}"),
        }
    }
}

impl Error for UnixExecutionError {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        match self {
            Self::Request(error) => Some(error),
            Self::UnsupportedRunner(_) => None,
            Self::Pty(error) => Some(error.as_ref()),
        }
    }
}

pub fn spawn_unix_pty(
    paths: &TermuxPaths,
    request: &ExecutionRequest,
    size: PtySize,
) -> ExecutionResult<UnixPtySession> {
    request.validate().map_err(UnixExecutionError::Request)?;
    if request.runner != RunnerKind::TerminalSession {
        return Err(UnixExecutionError::UnsupportedRunner(request.runner));
    }

    let environment = paths
        .environment_with_overrides(&request.environment)
        .map_err(UnixExecutionError::Request)?;
    let command = PtyCommand {
        program: request.executable.clone(),
        args: request.args.clone(),
        working_directory: request.working_directory.clone(),
        environment: Some(environment),
    };
    let mut session =
        UnixPtySession::spawn_command(&command, size).map_err(UnixExecutionError::Pty)?;
    if let Some(stdin) = &request.stdin {
        session
            .write_all(stdin)
            .map_err(|error| UnixExecutionError::Pty(Box::new(error)))?;
        session
            .flush()
            .map_err(|error| UnixExecutionError::Pty(Box::new(error)))?;
    }
    Ok(session)
}

#[cfg(test)]
#[cfg(unix)]
mod tests {
    use std::collections::BTreeMap;
    use std::io::Read;
    use std::path::PathBuf;

    use termux_pty::PtySize;
    use termux_runtime::{DEFAULT_PACKAGE_NAME, ExecutionRequest, RunnerKind, TermuxPaths};

    use super::{UnixExecutionError, spawn_unix_pty};

    #[test]
    fn spawns_terminal_request_with_args_stdin_cwd_and_environment() {
        let paths = TermuxPaths::new(DEFAULT_PACKAGE_NAME).unwrap();
        let request = ExecutionRequest {
            executable: PathBuf::from("/bin/sh"),
            args: vec![
                "-c".to_string(),
                "IFS= read -r input; printf '%s:%s:%s' \"$PWD\" \"$RUN_VALUE\" \"$input\""
                    .to_string(),
            ],
            stdin: Some(b"input\n".to_vec()),
            working_directory: Some(PathBuf::from("/tmp")),
            runner: RunnerKind::TerminalSession,
            label: None,
            environment: BTreeMap::from([("RUN_VALUE".to_string(), "value".to_string())]),
        };

        let mut session = spawn_unix_pty(&paths, &request, PtySize::new(80, 24)).unwrap();
        let mut output = String::new();
        session.read_to_string(&mut output).unwrap();

        let expected = format!(
            "{}:value:input",
            PathBuf::from("/tmp").canonicalize().unwrap().display()
        );
        assert!(output.contains(&expected));
    }

    #[test]
    fn rejects_invalid_request_before_spawning() {
        let paths = TermuxPaths::new(DEFAULT_PACKAGE_NAME).unwrap();
        let request = ExecutionRequest {
            executable: PathBuf::new(),
            args: Vec::new(),
            stdin: None,
            working_directory: None,
            runner: RunnerKind::TerminalSession,
            label: None,
            environment: BTreeMap::new(),
        };

        assert!(matches!(
            spawn_unix_pty(&paths, &request, PtySize::new(80, 24)),
            Err(UnixExecutionError::Request(_))
        ));
    }

    #[test]
    fn rejects_app_shell_requests() {
        let paths = TermuxPaths::new(DEFAULT_PACKAGE_NAME).unwrap();
        let request = ExecutionRequest {
            executable: PathBuf::from("/bin/sh"),
            args: Vec::new(),
            stdin: None,
            working_directory: None,
            runner: RunnerKind::AppShell,
            label: None,
            environment: BTreeMap::new(),
        };

        assert!(matches!(
            spawn_unix_pty(&paths, &request, PtySize::new(80, 24)),
            Err(UnixExecutionError::UnsupportedRunner(RunnerKind::AppShell))
        ));
    }
}
