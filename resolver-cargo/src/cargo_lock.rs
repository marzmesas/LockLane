//! Parse `Cargo.lock` to recover the resolved version of each crate.
//!
//! Cargo.lock is TOML with a top-level `[[package]]` array; each entry has
//! at minimum `name` and `version`. Returns a `{name: version}` map.
//! Missing file, malformed TOML, and absent package arrays all return an
//! empty map — absence of lock data is not an error.

use std::collections::BTreeMap;
use std::path::Path;

pub fn parse_cargo_lock(path: &Path) -> BTreeMap<String, String> {
    let mut result = BTreeMap::new();
    let Ok(content) = std::fs::read_to_string(path) else {
        return result;
    };
    let Ok(doc) = content.parse::<toml::Value>() else {
        return result;
    };
    let Some(packages) = doc.get("package").and_then(|v| v.as_array()) else {
        return result;
    };
    for entry in packages {
        let Some(table) = entry.as_table() else { continue };
        let name = table.get("name").and_then(|v| v.as_str());
        let version = table.get("version").and_then(|v| v.as_str());
        if let (Some(n), Some(v)) = (name, version) {
            if !n.is_empty() && !v.is_empty() {
                result.insert(n.to_string(), v.to_string());
            }
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn write_lock(dir: &TempDir, content: &str) -> std::path::PathBuf {
        let path = dir.path().join("Cargo.lock");
        std::fs::write(&path, content).unwrap();
        path
    }

    const SAMPLE: &str = r#"
version = 3

[[package]]
name = "serde"
version = "1.0.210"

[[package]]
name = "tokio"
version = "1.40.0"
"#;

    #[test]
    fn parses_name_and_version() {
        let dir = TempDir::new().unwrap();
        let path = write_lock(&dir, SAMPLE);
        let locks = parse_cargo_lock(&path);
        assert_eq!(locks.get("serde").map(String::as_str), Some("1.0.210"));
        assert_eq!(locks.get("tokio").map(String::as_str), Some("1.40.0"));
    }

    #[test]
    fn missing_file_returns_empty() {
        let locks = parse_cargo_lock(Path::new("/nonexistent/Cargo.lock"));
        assert!(locks.is_empty());
    }

    #[test]
    fn malformed_toml_returns_empty() {
        let dir = TempDir::new().unwrap();
        let path = write_lock(&dir, "not valid = toml = junk");
        let locks = parse_cargo_lock(&path);
        assert!(locks.is_empty());
    }

    #[test]
    fn entries_without_version_are_skipped() {
        let dir = TempDir::new().unwrap();
        let body = r#"
[[package]]
name = "complete"
version = "1.0.0"

[[package]]
name = "nover"
"#;
        let path = write_lock(&dir, body);
        let locks = parse_cargo_lock(&path);
        assert_eq!(locks.len(), 1);
        assert_eq!(locks.get("complete").map(String::as_str), Some("1.0.0"));
    }
}
