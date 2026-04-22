"""Parse ``uv.lock`` to recover the currently-resolved version of each package.

``uv.lock`` is TOML with a top-level ``[[package]]`` array; each entry has at
minimum ``name`` and ``version``. This module exposes a single helper that
returns a ``{lowercase_name: version}`` map. Missing or malformed files
return an empty dict — callers treat absence as "no locked data available"
rather than an error.
"""

from __future__ import annotations

import tomllib
from pathlib import Path


def parse_uv_lock(path: Path) -> dict[str, str]:
    """Return ``{package_name_lower: version}`` from a uv.lock file.

    Returns an empty dict if the file does not exist, is unreadable, or
    does not contain a ``[[package]]`` array. Package names are lowercased
    to match the normalization used by ``pyproject_parser``.
    """
    try:
        text = path.read_text(encoding="utf-8")
    except (FileNotFoundError, OSError):
        return {}

    try:
        data = tomllib.loads(text)
    except tomllib.TOMLDecodeError:
        return {}

    packages = data.get("package", [])
    if not isinstance(packages, list):
        return {}

    result: dict[str, str] = {}
    for entry in packages:
        if not isinstance(entry, dict):
            continue
        name = entry.get("name")
        version = entry.get("version")
        if isinstance(name, str) and isinstance(version, str) and name and version:
            result[name.lower()] = version
    return result
