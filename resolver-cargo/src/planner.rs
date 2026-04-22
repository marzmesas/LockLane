//! Plan composition: candidate enumeration, batch simulation, compatibility check.

use std::collections::{BTreeMap, BTreeSet};
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
                        group_id: None,
                    });
                    break;
                }
                "BLOCKED" => {
                    last_blocked = Some(BlockedUpdate {
                        package: dep.name.clone(),
                        target_version: target.clone(),
                        reason: sim.explanation,
                        conflict_chain: sim.conflict_chain,
                        suggestion: None,
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
        } else if let Some(mut blocked) = last_blocked {
            // Try to find a fallback suggestion from lower versions
            blocked.suggestion = find_fallback(manifest_path, &dep.name, &candidates);
            blocked_updates.push(blocked);
        }
    }

    // Compute interdependency groups. Only meaningful when the full set
    // resolves — if it doesn't, users fall back to sequential steps anyway.
    if safe_updates.len() >= 2 && simulator::simulate_combined(manifest_path, &safe_updates) {
        let group_ids = compute_groups(manifest_path, &safe_updates);
        for update in safe_updates.iter_mut() {
            if let Some(gid) = group_ids.get(&update.package) {
                update.group_id = Some(gid.clone());
            }
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

/// Assign interdependency group IDs to safe updates.
///
/// For each safe update, probes whether it resolves on its own (with every
/// other dependency at its current pinned version). If not, greedy-adds peers
/// from `safe_updates` in alphabetical order until resolution succeeds — those
/// peers must move together.
///
/// The resulting (directed) peer relation is decomposed into strongly connected
/// components: two packages share a group only when they mutually require each
/// other (directly or transitively). Independent updates are omitted.
fn compute_groups(
    manifest_path: &Path,
    safe_updates: &[SafeUpdate],
) -> BTreeMap<String, String> {
    if safe_updates.len() < 2 {
        return BTreeMap::new();
    }

    let by_pkg: BTreeMap<String, SafeUpdate> = safe_updates
        .iter()
        .map(|u| (u.package.clone(), u.clone()))
        .collect();
    let nodes: Vec<String> = by_pkg.keys().cloned().collect();

    let mut requires: BTreeMap<String, BTreeSet<String>> = nodes
        .iter()
        .map(|n| (n.clone(), BTreeSet::new()))
        .collect();

    for pkg in &nodes {
        let solo = vec![by_pkg[pkg].clone()];
        if simulator::simulate_combined(manifest_path, &solo) {
            continue;
        }

        let mut current = solo;
        for peer in &nodes {
            if peer == pkg {
                continue;
            }
            current.push(by_pkg[peer].clone());
            requires.get_mut(pkg).unwrap().insert(peer.clone());
            if simulator::simulate_combined(manifest_path, &current) {
                break;
            }
        }
    }

    let sccs = tarjan_sccs(&requires, &nodes);

    let mut group_ids = BTreeMap::new();
    let mut counter = 0usize;
    for component in sccs {
        if component.len() < 2 {
            continue;
        }
        counter += 1;
        let gid = format!("g{counter}");
        for pkg in component {
            group_ids.insert(pkg, gid.clone());
        }
    }
    group_ids
}

/// Tarjan's SCC. Returns components sorted by their lowest-named member.
fn tarjan_sccs(
    graph: &BTreeMap<String, BTreeSet<String>>,
    nodes: &[String],
) -> Vec<Vec<String>> {
    let mut state = TarjanState::default();
    for v in nodes {
        if !state.indices.contains_key(v) {
            strongconnect(v, graph, &mut state);
        }
    }
    state.result.sort_by(|a, b| a[0].cmp(&b[0]));
    state.result
}

#[derive(Default)]
struct TarjanState {
    indices: BTreeMap<String, usize>,
    lowlink: BTreeMap<String, usize>,
    on_stack: BTreeSet<String>,
    stack: Vec<String>,
    counter: usize,
    result: Vec<Vec<String>>,
}

fn strongconnect(
    v: &str,
    graph: &BTreeMap<String, BTreeSet<String>>,
    state: &mut TarjanState,
) {
    state.indices.insert(v.to_string(), state.counter);
    state.lowlink.insert(v.to_string(), state.counter);
    state.counter += 1;
    state.stack.push(v.to_string());
    state.on_stack.insert(v.to_string());

    let neighbors: Vec<String> = graph
        .get(v)
        .map(|s| s.iter().cloned().collect())
        .unwrap_or_default();

    for w in neighbors {
        if !state.indices.contains_key(&w) {
            strongconnect(&w, graph, state);
            let w_low = state.lowlink[&w];
            let v_low = state.lowlink[v];
            state.lowlink.insert(v.to_string(), v_low.min(w_low));
        } else if state.on_stack.contains(&w) {
            let w_idx = state.indices[&w];
            let v_low = state.lowlink[v];
            state.lowlink.insert(v.to_string(), v_low.min(w_idx));
        }
    }

    if state.lowlink[v] == state.indices[v] {
        let mut component = Vec::new();
        loop {
            let w = state.stack.pop().unwrap();
            state.on_stack.remove(&w);
            let done = w == v;
            component.push(w);
            if done {
                break;
            }
        }
        component.sort();
        state.result.push(component);
    }
}

/// Try up to 3 lower versions to find one that resolves safely.
fn find_fallback(
    manifest_path: &Path,
    package: &str,
    candidates: &crates_io::UpgradeCandidates,
) -> Option<String> {
    let mut fallbacks = Vec::new();
    for level in [&candidates.major, &candidates.minor, &candidates.patch] {
        if level.len() > 1 {
            // Skip the last (highest) which was already tried
            for v in level[..level.len() - 1].iter().rev() {
                fallbacks.push(v.clone());
                if fallbacks.len() >= 3 {
                    break;
                }
            }
        }
        if fallbacks.len() >= 3 {
            break;
        }
    }

    for target in &fallbacks {
        let sim = simulator::simulate_candidate(manifest_path, package, target);
        if sim.classification == "SAFE_NOW" {
            return Some(target.clone());
        }
    }
    None
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

#[cfg(test)]
mod tests {
    use super::*;

    fn make_graph(edges: &[(&str, &[&str])]) -> BTreeMap<String, BTreeSet<String>> {
        let mut g: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
        for (node, _) in edges {
            g.entry((*node).into()).or_default();
        }
        for (node, targets) in edges {
            for t in *targets {
                g.entry((*t).into()).or_default();
            }
            let set = g.get_mut(*node).unwrap();
            for t in *targets {
                set.insert((*t).into());
            }
        }
        g
    }

    fn nodes_sorted(g: &BTreeMap<String, BTreeSet<String>>) -> Vec<String> {
        g.keys().cloned().collect()
    }

    #[test]
    fn scc_single_node_is_singleton() {
        let g = make_graph(&[("a", &[])]);
        let sccs = tarjan_sccs(&g, &nodes_sorted(&g));
        assert_eq!(sccs, vec![vec!["a".to_string()]]);
    }

    #[test]
    fn scc_mutual_pair_is_one_component() {
        let g = make_graph(&[("a", &["b"]), ("b", &["a"])]);
        let sccs = tarjan_sccs(&g, &nodes_sorted(&g));
        assert_eq!(sccs, vec![vec!["a".to_string(), "b".to_string()]]);
    }

    #[test]
    fn scc_one_way_edge_yields_two_singletons() {
        // a -> b but b does not require a: each is its own component.
        let g = make_graph(&[("a", &["b"]), ("b", &[])]);
        let sccs = tarjan_sccs(&g, &nodes_sorted(&g));
        assert_eq!(
            sccs,
            vec![vec!["a".to_string()], vec!["b".to_string()]]
        );
    }

    #[test]
    fn scc_disjoint_pairs_stay_separate() {
        // Greedy probe might have pulled {a,b} into c's requires; SCC must
        // still separate them because a does not reach c.
        let g = make_graph(&[
            ("a", &["b"]),
            ("b", &["a"]),
            ("c", &["a", "b", "d"]),
            ("d", &["a", "b", "c"]),
        ]);
        let sccs = tarjan_sccs(&g, &nodes_sorted(&g));
        assert!(sccs.contains(&vec!["a".to_string(), "b".to_string()]));
        assert!(sccs.contains(&vec!["c".to_string(), "d".to_string()]));
        assert_eq!(sccs.len(), 2);
    }

    #[test]
    fn scc_three_cycle_is_one_component() {
        let g = make_graph(&[
            ("a", &["b"]),
            ("b", &["c"]),
            ("c", &["a"]),
        ]);
        let sccs = tarjan_sccs(&g, &nodes_sorted(&g));
        assert_eq!(
            sccs,
            vec![vec!["a".to_string(), "b".to_string(), "c".to_string()]]
        );
    }

    #[test]
    fn scc_ordering_is_deterministic_by_lowest_member() {
        let g = make_graph(&[
            ("z", &["y"]),
            ("y", &["z"]),
            ("a", &["b"]),
            ("b", &["a"]),
        ]);
        let sccs = tarjan_sccs(&g, &nodes_sorted(&g));
        assert_eq!(sccs[0][0], "a");
        assert_eq!(sccs[1][0], "y");
    }
}
