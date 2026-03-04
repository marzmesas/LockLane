"""Plan composition engine: candidate enumeration, batch simulation, compatibility check."""

from __future__ import annotations

import re
import shutil
import tempfile
from pathlib import Path
from typing import Any

from .models import ParsedDependency, ResolverError
from .pypi import enumerate_patch_candidates, PyPIError
from .resolver import run_uv_compile, run_pip_compile
from .simulator import create_modified_manifest, simulate_candidate


_PINNED_RE = re.compile(r"^==(\d+\.\d+\.\d+)$")


def _extract_pinned_version(specifier: str) -> str | None:
    """Extract version from ``==X.Y.Z`` specifier.

    Returns None for ranges, unpinned, or non-semver pinned versions.
    """
    m = _PINNED_RE.match(specifier.strip())
    return m.group(1) if m else None


def _simulate_combined(
    manifest_path: Path,
    safe_updates: list[dict[str, str]],
    dependencies: list[ParsedDependency],
    resolver: str,
    python_path: str | None,
) -> bool:
    """Resolve manifest with all safe bumps applied simultaneously.

    Returns True if combined resolution succeeds, False otherwise.
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
                run_uv_compile(current, python_path)
            return True
        except (ResolverError, Exception):
            return False
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)


def compose_upgrade_plan(
    manifest_path: Path,
    dependencies: list[ParsedDependency],
    resolver: str,
    python_path: str | None = None,
    timeout: int = 120,
) -> dict[str, Any]:
    """Compose an upgrade plan by enumerating, simulating, and validating candidates.

    Returns a deterministic UpgradePlan dict with safe_updates, blocked_updates,
    inconclusive_updates, and ordered_steps.
    """
    safe_updates: list[dict[str, str]] = []
    blocked_updates: list[dict[str, Any]] = []
    inconclusive_updates: list[dict[str, str]] = []

    # 1. For each pinned dependency, find and simulate the highest patch candidate
    for dep in sorted(dependencies, key=lambda d: d.name.lower()):
        current_version = _extract_pinned_version(dep.specifier)
        if current_version is None:
            continue

        try:
            candidates = enumerate_patch_candidates(dep.name, current_version)
        except PyPIError:
            inconclusive_updates.append({
                "package": dep.name,
                "target_version": current_version,
                "reason": f"Failed to fetch versions from PyPI for {dep.name}.",
            })
            continue

        if not candidates:
            continue

        # Pick the highest patch candidate (last in ascending list)
        target = candidates[-1]

        sim = simulate_candidate(
            manifest_path=manifest_path,
            dependencies=dependencies,
            package=dep.name,
            target_version=target,
            preferred_resolver=resolver,
            python_path=python_path,
            timeout=timeout,
        )

        if sim.result == "SAFE_NOW":
            safe_updates.append({
                "package": dep.name,
                "from_version": current_version,
                "to_version": target,
            })
        elif sim.result == "BLOCKED":
            entry: dict[str, Any] = {
                "package": dep.name,
                "target_version": target,
                "reason": sim.explanation,
            }
            if sim.conflict_chain:
                entry["conflict_chain"] = sim.conflict_chain.to_dict()
            blocked_updates.append(entry)
        else:
            inconclusive_updates.append({
                "package": dep.name,
                "target_version": target,
                "reason": sim.explanation,
            })

    # 2. Compatibility check: if 2+ safe updates, verify they work together
    combined_ok = True
    if len(safe_updates) >= 2:
        combined_ok = _simulate_combined(
            manifest_path, safe_updates, dependencies, resolver, python_path,
        )

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
