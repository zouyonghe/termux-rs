use std::collections::BTreeMap;
use std::fmt;
use std::path::{Path, PathBuf};

pub const DEFAULT_PACKAGE_NAME: &str = "com.termux";
const PROTECTED_ENVIRONMENT_VARIABLES: [&str; 5] =
    ["HOME", "PATH", "PREFIX", "TERMUX_APP_PACKAGE", "TMPDIR"];

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RunnerKind {
    TerminalSession,
    AppShell,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ExecutionRequest {
    pub executable: PathBuf,
    pub args: Vec<String>,
    pub stdin: Option<Vec<u8>>,
    pub working_directory: Option<PathBuf>,
    pub runner: RunnerKind,
    pub label: Option<String>,
    pub environment: BTreeMap<String, String>,
}

impl ExecutionRequest {
    pub fn validate(&self) -> Result<(), ExecutionRequestError> {
        if self.executable.as_os_str().is_empty() {
            return Err(ExecutionRequestError::EmptyExecutable);
        }
        if self.executable.as_os_str().as_encoded_bytes().contains(&0) {
            return Err(ExecutionRequestError::NulInExecutable);
        }
        for (index, argument) in self.args.iter().enumerate() {
            if argument.contains('\0') {
                return Err(ExecutionRequestError::NulInArgument(index));
            }
        }
        if self
            .working_directory
            .as_ref()
            .is_some_and(|path| path.as_os_str().as_encoded_bytes().contains(&0))
        {
            return Err(ExecutionRequestError::NulInWorkingDirectory);
        }
        validate_environment_overrides(&self.environment)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ExecutionRequestError {
    EmptyExecutable,
    NulInExecutable,
    NulInArgument(usize),
    NulInWorkingDirectory,
    InvalidEnvironmentName(String),
    NulInEnvironmentName(String),
    NulInEnvironmentValue(String),
    ProtectedEnvironmentVariable(String),
}

impl fmt::Display for ExecutionRequestError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "invalid execution request: {self:?}")
    }
}

impl std::error::Error for ExecutionRequestError {}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BootstrapPackageManager {
    Apt,
}

impl BootstrapPackageManager {
    pub const fn name(self) -> &'static str {
        match self {
            Self::Apt => "apt",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BootstrapVariant {
    AptAndroid5,
    AptAndroid7,
}

impl BootstrapVariant {
    pub const fn name(self) -> &'static str {
        match self {
            Self::AptAndroid5 => "apt-android-5",
            Self::AptAndroid7 => "apt-android-7",
        }
    }

    pub const fn package_manager(self) -> BootstrapPackageManager {
        BootstrapPackageManager::Apt
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct InvalidBootstrapVariant;

impl fmt::Display for InvalidBootstrapVariant {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("unsupported Termux bootstrap package variant")
    }
}

impl std::error::Error for InvalidBootstrapVariant {}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct BootstrapMetadata {
    variant: BootstrapVariant,
}

impl BootstrapMetadata {
    pub fn from_variant_name(name: &str) -> Result<Self, InvalidBootstrapVariant> {
        let variant = match name {
            "apt-android-5" => BootstrapVariant::AptAndroid5,
            "apt-android-7" => BootstrapVariant::AptAndroid7,
            _ => return Err(InvalidBootstrapVariant),
        };
        Ok(Self { variant })
    }

    pub const fn variant(self) -> BootstrapVariant {
        self.variant
    }

    pub const fn package_manager(self) -> BootstrapPackageManager {
        self.variant.package_manager()
    }
}

pub trait BootstrapStorage {
    fn is_installed(&mut self) -> Result<bool, String>;
    fn prepare_install(&mut self) -> Result<(), String>;
    fn install(&mut self) -> Result<(), String>;
    fn cleanup_failed_install(&mut self);
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum BootstrapState {
    NotInstalled,
    Installing,
    Installed,
    Failed(String),
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct BootstrapInstaller {
    state: BootstrapState,
}

impl Default for BootstrapInstaller {
    fn default() -> Self {
        Self::new()
    }
}

impl BootstrapInstaller {
    pub fn new() -> Self {
        Self {
            state: BootstrapState::NotInstalled,
        }
    }

    pub fn state(&self) -> &BootstrapState {
        &self.state
    }

    pub fn begin(&mut self, storage: &mut impl BootstrapStorage) -> &BootstrapState {
        if matches!(
            self.state,
            BootstrapState::Installed | BootstrapState::Installing
        ) {
            return &self.state;
        }

        self.state = match storage.is_installed() {
            Ok(true) => BootstrapState::Installed,
            Ok(false) => match storage.prepare_install() {
                Ok(()) => BootstrapState::Installing,
                Err(error) => BootstrapState::Failed(error),
            },
            Err(error) => BootstrapState::Failed(error),
        };
        &self.state
    }

    pub fn complete(&mut self, storage: &mut impl BootstrapStorage) -> &BootstrapState {
        if !matches!(self.state, BootstrapState::Installing) {
            return &self.state;
        }

        self.state = match storage.install() {
            Ok(()) => BootstrapState::Installed,
            Err(error) => {
                storage.cleanup_failed_install();
                BootstrapState::Failed(error)
            }
        };
        &self.state
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct InvalidPackageName;

impl fmt::Display for InvalidPackageName {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("package name must contain dot-separated ASCII identifier segments")
    }
}

impl std::error::Error for InvalidPackageName {}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TermuxPaths {
    package_name: String,
    files_dir: PathBuf,
    prefix_dir: PathBuf,
    home_dir: PathBuf,
}

impl TermuxPaths {
    pub fn new(package_name: impl AsRef<str>) -> Result<Self, InvalidPackageName> {
        let package_name = package_name.as_ref();
        if !valid_package_name(package_name) {
            return Err(InvalidPackageName);
        }

        let files_dir = Path::new("/data/data").join(package_name).join("files");
        let prefix_dir = files_dir.join("usr");
        let home_dir = files_dir.join("home");
        Ok(Self {
            package_name: package_name.to_owned(),
            files_dir,
            prefix_dir,
            home_dir,
        })
    }

    pub fn package_name(&self) -> &str {
        &self.package_name
    }

    pub fn files_dir(&self) -> &Path {
        &self.files_dir
    }

    pub fn prefix_dir(&self) -> &Path {
        &self.prefix_dir
    }

    pub fn bin_dir(&self) -> PathBuf {
        self.prefix_dir.join("bin")
    }

    pub fn tmp_dir(&self) -> PathBuf {
        self.prefix_dir.join("tmp")
    }

    pub fn home_dir(&self) -> &Path {
        &self.home_dir
    }

    pub fn environment(&self) -> BTreeMap<&'static str, String> {
        BTreeMap::from([
            ("HOME", self.home_dir.display().to_string()),
            ("PATH", self.bin_dir().display().to_string()),
            ("PREFIX", self.prefix_dir.display().to_string()),
            ("TERMUX_APP_PACKAGE", self.package_name.clone()),
            ("TMPDIR", self.tmp_dir().display().to_string()),
        ])
    }

    pub fn environment_with_overrides(
        &self,
        overrides: &BTreeMap<String, String>,
    ) -> Result<BTreeMap<String, String>, ExecutionRequestError> {
        validate_environment_overrides(overrides)?;
        let mut environment = self
            .environment()
            .into_iter()
            .map(|(name, value)| (name.to_owned(), value))
            .collect::<BTreeMap<_, _>>();
        environment.extend(overrides.clone());
        Ok(environment)
    }
}

fn validate_environment_overrides(
    overrides: &BTreeMap<String, String>,
) -> Result<(), ExecutionRequestError> {
    for (name, value) in overrides {
        if name.contains('\0') {
            return Err(ExecutionRequestError::NulInEnvironmentName(name.clone()));
        }
        if !valid_environment_name(name) {
            return Err(ExecutionRequestError::InvalidEnvironmentName(name.clone()));
        }
        if value.contains('\0') {
            return Err(ExecutionRequestError::NulInEnvironmentValue(name.clone()));
        }
        if PROTECTED_ENVIRONMENT_VARIABLES.contains(&name.as_str()) {
            return Err(ExecutionRequestError::ProtectedEnvironmentVariable(
                name.clone(),
            ));
        }
    }
    Ok(())
}

fn valid_environment_name(name: &str) -> bool {
    let mut bytes = name.bytes();
    matches!(bytes.next(), Some(b'A'..=b'Z' | b'a'..=b'z' | b'_'))
        && bytes.all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
}

fn valid_package_name(name: &str) -> bool {
    name.split('.').count() > 1
        && name.split('.').all(|segment| {
            !segment.is_empty()
                && segment
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
        })
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;
    use std::path::PathBuf;

    use super::{
        BootstrapInstaller, BootstrapMetadata, BootstrapPackageManager, BootstrapState,
        BootstrapStorage, BootstrapVariant, DEFAULT_PACKAGE_NAME, ExecutionRequest,
        ExecutionRequestError, RunnerKind, TermuxPaths,
    };

    struct MockStorage {
        installed: Result<bool, String>,
        prepare: Result<(), String>,
        install: Result<(), String>,
        calls: Vec<&'static str>,
    }

    impl Default for MockStorage {
        fn default() -> Self {
            Self {
                installed: Ok(false),
                prepare: Ok(()),
                install: Ok(()),
                calls: Vec::new(),
            }
        }
    }

    impl BootstrapStorage for MockStorage {
        fn is_installed(&mut self) -> Result<bool, String> {
            self.calls.push("is_installed");
            self.installed.clone()
        }

        fn prepare_install(&mut self) -> Result<(), String> {
            self.calls.push("prepare_install");
            self.prepare.clone()
        }

        fn install(&mut self) -> Result<(), String> {
            self.calls.push("install");
            self.install.clone()
        }

        fn cleanup_failed_install(&mut self) {
            self.calls.push("cleanup_failed_install");
        }
    }

    #[test]
    fn builds_default_termux_android_paths() {
        let paths = TermuxPaths::new(DEFAULT_PACKAGE_NAME).unwrap();

        assert_eq!(
            "/data/data/com.termux/files",
            paths.files_dir().to_str().unwrap()
        );
        assert_eq!(
            "/data/data/com.termux/files/usr",
            paths.prefix_dir().to_str().unwrap()
        );
        assert_eq!(
            "/data/data/com.termux/files/home",
            paths.home_dir().to_str().unwrap()
        );
        assert_eq!(
            "/data/data/com.termux/files/usr/tmp",
            paths.tmp_dir().to_str().unwrap()
        );
    }

    #[test]
    fn derives_paths_for_package_name_variants() {
        let paths = TermuxPaths::new("com.termux.debug").unwrap();

        assert_eq!("com.termux.debug", paths.package_name());
        assert_eq!(
            "com.termux.debug",
            paths.environment().get("TERMUX_APP_PACKAGE").unwrap()
        );
        assert_eq!(
            "/data/data/com.termux.debug/files/usr/bin",
            paths.environment().get("PATH").unwrap()
        );
    }

    #[test]
    fn rejects_package_names_that_cannot_be_path_components() {
        assert!(TermuxPaths::new("com.termux/../other").is_err());
    }

    #[test]
    fn bootstrap_metadata_only_accepts_supported_variants() {
        let metadata = BootstrapMetadata::from_variant_name("apt-android-7").unwrap();

        assert_eq!(BootstrapVariant::AptAndroid7, metadata.variant());
        assert_eq!(BootstrapPackageManager::Apt, metadata.package_manager());
        assert_eq!("apt", metadata.package_manager().name());
        assert_eq!("apt-android-7", metadata.variant().name());
        assert!(BootstrapMetadata::from_variant_name("pacman-android-7").is_err());
    }

    fn execution_request() -> ExecutionRequest {
        ExecutionRequest {
            executable: PathBuf::from("/data/data/com.termux/files/usr/bin/sh"),
            args: vec!["-l".to_string()],
            stdin: None,
            working_directory: Some(PathBuf::from("/data/data/com.termux/files/home")),
            runner: RunnerKind::TerminalSession,
            label: Some("shell".to_string()),
            environment: BTreeMap::new(),
        }
    }

    #[test]
    fn execution_request_allows_raw_stdin_and_validates_runner_data() {
        let mut request = execution_request();
        request.stdin = Some(vec![b'a', 0, b'b']);
        request.runner = RunnerKind::AppShell;

        assert_eq!(Ok(()), request.validate());
    }

    #[test]
    fn execution_request_rejects_invalid_fields_with_structured_errors() {
        let mut request = execution_request();
        request.executable = PathBuf::new();
        assert_eq!(
            Err(ExecutionRequestError::EmptyExecutable),
            request.validate()
        );

        request.executable = PathBuf::from("shell\0");
        assert_eq!(
            Err(ExecutionRequestError::NulInExecutable),
            request.validate()
        );

        request.executable = PathBuf::from("shell");
        request.args = vec!["ok".to_string(), "bad\0".to_string()];
        assert_eq!(
            Err(ExecutionRequestError::NulInArgument(1)),
            request.validate()
        );

        request.args.clear();
        request.working_directory = Some(PathBuf::from("home\0"));
        assert_eq!(
            Err(ExecutionRequestError::NulInWorkingDirectory),
            request.validate()
        );
    }

    #[test]
    fn execution_request_rejects_invalid_and_protected_environment_overrides() {
        let mut request = execution_request();
        request
            .environment
            .insert("1INVALID".to_string(), "x".to_string());
        assert_eq!(
            Err(ExecutionRequestError::InvalidEnvironmentName(
                "1INVALID".to_string()
            )),
            request.validate()
        );

        request.environment.clear();
        request
            .environment
            .insert("A\0".to_string(), "x".to_string());
        assert_eq!(
            Err(ExecutionRequestError::NulInEnvironmentName(
                "A\0".to_string()
            )),
            request.validate()
        );

        request.environment.clear();
        request
            .environment
            .insert("LANG".to_string(), "x\0".to_string());
        assert_eq!(
            Err(ExecutionRequestError::NulInEnvironmentValue(
                "LANG".to_string()
            )),
            request.validate()
        );

        request.environment.clear();
        request
            .environment
            .insert("PATH".to_string(), "other".to_string());
        assert_eq!(
            Err(ExecutionRequestError::ProtectedEnvironmentVariable(
                "PATH".to_string()
            )),
            request.validate()
        );
    }

    #[test]
    fn environment_overrides_merge_in_stable_order_without_replacing_termux_values() {
        let paths = TermuxPaths::new(DEFAULT_PACKAGE_NAME).unwrap();
        let overrides = BTreeMap::from([
            ("LANG".to_string(), "C.UTF-8".to_string()),
            ("TERM".to_string(), "xterm-256color".to_string()),
        ]);

        let environment = paths.environment_with_overrides(&overrides).unwrap();

        assert_eq!(Some(&"C.UTF-8".to_string()), environment.get("LANG"));
        assert_eq!(Some(&"xterm-256color".to_string()), environment.get("TERM"));
        assert_eq!(
            Some(&"/data/data/com.termux/files/usr/bin".to_string()),
            environment.get("PATH")
        );
        assert_eq!(
            vec![
                "HOME",
                "LANG",
                "PATH",
                "PREFIX",
                "TERM",
                "TERMUX_APP_PACKAGE",
                "TMPDIR"
            ],
            environment.keys().map(String::as_str).collect::<Vec<_>>()
        );
        assert_eq!(
            Err(ExecutionRequestError::ProtectedEnvironmentVariable(
                "HOME".to_string()
            )),
            paths.environment_with_overrides(&BTreeMap::from([(
                "HOME".to_string(),
                "other".to_string()
            )]))
        );
    }

    #[test]
    fn bootstrap_installer_transitions_from_missing_to_installed() {
        let mut storage = MockStorage {
            installed: Ok(false),
            prepare: Ok(()),
            install: Ok(()),
            ..MockStorage::default()
        };
        let mut installer = BootstrapInstaller::new();

        assert_eq!(&BootstrapState::Installing, installer.begin(&mut storage));
        assert_eq!(&BootstrapState::Installed, installer.complete(&mut storage));
        assert_eq!(
            storage.calls,
            ["is_installed", "prepare_install", "install"]
        );
    }

    #[test]
    fn bootstrap_installer_is_idempotent_after_installation() {
        let mut storage = MockStorage {
            installed: Ok(true),
            ..MockStorage::default()
        };
        let mut installer = BootstrapInstaller::new();

        assert_eq!(&BootstrapState::Installed, installer.begin(&mut storage));
        assert_eq!(&BootstrapState::Installed, installer.begin(&mut storage));
        assert_eq!(&BootstrapState::Installed, installer.complete(&mut storage));
        assert_eq!(storage.calls, ["is_installed"]);
    }

    #[test]
    fn bootstrap_installer_records_failures_and_cleans_up_install_errors() {
        let mut storage = MockStorage {
            installed: Ok(false),
            prepare: Ok(()),
            install: Err("extract failed".to_string()),
            ..MockStorage::default()
        };
        let mut installer = BootstrapInstaller::new();

        installer.begin(&mut storage);
        assert_eq!(
            &BootstrapState::Failed("extract failed".to_string()),
            installer.complete(&mut storage)
        );
        assert_eq!(
            storage.calls,
            [
                "is_installed",
                "prepare_install",
                "install",
                "cleanup_failed_install"
            ]
        );
    }
}
