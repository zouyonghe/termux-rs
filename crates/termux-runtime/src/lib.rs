use std::collections::BTreeMap;
use std::fmt;
use std::path::{Path, PathBuf};

pub const DEFAULT_PACKAGE_NAME: &str = "com.termux";

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
    use super::{DEFAULT_PACKAGE_NAME, TermuxPaths};

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
}
