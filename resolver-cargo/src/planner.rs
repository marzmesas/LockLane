//! Plan composition: candidate enumeration, batch simulation, compatibility check.

use std::path::Path;

use crate::cargo_parser;
use crate::crates_io;
use crate::models::*;
use crate::simulator;

/// Compose an upgrade plan for all dependencies with upgradable versions.
pub fn compose_upgrade_plan(
    manifest_path: &Path,
    exclude_newer: Option<&str>,
) -> PlanResponse {
    let deps = match cargo_parser::parse_cargo_toml(manifest_path) {
        Ok(d) => d,
        Err(e) => {
            return PlanResponse {
                schema_version: SCHEMA_VERSION.into(),
                timestamp_utc: now_utc_iso(),
                status: "error".into(),
                manifest_path: manifest_path.display().to_string(),
                resolver: "cargo".into(),
                safe_updates: vec![],
                blocked_updates: vec![],
                inconclusive_updates: vec![],
                ordered_steps: vec![],
                error: Some(e),
            };
        }
    };

    let mut safe_updates: Vec<SafeUpdate> = Vec::new();
    let mut blocked_updates: Vec<BlockedUpdate> = Vec::new();
    let mut inconclusive_updates: Vec<InconclusiveUpdate> = Vec::new();

    // Read the full Cargo.toml to check which deps are registry deps
    let content = std::fs::read_to_string(manifest_path).unwrap_or_default();
    let doc: toml::Value = content.parse().unwrap_or(toml::Value::Table(Default::default()));

    for dep in deps.iter() {
        let current_version = match cargo_parser::extract_pinned_version(&dep.specifier) {
            Some(v) => v,
            None => continue,
        };

        // Check if this is a registry dep (not path/git)
        let is_registry = is_dep_registry(&doc, &dep.name);
        if !is_registry {
            continue;
        }

        // Enumerate candidates from crates.io
        let candidates = match crates_io::enumerate_upgrade_candidates(
            &dep.name,
            &current_version,
            exclude_newer,
        ) {
            Ok(c) => c,
            Err(e) => {
                inconclusive_updates.push(InconclusiveUpdate {
                    package: dep.name.clone(),
                    target_version: current_version,
                    reason: format!("Failed to fetch versions from crates.io: {e}"),
                });
                continue;
            }
        };

        // Try major -> minor -> patch (highest version in each level)
        let mut targets = Vec::new();
        if let Some(v) = candidates.major.last() {
            targets.push(v.clone());
        }
        if let Some(v) = candidates.minor.last() {
            targets.push(v.clone());
        }
        if let Some(v) = candidates.patch.last() {
            targets.push(v.clone());
        }

        if targets.is_empty() {
            continue;
        }

        let mut best_safe: Option<SafeUpdate> = None;
        let mut last_blocked: Option<BlockedUpdate> = None;

        for target in &targets {
            let sim = simulator::simulate_candidate(manifest_path, &dep.name, target);

            match sim.classification {
                "SAFE_NOW" => {
                    best_safe = Some(SafeUpdate {
                        package: dep.name.clone(),
                        from_version: current_version.clone(),
                        to_version: target.clone(),
                    });
                    break;
                }
                "BLOCKED" => {
                    last_blocked = Some(BlockedUpdate {
                        package: dep.name.clone(),
                        target_version: target.clone(),
                        reason: sim.explanation,
                        conflict_chain: sim.conflict_chain,
                    });
                    // Continue trying lower bump levels
                }
                _ => {
                    inconclusive_updates.push(InconclusiveUpdate {
                        package: dep.name.clone(),
                        target_version: target.clone(),
                        reason: sim.explanation,
                    });
                    break;
                }
            }
        }

        if let Some(safe) = best_safe {
            safe_updates.push(safe);
        } else if let Some(blocked) = last_blocked {
            blocked_updates.push(blocked);
        }
    }

    // Build ordered steps
    let ordered_steps = if safe_updates.len() >= 2 {
        // For now, suggest applying all at once (combined check can be added later)
        let descriptions: Vec<String> = safe_updates
            .iter()
            .map(|u| format!("{} {}→{}", u.package, u.from_version, u.to_version))
            .collect();
        vec![OrderedStep {
            step: 1,
            description: format!(
                "Apply all {} safe updates: {}",
                safe_updates.len(),
                descriptions.join(", ")
            ),
        }]
    } else if safe_updates.len() == 1 {
        vec![OrderedStep {
            step: 1,
            description: format!(
                "Update {} from {} to {}",
                safe_updates[0].package, safe_updates[0].from_version, safe_updates[0].to_version
            ),
        }]
    } else {
        vec![]
    };

    PlanResponse {
        schema_version: SCHEMA_VERSION.into(),
        timestamp_utc: now_utc_iso(),
        status: "ok".into(),
        manifest_path: manifest_path.display().to_string(),
        resolver: "cargo".into(),
        safe_updates,
        blocked_updates,
        inconclusive_updates,
        ordered_steps,
        error: None,
    }
}

/// Check if a dependency is a registry dep in the parsed TOML document.
fn is_dep_registry(doc: &toml::Value, name: &str) -> bool {
    for section in &["dependencies", "dev-dependencies", "build-dependencies"] {
        if let Some(table) = doc.get(section).and_then(|v| v.as_table()) {
            if let Some(value) = table.get(name) {
                return cargo_parser::is_registry_dep(value);
            }
        }
    }
    // Default to true if we can't determine
    true
}
