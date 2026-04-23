//! Parse Cargo.toml into a list of ParsedDependency.
//!
//! Handles `[dependencies]`, `[dev-dependencies]`, and `[build-dependencies]`
//! sections. Supports both shorthand (`serde = "1.0"`) and table
//! (`serde = { version = "1.0", features = ["derive"] }`) formats.

use std::path::Path;

use crate::cargo_lock::parse_cargo_lock;
use crate::models::ParsedDependency;

/// Parse all dependencies from a Cargo.toml file.
///
/// When a sibling `Cargo.lock` is present, each dependency is annotated
/// with `locked_version` so callers can recover the current version for
/// range specifiers like `serde = "1"` (which Cargo resolves to e.g.
/// `1.0.210` in the lockfile).
pub fn parse_cargo_toml(manifest_path: &Path) -> Result<Vec<ParsedDependency>, String> {
    let content = std::fs::read_to_string(manifest_path)
        .map_err(|e| format!("Failed to read {}: {}", manifest_path.display(), e))?;

    let doc: toml::Value = content
        .parse()
        .map_err(|e| format!("Failed to parse TOML: {e}"))?;

    let lines: Vec<&str> = content.lines().collect();
    let locks = manifest_path
        .parent()
        .map(|p| parse_cargo_lock(&p.join("Cargo.lock")))
        .unwrap_or_default();
    let mut deps = Vec::new();

    for section in &["dependencies", "dev-dependencies", "build-dependencies"] {
        if let Some(table) = doc.get(section).and_then(|v| v.as_table()) {
            for (name, value) in table {
                let (specifier, raw_line, line_number) =
                    extract_dep_info(name, value, section, &lines);
                deps.push(ParsedDependency {
                    name: name.clone(),
                    specifier,
                    raw_line,
                    line_number,
                    locked_version: locks.get(name).cloned(),
                });
            }
        }
    }

    // Also check workspace dependencies
    if let Some(ws) = doc
        .get("workspace")
        .and_then(|v| v.get("dependencies"))
        .and_then(|v| v.as_table())
    {
        for (name, value) in ws {
            let (specifier, raw_line, line_number) =
                extract_dep_info(name, value, "workspace.dependencies", &lines);
            deps.push(ParsedDependency {
                name: name.clone(),
                specifier,
                raw_line,
                line_number,
                locked_version: locks.get(name).cloned(),
            });
        }
    }

    Ok(deps)
}

/// Extract version specifier from a dependency value.
///
/// Returns the Cargo version requirement string (e.g. "^1.0", "=1.0.200")
/// or an empty string for path/git dependencies.
pub fn extract_version_req(value: &toml::Value) -> String {
    match value {
        toml::Value::String(s) => s.clone(),
        toml::Value::Table(t) => t
            .get("version")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        _ => String::new(),
    }
}

/// Check if a dependency is a registry dependency (has a version, not just path/git).
pub fn is_registry_dep(value: &toml::Value) -> bool {
    match value {
        toml::Value::String(_) => true,
        toml::Value::Table(t) => {
            t.contains_key("version") && !t.contains_key("path") && !t.contains_key("git")
        }
        _ => false,
    }
}

fn extract_dep_info(
    name: &str,
    value: &toml::Value,
    section: &str,
    lines: &[&str],
) -> (String, String, usize) {
    let specifier = extract_version_req(value);
    let line_number = find_dep_line(name, section, lines).unwrap_or(0);
    let raw_line = if line_number > 0 && line_number <= lines.len() {
        lines[line_number - 1].to_string()
    } else {
        format!("{name} = {value}")
    };
    (specifier, raw_line, line_number)
}

/// Find the line number (1-based) of a dependency declaration in a section.
fn find_dep_line(name: &str, section: &str, lines: &[&str]) -> Option<usize> {
    let section_headers: Vec<String> = if section.contains('.') {
        // workspace.dependencies -> [workspace.dependencies]
        vec![format!("[{section}]")]
    } else {
        vec![format!("[{section}]")]
    };

    let mut in_section = false;
    for (i, line) in lines.iter().enumerate() {
        let trimmed = line.trim();
        if trimmed.starts_with('[') {
            let header = trimmed
                .trim_start_matches('[')
                .trim_end_matches(']')
                .trim();
            in_section = section_headers
                .iter()
                .any(|h| h.trim_start_matches('[').trim_end_matches(']') == header);
            continue;
        }
        if in_section && trimmed.starts_with(name) {
            // Check it's actually this dep (not a prefix match)
            let rest = trimmed[name.len()..].trim_start();
            if rest.starts_with('=') || rest.starts_with('.') || rest.is_empty() {
                if rest.starts_with('=') {
                    return Some(i + 1);
                }
            }
        }
    }
    None
}

/// Render a new version string for Cargo, preserving the user's operator.
///
/// Cargo accepts several forms: `"1.0"` (implicit caret), `"^1.0"`,
/// `"~1.0"`, `"=1.0.200"`, and compound ranges like `">=1.0, <2.0"`. On
/// the apply path we keep whichever style the user chose so accepting an
/// update doesn't silently flatten `serde = "1"` into `serde = "=1.0.250"`.
/// Compound ranges and wildcards fall back to a bare version — the
/// original expression is too loose to faithfully preserve.
pub fn preserve_cargo_operator(old: &str, new_version: &str) -> String {
    let trimmed = old.trim();
    if trimmed.is_empty() || trimmed == "*" || trimmed.contains(',') {
        return new_version.to_string();
    }
    if trimmed.starts_with('=') {
        return format!("={new_version}");
    }
    if trimmed.starts_with('^') {
        return format!("^{new_version}");
    }
    if trimmed.starts_with('~') {
        return format!("~{new_version}");
    }
    if trimmed.starts_with(">=")
        || trimmed.starts_with("<=")
        || trimmed.starts_with('>')
        || trimmed.starts_with('<')
    {
        return new_version.to_string();
    }
    // Bare: "1", "1.0", "1.0.200" — keep bare (caret is implicit in Cargo).
    new_version.to_string()
}

/// Rewrite a dependency's version in place.
///
/// Handles the three toml_edit shapes: simple string value, inline table,
/// and full table. When `force_pin` is true the written version is always
/// `=X.Y.Z` (used by the simulator to force the resolver onto the exact
/// candidate under test). When false the user's operator is preserved via
/// `preserve_cargo_operator` (used by the applier).
pub fn rewrite_dep_value(
    item: &mut toml_edit::Item,
    target_version: &str,
    force_pin: bool,
) {
    match item {
        toml_edit::Item::Value(toml_edit::Value::String(s)) => {
            let new = if force_pin {
                format!("={target_version}")
            } else {
                preserve_cargo_operator(s.value(), target_version)
            };
            *s = toml_edit::Formatted::new(new);
        }
        toml_edit::Item::Value(toml_edit::Value::InlineTable(t)) => {
            if let Some(v) = t.get_mut("version") {
                let old = v.as_str().unwrap_or("").to_string();
                let new = if force_pin {
                    format!("={target_version}")
                } else {
                    preserve_cargo_operator(&old, target_version)
                };
                *v = toml_edit::Value::String(toml_edit::Formatted::new(new));
            }
        }
        toml_edit::Item::Table(t) => {
            let old = t
                .get("version")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            let new = if force_pin {
                format!("={target_version}")
            } else {
                preserve_cargo_operator(&old, target_version)
            };
            t.insert("version", toml_edit::value(new));
        }
        _ => {}
    }
}

/// Extract the pinned version from a Cargo version specifier.
///
/// Returns Some(version) for exact pins (`=X.Y.Z`) or simple semver
/// strings that we can extract a base version from.
pub fn extract_pinned_version(specifier: &str) -> Option<String> {
    let s = specifier.trim();
    if s.is_empty() {
        return None;
    }

    // Exact pin: =1.0.200
    if let Some(v) = s.strip_prefix('=') {
        let v = v.trim();
        if semver::Version::parse(v).is_ok() {
            return Some(v.to_string());
        }
    }

    // Caret (default): ^1.0.200 or just 1.0.200
    let v = s
        .strip_prefix('^')
        .or_else(|| s.strip_prefix('~'))
        .unwrap_or(s)
        .trim();
    if semver::Version::parse(v).is_ok() {
        return Some(v.to_string());
    }

    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_extract_version_req_string() {
        let val = toml::Value::String("1.0".into());
        assert_eq!(extract_version_req(&val), "1.0");
    }

    #[test]
    fn test_extract_version_req_table() {
        let doc: toml::Value =
            toml::from_str(r#"dep = { version = "1.0", features = ["derive"] }"#).unwrap();
        let val = doc.get("dep").unwrap();
        assert_eq!(extract_version_req(val), "1.0");
    }

    #[test]
    fn test_extract_pinned_version() {
        assert_eq!(extract_pinned_version("=1.0.200"), Some("1.0.200".into()));
        assert_eq!(extract_pinned_version("1.0.200"), Some("1.0.200".into()));
        assert_eq!(extract_pinned_version("^1.0.200"), Some("1.0.200".into()));
        assert_eq!(extract_pinned_version("~1.0.200"), Some("1.0.200".into()));
        assert_eq!(extract_pinned_version(">=1.0, <2.0"), None);
        assert_eq!(extract_pinned_version("*"), None);
        assert_eq!(extract_pinned_version(""), None);
    }

    #[test]
    fn test_preserve_cargo_operator() {
        // Caret (explicit and implicit).
        assert_eq!(preserve_cargo_operator("^1.0", "1.0.250"), "^1.0.250");
        assert_eq!(preserve_cargo_operator("1.0", "1.0.250"), "1.0.250");
        assert_eq!(preserve_cargo_operator("1", "1.0.250"), "1.0.250");

        // Tilde.
        assert_eq!(preserve_cargo_operator("~1.0", "1.0.250"), "~1.0.250");
        assert_eq!(preserve_cargo_operator("~1.0.200", "1.0.250"), "~1.0.250");

        // Exact pin.
        assert_eq!(preserve_cargo_operator("=1.0.200", "1.0.250"), "=1.0.250");

        // Compound range and wildcard fall back to bare.
        assert_eq!(preserve_cargo_operator(">=1.0, <2.0", "1.5.0"), "1.5.0");
        assert_eq!(preserve_cargo_operator("*", "1.5.0"), "1.5.0");

        // Single-sided comparators also fall back to bare.
        assert_eq!(preserve_cargo_operator(">=1.0", "1.5.0"), "1.5.0");

        // Whitespace is tolerated.
        assert_eq!(preserve_cargo_operator("  ^1.0  ", "1.0.250"), "^1.0.250");
    }

    #[test]
    fn test_rewrite_dep_value_preserves_operator() {
        let mut doc: toml_edit::DocumentMut = r#"
[dependencies]
serde = "1.0"
tokio = "~1.0"
anyhow = "=1.0.75"
"#
        .parse()
        .unwrap();

        for pkg in ["serde", "tokio", "anyhow"] {
            let item = doc["dependencies"].get_mut(pkg).unwrap();
            rewrite_dep_value(item, "1.0.250", /*force_pin=*/ false);
        }

        let out = doc.to_string();
        assert!(out.contains("serde = \"1.0.250\""), "got: {out}");
        assert!(out.contains("tokio = \"~1.0.250\""), "got: {out}");
        assert!(out.contains("anyhow = \"=1.0.250\""), "got: {out}");
    }

    #[test]
    fn test_rewrite_dep_value_force_pin_overrides_operator() {
        let mut doc: toml_edit::DocumentMut = r#"
[dependencies]
serde = "^1.0"
tokio = { version = "~1.0", features = ["macros"] }
"#
        .parse()
        .unwrap();

        let serde_item = doc["dependencies"].get_mut("serde").unwrap();
        rewrite_dep_value(serde_item, "1.0.250", /*force_pin=*/ true);
        let tokio_item = doc["dependencies"].get_mut("tokio").unwrap();
        rewrite_dep_value(tokio_item, "1.40.0", /*force_pin=*/ true);

        let out = doc.to_string();
        assert!(out.contains("serde = \"=1.0.250\""), "got: {out}");
        assert!(out.contains("version = \"=1.40.0\""), "got: {out}");
        // Features preserved in inline table.
        assert!(out.contains("\"macros\""), "got: {out}");
    }

    #[test]
    fn test_rewrite_dep_value_inline_table_preserves_operator() {
        let mut doc: toml_edit::DocumentMut = r#"
[dependencies]
tokio = { version = "~1.0", features = ["macros"] }
"#
        .parse()
        .unwrap();

        let item = doc["dependencies"].get_mut("tokio").unwrap();
        rewrite_dep_value(item, "1.40.0", /*force_pin=*/ false);

        let out = doc.to_string();
        assert!(out.contains("version = \"~1.40.0\""), "got: {out}");
        assert!(out.contains("\"macros\""), "got: {out}");
    }

    #[test]
    fn test_is_registry_dep() {
        assert!(is_registry_dep(&toml::Value::String("1.0".into())));
        let doc: toml::Value = toml::from_str(r#"dep = { version = "1.0" }"#).unwrap();
        assert!(is_registry_dep(doc.get("dep").unwrap()));
        let doc: toml::Value = toml::from_str(r#"dep = { path = "../foo" }"#).unwrap();
        assert!(!is_registry_dep(doc.get("dep").unwrap()));
        let doc: toml::Value = toml::from_str(r#"dep = { git = "https://example.com" }"#).unwrap();
        assert!(!is_registry_dep(doc.get("dep").unwrap()));
    }
}
