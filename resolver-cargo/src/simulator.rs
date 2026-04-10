//! Simulation engine: Cargo.toml rewriting, resolution via cargo metadata,
//! conflict chain parsing from cargo stderr.

use std::path::{Path, PathBuf};
use std::process::Command;

use regex::Regex;
use tempfile::TempDir;

use crate::models::*;

/// Result of simulating a single candidate version bump.
pub struct SimResult {
    pub classification: &'static str, // SAFE_NOW, BLOCKED, INCONCLUSIVE
    pub explanation: String,
    pub conflict_chain: Option<ConflictChain>,
    pub stdout: String,
    pub stderr: String,
}

/// Simulate resolution with a bumped version and classify the result.
///
/// Copies the project to a temp directory, rewrites the dependency version,
/// runs `cargo metadata` to test resolution, and classifies the outcome.
pub fn simulate_candidate(
    manifest_path: &Path,
    package: &str,
    target_version: &str,
) -> SimResult {
    // Create temp workspace
    let temp_dir = match setup_temp_workspace(manifest_path) {
        Ok(d) => d,
        Err(e) => {
            return SimResult {
                classification: "INCONCLUSIVE",
                explanation: format!("Failed to create temp workspace: {e}"),
                conflict_chain: None,
                stdout: String::new(),
                stderr: String::new(),
            };
        }
    };

    let temp_manifest = temp_dir.path().join("Cargo.toml");

    // Rewrite the dependency version
    if let Err(e) = rewrite_dependency(&temp_manifest, package, target_version) {
        return SimResult {
            classification: "INCONCLUSIVE",
            explanation: format!("Failed to rewrite manifest: {e}"),
            conflict_chain: None,
            stdout: String::new(),
            stderr: String::new(),
        };
    }

    // Run cargo metadata to test resolution
    let output = Command::new("cargo")
        .args([
            "metadata",
            "--format-version",
            "1",
            "--manifest-path",
            &temp_manifest.to_string_lossy(),
        ])
        .output();

    let output = match output {
        Ok(o) => o,
        Err(e) => {
            return SimResult {
                classification: "INCONCLUSIVE",
                explanation: format!("Failed to run cargo metadata: {e}"),
                conflict_chain: None,
                stdout: String::new(),
                stderr: String::new(),
            };
        }
    };

    let stdout = String::from_utf8_lossy(&output.stdout).to_string();
    let stderr = String::from_utf8_lossy(&output.stderr).to_string();

    if output.status.success() {
        // Check if the target version is in the resolved output
        if version_in_metadata(&stdout, package, target_version) {
            SimResult {
                classification: "SAFE_NOW",
                explanation: format!("Resolution succeeded with {package}=={target_version}."),
                conflict_chain: None,
                stdout,
                stderr,
            }
        } else {
            SimResult {
                classification: "INCONCLUSIVE",
                explanation: format!(
                    "Resolution succeeded but {package}=={target_version} was not found in resolved output."
                ),
                conflict_chain: None,
                stdout,
                stderr,
            }
        }
    } else {
        let chain = parse_cargo_conflict(&stderr);
        SimResult {
            classification: "BLOCKED",
            explanation: format!("Resolution failed: {}", first_error_line(&stderr)),
            conflict_chain: chain,
            stdout,
            stderr,
        }
    }
}

/// Set up a temporary workspace with Cargo.toml (and Cargo.lock if present).
fn setup_temp_workspace(manifest_path: &Path) -> Result<TempDir, String> {
    let temp_dir = TempDir::new().map_err(|e| e.to_string())?;
    let project_dir = manifest_path.parent().ok_or("No parent directory")?;

    // Copy Cargo.toml
    std::fs::copy(manifest_path, temp_dir.path().join("Cargo.toml"))
        .map_err(|e| format!("Failed to copy Cargo.toml: {e}"))?;

    // Copy Cargo.lock if present (keeps unrelated deps pinned)
    let lock_path = project_dir.join("Cargo.lock");
    if lock_path.is_file() {
        std::fs::copy(&lock_path, temp_dir.path().join("Cargo.lock"))
            .map_err(|e| format!("Failed to copy Cargo.lock: {e}"))?;
    }

    // Create a minimal src/lib.rs so cargo metadata can run
    let src_dir = temp_dir.path().join("src");
    std::fs::create_dir_all(&src_dir).map_err(|e| e.to_string())?;

    // Check if original has src/main.rs or src/lib.rs to determine project type
    if project_dir.join("src/main.rs").is_file() {
        std::fs::write(src_dir.join("main.rs"), "fn main() {}\n")
            .map_err(|e| e.to_string())?;
    } else {
        std::fs::write(src_dir.join("lib.rs"), "// placeholder\n")
            .map_err(|e| e.to_string())?;
    }

    Ok(temp_dir)
}

/// Rewrite a dependency version in Cargo.toml using toml_edit to preserve formatting.
fn rewrite_dependency(manifest_path: &Path, package: &str, target_version: &str) -> Result<(), String> {
    let content = std::fs::read_to_string(manifest_path).map_err(|e| e.to_string())?;
    let mut doc: toml_edit::DocumentMut = content.parse().map_err(|e: toml_edit::TomlError| e.to_string())?;

    let mut found = false;
    for section in &["dependencies", "dev-dependencies", "build-dependencies"] {
        if let Some(table) = doc.get_mut(section).and_then(|v| v.as_table_like_mut()) {
            if let Some(dep) = table.get_mut(package) {
                rewrite_dep_value(dep, target_version);
                found = true;
                break;
            }
        }
    }

    if !found {
        return Err(format!("Package '{package}' not found in Cargo.toml"));
    }

    std::fs::write(manifest_path, doc.to_string()).map_err(|e| e.to_string())
}

/// Rewrite the version in a dependency value (handles both string and table forms).
fn rewrite_dep_value(item: &mut toml_edit::Item, target_version: &str) {
    match item {
        toml_edit::Item::Value(toml_edit::Value::String(s)) => {
            // Simple string: serde = "1.0" -> serde = "=1.0.200"
            *s = toml_edit::Formatted::new(format!("={target_version}"));
        }
        toml_edit::Item::Value(toml_edit::Value::InlineTable(t)) => {
            if let Some(v) = t.get_mut("version") {
                *v = toml_edit::Value::String(toml_edit::Formatted::new(format!("={target_version}")));
            }
        }
        toml_edit::Item::Table(t) => {
            t.insert("version", toml_edit::value(format!("={target_version}")));
        }
        _ => {}
    }
}

/// Check if cargo metadata JSON output contains the target version of a package.
fn version_in_metadata(stdout: &str, package: &str, target_version: &str) -> bool {
    // Parse the metadata JSON and look for the package in the resolve section
    let metadata: serde_json::Value = match serde_json::from_str(stdout) {
        Ok(v) => v,
        Err(_) => return false,
    };

    // Check in packages array
    if let Some(packages) = metadata.get("packages").and_then(|v| v.as_array()) {
        for pkg in packages {
            let name = pkg.get("name").and_then(|v| v.as_str()).unwrap_or("");
            let version = pkg.get("version").and_then(|v| v.as_str()).unwrap_or("");
            if name == package && version == target_version {
                return true;
            }
        }
    }

    false
}

/// Parse cargo error output for conflict chain information.
fn parse_cargo_conflict(stderr: &str) -> Option<ConflictChain> {
    if stderr.is_empty() {
        return None;
    }

    let mut links = Vec::new();

    // Pattern: "failed to select a version for the requirement `foo = \"^2.0\"`"
    let failed_re = Regex::new(r#"failed to select a version for the requirement `(\S+)\s*=\s*"([^"]+)"`"#).ok()?;
    for cap in failed_re.captures_iter(stderr) {
        links.push(ConflictLink {
            package: cap[1].to_string(),
            constraint: cap[2].to_string(),
            required_by: "(root)".into(),
        });
    }

    // Pattern: "required by package `foo v1.0.0`"
    let required_re = Regex::new(r"required by package `(\S+)\s+v(\S+)`").ok()?;
    for cap in required_re.captures_iter(stderr) {
        if let Some(last) = links.last_mut() {
            last.required_by = format!("{} v{}", &cap[1], &cap[2]);
        }
    }

    // Pattern: "which is depended on by `bar v1.0.0`"
    let depended_re = Regex::new(r"which is depended on by `(\S+)\s+v(\S+)`").ok()?;
    for cap in depended_re.captures_iter(stderr) {
        links.push(ConflictLink {
            package: cap[1].to_string(),
            constraint: String::new(),
            required_by: format!("{} v{}", &cap[1], &cap[2]),
        });
    }

    if links.is_empty() {
        return None;
    }

    let summary = first_error_line(stderr);
    Some(ConflictChain { summary, links })
}

/// Extract the first meaningful error line from cargo stderr.
fn first_error_line(stderr: &str) -> String {
    for line in stderr.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with("error") || trimmed.starts_with("failed") {
            return trimmed.chars().take(200).collect();
        }
    }
    stderr
        .lines()
        .next()
        .unwrap_or("Unknown error")
        .chars()
        .take(200)
        .collect()
}
