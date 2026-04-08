"""Fetch available versions from PyPI JSON API (stdlib-only)."""

from __future__ import annotations

import json
import re
import urllib.request
from datetime import datetime, timezone
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


def _parse_exclude_newer(value: str) -> datetime:
    """Parse an exclude-newer value into a UTC datetime.

    Supports ISO 8601 timestamps (``2026-01-15T00:00:00Z``, ``2026-01-15``)
    and simple duration strings (``7 days``, ``1 week``, ``24 hours``).
    """
    # Try ISO timestamp first
    for fmt in ("%Y-%m-%dT%H:%M:%SZ", "%Y-%m-%dT%H:%M:%S%z", "%Y-%m-%d"):
        try:
            dt = datetime.strptime(value, fmt)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            return dt
        except ValueError:
            continue

    # Try duration: "N days", "N weeks", "N hours"
    m = re.match(r"(\d+)\s*(days?|weeks?|hours?)", value.strip(), re.IGNORECASE)
    if m:
        from datetime import timedelta
        amount = int(m.group(1))
        unit = m.group(2).lower().rstrip("s")
        delta = {"day": timedelta(days=amount), "week": timedelta(weeks=amount), "hour": timedelta(hours=amount)}
        if unit in delta:
            return datetime.now(timezone.utc) - delta[unit]

    raise ValueError(f"Cannot parse exclude-newer value: {value!r}")


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


def fetch_versions_with_dates(package: str, timeout: int = 15) -> dict[str, str | None]:
    """Fetch release versions with their upload timestamps.

    Returns {version_string: upload_time_iso_or_None}.
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

    result: dict[str, str | None] = {}
    for version, files in releases.items():
        upload_time = None
        if files:
            upload_time = files[0].get("upload_time_iso_8601") or files[0].get("upload_time")
        result[version] = upload_time
    return result


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


def enumerate_upgrade_candidates(
    package: str, current_version: str, timeout: int = 15,
    exclude_newer: str | None = None,
) -> dict[str, list[str]]:
    """Return upgrade candidates grouped by bump level: patch, minor, major.

    Each list is sorted ascending. Only stable (non-prerelease) semver versions
    are included. If *exclude_newer* is set, versions uploaded after the cutoff
    are filtered out.
    """
    current = parse_version(current_version)
    if current is None:
        return {"patch": [], "minor": [], "major": []}

    # If exclude_newer is set, fetch versions with upload dates for filtering
    cutoff: datetime | None = None
    excluded_versions: set[str] = set()
    if exclude_newer:
        try:
            cutoff = _parse_exclude_newer(exclude_newer)
            versions_with_dates = fetch_versions_with_dates(package, timeout=timeout)
            for v_str, upload_time in versions_with_dates.items():
                if upload_time and cutoff:
                    try:
                        upload_dt = datetime.fromisoformat(upload_time.replace("Z", "+00:00"))
                        if upload_dt > cutoff:
                            excluded_versions.add(v_str)
                    except (ValueError, TypeError):
                        pass  # Can't parse date — keep the version
            all_versions = [v for v in versions_with_dates if v not in excluded_versions]
        except (ValueError, PyPIError):
            all_versions = fetch_versions(package, timeout=timeout)
    else:
        all_versions = fetch_versions(package, timeout=timeout)

    patch: list[tuple[int, str]] = []
    minor: list[tuple[tuple[int, int], str]] = []
    major: list[tuple[tuple[int, int, int], str]] = []

    for v_str in all_versions:
        v = parse_version(v_str)
        if v is None:
            continue
        if v.major == current.major and v.minor == current.minor and v.patch > current.patch:
            patch.append((v.patch, v_str))
        elif v.major == current.major and v.minor > current.minor:
            minor.append(((v.minor, v.patch), v_str))
        elif v.major > current.major:
            major.append(((v.major, v.minor, v.patch), v_str))

    patch.sort(key=lambda t: t[0])
    minor.sort(key=lambda t: t[0])
    major.sort(key=lambda t: t[0])

    return {
        "patch": [v for _, v in patch],
        "minor": [v for _, v in minor],
        "major": [v for _, v in major],
    }
