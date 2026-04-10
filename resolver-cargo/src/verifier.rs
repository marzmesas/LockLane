//! Verification engine: apply safe updates in a temp workspace, then run
//! `cargo check` (or a custom command) to verify the upgrade compiles.

use std::path::Path;
use std::process::Command;
use std::time::Instant;

use tempfile::TempDir;

use crate::models::*;

/// Verify an upgrade plan by applying it in a temporary workspace and running
/// `cargo check` (or a custom verification command).
pub fn verify_plan(
    manifest_path: &Path,
    plan_data: &serde_json::Value,
    command: Option<&str>,
) -> VerifyResponse {
    let manifest_path = manifest_path
        .canonicalize()
        .unwrap_or_else(|_| manifest_path.to_path_buf());

    // Set up temp workspace
    let temp_dir = match setup_verify_workspace(&manifest_path) {
        Ok(d) => d,
        Err(e) => {
            return VerifyResponse {
                schema_version: SCHEMA_VERSION.into(),
                timestamp_utc: now_utc_iso(),
                status: "error".into(),
                manifest_path: manifest_path.display().to_string(),
                plan_path: String::new(),
                resolver: "cargo".into(),
                verification: Some(Verification {
                    passed: false,
                    steps: vec![],
                    summary: format!("Failed to set up verification workspace: {e}"),
                    venv_path: String::new(),
                    modified_manifest_path: String::new(),
                }),
            };
        }
    };

    let temp_manifest = temp_dir.path().join("Cargo.toml");

    // Apply safe updates to the temp manifest
    let safe_updates = plan_data
        .get("safe_updates")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();

    let mut apply_doc: toml_edit::DocumentMut = match std::fs::read_to_string(&temp_manifest)
        .and_then(|s| Ok(s.parse::<toml_edit::DocumentMut>().map_err(|e| std::io::Error::other(e.to_string()))?))
    {
        Ok(d) => d,
        Err(e) => {
            return error_response(&manifest_path, &format!("Failed to parse temp manifest: {e}"));
        }
    };

    for update in &safe_updates {
        let pkg = update.get("package").and_then(|v| v.as_str()).unwrap_or("");
        let to_ver = update.get("to_version").and_then(|v| v.as_str()).unwrap_or("");
        if !pkg.is_empty() && !to_ver.is_empty() {
            apply_version(&mut apply_doc, pkg, to_ver);
        }
    }

    if let Err(e) = std::fs::write(&temp_manifest, apply_doc.to_string()) {
        return error_response(&manifest_path, &format!("Failed to write temp manifest: {e}"));
    }

    // Run verification steps
    let mut steps = Vec::new();

    // Step 1: cargo check (or custom command)
    let verify_cmd = command.unwrap_or("cargo check");
    let parts: Vec<&str> = verify_cmd.split_whitespace().collect();
    if parts.is_empty() {
        return error_response(&manifest_path, "Empty verification command");
    }

    let start = Instant::now();
    let output = Command::new(parts[0])
        .args(&parts[1..])
        .arg("--manifest-path")
        .arg(&temp_manifest)
        .current_dir(temp_dir.path())
        .output();
    let duration = start.elapsed().as_secs_f64();

    let step = match output {
        Ok(o) => {
            VerificationStep {
                name: "Verification".into(),
                command: verify_cmd.into(),
                passed: o.status.success(),
                exit_code: o.status.code().unwrap_or(-1),
                stdout: String::from_utf8_lossy(&o.stdout).to_string(),
                stderr: String::from_utf8_lossy(&o.stderr).to_string(),
                duration_seconds: duration,
            }
        }
        Err(e) => {
            VerificationStep {
                name: "Verification".into(),
                command: verify_cmd.into(),
                passed: false,
                exit_code: -1,
                stdout: String::new(),
                stderr: format!("Failed to execute: {e}"),
                duration_seconds: duration,
            }
        }
    };

    let passed = step.passed;
    steps.push(step);

    let summary = if passed {
        format!(
            "Verification passed: {} update(s) verified with '{verify_cmd}'",
            safe_updates.len()
        )
    } else {
        format!("Verification failed: '{verify_cmd}' returned non-zero exit code")
    };

    VerifyResponse {
        schema_version: SCHEMA_VERSION.into(),
        timestamp_utc: now_utc_iso(),
        status: if passed { "ok" } else { "error" }.into(),
        manifest_path: manifest_path.display().to_string(),
        plan_path: String::new(),
        resolver: "cargo".into(),
        verification: Some(Verification {
            passed,
            steps,
            summary,
            venv_path: temp_dir.path().display().to_string(),
            modified_manifest_path: temp_manifest.display().to_string(),
        }),
    }
}

/// Set up a temporary workspace by copying the project structure needed for cargo.
fn setup_verify_workspace(manifest_path: &Path) -> Result<TempDir, String> {
    let temp_dir = TempDir::new().map_err(|e| e.to_string())?;
    let project_dir = manifest_path.parent().ok_or("No parent directory")?;

    // Copy Cargo.toml
    std::fs::copy(manifest_path, temp_dir.path().join("Cargo.toml"))
        .map_err(|e| format!("Copy Cargo.toml: {e}"))?;

    // Copy Cargo.lock if present
    let lock_path = project_dir.join("Cargo.lock");
    if lock_path.is_file() {
        std::fs::copy(&lock_path, temp_dir.path().join("Cargo.lock"))
            .map_err(|e| format!("Copy Cargo.lock: {e}"))?;
    }

    // Copy src/ directory
    let src_dir = project_dir.join("src");
    let temp_src = temp_dir.path().join("src");
    if src_dir.is_dir() {
        copy_dir_recursive(&src_dir, &temp_src)?;
    } else {
        std::fs::create_dir_all(&temp_src).map_err(|e| e.to_string())?;
        std::fs::write(temp_src.join("lib.rs"), "// placeholder\n").map_err(|e| e.to_string())?;
    }

    // Copy build.rs if present
    let build_rs = project_dir.join("build.rs");
    if build_rs.is_file() {
        std::fs::copy(&build_rs, temp_dir.path().join("build.rs"))
            .map_err(|e| format!("Copy build.rs: {e}"))?;
    }

    Ok(temp_dir)
}

/// Recursively copy a directory.
fn copy_dir_recursive(src: &Path, dst: &Path) -> Result<(), String> {
    std::fs::create_dir_all(dst).map_err(|e| e.to_string())?;
    for entry in std::fs::read_dir(src).map_err(|e| e.to_string())? {
        let entry = entry.map_err(|e| e.to_string())?;
        let ty = entry.file_type().map_err(|e| e.to_string())?;
        let dest_path = dst.join(entry.file_name());
        if ty.is_dir() {
            copy_dir_recursive(&entry.path(), &dest_path)?;
        } else {
            std::fs::copy(entry.path(), &dest_path).map_err(|e| e.to_string())?;
        }
    }
    Ok(())
}

/// Rewrite a dependency version in the document.
fn apply_version(doc: &mut toml_edit::DocumentMut, package: &str, version: &str) {
    for section in &["dependencies", "dev-dependencies", "build-dependencies"] {
        if let Some(table) = doc.get_mut(section).and_then(|v| v.as_table_like_mut()) {
            if let Some(dep) = table.get_mut(package) {
                match dep {
                    toml_edit::Item::Value(toml_edit::Value::String(s)) => {
                        *s = toml_edit::Formatted::new(format!("={version}"));
                    }
                    toml_edit::Item::Value(toml_edit::Value::InlineTable(t)) => {
                        if let Some(v) = t.get_mut("version") {
                            *v = toml_edit::Value::String(toml_edit::Formatted::new(
                                format!("={version}"),
                            ));
                        }
                    }
                    toml_edit::Item::Table(t) => {
                        t.insert("version", toml_edit::value(format!("={version}")));
                    }
                    _ => {}
                }
                return;
            }
        }
    }
}

fn error_response(manifest_path: &Path, msg: &str) -> VerifyResponse {
    VerifyResponse {
        schema_version: SCHEMA_VERSION.into(),
        timestamp_utc: now_utc_iso(),
        status: "error".into(),
        manifest_path: manifest_path.display().to_string(),
        plan_path: String::new(),
        resolver: "cargo".into(),
        verification: Some(Verification {
            passed: false,
            steps: vec![],
            summary: msg.into(),
            venv_path: String::new(),
            modified_manifest_path: String::new(),
        }),
    }
}
