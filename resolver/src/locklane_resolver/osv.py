"""Query the OSV (Open Source Vulnerabilities) database for known CVEs."""

from __future__ import annotations

import json
import urllib.request
from pathlib import Path
from typing import Any

from .cli import parse_manifest
from .models import SCHEMA_VERSION, now_utc_iso

OSV_API_URL = "https://api.osv.dev/v1/query"


def query_osv(
    package: str,
    version: str,
    ecosystem: str = "PyPI",
    timeout: int = 15,
) -> list[dict[str, Any]]:
    """Query OSV for vulnerabilities affecting a specific package version."""
    payload = json.dumps({
        "package": {"name": package, "ecosystem": ecosystem},
        "version": version,
    }).encode("utf-8")

    req = urllib.request.Request(
        OSV_API_URL,
        data=payload,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )

    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310
            data = json.loads(resp.read().decode("utf-8"))
    except Exception:
        return []

    return data.get("vulns", [])


def _extract_severity(vuln: dict[str, Any]) -> str:
    """Extract a human-readable severity from an OSV vulnerability entry."""
    severity_list = vuln.get("severity", [])
    if severity_list:
        for s in severity_list:
            if s.get("type") == "CVSS_V3":
                score = s.get("score", "")
                if score:
                    return score
        return severity_list[0].get("score", "unknown")

    # Try database_specific
    db = vuln.get("database_specific", {})
    if isinstance(db, dict):
        sev = db.get("severity")
        if sev:
            return str(sev)

    return "unknown"


def _extract_references(vuln: dict[str, Any]) -> list[dict[str, str]]:
    """Extract reference URLs from an OSV vulnerability entry."""
    refs = vuln.get("references", [])
    return [{"url": r.get("url", "")} for r in refs if r.get("url")]


def audit_manifest(manifest_path: Path, timeout: int = 15) -> dict[str, Any]:
    """Audit all dependencies in a manifest for known vulnerabilities."""
    dependencies = parse_manifest(manifest_path)

    packages: list[dict[str, Any]] = []

    for dep in dependencies:
        # Extract pinned version
        version = ""
        spec = dep.specifier
        if spec.startswith("=="):
            version = spec[2:].strip()
        elif not spec:
            continue  # Can't audit without a version
        else:
            # For >= or ~= specifiers, use the version number as best effort
            for op in (">=", "~=", "<=", ">", "<", "!="):
                if spec.startswith(op):
                    version = spec[len(op):].strip().split(",")[0].strip()
                    break

        if not version:
            continue

        vulns = query_osv(dep.name, version, timeout=timeout)

        vuln_entries = []
        for v in vulns:
            vuln_entries.append({
                "id": v.get("id", ""),
                "summary": v.get("summary", v.get("details", "")[:200]),
                "severity": _extract_severity(v),
                "aliases": v.get("aliases", []),
                "references": _extract_references(v),
            })

        packages.append({
            "package": dep.name,
            "version": version,
            "vulnerabilities": vuln_entries,
        })

    return {
        "schema_version": SCHEMA_VERSION,
        "timestamp_utc": now_utc_iso(),
        "status": "ok",
        "manifest_path": str(manifest_path),
        "packages": packages,
    }
