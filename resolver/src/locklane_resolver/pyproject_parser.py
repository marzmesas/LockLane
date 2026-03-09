"""Parser and surgery functions for pyproject.toml manifest files."""

from __future__ import annotations

import re
import tomllib
from pathlib import Path

from .models import ParsedDependency


# ---------------------------------------------------------------------------
# Section detection
# ---------------------------------------------------------------------------

def _detect_sections(data: dict) -> list[tuple[str, list[str]]]:
    """Discover dependency sections and return (section_path, dep_strings) pairs.

    Supports:
      - PEP 621: [project].dependencies, [project.optional-dependencies].*
      - Poetry:  [tool.poetry.dependencies], [tool.poetry.group.*.dependencies]
    """
    sections: list[tuple[str, list[str]]] = []

    # PEP 621
    project = data.get("project", {})
    if isinstance(project, dict):
        deps = project.get("dependencies", [])
        if isinstance(deps, list):
            sections.append(("project.dependencies", deps))

        opt_deps = project.get("optional-dependencies", {})
        if isinstance(opt_deps, dict):
            for group, group_deps in opt_deps.items():
                if isinstance(group_deps, list):
                    sections.append((f"project.optional-dependencies.{group}", group_deps))

    # Poetry
    tool = data.get("tool", {})
    poetry = tool.get("poetry", {}) if isinstance(tool, dict) else {}
    if isinstance(poetry, dict):
        poetry_deps = poetry.get("dependencies", {})
        if isinstance(poetry_deps, dict):
            for pkg, spec in poetry_deps.items():
                if pkg.lower() == "python":
                    continue
                dep_str = _poetry_dep_to_string(pkg, spec)
                if dep_str:
                    sections.append((f"tool.poetry.dependencies.{pkg}", [dep_str]))

        groups = poetry.get("group", {})
        if isinstance(groups, dict):
            for group_name, group_data in groups.items():
                if not isinstance(group_data, dict):
                    continue
                group_deps = group_data.get("dependencies", {})
                if isinstance(group_deps, dict):
                    for pkg, spec in group_deps.items():
                        if pkg.lower() == "python":
                            continue
                        dep_str = _poetry_dep_to_string(pkg, spec)
                        if dep_str:
                            sections.append((
                                f"tool.poetry.group.{group_name}.dependencies.{pkg}",
                                [dep_str],
                            ))

    return sections


def _poetry_dep_to_string(pkg: str, spec: str | dict) -> str | None:
    """Convert a Poetry dependency spec to a PEP 508-ish string for parsing."""
    if isinstance(spec, str):
        version = _poetry_version_to_pep508(spec)
        return f"{pkg}{version}" if version else pkg
    if isinstance(spec, dict):
        ver = spec.get("version", "")
        if isinstance(ver, str) and ver:
            version = _poetry_version_to_pep508(ver)
            return f"{pkg}{version}" if version else pkg
        return pkg
    return None


def _poetry_version_to_pep508(ver: str) -> str:
    """Convert Poetry version constraint to a PEP 508 specifier (best effort).

    We only need the pinned version for comparison — the actual constraint
    is handled by the resolver tools.  For display and matching we do:
      "^1.2.3" -> ">=1.2.3"   (caret)
      "~1.2.3" -> "~=1.2.3"  (tilde)
      "1.2.3"  -> "==1.2.3"  (exact)
      "*"      -> ""          (any)
    """
    ver = ver.strip()
    if not ver or ver == "*":
        return ""
    if ver.startswith("^"):
        return f">={ver[1:]}"
    if ver.startswith("~"):
        return f"~={ver[1:]}"
    if ver[0].isdigit():
        return f"=={ver}"
    # Already has operator (>=, ==, etc.)
    return ver


# ---------------------------------------------------------------------------
# PEP 508 dep string parsing (reusable for both formats)
# ---------------------------------------------------------------------------

_VERSION_OPS = ("==", "~=", "!=", ">=", "<=", ">", "<")


def _parse_dep_string(dep_str: str) -> tuple[str, str]:
    """Parse a PEP 508 dependency string into (name, specifier)."""
    token = dep_str.split(";", 1)[0].strip()
    name = token
    specifier = ""

    for op in _VERSION_OPS:
        if op in token:
            idx = token.index(op)
            name = token[:idx].strip()
            specifier = token[idx:].strip()
            break

    # Strip extras
    if "[" in name and "]" in name:
        name = name.split("[", 1)[0]

    return name, specifier


# ---------------------------------------------------------------------------
# Main parser
# ---------------------------------------------------------------------------

def parse_pyproject_dependencies(manifest_path: Path) -> list[ParsedDependency]:
    """Parse dependencies from a pyproject.toml file."""
    text = manifest_path.read_text(encoding="utf-8")
    data = tomllib.loads(text)
    raw_lines = text.splitlines()

    sections = _detect_sections(data)
    dependencies: list[ParsedDependency] = []
    seen: set[str] = set()

    for section_path, dep_strings in sections:
        for dep_str in dep_strings:
            name, specifier = _parse_dep_string(dep_str)
            name_lower = name.lower()
            if name_lower in seen:
                continue
            seen.add(name_lower)

            line_number = _find_line_number(raw_lines, name, section_path)
            raw_line = raw_lines[line_number - 1].strip() if line_number > 0 else dep_str

            dependencies.append(ParsedDependency(
                name=name,
                specifier=specifier,
                raw_line=raw_line,
                line_number=line_number,
            ))

    return dependencies


def _find_line_number(
    raw_lines: list[str],
    package_name: str,
    section_path: str,
) -> int:
    """Find the 1-based line number of a package in the raw TOML text.

    Searches within the appropriate section context.
    """
    pkg_lower = package_name.lower()
    pkg_patterns = [
        re.compile(rf'["\']?{re.escape(pkg_lower)}[\[>=<~!=\s"\']', re.IGNORECASE),
        re.compile(rf'^{re.escape(pkg_lower)}\s*=', re.IGNORECASE),
    ]

    for i, line in enumerate(raw_lines):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        for pat in pkg_patterns:
            if pat.search(stripped):
                return i + 1

    return 0


# ---------------------------------------------------------------------------
# TOML-aware line surgery
# ---------------------------------------------------------------------------

def find_pyproject_dependency_line(
    lines: list[str],
    package: str,
) -> tuple[int, str] | None:
    """Find the line index and stripped content for a dependency in pyproject.toml."""
    pkg_lower = package.lower()

    for i, line in enumerate(lines):
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or stripped.startswith("["):
            continue

        # PEP 621 style: "package>=1.0" inside an array
        if '"' in stripped or "'" in stripped:
            # Extract quoted strings and check for package name
            for quote in ('"', "'"):
                parts = stripped.split(quote)
                for j in range(1, len(parts), 2):
                    dep_str = parts[j]
                    name, _ = _parse_dep_string(dep_str)
                    if name.lower() == pkg_lower:
                        return (i, stripped)

        # Poetry style: package = "^1.0" or package = {version = "^1.0"}
        if "=" in stripped and not stripped.startswith(("-", '"', "'")):
            key = stripped.split("=", 1)[0].strip().strip('"').strip("'")
            if key.lower() == pkg_lower:
                return (i, stripped)

    return None


def build_pyproject_replacement_line(
    stripped: str,
    package: str,
    target_version: str,
) -> str:
    """Build a replacement line for a pyproject.toml dependency.

    Handles both PEP 621 array items and Poetry key-value pairs.
    Preserves the original line structure, only replacing the version.
    """
    # Poetry style: package = "^1.0.0" or package = {version = "^1.0.0", ...}
    poetry_simple = re.match(
        rf'^(\s*{re.escape(package)}\s*=\s*["\'])([^"\']*)(["\']\s*(?:#.*)?)$',
        stripped,
        re.IGNORECASE,
    )
    if poetry_simple:
        prefix, old_ver, suffix = poetry_simple.groups()
        new_ver = _preserve_poetry_operator(old_ver, target_version)
        return f"{prefix}{new_ver}{suffix}"

    # Poetry inline table: package = {version = "^1.0.0", ...}
    poetry_table = re.search(
        r'(version\s*=\s*["\'])([^"\']*)(["\']\s*)',
        stripped,
        re.IGNORECASE,
    )
    if poetry_table:
        old_ver = poetry_table.group(2)
        new_ver = _preserve_poetry_operator(old_ver, target_version)
        return (
            stripped[:poetry_table.start()]
            + poetry_table.group(1) + new_ver + poetry_table.group(3)
            + stripped[poetry_table.end():]
        )

    # PEP 621 style: "package>=1.0.0" inside array
    # Find the quoted dep string containing this package and replace the version
    for quote in ('"', "'"):
        pattern = re.compile(
            rf'({quote})({re.escape(package)}(?:\[[^\]]*\])?\s*[>=<~!=!]+\s*)([^{quote}]+?)({quote})',
            re.IGNORECASE,
        )
        m = pattern.search(stripped)
        if m:
            return (
                stripped[:m.start()]
                + m.group(1) + m.group(2).split("=")[0].split(">")[0].split("<")[0].split("~")[0].split("!")[0]
                + f"=={target_version}"
                + m.group(4)
                + stripped[m.end():]
            )

    # Fallback: try simpler PEP 621 match (e.g., "package" with no version)
    for quote in ('"', "'"):
        simple_pattern = re.compile(
            rf'({quote})({re.escape(package)})({quote})',
            re.IGNORECASE,
        )
        m = simple_pattern.search(stripped)
        if m:
            return (
                stripped[:m.start()]
                + m.group(1) + f"{package}=={target_version}" + m.group(3)
                + stripped[m.end():]
            )

    return stripped


def _preserve_poetry_operator(old_version: str, new_version: str) -> str:
    """Preserve the Poetry version operator when replacing the version number."""
    old_version = old_version.strip()
    if old_version.startswith("^"):
        return f"^{new_version}"
    if old_version.startswith("~"):
        return f"~{new_version}"
    if old_version.startswith(">="):
        return f">={new_version}"
    if old_version.startswith("=="):
        return f"=={new_version}"
    # No operator = exact pin
    return new_version
