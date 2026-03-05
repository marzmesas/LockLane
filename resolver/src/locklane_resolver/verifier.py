"""Verification engine: venv creation, dependency install, smoke check, log writing."""

from __future__ import annotations

import os
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .simulator import create_modified_manifest


# ---------------------------------------------------------------------------
# Data types
# ---------------------------------------------------------------------------

@dataclass(slots=True)
class VerificationStep:
    """Result of a single verification step."""

    name: str
    command: str
    passed: bool
    exit_code: int
    stdout: str
    stderr: str
    duration_seconds: float

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "command": self.command,
            "passed": self.passed,
            "exit_code": self.exit_code,
            "stdout": self.stdout,
            "stderr": self.stderr,
            "duration_seconds": self.duration_seconds,
        }


@dataclass(slots=True)
class VerificationReport:
    """Full verification report for an upgrade plan."""

    passed: bool
    steps: list[VerificationStep] = field(default_factory=list)
    summary: str = ""
    venv_path: str = ""
    modified_manifest_path: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "passed": self.passed,
            "steps": [s.to_dict() for s in self.steps],
            "summary": self.summary,
            "venv_path": self.venv_path,
            "modified_manifest_path": self.modified_manifest_path,
        }


# ---------------------------------------------------------------------------
# Platform helpers
# ---------------------------------------------------------------------------

def _venv_python(venv_dir: Path) -> Path:
    """Return path to the venv Python interpreter."""
    if sys.platform == "win32":
        return venv_dir / "Scripts" / "python.exe"
    return venv_dir / "bin" / "python"


def _venv_pip(venv_dir: Path) -> Path:
    """Return path to the venv pip executable."""
    if sys.platform == "win32":
        return venv_dir / "Scripts" / "pip.exe"
    return venv_dir / "bin" / "pip"


# ---------------------------------------------------------------------------
# Core functions
# ---------------------------------------------------------------------------

def create_verification_venv(
    python_path: str,
    dest_dir: Path,
    timeout: int = 60,
) -> VerificationStep:
    """Create a disposable venv for verification."""
    venv_dir = dest_dir / ".venv"
    cmd = [python_path, "-m", "venv", str(venv_dir)]

    start = time.monotonic()
    try:
        completed = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            check=False,
            timeout=timeout,
        )
        duration = time.monotonic() - start
        passed = completed.returncode == 0
        return VerificationStep(
            name="create_venv",
            command=" ".join(cmd),
            passed=passed,
            exit_code=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
            duration_seconds=round(duration, 3),
        )
    except subprocess.TimeoutExpired:
        duration = time.monotonic() - start
        return VerificationStep(
            name="create_venv",
            command=" ".join(cmd),
            passed=False,
            exit_code=-1,
            stdout="",
            stderr=f"Timed out after {timeout}s",
            duration_seconds=round(duration, 3),
        )


def build_modified_manifest(
    manifest_path: Path,
    safe_updates: list[dict[str, str]],
    dependencies: list[Any],
    dest_dir: Path,
) -> Path:
    """Iteratively apply safe updates to produce a modified manifest."""
    current = manifest_path
    for update in safe_updates:
        modified = create_modified_manifest(
            current, dependencies, update["package"], update["to_version"], dest_dir,
        )
        current = modified
    return current


def install_dependencies(
    manifest_path: Path,
    venv_dir: Path,
    resolver: str,
    python_path: str,
    timeout: int = 300,
) -> VerificationStep:
    """Install dependencies from manifest into the venv."""
    venv_py = _venv_python(venv_dir)

    if resolver == "uv" and shutil.which("uv"):
        cmd = ["uv", "pip", "install", "-r", str(manifest_path), "--python", str(venv_py)]
    else:
        pip = _venv_pip(venv_dir)
        cmd = [str(pip), "install", "-r", str(manifest_path)]

    start = time.monotonic()
    try:
        completed = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            check=False,
            timeout=timeout,
        )
        duration = time.monotonic() - start
        passed = completed.returncode == 0
        return VerificationStep(
            name="install_dependencies",
            command=" ".join(cmd),
            passed=passed,
            exit_code=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
            duration_seconds=round(duration, 3),
        )
    except subprocess.TimeoutExpired:
        duration = time.monotonic() - start
        return VerificationStep(
            name="install_dependencies",
            command=" ".join(cmd),
            passed=False,
            exit_code=-1,
            stdout="",
            stderr=f"Timed out after {timeout}s",
            duration_seconds=round(duration, 3),
        )


def run_verification_command(
    command: str,
    venv_dir: Path,
    cwd: Path,
    timeout: int = 120,
) -> VerificationStep:
    """Run a verification command inside the venv environment."""
    if sys.platform == "win32":
        bin_dir = str(venv_dir / "Scripts")
    else:
        bin_dir = str(venv_dir / "bin")

    env = os.environ.copy()
    env["VIRTUAL_ENV"] = str(venv_dir)
    env["PATH"] = bin_dir + os.pathsep + env.get("PATH", "")

    args = shlex.split(command)

    start = time.monotonic()
    try:
        completed = subprocess.run(
            args,
            capture_output=True,
            text=True,
            check=False,
            cwd=str(cwd),
            env=env,
            timeout=timeout,
        )
        duration = time.monotonic() - start
        passed = completed.returncode == 0
        return VerificationStep(
            name="verify_command",
            command=command,
            passed=passed,
            exit_code=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
            duration_seconds=round(duration, 3),
        )
    except subprocess.TimeoutExpired:
        duration = time.monotonic() - start
        return VerificationStep(
            name="verify_command",
            command=command,
            passed=False,
            exit_code=-1,
            stdout="",
            stderr=f"Timed out after {timeout}s",
            duration_seconds=round(duration, 3),
        )


def verify_plan(
    manifest_path: Path,
    plan_data: dict[str, Any],
    resolver: str,
    *,
    command: str | None = None,
    python_path: str | None = None,
    timeout: int = 120,
) -> VerificationReport:
    """Orchestrate verification: venv create -> install -> verify -> cleanup."""
    python = python_path or sys.executable
    safe_updates = plan_data.get("safe_updates", [])

    if not safe_updates:
        return VerificationReport(
            passed=True,
            summary="No safe updates to verify.",
        )

    temp_dir = Path(tempfile.mkdtemp(prefix="locklane-verify-"))
    try:
        steps: list[VerificationStep] = []

        # Step 1: Create venv
        venv_step = create_verification_venv(python, temp_dir, timeout=60)
        steps.append(venv_step)
        if not venv_step.passed:
            return VerificationReport(
                passed=False,
                steps=steps,
                summary=f"Venv creation failed (exit {venv_step.exit_code}): {venv_step.stderr}",
                venv_path=str(temp_dir / ".venv"),
            )

        venv_dir = temp_dir / ".venv"

        # Step 2: Build modified manifest and install
        from .cli import parse_requirements
        dependencies = parse_requirements(manifest_path)
        modified = build_modified_manifest(manifest_path, safe_updates, dependencies, temp_dir)

        install_step = install_dependencies(modified, venv_dir, resolver, python, timeout=timeout)
        steps.append(install_step)
        if not install_step.passed:
            return VerificationReport(
                passed=False,
                steps=steps,
                summary=f"Install failed (exit {install_step.exit_code}): {install_step.stderr}",
                venv_path=str(venv_dir),
                modified_manifest_path=str(modified),
            )

        # Step 3: Run verification command (if provided)
        if command:
            verify_step = run_verification_command(command, venv_dir, manifest_path.parent, timeout=timeout)
            steps.append(verify_step)
            if not verify_step.passed:
                return VerificationReport(
                    passed=False,
                    steps=steps,
                    summary=f"Verification command failed (exit {verify_step.exit_code}): {verify_step.stderr}",
                    venv_path=str(venv_dir),
                    modified_manifest_path=str(modified),
                )

        passed_count = sum(1 for s in steps if s.passed)
        return VerificationReport(
            passed=True,
            steps=steps,
            summary=f"All {passed_count}/{len(steps)} steps passed.",
            venv_path=str(venv_dir),
            modified_manifest_path=str(modified),
        )
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)


def write_log_file(log_path: Path, report: VerificationReport) -> None:
    """Write human-readable verification log."""
    lines: list[str] = []
    lines.append("=" * 60)
    lines.append("Locklane Verification Report")
    lines.append("=" * 60)
    lines.append("")

    for step in report.steps:
        lines.append(f"--- {step.name} ---")
        lines.append(f"Command: {step.command}")
        lines.append(f"Passed:  {step.passed}")
        lines.append(f"Exit:    {step.exit_code}")
        lines.append(f"Duration: {step.duration_seconds}s")
        if step.stdout.strip():
            lines.append(f"Stdout:\n{step.stdout.strip()}")
        if step.stderr.strip():
            lines.append(f"Stderr:\n{step.stderr.strip()}")
        lines.append("")

    lines.append(f"Summary: {report.summary}")
    lines.append(f"Overall: {'PASSED' if report.passed else 'FAILED'}")
    lines.append("")

    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text("\n".join(lines), encoding="utf-8")
