"""Parse resolver annotated output into a dependency graph."""

from __future__ import annotations

import re

from .models import ResolvedPackage

_NORMALIZE_RE = re.compile(r"[-_.]+")


def _normalize(name: str) -> str:
    """PEP 503 normalization: lowercase, replace runs of [-_.] with a single dash."""
    return _NORMALIZE_RE.sub("-", name.strip().lower())


def parse_resolver_output(
    raw_output: str,
    direct_names: set[str],
) -> list[ResolvedPackage]:
    """Parse annotated resolver output into a sorted list of ResolvedPackage.

    Expects output in ``--annotation-style split`` format:
        package-name==1.2.3
            # via
            #   parent-package
            #   -r requirements.txt
    """
    normalized_directs = {_normalize(n) for n in direct_names}
    packages: dict[str, ResolvedPackage] = {}
    current_name: str | None = None
    in_via_block = False

    for line in raw_output.splitlines():
        stripped = line.strip()

        if not stripped:
            current_name = None
            in_via_block = False
            continue

        # Package line: name==version
        if "==" in stripped and not stripped.startswith("#"):
            name_part, version_part = stripped.split("==", 1)
            norm = _normalize(name_part)
            is_direct = norm in normalized_directs
            packages[norm] = ResolvedPackage(
                name=norm,
                version=version_part.strip(),
                is_direct=is_direct,
                required_by=[],
            )
            current_name = norm
            in_via_block = False
            continue

        # Start of via block
        if stripped == "# via":
            in_via_block = True
            continue

        # Single-line via: "# via parent-name"
        if stripped.startswith("# via ") and not in_via_block:
            if current_name and current_name in packages:
                parent = stripped[len("# via "):].strip()
                if parent.startswith("-r ") or parent.startswith("-c "):
                    packages[current_name].is_direct = True
                else:
                    norm_parent = _normalize(parent)
                    packages[current_name].required_by.append(norm_parent)
            in_via_block = False
            continue

        # Multi-line via entries
        if in_via_block and stripped.startswith("#"):
            entry = stripped.lstrip("#").strip()
            if current_name and current_name in packages and entry:
                if entry.startswith("-r ") or entry.startswith("-c "):
                    packages[current_name].is_direct = True
                else:
                    norm_parent = _normalize(entry)
                    packages[current_name].required_by.append(norm_parent)
            continue

    # Sort required_by lists and return packages sorted by name
    for pkg in packages.values():
        pkg.required_by = sorted(set(pkg.required_by))

    return sorted(packages.values(), key=lambda p: p.name)
