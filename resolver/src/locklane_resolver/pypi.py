"""Fetch available versions from PyPI JSON API (stdlib-only)."""

from __future__ import annotations

import json
import re
import urllib.request
from typing import NamedTuple


class PyPIError(Exception):
    """Raised on network or parse failure when querying PyPI."""


class VersionInfo(NamedTuple):
    """Parsed semantic version components."""

    major: int
    minor: int
    patch: int


_SEMVER_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")

# Pre-release / dev / post suffixes to reject
_PRERELEASE_SUFFIXES = re.compile(
    r"(a|b|rc|alpha|beta|dev|post|pre)\d*", re.IGNORECASE
)


def parse_version(version_str: str) -> VersionInfo | None:
    """Parse a strict X.Y.Z version string.

    Returns None for pre-releases, dev, post, or non-3-component versions.
    """
    if _PRERELEASE_SUFFIXES.search(version_str):
        return None
    m = _SEMVER_RE.match(version_str)
    if m is None:
        return None
    return VersionInfo(int(m.group(1)), int(m.group(2)), int(m.group(3)))


def fetch_versions(package: str, timeout: int = 15) -> list[str]:
    """Fetch all release version strings for a package from PyPI.

    Raises PyPIError on network or parse failure.
    """
    url = f"https://pypi.org/pypi/{package}/json"
    try:
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310
            data = json.loads(resp.read().decode("utf-8"))
    except Exception as exc:
        raise PyPIError(f"Failed to fetch versions for {package}: {exc}") from exc

    releases = data.get("releases")
    if not isinstance(releases, dict):
        raise PyPIError(f"Unexpected PyPI response structure for {package}")

    return list(releases.keys())


def enumerate_patch_candidates(
    package: str, current_version: str, timeout: int = 15
) -> list[str]:
    """Return patch-level upgrades for the same major.minor, sorted ascending.

    For example, if current_version is "2.31.0", returns ["2.31.1", "2.31.2", ...]
    excluding pre-releases and non-semver versions.
    """
    current = parse_version(current_version)
    if current is None:
        return []

    all_versions = fetch_versions(package, timeout=timeout)
    candidates: list[tuple[int, str]] = []

    for v_str in all_versions:
        v = parse_version(v_str)
        if v is None:
            continue
        if v.major == current.major and v.minor == current.minor and v.patch > current.patch:
            candidates.append((v.patch, v_str))

    candidates.sort(key=lambda t: t[0])
    return [v_str for _, v_str in candidates]
