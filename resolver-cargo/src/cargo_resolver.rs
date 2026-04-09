//! Shell out to `cargo metadata` for dependency resolution and graph building.

use std::collections::{HashMap, HashSet};
use std::path::Path;
use std::process::Command;

use serde::Deserialize;

use crate::models::{DependencyGraph, ResolvedPackage, ToolAvailability};

/// Run `cargo metadata` and build a DependencyGraph.
pub fn resolve(manifest_path: &Path) -> Result<DependencyGraph, String> {
    let cargo_version = detect_cargo_version()?;

    let output = Command::new("cargo")
        .args([
            "metadata",
            "--format-version",
            "1",
            "--manifest-path",
            &manifest_path.to_string_lossy(),
        ])
        .output()
        .map_err(|e| format!("Failed to run cargo metadata: {e}"))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!(
            "cargo metadata failed (exit {}):\n{}",
            output.status.code().unwrap_or(-1),
            stderr.lines().take(20).collect::<Vec<_>>().join("\n")
        ));
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let metadata: CargoMetadata =
        serde_json::from_str(&stdout).map_err(|e| format!("Failed to parse cargo metadata: {e}"))?;

    let graph = build_graph(&metadata, manifest_path)?;

    Ok(DependencyGraph {
        packages: graph,
        resolver_tool: "cargo".into(),
        resolver_version: cargo_version,
        python_version: String::new(),
    })
}

/// Detect cargo binary version.
pub fn detect_cargo_version() -> Result<String, String> {
    let output = Command::new("cargo")
        .arg("--version")
        .output()
        .map_err(|e| format!("Failed to run cargo --version: {e}"))?;

    Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
}

/// Check if cargo is available on PATH.
pub fn tooling_availability() -> HashMap<String, ToolAvailability> {
    let mut result = HashMap::new();
    let available = which("cargo");
    result.insert(
        "cargo".into(),
        ToolAvailability {
            available,
            binary: "cargo".into(),
        },
    );
    result
}

fn which(binary: &str) -> bool {
    Command::new("which")
        .arg(binary)
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

// --- cargo metadata JSON structures ---

#[derive(Deserialize)]
struct CargoMetadata {
    packages: Vec<MetadataPackage>,
    resolve: Option<MetadataResolve>,
    workspace_members: Vec<String>,
}

#[derive(Deserialize)]
struct MetadataPackage {
    id: String,
    name: String,
    version: String,
}

#[derive(Deserialize)]
struct MetadataResolve {
    nodes: Vec<MetadataNode>,
    root: Option<String>,
}

#[derive(Deserialize)]
struct MetadataNode {
    id: String,
    deps: Vec<MetadataDep>,
}

#[derive(Deserialize)]
struct MetadataDep {
    name: String,
    pkg: String,
}

fn build_graph(
    metadata: &CargoMetadata,
    manifest_path: &Path,
) -> Result<Vec<ResolvedPackage>, String> {
    let workspace_ids: HashSet<&str> = metadata
        .workspace_members
        .iter()
        .map(|s| s.as_str())
        .collect();

    // Build a map of package id -> (name, version)
    let pkg_map: HashMap<&str, (&str, &str)> = metadata
        .packages
        .iter()
        .map(|p| (p.id.as_str(), (p.name.as_str(), p.version.as_str())))
        .collect();

    // Find direct dependencies from workspace root's resolve node
    let resolve = metadata
        .resolve
        .as_ref()
        .ok_or("No resolve section in cargo metadata")?;

    // Find the root node (the workspace member that matches our manifest)
    let manifest_dir = manifest_path
        .parent()
        .map(|p| p.to_string_lossy().to_string())
        .unwrap_or_default();

    let root_id = resolve
        .root
        .as_deref()
        .or_else(|| {
            // If no root, find the workspace member whose path matches
            metadata
                .workspace_members
                .iter()
                .find(|id| id.contains(&manifest_dir))
                .map(|s| s.as_str())
        })
        .or_else(|| metadata.workspace_members.first().map(|s| s.as_str()));

    let direct_deps: HashSet<String> = if let Some(root) = root_id {
        resolve
            .nodes
            .iter()
            .find(|n| n.id == root)
            .map(|node| node.deps.iter().map(|d| d.name.clone()).collect())
            .unwrap_or_default()
    } else {
        HashSet::new()
    };

    // Build reverse dependency map (who requires whom)
    let mut required_by: HashMap<String, Vec<String>> = HashMap::new();
    for node in &resolve.nodes {
        if workspace_ids.contains(node.id.as_str()) {
            continue;
        }
        let parent_name = pkg_map
            .get(node.id.as_str())
            .map(|(n, _)| n.to_string())
            .unwrap_or_default();
        for dep in &node.deps {
            required_by
                .entry(dep.name.clone())
                .or_default()
                .push(parent_name.clone());
        }
    }

    let mut packages: Vec<ResolvedPackage> = Vec::new();
    for node in &resolve.nodes {
        // Skip workspace members themselves
        if workspace_ids.contains(node.id.as_str()) {
            continue;
        }
        if let Some(&(name, version)) = pkg_map.get(node.id.as_str()) {
            packages.push(ResolvedPackage {
                name: name.to_string(),
                version: version.to_string(),
                is_direct: direct_deps.contains(name),
                required_by: required_by
                    .get(name)
                    .cloned()
                    .unwrap_or_default(),
            });
        }
    }

    packages.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(packages)
}
