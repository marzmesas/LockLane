//! Apply engine: patch preview, rollback artifact, manifest writing.
//!
//! Rewrites Cargo.toml with upgraded dependency versions using toml_edit
//! to preserve comments and formatting.

use std::path::Path;

use crate::models::*;

/// Apply an upgrade plan to a Cargo.toml manifest.
///
/// If `dry_run` is true, produces a patch preview without writing.
/// Returns an `ApplyData` with the patch diff, updates applied, and rollback artifact.
pub fn apply_plan(
    manifest_path: &Path,
    plan_data: &serde_json::Value,
    output_path: Option<&Path>,
    dry_run: bool,
) -> Result<ApplyData, String> {
    let original_content =
        std::fs::read_to_string(manifest_path).map_err(|e| format!("Failed to read manifest: {e}"))?;

    let safe_updates = extract_safe_updates(plan_data)?;
    if safe_updates.is_empty() {
        return Ok(ApplyData {
            applied: false,
            manifest_path: manifest_path.display().to_string(),
            output_path: output_path.map(|p| p.display().to_string()),
            patch_preview: String::new(),
            updates_applied: vec![],
            rollback: None,
        });
    }

    // Apply all updates to the document
    let mut doc: toml_edit::DocumentMut = original_content
        .parse()
        .map_err(|e: toml_edit::TomlError| format!("Failed to parse Cargo.toml: {e}"))?;

    for update in &safe_updates {
        rewrite_dep(&mut doc, &update.package, &update.to_version)?;
    }

    let new_content = doc.to_string();

    // Build unified diff as patch preview
    let patch_preview = build_diff(&original_content, &new_content, manifest_path);

    // Build rollback artifact
    let reverse_updates: Vec<SafeUpdate> = safe_updates
        .iter()
        .map(|u| SafeUpdate {
            package: u.package.clone(),
            from_version: u.to_version.clone(),
            to_version: u.from_version.clone(),
        })
        .collect();

    let rollback = RollbackArtifact {
        schema_version: SCHEMA_VERSION.into(),
        created_utc: now_utc_iso(),
        manifest_path: manifest_path.display().to_string(),
        original_content: original_content.clone(),
        reverse_updates,
    };

    // Write the modified manifest (unless dry run)
    let applied = if dry_run {
        false
    } else {
        let dest = output_path.unwrap_or(manifest_path);
        std::fs::write(dest, &new_content)
            .map_err(|e| format!("Failed to write manifest: {e}"))?;
        true
    };

    Ok(ApplyData {
        applied,
        manifest_path: manifest_path.display().to_string(),
        output_path: output_path.map(|p| p.display().to_string()),
        patch_preview,
        updates_applied: safe_updates,
        rollback: Some(rollback),
    })
}

/// Extract safe_updates from the plan JSON.
fn extract_safe_updates(plan_data: &serde_json::Value) -> Result<Vec<SafeUpdate>, String> {
    let arr = plan_data
        .get("safe_updates")
        .and_then(|v| v.as_array())
        .ok_or("No safe_updates in plan JSON")?;

    let mut updates = Vec::new();
    for item in arr {
        let package = item
            .get("package")
            .and_then(|v| v.as_str())
            .ok_or("Missing package in safe_update")?;
        let from = item
            .get("from_version")
            .and_then(|v| v.as_str())
            .ok_or("Missing from_version")?;
        let to = item
            .get("to_version")
            .and_then(|v| v.as_str())
            .ok_or("Missing to_version")?;
        updates.push(SafeUpdate {
            package: package.into(),
            from_version: from.into(),
            to_version: to.into(),
        });
    }
    Ok(updates)
}

/// Rewrite a dependency version in the document.
fn rewrite_dep(
    doc: &mut toml_edit::DocumentMut,
    package: &str,
    target_version: &str,
) -> Result<(), String> {
    for section in &["dependencies", "dev-dependencies", "build-dependencies"] {
        if let Some(table) = doc.get_mut(section).and_then(|v| v.as_table_like_mut()) {
            if let Some(dep) = table.get_mut(package) {
                rewrite_dep_value(dep, target_version);
                return Ok(());
            }
        }
    }
    Err(format!("Package '{package}' not found in Cargo.toml"))
}

/// Rewrite the version in a dependency value (handles string and table forms).
fn rewrite_dep_value(item: &mut toml_edit::Item, target_version: &str) {
    match item {
        toml_edit::Item::Value(toml_edit::Value::String(s)) => {
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

/// Build a simple unified diff between two strings.
fn build_diff(old: &str, new: &str, path: &Path) -> String {
    let old_lines: Vec<&str> = old.lines().collect();
    let new_lines: Vec<&str> = new.lines().collect();
    let filename = path.display().to_string();

    let mut diff = format!("--- a/{filename}\n+++ b/{filename}\n");

    let max = old_lines.len().max(new_lines.len());
    for i in 0..max {
        let old_line = old_lines.get(i).copied().unwrap_or("");
        let new_line = new_lines.get(i).copied().unwrap_or("");
        if old_line != new_line {
            diff.push_str(&format!("-{old_line}\n"));
            diff.push_str(&format!("+{new_line}\n"));
        }
    }

    diff
}
