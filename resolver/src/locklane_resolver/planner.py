"""Plan composition engine: candidate enumeration, batch simulation, compatibility check."""

from __future__ import annotations

import re
import shutil
import tempfile
from pathlib import Path
from typing import Any

from .models import ParsedDependency, ResolverError
from .pypi import enumerate_upgrade_candidates, PyPIError
from .resolver import run_uv_compile, run_pip_compile
from .simulator import create_modified_manifest, simulate_candidate


_PINNED_RE = re.compile(r"^==(\d+\.\d+\.\d+)$")
_SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+$")


def _extract_pinned_version(specifier: str) -> str | None:
    """Extract version from ``==X.Y.Z`` specifier.

    Returns None for ranges, unpinned, or non-semver pinned versions.
    """
    m = _PINNED_RE.match(specifier.strip())
    return m.group(1) if m else None


def _current_version_for(dep: ParsedDependency) -> str | None:
    """Pick the "current version" for a dep.

    Prefers an exact pin (``==X.Y.Z``) in the manifest specifier; falls
    back to ``dep.locked_version`` for range specifiers. Only returns
    strict ``X.Y.Z`` semver so downstream bump enumeration stays well-
    behaved.
    """
    pinned = _extract_pinned_version(dep.specifier)
    if pinned is not None:
        return pinned
    locked = dep.locked_version
    if locked is not None and _SEMVER_RE.match(locked):
        return locked
    return None


def _find_fallback(
    manifest_path: Path,
    dependencies: list[ParsedDependency],
    package: str,
    candidates_by_level: dict[str, list[str]],
    resolver: str,
    python_path: str | None,
    timeout: int,
    exclude_newer: str | None,
) -> str | None:
    """Try up to 3 lower versions across all levels to find one that resolves."""
    # Collect candidates in descending order (skip the highest we already tried)
    fallbacks: list[str] = []
    for level in ("major", "minor", "patch"):
        level_cands = candidates_by_level.get(level, [])
        # Skip the last (highest) which was already tried
        for v in reversed(level_cands[:-1] if len(level_cands) > 1 else []):
            fallbacks.append(v)
            if len(fallbacks) >= 3:
                break
        if len(fallbacks) >= 3:
            break

    for target in fallbacks:
        sim = simulate_candidate(
            manifest_path=manifest_path,
            dependencies=dependencies,
            package=package,
            target_version=target,
            preferred_resolver=resolver,
            python_path=python_path,
            timeout=timeout,
            exclude_newer=exclude_newer,
        )
        if sim.result == "SAFE_NOW":
            return target
    return None


def _simulate_combined(
    manifest_path: Path,
    safe_updates: list[dict[str, str]],
    dependencies: list[ParsedDependency],
    resolver: str,
    python_path: str | None,
    exclude_newer: str | None = None,
) -> bool:
    """Resolve manifest with a set of safe bumps applied simultaneously.

    Any package not listed in ``safe_updates`` is left at its current
    pinned version. Returns True if combined resolution succeeds, False
    otherwise. Also used to probe arbitrary subsets when computing
    interdependency groups.
    """
    temp_dir = Path(tempfile.mkdtemp(prefix="locklane-combined-"))
    try:
        # Iteratively apply each safe update to build the combined manifest
        current = manifest_path
        for update in safe_updates:
            modified = create_modified_manifest(
                current, dependencies, update["package"], update["to_version"], temp_dir,
            )
            current = modified

        try:
            if resolver == "pip-tools":
                run_pip_compile(current)
            else:
                run_uv_compile(current, python_path, exclude_newer=exclude_newer)
            return True
        except (ResolverError, Exception):
            return False
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)


def _compute_groups(
    manifest_path: Path,
    safe_updates: list[dict[str, str]],
    dependencies: list[ParsedDependency],
    resolver: str,
    python_path: str | None,
    exclude_newer: str | None = None,
) -> dict[str, str]:
    """Assign interdependency group IDs to safe updates.

    For each safe update, probes whether it resolves on its own (with
    every other dependency at its current pinned version). If not,
    greedy-adds peers from ``safe_updates`` in alphabetical order until
    resolution succeeds — those peers must move together.

    The resulting peer relation is symmetrized and decomposed into
    connected components; each multi-member component gets a stable
    ``g1``, ``g2``, ... id. Independent updates are omitted from the
    returned mapping.
    """
    if len(safe_updates) < 2:
        return {}

    safe_by_pkg = {u["package"]: u for u in safe_updates}
    all_pkgs_sorted = sorted(safe_by_pkg.keys())

    requires: dict[str, set[str]] = {pkg: set() for pkg in all_pkgs_sorted}
    for pkg in all_pkgs_sorted:
        update = safe_by_pkg[pkg]
        if _simulate_combined(
            manifest_path, [update], dependencies, resolver, python_path,
            exclude_newer=exclude_newer,
        ):
            continue

        current_subset = [update]
        for peer in all_pkgs_sorted:
            if peer == pkg:
                continue
            current_subset.append(safe_by_pkg[peer])
            requires[pkg].add(peer)
            if _simulate_combined(
                manifest_path, current_subset, dependencies, resolver, python_path,
                exclude_newer=exclude_newer,
            ):
                break

    # Greedy may over-approximate (alphabetical sweep pulls in unrelated peers
    # that just happen to be in an interdependent pair of their own). Use
    # strongly connected components of the directed requires graph: a package
    # and its peer share a group only if they mutually require each other
    # (directly or transitively).
    sccs = _tarjan_sccs(requires, all_pkgs_sorted)

    group_ids: dict[str, str] = {}
    group_counter = 0
    for component in sccs:
        if len(component) < 2:
            continue
        group_counter += 1
        gid = f"g{group_counter}"
        for pkg in component:
            group_ids[pkg] = gid
    return group_ids


def _tarjan_sccs(
    graph: dict[str, set[str]],
    nodes: list[str],
) -> list[list[str]]:
    """Tarjan's SCC. Returns components sorted by their lowest-named member."""
    indices: dict[str, int] = {}
    lowlink: dict[str, int] = {}
    on_stack: set[str] = set()
    stack: list[str] = []
    counter = [0]
    result: list[list[str]] = []

    def strongconnect(v: str) -> None:
        indices[v] = counter[0]
        lowlink[v] = counter[0]
        counter[0] += 1
        stack.append(v)
        on_stack.add(v)

        for w in graph.get(v, set()):
            if w not in indices:
                strongconnect(w)
                lowlink[v] = min(lowlink[v], lowlink[w])
            elif w in on_stack:
                lowlink[v] = min(lowlink[v], indices[w])

        if lowlink[v] == indices[v]:
            component: list[str] = []
            while True:
                w = stack.pop()
                on_stack.discard(w)
                component.append(w)
                if w == v:
                    break
            result.append(sorted(component))

    for v in nodes:
        if v not in indices:
            strongconnect(v)

    result.sort(key=lambda c: c[0])
    return result


def compose_upgrade_plan(
    manifest_path: Path,
    dependencies: list[ParsedDependency],
    resolver: str,
    python_path: str | None = None,
    timeout: int = 120,
    exclude_newer: str | None = None,
) -> dict[str, Any]:
    """Compose an upgrade plan by enumerating, simulating, and validating candidates.

    Returns a deterministic UpgradePlan dict with safe_updates, blocked_updates,
    inconclusive_updates, and ordered_steps.
    """
    safe_updates: list[dict[str, str]] = []
    blocked_updates: list[dict[str, Any]] = []
    inconclusive_updates: list[dict[str, str]] = []

    # 1. For each pinned dependency, find the highest upgrade candidate
    #    Try major first (highest jump), then minor, then patch.
    #    The first level that resolves safely wins.
    for dep in sorted(dependencies, key=lambda d: d.name.lower()):
        current_version = _current_version_for(dep)
        if current_version is None:
            continue

        try:
            candidates_by_level = enumerate_upgrade_candidates(
                dep.name, current_version, exclude_newer=exclude_newer,
            )
        except PyPIError:
            inconclusive_updates.append({
                "package": dep.name,
                "target_version": current_version,
                "reason": f"Failed to fetch versions from PyPI for {dep.name}.",
            })
            continue

        # Try from highest bump level down: major -> minor -> patch
        # Pick the highest version within each level
        targets = []
        for level in ("major", "minor", "patch"):
            level_candidates = candidates_by_level.get(level, [])
            if level_candidates:
                targets.append(level_candidates[-1])  # highest in that level

        if not targets:
            continue

        best_safe = None
        last_blocked_entry = None

        for target in targets:
            sim = simulate_candidate(
                manifest_path=manifest_path,
                dependencies=dependencies,
                package=dep.name,
                target_version=target,
                preferred_resolver=resolver,
                python_path=python_path,
                timeout=timeout,
                exclude_newer=exclude_newer,
            )

            if sim.result == "SAFE_NOW":
                best_safe = {
                    "package": dep.name,
                    "from_version": current_version,
                    "to_version": target,
                }
                break  # Take the highest safe version
            elif sim.result == "BLOCKED":
                entry: dict[str, Any] = {
                    "package": dep.name,
                    "target_version": target,
                    "reason": sim.explanation,
                }
                if sim.conflict_chain:
                    entry["conflict_chain"] = sim.conflict_chain.to_dict()
                last_blocked_entry = entry
                # Continue trying lower bump levels
            else:
                inconclusive_updates.append({
                    "package": dep.name,
                    "target_version": target,
                    "reason": sim.explanation,
                })
                break  # Don't keep trying on inconclusive

        if best_safe:
            safe_updates.append(best_safe)
        elif last_blocked_entry:
            # Try to find a fallback suggestion from lower versions
            suggestion = _find_fallback(
                manifest_path, dependencies, dep.name, candidates_by_level,
                resolver, python_path, timeout, exclude_newer,
            )
            if suggestion:
                last_blocked_entry["suggestion"] = suggestion
            blocked_updates.append(last_blocked_entry)

    # 2. Compatibility check: if 2+ safe updates, verify they work together
    combined_ok = True
    if len(safe_updates) >= 2:
        combined_ok = _simulate_combined(
            manifest_path, safe_updates, dependencies, resolver, python_path,
            exclude_newer=exclude_newer,
        )

    # 2b. Interdependency groups. Only meaningful when the full set resolves —
    # if it doesn't, users fall back to sequential steps anyway.
    if combined_ok and len(safe_updates) >= 2:
        group_ids = _compute_groups(
            manifest_path, safe_updates, dependencies, resolver, python_path,
            exclude_newer=exclude_newer,
        )
        for update in safe_updates:
            gid = group_ids.get(update["package"])
            if gid is not None:
                update["group_id"] = gid

    # 3. Build ordered_steps
    ordered_steps: list[dict[str, Any]]
    if safe_updates and combined_ok:
        descriptions = ", ".join(
            f"{u['package']} {u['from_version']}->{u['to_version']}"
            for u in safe_updates
        )
        ordered_steps = [{
            "step": 1,
            "description": f"Apply all {len(safe_updates)} safe updates: {descriptions}",
        }]
    elif safe_updates:
        ordered_steps = [
            {
                "step": i + 1,
                "description": f"Update {u['package']} from {u['from_version']} to {u['to_version']}",
            }
            for i, u in enumerate(safe_updates)
        ]
    else:
        ordered_steps = []

    return {
        "manifest_path": str(manifest_path),
        "resolver": resolver,
        "safe_updates": safe_updates,
        "blocked_updates": blocked_updates,
        "inconclusive_updates": inconclusive_updates,
        "ordered_steps": ordered_steps,
    }
