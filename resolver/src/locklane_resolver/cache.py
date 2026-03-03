"""Baseline resolution cache with XDG-compliant storage and atomic writes."""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

from .models import CacheEntry
from .models import CacheKey
from .models import now_utc_iso


def _cache_dir() -> Path:
    """Return XDG-compliant cache directory for baselines."""
    if sys.platform == "darwin":
        base = Path(os.environ.get("XDG_CACHE_HOME", "")) if os.environ.get("XDG_CACHE_HOME") else Path.home() / "Library" / "Caches"
    else:
        base = Path(os.environ.get("XDG_CACHE_HOME", "")) if os.environ.get("XDG_CACHE_HOME") else Path.home() / ".cache"
    return base / "locklane" / "baselines"


def compute_cache_key(manifest_path: Path, python_path: str | None = None) -> CacheKey:
    """Compute a deterministic cache key from manifest contents and interpreter info."""
    python = python_path or sys.executable
    manifest_sha = hashlib.sha256(manifest_path.read_bytes()).hexdigest()

    result = subprocess.run(
        [python, "-c", "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}')"],
        capture_output=True,
        text=True,
        check=True,
        timeout=10,
    )
    py_version = result.stdout.strip()

    return CacheKey(
        interpreter_path=python,
        python_version=py_version,
        manifest_sha256=manifest_sha,
    )


def _cache_file(key: CacheKey) -> Path:
    """Return the path to the cache file for a given key."""
    return _cache_dir() / f"{key.to_hex()}.json"


def load_cached(key: CacheKey) -> dict[str, Any] | None:
    """Load a cached baseline result, or return None on miss."""
    path = _cache_file(key)
    if not path.exists():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data.get("baseline_result")
    except (json.JSONDecodeError, KeyError):
        return None


def save_to_cache(key: CacheKey, result: dict[str, Any]) -> None:
    """Save a baseline result to cache with atomic write."""
    cache_dir = _cache_dir()
    cache_dir.mkdir(parents=True, exist_ok=True)

    entry = CacheEntry(
        cache_key=key,
        created_utc=now_utc_iso(),
        baseline_result=result,
    )
    payload = {
        "cache_key": key.to_dict(),
        "created_utc": entry.created_utc,
        "baseline_result": entry.baseline_result,
    }

    fd, tmp_path = tempfile.mkstemp(dir=cache_dir, suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2, sort_keys=True)
            f.write("\n")
        os.replace(tmp_path, _cache_file(key))
    except BaseException:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise


def invalidate(key: CacheKey) -> bool:
    """Remove cached entry for the given key. Returns True if file was removed."""
    path = _cache_file(key)
    try:
        path.unlink()
        return True
    except FileNotFoundError:
        return False
