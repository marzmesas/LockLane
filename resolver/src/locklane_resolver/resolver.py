"""Subprocess invocation of resolver tools (uv pip compile / pip-compile)."""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from .models import ResolverError

_TIMEOUT_SECONDS = 120


def _detect_python_version(python_path: str) -> str:
    """Return the Python version string for the given interpreter."""
    result = subprocess.run(
        [python_path, "-c", "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}')"],
        capture_output=True,
        text=True,
        check=True,
        timeout=10,
    )
    return result.stdout.strip()


def _detect_tool_version(tool: str) -> str:
    """Return the version string for a resolver tool."""
    result = subprocess.run(
        [tool, "--version"],
        capture_output=True,
        text=True,
        check=True,
        timeout=10,
    )
    return result.stdout.strip()


def _prepare_workspace(manifest_path: Path) -> Path:
    """Copy manifest into a temporary directory and return the temp dir path."""
    temp_dir = Path(tempfile.mkdtemp(prefix="locklane-"))
    dest = temp_dir / manifest_path.name
    dest.write_bytes(manifest_path.read_bytes())
    return temp_dir


def _cleanup_workspace(temp_dir: Path) -> None:
    """Remove the temporary workspace directory."""
    shutil.rmtree(temp_dir, ignore_errors=True)


def run_uv_compile(manifest_path: Path, python_path: str | None = None) -> str:
    """Run uv pip compile and return the annotated output."""
    cmd = [
        "uv", "pip", "compile",
        str(manifest_path),
        "--annotation-style", "split",
        "--no-header",
    ]
    if python_path:
        cmd.extend(["--python", python_path])

    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        check=False,
        timeout=_TIMEOUT_SECONDS,
    )
    if result.returncode != 0:
        raise ResolverError(
            f"uv pip compile failed (exit {result.returncode}): {result.stderr}",
            stderr=result.stderr,
            exit_code=result.returncode,
        )
    return result.stdout


def run_pip_compile(manifest_path: Path) -> str:
    """Run pip-compile and return the annotated output."""
    cmd = [
        "pip-compile",
        str(manifest_path),
        "--annotation-style", "split",
        "--no-header",
        "--strip-extras",
        "--quiet",
    ]
    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        check=False,
        timeout=_TIMEOUT_SECONDS,
    )
    if result.returncode != 0:
        raise ResolverError(
            f"pip-compile failed (exit {result.returncode}): {result.stderr}",
            stderr=result.stderr,
            exit_code=result.returncode,
        )
    return result.stdout


def resolve(
    manifest_path: Path,
    preferred: str = "uv",
    python_path: str | None = None,
) -> tuple[str, str, str]:
    """Resolve dependencies using preferred tool with fallback.

    Returns (raw_output, tool_name, tool_version).
    Raises ResolverError if both tools fail.
    """
    python = python_path or sys.executable
    temp_dir = _prepare_workspace(manifest_path)
    temp_manifest = temp_dir / manifest_path.name

    try:
        tools = _resolve_order(preferred)
        errors: list[str] = []

        for tool_name, runner in tools:
            binary = "uv" if tool_name == "uv" else "pip-compile"
            if shutil.which(binary) is None:
                errors.append(f"{binary} not found on PATH")
                continue
            try:
                if tool_name == "uv":
                    raw_output = run_uv_compile(temp_manifest, python)
                else:
                    raw_output = run_pip_compile(temp_manifest)
                tool_version = _detect_tool_version(binary)
                return raw_output, tool_name, tool_version
            except (ResolverError, subprocess.TimeoutExpired) as exc:
                errors.append(str(exc))
                continue

        raise ResolverError(
            "All resolver tools failed:\n" + "\n".join(f"  - {e}" for e in errors)
        )
    finally:
        _cleanup_workspace(temp_dir)


def _resolve_order(preferred: str) -> list[tuple[str, str]]:
    """Return tool invocation order based on preference."""
    if preferred == "pip-tools":
        return [("pip-tools", "pip-compile"), ("uv", "uv")]
    return [("uv", "uv"), ("pip-tools", "pip-compile")]
