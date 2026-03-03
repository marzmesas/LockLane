"""Unit tests for resolver subprocess invocation."""

from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from locklane_resolver.models import ResolverError
from locklane_resolver.resolver import (
    _detect_python_version,
    _detect_tool_version,
    _prepare_workspace,
    _cleanup_workspace,
    resolve,
    run_uv_compile,
    run_pip_compile,
)


class WorkspaceTests(unittest.TestCase):
    """Temp workspace isolation."""

    def test_prepare_copies_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            temp_dir = _prepare_workspace(manifest)
            try:
                copied = temp_dir / "requirements.txt"
                self.assertTrue(copied.exists())
                self.assertEqual(copied.read_text(encoding="utf-8"), "requests==2.31.0\n")
            finally:
                _cleanup_workspace(temp_dir)

    def test_cleanup_removes_directory(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("x==1.0\n", encoding="utf-8")

            temp_dir = _prepare_workspace(manifest)
            _cleanup_workspace(temp_dir)
            self.assertFalse(temp_dir.exists())


class RunUvCompileTests(unittest.TestCase):
    """Mock uv pip compile invocations."""

    @mock.patch("locklane_resolver.resolver.subprocess.run")
    def test_success_returns_stdout(self, mock_run: mock.Mock) -> None:
        mock_run.return_value = mock.Mock(returncode=0, stdout="click==8.1.7\n", stderr="")
        result = run_uv_compile(Path("/tmp/requirements.txt"))
        self.assertEqual(result, "click==8.1.7\n")

    @mock.patch("locklane_resolver.resolver.subprocess.run")
    def test_failure_raises_resolver_error(self, mock_run: mock.Mock) -> None:
        mock_run.return_value = mock.Mock(returncode=1, stdout="", stderr="error: bad input")
        with self.assertRaises(ResolverError):
            run_uv_compile(Path("/tmp/requirements.txt"))

    @mock.patch("locklane_resolver.resolver.subprocess.run")
    def test_passes_python_flag(self, mock_run: mock.Mock) -> None:
        mock_run.return_value = mock.Mock(returncode=0, stdout="", stderr="")
        run_uv_compile(Path("/tmp/requirements.txt"), python_path="/usr/bin/python3.12")
        cmd = mock_run.call_args[0][0]
        self.assertIn("--python", cmd)
        self.assertIn("/usr/bin/python3.12", cmd)


class RunPipCompileTests(unittest.TestCase):
    """Mock pip-compile invocations."""

    @mock.patch("locklane_resolver.resolver.subprocess.run")
    def test_success_returns_stdout(self, mock_run: mock.Mock) -> None:
        mock_run.return_value = mock.Mock(returncode=0, stdout="click==8.1.7\n", stderr="")
        result = run_pip_compile(Path("/tmp/requirements.txt"))
        self.assertEqual(result, "click==8.1.7\n")

    @mock.patch("locklane_resolver.resolver.subprocess.run")
    def test_failure_raises_resolver_error(self, mock_run: mock.Mock) -> None:
        mock_run.return_value = mock.Mock(returncode=1, stdout="", stderr="pip-compile error")
        with self.assertRaises(ResolverError):
            run_pip_compile(Path("/tmp/requirements.txt"))


class ResolveTests(unittest.TestCase):
    """Fallback and orchestration logic."""

    @mock.patch("locklane_resolver.resolver._detect_tool_version", return_value="uv 0.5.0")
    @mock.patch("locklane_resolver.resolver.run_uv_compile", return_value="click==8.1.7\n")
    @mock.patch("locklane_resolver.resolver.shutil.which", return_value="/usr/bin/uv")
    def test_uv_preferred(self, mock_which: mock.Mock, mock_uv: mock.Mock, mock_ver: mock.Mock) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("click==8.1.7\n", encoding="utf-8")

            raw, tool, version = resolve(manifest, preferred="uv")
            self.assertEqual(tool, "uv")
            self.assertIn("click", raw)

    @mock.patch("locklane_resolver.resolver._detect_tool_version", return_value="pip-compile 7.0")
    @mock.patch("locklane_resolver.resolver.run_pip_compile", return_value="click==8.1.7\n")
    @mock.patch("locklane_resolver.resolver.run_uv_compile", side_effect=ResolverError("uv failed"))
    @mock.patch("locklane_resolver.resolver.shutil.which", return_value="/usr/bin/found")
    def test_fallback_to_pip_compile(
        self,
        mock_which: mock.Mock,
        mock_uv: mock.Mock,
        mock_pip: mock.Mock,
        mock_ver: mock.Mock,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("click==8.1.7\n", encoding="utf-8")

            raw, tool, version = resolve(manifest, preferred="uv")
            self.assertEqual(tool, "pip-tools")

    @mock.patch("locklane_resolver.resolver.shutil.which", return_value=None)
    def test_all_tools_missing_raises(self, mock_which: mock.Mock) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("click==8.1.7\n", encoding="utf-8")

            with self.assertRaises(ResolverError):
                resolve(manifest)

    @mock.patch("locklane_resolver.resolver._detect_tool_version", return_value="uv 0.5.0")
    @mock.patch("locklane_resolver.resolver.run_uv_compile", side_effect=subprocess.TimeoutExpired("uv", 120))
    @mock.patch("locklane_resolver.resolver.run_pip_compile", return_value="click==8.1.7\n")
    @mock.patch("locklane_resolver.resolver.shutil.which", return_value="/usr/bin/found")
    def test_timeout_triggers_fallback(
        self,
        mock_which: mock.Mock,
        mock_uv: mock.Mock,
        mock_pip: mock.Mock,
        mock_ver: mock.Mock,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("click==8.1.7\n", encoding="utf-8")

            raw, tool, version = resolve(manifest, preferred="uv")
            self.assertEqual(tool, "pip-tools")


class DetectVersionTests(unittest.TestCase):
    """Version detection helpers."""

    @mock.patch("locklane_resolver.resolver.subprocess.run")
    def test_detect_python_version(self, mock_run: mock.Mock) -> None:
        mock_run.return_value = mock.Mock(returncode=0, stdout="3.12.1\n", stderr="")
        version = _detect_python_version("/usr/bin/python3")
        self.assertEqual(version, "3.12.1")

    @mock.patch("locklane_resolver.resolver.subprocess.run")
    def test_detect_tool_version(self, mock_run: mock.Mock) -> None:
        mock_run.return_value = mock.Mock(returncode=0, stdout="uv 0.5.0\n", stderr="")
        version = _detect_tool_version("uv")
        self.assertEqual(version, "uv 0.5.0")


if __name__ == "__main__":
    unittest.main()
