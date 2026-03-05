"""Core simulation engine: manifest rewriting, resolution, conflict chain parsing."""

from __future__ import annotations

import re
import shutil
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .models import ResolverError
from .resolver import run_uv_compile, run_pip_compile


# ---------------------------------------------------------------------------
# Conflict chain data types
# ---------------------------------------------------------------------------

@dataclass(slots=True)
class ConflictLink:
    """A single link in a dependency conflict chain."""

    package: str
    constraint: str
    required_by: str

    def to_dict(self) -> dict[str, str]:
        return {"package": self.package, "constraint": self.constraint, "required_by": self.required_by}


@dataclass(slots=True)
class ConflictChain:
    """Parsed conflict chain from resolver stderr."""

    summary: str
    links: list[ConflictLink] = field(default_factory=list)
    raw_stderr: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "summary": self.summary,
            "links": [link.to_dict() for link in self.links],
        }


@dataclass(slots=True)
class SimulationResult:
    """Result of simulating a single candidate version bump."""

    result: str  # SAFE_NOW, BLOCKED, INCONCLUSIVE
    explanation: str
    conflict_chain: ConflictChain | None = None
    raw_logs: dict[str, str] | None = None

    def to_dict(self) -> dict[str, Any]:
        d: dict[str, Any] = {
            "result": self.result,
            "explanation": self.explanation,
            "conflict_chain": self.conflict_chain.to_dict() if self.conflict_chain else None,
            "raw_logs": self.raw_logs,
        }
        return d


# ---------------------------------------------------------------------------
# ANSI / Unicode stripping
# ---------------------------------------------------------------------------

_ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")
_BOX_CHARS = re.compile(r"[\u2500-\u257f]")


def _strip_formatting(text: str) -> str:
    """Strip ANSI escape codes and Unicode box-drawing characters."""
    text = _ANSI_RE.sub("", text)
    text = _BOX_CHARS.sub("", text)
    return text


# ---------------------------------------------------------------------------
# Conflict chain parsing
# ---------------------------------------------------------------------------

# uv patterns: "Because <pkg> depends on <dep>(<constraint>)"
_UV_BECAUSE_RE = re.compile(
    r"[Bb]ecause\s+(\S+)\s+depends\s+on\s+(\S+)\s*\(([^)]+)\)"
)

# uv/pip pattern: "you require <pkg>(<constraint>)" or "<pkg>==<version>"
_UV_REQUIRE_RE = re.compile(
    r"(?:you\s+require|requires?)\s+(\S+?)(?:\s*\(([^)]+)\)|==(\S+))"
)

# pip-compile pattern: "Could not find a version that satisfies the requirement <pkg>..."
_PIP_UNSATISFIED_RE = re.compile(
    r"Could not find a version that satisfies the requirement\s+(\S+)"
)

# pip-compile pattern: "... (from <parent>)"
_PIP_FROM_RE = re.compile(
    r"\(from\s+(\S+?)(?:[<>=!~].*?)?\)"
)


def parse_conflict_chain(stderr: str) -> ConflictChain | None:
    """Extract a conflict chain from resolver stderr.

    Returns None if stderr does not contain parseable conflict information.
    """
    if not stderr or not stderr.strip():
        return None

    cleaned = _strip_formatting(stderr)
    links: list[ConflictLink] = []

    # Try uv-style "Because X depends on Y(constraint)" patterns
    for m in _UV_BECAUSE_RE.finditer(cleaned):
        parent, dep, constraint = m.group(1), m.group(2), m.group(3)
        links.append(ConflictLink(package=dep, constraint=constraint, required_by=parent))

    for m in _UV_REQUIRE_RE.finditer(cleaned):
        pkg = m.group(1)
        constraint = m.group(2) or f"=={m.group(3)}"
        links.append(ConflictLink(package=pkg, constraint=constraint, required_by="(root)"))

    # Try pip-compile style "Could not find a version that satisfies..."
    if not links:
        for m in _PIP_UNSATISFIED_RE.finditer(cleaned):
            pkg = m.group(1)
            # Look for "(from parent)" nearby
            parent = "(root)"
            from_match = _PIP_FROM_RE.search(cleaned[m.end():m.end() + 200])
            if from_match:
                parent = from_match.group(1)
            links.append(ConflictLink(package=pkg, constraint="unsatisfied", required_by=parent))

    if not links:
        return None

    # Build summary from first line of conflict
    first_line = cleaned.strip().split("\n")[0][:200]
    return ConflictChain(summary=first_line, links=links, raw_stderr=stderr)


# ---------------------------------------------------------------------------
# Manifest manipulation
# ---------------------------------------------------------------------------

def _find_dependency_line(
    lines: list[str],
    package: str,
) -> tuple[int, str] | None:
    """Find the line index and stripped content for a dependency.

    Iterates *lines* (raw, with possible newlines), skips comments and flags,
    extracts the bare package name, and returns ``(line_index, stripped)`` on
    match or ``None`` if the package is not present.
    """
    pkg_lower = package.lower()

    for i, line in enumerate(lines):
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or stripped.startswith("-"):
            continue

        token = stripped.split(";", 1)[0].strip()
        name = token
        for op in ("==", "~=", "!=", ">=", "<=", ">", "<"):
            if op in token:
                name = token[:token.index(op)].strip()
                break

        bare_name = name.split("[", 1)[0] if "[" in name else name

        if bare_name.lower() == pkg_lower:
            return (i, stripped)

    return None


def _build_replacement_line(
    stripped: str,
    package: str,
    target_version: str,
) -> str:
    """Build a replacement dependency line from a stripped original.

    Preserves markers (after ``;``), inline comments (after ``#``), and
    extras (``[security]``).  Returns the new line content **without** a
    trailing newline.
    """
    # Extract name (with possible extras) before any specifier
    token = stripped.split(";", 1)[0].strip()
    name = token
    for op in ("==", "~=", "!=", ">=", "<=", ">", "<"):
        if op in token:
            name = token[:token.index(op)].strip()
            break

    bare_name = name.split("[", 1)[0] if "[" in name else name

    # Preserve markers
    marker_part = ""
    if ";" in stripped:
        marker_part = " ; " + stripped.split(";", 1)[1].strip()

    # Preserve inline comment (only in the spec portion, before markers)
    comment_part = ""
    raw_no_marker = stripped.split(";", 1)[0]
    if "#" in raw_no_marker:
        comment_idx = raw_no_marker.index("#")
        comment_part = "  " + raw_no_marker[comment_idx:]

    # Preserve extras
    extras = ""
    if "[" in name:
        extras = "[" + name.split("[", 1)[1]

    return f"{bare_name}{extras}=={target_version}{marker_part}{comment_part}"


def create_modified_manifest(
    original: Path,
    dependencies: list[Any],
    package: str,
    target_version: str,
    dest_dir: Path,
) -> Path:
    """Rewrite manifest with package pinned to target_version.

    Preserves markers, comments, and all other lines. Only the matching
    dependency line is replaced.
    """
    lines = original.read_text(encoding="utf-8").splitlines(keepends=True)

    match = _find_dependency_line(lines, package)
    if match is not None:
        idx, stripped = match
        new_content = _build_replacement_line(stripped, package, target_version)
        lines[idx] = new_content + "\n"

    dest = dest_dir / original.name
    dest.write_text("".join(lines), encoding="utf-8")
    return dest


# ---------------------------------------------------------------------------
# Version-in-output check
# ---------------------------------------------------------------------------

def _normalize_name(name: str) -> str:
    """PEP 503 normalization."""
    return re.sub(r"[-_.]+", "-", name).lower()


def _version_in_output(raw_output: str, package: str, target_version: str) -> bool:
    """Check if the resolved output contains the target version of the package."""
    norm_pkg = _normalize_name(package)
    for line in raw_output.splitlines():
        line = line.strip()
        if "==" not in line:
            continue
        parts = line.split("==", 1)
        if len(parts) != 2:
            continue
        name_part = parts[0].strip()
        version_part = parts[1].split(";", 1)[0].strip()
        if _normalize_name(name_part) == norm_pkg and version_part == target_version:
            return True
    return False


# ---------------------------------------------------------------------------
# Simulation engine
# ---------------------------------------------------------------------------

def simulate_candidate(
    manifest_path: Path,
    dependencies: list[Any],
    package: str,
    target_version: str,
    preferred_resolver: str = "uv",
    python_path: str | None = None,
    timeout: int | None = None,
) -> SimulationResult:
    """Simulate resolution with a bumped version and classify the result.

    Returns SimulationResult with classification:
      SAFE_NOW     — resolution succeeds, target version present in output
      BLOCKED      — resolution fails (with optional conflict chain)
      INCONCLUSIVE — resolution succeeds but target version missing, or unexpected error
    """
    temp_dir = Path(tempfile.mkdtemp(prefix="locklane-sim-"))
    try:
        modified = create_modified_manifest(
            manifest_path, dependencies, package, target_version, temp_dir,
        )

        try:
            if preferred_resolver == "pip-tools":
                raw_output = run_pip_compile(modified)
            else:
                raw_output = run_uv_compile(modified, python_path)
        except ResolverError as exc:
            chain = parse_conflict_chain(exc.stderr)
            return SimulationResult(
                result="BLOCKED",
                explanation=f"Resolution failed: {exc}",
                conflict_chain=chain,
                raw_logs={"stdout": "", "stderr": exc.stderr},
            )
        except Exception as exc:
            return SimulationResult(
                result="INCONCLUSIVE",
                explanation=f"Unexpected error during simulation: {exc}",
            )

        if _version_in_output(raw_output, package, target_version):
            return SimulationResult(
                result="SAFE_NOW",
                explanation=f"Resolution succeeded with {package}=={target_version}.",
                raw_logs={"stdout": raw_output, "stderr": ""},
            )
        else:
            return SimulationResult(
                result="INCONCLUSIVE",
                explanation=(
                    f"Resolution succeeded but {package}=={target_version} "
                    f"was not found in the resolved output."
                ),
                raw_logs={"stdout": raw_output, "stderr": ""},
            )
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)
