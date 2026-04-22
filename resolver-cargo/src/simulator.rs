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

/// Simulate a subset of safe updates applied together.
///
/// Copies the project to a temp directory, rewrites each update's version
/// in the manifest, then runs `cargo metadata` to test combined resolution.
/// Returns `true` if resolution succeeds. Dependencies not included in
/// `updates` are left at their currently pinned version.
pub fn simulate_combined(manifest_path: &Path, updates: &[SafeUpdate]) -> bool {
    let temp_dir = match setup_temp_workspace(manifest_path) {
        Ok(d) => d,
        Err(_) => return false,
    };
    let temp_manifest = temp_dir.path().join("Cargo.toml");

    for update in updates {
        if rewrite_dependency(&temp_manifest, &update.package, &update.to_version).is_err() {
            return false;
        }
    }

    let output = Command::new("cargo")
        .args([
            "metadata",
            "--format-version",
            "1",
            "--manifest-path",
            &temp_manifest.to_string_lossy(),
        ])
        .output();

    match output {
        Ok(o) => o.status.success(),
        Err(_) => false,
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
///
/// Cargo error structure (real example):
///   error: failed to select a version for `serde`.
///       ... required by package `serde_json v1.0.149`
///       ... which satisfies dependency `serde_json = "=1.0.149"` of package `root v0.1.0`
///   versions that meet the requirements `^1.0.220` are: 1.0.228, 1.0.227, ...
///   all possible versions conflict with previously selected packages.
///     previously selected package `serde v1.0.150`
///       ... which satisfies dependency `serde = "=1.0.150"` of package `root v0.1.0`
///   failed to select a version for `serde` which could resolve this conflict
fn parse_cargo_conflict(stderr: &str) -> Option<ConflictChain> {
    if stderr.is_empty() {
        return None;
    }

    let mut links = Vec::new();

    // Pattern 1: Top-level "failed to select a version for `pkg`"
    let failed_re = Regex::new(r#"failed to select a version for `([^`]+)`"#).ok()?;
    let conflicted_pkg = failed_re
        .captures(stderr)
        .map(|cap| cap[1].to_string());

    // Pattern 2: "required by package `name vX.Y.Z`"
    let required_re = Regex::new(r"required by package `([^`\s]+)\s+v([^`]+)`").ok()?;

    // Pattern 3: "satisfies dependency `name = \"...\"` of package `parent vX.Y.Z`"
    let satisfies_re = Regex::new(
        r#"satisfies dependency `([^`\s]+)\s*=\s*"([^"]+)"` of package `([^`\s]+)\s+v([^`]+)`"#,
    ).ok()?;

    // Pattern 4: "previously selected package `name vX.Y.Z`"
    let previously_re = Regex::new(r"previously selected package `([^`\s]+)\s+v([^`]+)`").ok()?;

    // Pattern 5: "versions that meet the requirements `...` are: ..."
    let versions_re = Regex::new(r#"versions that meet the requirements `([^`]+)` are:\s*([^\n]+)"#).ok()?;

    // Build links from all matched satisfies entries — each shows a requirement chain
    for cap in satisfies_re.captures_iter(stderr) {
        let pkg = &cap[1];
        let constraint = &cap[2];
        let parent = &cap[3];
        let parent_ver = &cap[4];
        links.push(ConflictLink {
            package: pkg.to_string(),
            constraint: constraint.to_string(),
            required_by: format!("{parent} v{parent_ver}"),
        });
    }

    // Add "previously selected" entries as additional context
    for cap in previously_re.captures_iter(stderr) {
        let pkg = &cap[1];
        let ver = &cap[2];
        links.push(ConflictLink {
            package: pkg.to_string(),
            constraint: format!("currently {ver}"),
            required_by: "(previously selected)".into(),
        });
    }

    // Fallback: if no satisfies but we have "required by package", add those
    if links.is_empty() {
        for cap in required_re.captures_iter(stderr) {
            let pkg = &cap[1];
            let ver = &cap[2];
            links.push(ConflictLink {
                package: pkg.to_string(),
                constraint: String::new(),
                required_by: format!("{pkg} v{ver}"),
            });
        }
    }

    // Add "versions that meet the requirements" as a synthetic link
    if let Some(cap) = versions_re.captures(stderr) {
        let req = &cap[1];
        let available = cap[2].trim();
        let pkg = conflicted_pkg.clone().unwrap_or_else(|| "(unknown)".to_string());
        links.push(ConflictLink {
            package: pkg,
            constraint: req.to_string(),
            required_by: format!("available: {available}"),
        });
    }

    if links.is_empty() {
        return None;
    }

    let summary = if let Some(pkg) = conflicted_pkg {
        format!("Cannot select a version for `{pkg}` — incompatible with currently selected packages")
    } else {
        first_error_line(stderr)
    };
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
