"""Typed data models for resolver JSON responses."""

from __future__ import annotations

import hashlib
from dataclasses import asdict
from dataclasses import dataclass
from dataclasses import field
from datetime import datetime
from datetime import timezone
from typing import Any


SCHEMA_VERSION = "0.4.0"


def now_utc_iso() -> str:
    """Return a stable ISO-8601 UTC timestamp."""
    return datetime.now(tz=timezone.utc).isoformat()


@dataclass(slots=True)
class ToolAvailability:
    """Availability metadata for a CLI tool dependency."""

    available: bool
    binary: str

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(slots=True)
class ParsedDependency:
    """Representation of a parsed dependency line from requirements files."""

    name: str
    specifier: str
    raw_line: str
    line_number: int

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


class ResolverError(Exception):
    """Raised when all resolver tools fail."""

    def __init__(self, message: str, stderr: str = "", exit_code: int = -1):
        super().__init__(message)
        self.stderr = stderr
        self.exit_code = exit_code


@dataclass(slots=True)
class ResolvedPackage:
    """A single resolved package with provenance info."""

    name: str
    version: str
    is_direct: bool
    required_by: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(slots=True)
class DependencyGraph:
    """Full resolved dependency graph."""

    packages: list[ResolvedPackage]
    resolver_tool: str
    resolver_version: str
    python_version: str
    raw_output: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "packages": [p.to_dict() for p in self.packages],
            "resolver_tool": self.resolver_tool,
            "resolver_version": self.resolver_version,
            "python_version": self.python_version,
        }


@dataclass(slots=True)
class CacheKey:
    """Deterministic cache key for a baseline resolution."""

    interpreter_path: str
    python_version: str
    manifest_sha256: str

    def to_hex(self) -> str:
        combined = f"{self.interpreter_path}|{self.python_version}|{self.manifest_sha256}"
        return hashlib.sha256(combined.encode()).hexdigest()

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(slots=True)
class CacheEntry:
    """Stored cache entry for a baseline result."""

    cache_key: CacheKey
    created_utc: str
    baseline_result: dict[str, Any]

