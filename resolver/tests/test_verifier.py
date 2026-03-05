"""Unit tests for the verifier module."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from locklane_resolver.verifier import (
    VerificationReport,
    VerificationStep,
    _venv_pip,
    _venv_python,
    build_modified_manifest,
    create_verification_venv,
    install_dependencies,
    run_verification_command,
    verify_plan,
    write_log_file,
)


class VenvPathTests(unittest.TestCase):
    """Platform path helpers."""

    def test_venv_python_unix(self) -> None:
        with mock.patch("locklane_resolver.verifier.sys") as mock_sys:
            mock_sys.platform = "linux"
            result = _venv_python(Path("/tmp/venv"))
            self.assertEqual(result, Path("/tmp/venv/bin/python"))

    def test_venv_python_windows(self) -> None:
        with mock.patch("locklane_resolver.verifier.sys") as mock_sys:
            mock_sys.platform = "win32"
            result = _venv_python(Path("/tmp/venv"))
            self.assertEqual(result, Path("/tmp/venv/Scripts/python.exe"))

    def test_venv_pip_unix(self) -> None:
        with mock.patch("locklane_resolver.verifier.sys") as mock_sys:
            mock_sys.platform = "darwin"
            result = _venv_pip(Path("/tmp/venv"))
            self.assertEqual(result, Path("/tmp/venv/bin/pip"))

    def test_venv_pip_windows(self) -> None:
        with mock.patch("locklane_resolver.verifier.sys") as mock_sys:
            mock_sys.platform = "win32"
            result = _venv_pip(Path("/tmp/venv"))
            self.assertEqual(result, Path("/tmp/venv/Scripts/pip.exe"))


class BuildModifiedManifestTests(unittest.TestCase):
    """Iterative manifest modification."""

    def test_applies_multiple_updates(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\nclick==8.1.7\n", encoding="utf-8")

            from locklane_resolver.cli import parse_requirements
            deps = parse_requirements(manifest)

            dest_dir = Path(tmp) / "out"
            dest_dir.mkdir()
            safe_updates = [
                {"package": "requests", "to_version": "2.31.1"},
                {"package": "click", "to_version": "8.1.8"},
            ]

            result = build_modified_manifest(manifest, safe_updates, deps, dest_dir)
            content = result.read_text(encoding="utf-8")
            self.assertIn("requests==2.31.1", content)
            self.assertIn("click==8.1.8", content)


class CreateVenvTests(unittest.TestCase):
    """Venv creation with mocked subprocess."""

    @mock.patch("locklane_resolver.verifier.subprocess.run")
    def test_success(self, mock_run: mock.Mock) -> None:
        mock_run.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="", stderr=""
        )
        with tempfile.TemporaryDirectory() as tmp:
            step = create_verification_venv(sys.executable, Path(tmp))
            self.assertTrue(step.passed)
            self.assertEqual(step.exit_code, 0)
            self.assertEqual(step.name, "create_venv")

    @mock.patch("locklane_resolver.verifier.subprocess.run")
    def test_failure(self, mock_run: mock.Mock) -> None:
        mock_run.return_value = subprocess.CompletedProcess(
            args=[], returncode=1, stdout="", stderr="venv error"
        )
        with tempfile.TemporaryDirectory() as tmp:
            step = create_verification_venv(sys.executable, Path(tmp))
            self.assertFalse(step.passed)
            self.assertEqual(step.exit_code, 1)

    @mock.patch("locklane_resolver.verifier.subprocess.run", side_effect=subprocess.TimeoutExpired(cmd="", timeout=5))
    def test_timeout(self, mock_run: mock.Mock) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            step = create_verification_venv(sys.executable, Path(tmp), timeout=5)
            self.assertFalse(step.passed)
            self.assertEqual(step.exit_code, -1)
            self.assertIn("Timed out", step.stderr)


class InstallDependenciesTests(unittest.TestCase):
    """Dependency installation with resolver selection."""

    @mock.patch("locklane_resolver.verifier.shutil.which", return_value="/usr/bin/uv")
    @mock.patch("locklane_resolver.verifier.subprocess.run")
    def test_uv_path(self, mock_run: mock.Mock, mock_which: mock.Mock) -> None:
        mock_run.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="installed", stderr=""
        )
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "req.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")
            venv_dir = Path(tmp) / ".venv"

            step = install_dependencies(manifest, venv_dir, "uv", sys.executable)
            self.assertTrue(step.passed)
            cmd_str = mock_run.call_args[0][0]
            self.assertEqual(cmd_str[0], "uv")

    @mock.patch("locklane_resolver.verifier.shutil.which", return_value=None)
    @mock.patch("locklane_resolver.verifier.subprocess.run")
    def test_pip_fallback(self, mock_run: mock.Mock, mock_which: mock.Mock) -> None:
        mock_run.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="installed", stderr=""
        )
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "req.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")
            venv_dir = Path(tmp) / ".venv"

            step = install_dependencies(manifest, venv_dir, "uv", sys.executable)
            self.assertTrue(step.passed)
            cmd_str = mock_run.call_args[0][0]
            self.assertIn("pip", cmd_str[0])

    @mock.patch("locklane_resolver.verifier.shutil.which", return_value=None)
    @mock.patch("locklane_resolver.verifier.subprocess.run")
    def test_install_failure(self, mock_run: mock.Mock, mock_which: mock.Mock) -> None:
        mock_run.return_value = subprocess.CompletedProcess(
            args=[], returncode=1, stdout="", stderr="ERROR: could not install"
        )
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "req.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")
            venv_dir = Path(tmp) / ".venv"

            step = install_dependencies(manifest, venv_dir, "pip-tools", sys.executable)
            self.assertFalse(step.passed)
            self.assertIn("could not install", step.stderr)


class RunVerificationCommandTests(unittest.TestCase):
    """Smoke check execution."""

    @mock.patch("locklane_resolver.verifier.subprocess.run")
    def test_success(self, mock_run: mock.Mock) -> None:
        mock_run.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="ok\n", stderr=""
        )
        with tempfile.TemporaryDirectory() as tmp:
            venv_dir = Path(tmp) / ".venv"
            step = run_verification_command("python -c 'print(1)'", venv_dir, Path(tmp))
            self.assertTrue(step.passed)
            self.assertEqual(step.name, "verify_command")

    @mock.patch("locklane_resolver.verifier.subprocess.run")
    def test_failure(self, mock_run: mock.Mock) -> None:
        mock_run.return_value = subprocess.CompletedProcess(
            args=[], returncode=1, stdout="", stderr="assertion error"
        )
        with tempfile.TemporaryDirectory() as tmp:
            venv_dir = Path(tmp) / ".venv"
            step = run_verification_command("pytest", venv_dir, Path(tmp))
            self.assertFalse(step.passed)
            self.assertIn("assertion error", step.stderr)

    @mock.patch("locklane_resolver.verifier.subprocess.run")
    def test_env_vars_set(self, mock_run: mock.Mock) -> None:
        mock_run.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="", stderr=""
        )
        with tempfile.TemporaryDirectory() as tmp:
            venv_dir = Path(tmp) / ".venv"
            run_verification_command("echo test", venv_dir, Path(tmp))

            call_kwargs = mock_run.call_args[1]
            env = call_kwargs["env"]
            self.assertEqual(env["VIRTUAL_ENV"], str(venv_dir))
            self.assertTrue(env["PATH"].startswith(str(venv_dir)))


class VerifyPlanTests(unittest.TestCase):
    """Orchestrator tests."""

    def test_no_safe_updates_immediate_pass(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            plan_data = {"safe_updates": []}
            report = verify_plan(manifest, plan_data, "uv")
            self.assertTrue(report.passed)
            self.assertEqual(len(report.steps), 0)
            self.assertIn("No safe updates", report.summary)

    @mock.patch("locklane_resolver.verifier.create_verification_venv")
    def test_venv_fails_early_exit(self, mock_venv: mock.Mock) -> None:
        mock_venv.return_value = VerificationStep(
            name="create_venv", command="python -m venv",
            passed=False, exit_code=1, stdout="", stderr="venv creation failed",
            duration_seconds=0.5,
        )
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            plan_data = {"safe_updates": [
                {"package": "requests", "from_version": "2.31.0", "to_version": "2.31.1"},
            ]}
            report = verify_plan(manifest, plan_data, "uv")
            self.assertFalse(report.passed)
            self.assertEqual(len(report.steps), 1)
            self.assertIn("Venv creation failed", report.summary)

    @mock.patch("locklane_resolver.verifier.install_dependencies")
    @mock.patch("locklane_resolver.verifier.create_verification_venv")
    def test_install_fails_actionable_error(self, mock_venv: mock.Mock, mock_install: mock.Mock) -> None:
        mock_venv.return_value = VerificationStep(
            name="create_venv", command="python -m venv",
            passed=True, exit_code=0, stdout="", stderr="",
            duration_seconds=0.5,
        )
        mock_install.return_value = VerificationStep(
            name="install_dependencies", command="pip install -r req.txt",
            passed=False, exit_code=1, stdout="",
            stderr="ERROR: No matching distribution found for nonexistent==1.0.0",
            duration_seconds=1.2,
        )
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            plan_data = {"safe_updates": [
                {"package": "requests", "from_version": "2.31.0", "to_version": "2.31.1"},
            ]}
            report = verify_plan(manifest, plan_data, "uv")
            self.assertFalse(report.passed)
            self.assertEqual(len(report.steps), 2)
            self.assertIn("Install failed", report.summary)
            self.assertIn("No matching distribution", report.summary)

    @mock.patch("locklane_resolver.verifier.run_verification_command")
    @mock.patch("locklane_resolver.verifier.install_dependencies")
    @mock.patch("locklane_resolver.verifier.create_verification_venv")
    def test_verify_command_fails(self, mock_venv: mock.Mock, mock_install: mock.Mock, mock_verify: mock.Mock) -> None:
        mock_venv.return_value = VerificationStep(
            name="create_venv", command="python -m venv",
            passed=True, exit_code=0, stdout="", stderr="",
            duration_seconds=0.5,
        )
        mock_install.return_value = VerificationStep(
            name="install_dependencies", command="pip install -r req.txt",
            passed=True, exit_code=0, stdout="installed", stderr="",
            duration_seconds=1.0,
        )
        mock_verify.return_value = VerificationStep(
            name="verify_command", command="pytest",
            passed=False, exit_code=1, stdout="", stderr="FAILED test_foo.py",
            duration_seconds=2.0,
        )
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            plan_data = {"safe_updates": [
                {"package": "requests", "from_version": "2.31.0", "to_version": "2.31.1"},
            ]}
            report = verify_plan(manifest, plan_data, "uv", command="pytest")
            self.assertFalse(report.passed)
            self.assertEqual(len(report.steps), 3)
            self.assertIn("Verification command failed", report.summary)

    @mock.patch("locklane_resolver.verifier.run_verification_command")
    @mock.patch("locklane_resolver.verifier.install_dependencies")
    @mock.patch("locklane_resolver.verifier.create_verification_venv")
    def test_all_pass(self, mock_venv: mock.Mock, mock_install: mock.Mock, mock_verify: mock.Mock) -> None:
        mock_venv.return_value = VerificationStep(
            name="create_venv", command="python -m venv",
            passed=True, exit_code=0, stdout="", stderr="",
            duration_seconds=0.5,
        )
        mock_install.return_value = VerificationStep(
            name="install_dependencies", command="pip install -r req.txt",
            passed=True, exit_code=0, stdout="installed", stderr="",
            duration_seconds=1.0,
        )
        mock_verify.return_value = VerificationStep(
            name="verify_command", command="python -c 'print(1)'",
            passed=True, exit_code=0, stdout="1\n", stderr="",
            duration_seconds=0.3,
        )
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            plan_data = {"safe_updates": [
                {"package": "requests", "from_version": "2.31.0", "to_version": "2.31.1"},
            ]}
            report = verify_plan(manifest, plan_data, "uv", command="python -c 'print(1)'")
            self.assertTrue(report.passed)
            self.assertEqual(len(report.steps), 3)
            self.assertIn("3/3", report.summary)


class WriteLogFileTests(unittest.TestCase):
    """Log file output format."""

    def test_writes_expected_format(self) -> None:
        report = VerificationReport(
            passed=True,
            steps=[
                VerificationStep(
                    name="create_venv", command="python -m venv /tmp/.venv",
                    passed=True, exit_code=0, stdout="", stderr="",
                    duration_seconds=0.5,
                ),
                VerificationStep(
                    name="install_dependencies", command="pip install -r req.txt",
                    passed=True, exit_code=0, stdout="installed ok", stderr="",
                    duration_seconds=1.2,
                ),
            ],
            summary="All 2/2 steps passed.",
            venv_path="/tmp/.venv",
            modified_manifest_path="/tmp/requirements.txt",
        )

        with tempfile.TemporaryDirectory() as tmp:
            log_path = Path(tmp) / "verify.log"
            write_log_file(log_path, report)

            content = log_path.read_text(encoding="utf-8")
            self.assertIn("Locklane Verification Report", content)
            self.assertIn("create_venv", content)
            self.assertIn("install_dependencies", content)
            self.assertIn("PASSED", content)
            self.assertIn("All 2/2 steps passed.", content)


if __name__ == "__main__":
    unittest.main()
