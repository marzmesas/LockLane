"""Unit tests for simulation engine: conflict parsing, manifest rewriting, resolution."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest import mock

from locklane_resolver.models import ResolverError
from locklane_resolver.simulator import (
    ConflictChain,
    ConflictLink,
    SimulationResult,
    _version_in_output,
    create_modified_manifest,
    parse_conflict_chain,
    simulate_candidate,
)


FIXTURE_DIR = Path(__file__).parent / "fixtures"


class ParseConflictChainTests(unittest.TestCase):
    """Tests for parse_conflict_chain()."""

    def test_uv_stderr_format(self) -> None:
        stderr = (FIXTURE_DIR / "conflict_stderr_uv.txt").read_text(encoding="utf-8")
        chain = parse_conflict_chain(stderr)
        self.assertIsNotNone(chain)
        self.assertIsInstance(chain, ConflictChain)
        self.assertTrue(len(chain.links) > 0)
        # Should extract the "Because fastapi depends on starlette" link
        pkg_names = [link.package for link in chain.links]
        self.assertIn("starlette", pkg_names)

    def test_empty_stderr_returns_none(self) -> None:
        self.assertIsNone(parse_conflict_chain(""))
        self.assertIsNone(parse_conflict_chain("   "))

    def test_unrelated_stderr_returns_none(self) -> None:
        self.assertIsNone(parse_conflict_chain("Some unrelated warning text\n"))

    def test_raw_stderr_preserved(self) -> None:
        stderr = (FIXTURE_DIR / "conflict_stderr_uv.txt").read_text(encoding="utf-8")
        chain = parse_conflict_chain(stderr)
        self.assertIsNotNone(chain)
        self.assertEqual(chain.raw_stderr, stderr)

    def test_ansi_codes_stripped(self) -> None:
        stderr = "\x1b[31mBecause foo depends on bar(>=2.0)\x1b[0m"
        chain = parse_conflict_chain(stderr)
        self.assertIsNotNone(chain)
        self.assertEqual(chain.links[0].package, "bar")
        self.assertEqual(chain.links[0].constraint, ">=2.0")
        self.assertEqual(chain.links[0].required_by, "foo")

    def test_to_dict(self) -> None:
        chain = ConflictChain(
            summary="test",
            links=[ConflictLink(package="foo", constraint=">=1.0", required_by="bar")],
            raw_stderr="raw",
        )
        d = chain.to_dict()
        self.assertEqual(d["summary"], "test")
        self.assertEqual(len(d["links"]), 1)
        self.assertEqual(d["links"][0]["package"], "foo")


class CreateModifiedManifestTests(unittest.TestCase):
    """Tests for create_modified_manifest()."""

    def test_line_replacement(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            original = Path(tmp) / "requirements.txt"
            original.write_text("requests==2.31.0\nclick==8.1.7\n", encoding="utf-8")
            dest_dir = Path(tmp) / "out"
            dest_dir.mkdir()

            result = create_modified_manifest(original, [], "requests", "2.31.1", dest_dir)
            content = result.read_text(encoding="utf-8")
            self.assertIn("requests==2.31.1", content)
            self.assertIn("click==8.1.7", content)
            self.assertNotIn("requests==2.31.0", content)

    def test_marker_preservation(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            original = Path(tmp) / "requirements.txt"
            original.write_text('scipy>=1.12.0; python_version >= "3.11"\n', encoding="utf-8")
            dest_dir = Path(tmp) / "out"
            dest_dir.mkdir()

            result = create_modified_manifest(original, [], "scipy", "1.13.0", dest_dir)
            content = result.read_text(encoding="utf-8")
            self.assertIn("scipy==1.13.0", content)
            self.assertIn('python_version >= "3.11"', content)

    def test_comment_preservation(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            original = Path(tmp) / "requirements.txt"
            original.write_text("# Header comment\nrequests==2.31.0\n", encoding="utf-8")
            dest_dir = Path(tmp) / "out"
            dest_dir.mkdir()

            result = create_modified_manifest(original, [], "requests", "2.31.1", dest_dir)
            content = result.read_text(encoding="utf-8")
            self.assertIn("# Header comment", content)

    def test_extras_preserved(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            original = Path(tmp) / "requirements.txt"
            original.write_text("uvicorn[standard]==0.29.0\n", encoding="utf-8")
            dest_dir = Path(tmp) / "out"
            dest_dir.mkdir()

            result = create_modified_manifest(original, [], "uvicorn", "0.30.0", dest_dir)
            content = result.read_text(encoding="utf-8")
            self.assertIn("uvicorn[standard]==0.30.0", content)

    def test_package_not_found_writes_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            original = Path(tmp) / "requirements.txt"
            original.write_text("requests==2.31.0\n", encoding="utf-8")
            dest_dir = Path(tmp) / "out"
            dest_dir.mkdir()

            result = create_modified_manifest(original, [], "nonexistent", "1.0.0", dest_dir)
            content = result.read_text(encoding="utf-8")
            self.assertEqual(content, "requests==2.31.0\n")


class VersionInOutputTests(unittest.TestCase):
    """Tests for _version_in_output()."""

    def test_exact_match(self) -> None:
        output = "requests==2.31.1\nclick==8.1.7\n"
        self.assertTrue(_version_in_output(output, "requests", "2.31.1"))

    def test_different_version(self) -> None:
        output = "requests==2.31.0\n"
        self.assertFalse(_version_in_output(output, "requests", "2.31.1"))

    def test_normalization(self) -> None:
        output = "my-package==1.0.0\n"
        self.assertTrue(_version_in_output(output, "my_package", "1.0.0"))

    def test_package_not_present(self) -> None:
        output = "click==8.1.7\n"
        self.assertFalse(_version_in_output(output, "requests", "2.31.1"))


class SimulateCandidateTests(unittest.TestCase):
    """Tests for simulate_candidate()."""

    @mock.patch("locklane_resolver.simulator.run_uv_compile")
    def test_safe_now_on_success(self, mock_uv: mock.Mock) -> None:
        mock_uv.return_value = "requests==2.31.1\nclick==8.1.7\n"

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\nclick==8.1.7\n", encoding="utf-8")

            result = simulate_candidate(manifest, [], "requests", "2.31.1")
            self.assertEqual(result.result, "SAFE_NOW")
            self.assertIsNone(result.conflict_chain)

    @mock.patch("locklane_resolver.simulator.run_uv_compile")
    def test_blocked_on_resolver_error(self, mock_uv: mock.Mock) -> None:
        stderr = (FIXTURE_DIR / "conflict_stderr_uv.txt").read_text(encoding="utf-8")
        mock_uv.side_effect = ResolverError("failed", stderr=stderr, exit_code=1)

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            result = simulate_candidate(manifest, [], "requests", "2.31.1")
            self.assertEqual(result.result, "BLOCKED")
            self.assertIsNotNone(result.conflict_chain)
            self.assertIsNotNone(result.raw_logs)
            self.assertEqual(result.raw_logs["stderr"], stderr)

    @mock.patch("locklane_resolver.simulator.run_uv_compile")
    def test_inconclusive_on_unexpected_error(self, mock_uv: mock.Mock) -> None:
        mock_uv.side_effect = RuntimeError("unexpected")

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            result = simulate_candidate(manifest, [], "requests", "2.31.1")
            self.assertEqual(result.result, "INCONCLUSIVE")

    @mock.patch("locklane_resolver.simulator.run_uv_compile")
    def test_inconclusive_when_version_not_in_output(self, mock_uv: mock.Mock) -> None:
        mock_uv.return_value = "requests==2.31.0\n"

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            result = simulate_candidate(manifest, [], "requests", "2.31.1")
            self.assertEqual(result.result, "INCONCLUSIVE")

    @mock.patch("locklane_resolver.simulator.run_pip_compile")
    def test_pip_tools_resolver(self, mock_pip: mock.Mock) -> None:
        mock_pip.return_value = "requests==2.31.1\n"

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            result = simulate_candidate(
                manifest, [], "requests", "2.31.1", preferred_resolver="pip-tools",
            )
            self.assertEqual(result.result, "SAFE_NOW")


if __name__ == "__main__":
    unittest.main()
