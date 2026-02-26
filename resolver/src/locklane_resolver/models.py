"""Typed data models for resolver JSON responses."""

from __future__ import annotations

from dataclasses import asdict
from dataclasses import dataclass
from datetime import datetime
from datetime import timezone
from typing import Any


SCHEMA_VERSION = "0.1.0"


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

