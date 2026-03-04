"""Unit tests for Locklane resolver CLI."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from locklane_resolver import cli
from locklane_resolver.models import ResolverError
from locklane_resolver.simulator import ConflictChain, ConflictLink, SimulationResult


class ResolverCliTests(unittest.TestCase):
    """CLI coverage for phase-1 command behavior."""

    def test_parse_requirements_reads_basic_dependencies(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text(
                "\n".join(
                    [
                        "# comment",
                        "requests==2.31.0",
                        "fastapi>=0.110.0",
                        "--index-url https://example.invalid/simple",
                    ]
                ),
                encoding="utf-8",
            )

            deps = cli.parse_requirements(manifest)
            self.assertEqual([dep.name for dep in deps], ["requests", "fastapi"])

    def test_baseline_emits_dependencies(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            payload = cli.baseline(manifest, "uv", no_resolve=True)
            self.assertEqual(payload["status"], "ok")
            self.assertEqual(payload["resolver"], "uv")
            self.assertEqual(payload["dependencies"][0]["name"], "requests")

    def test_simulate_blocked_when_package_missing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            payload = cli.simulate(manifest, "uv", "httpx", "0.28.1")
            self.assertEqual(payload["result"], "BLOCKED")
            self.assertIsNone(payload["conflict_chain"])
            self.assertIsNone(payload["raw_logs"])

    def test_verify_reports_failure(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            payload = cli.verify(manifest, "uv", 'python -c "raise SystemExit(2)"')
            self.assertFalse(payload["verification"]["passed"])
            self.assertEqual(payload["verification"]["exit_code"], 2)

    def test_main_outputs_json(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")
            out_file = Path(tmp) / "baseline.json"

            exit_code = cli.main(
                [
                    "baseline",
                    "--manifest",
                    str(manifest),
                    "--resolver",
                    "uv",
                    "--no-resolve",
                    "--json-out",
                    str(out_file),
                ]
            )
            self.assertEqual(exit_code, 0)
            payload = json.loads(out_file.read_text(encoding="utf-8"))
            self.assertEqual(payload["status"], "ok")


class BaselineNoResolveTests(unittest.TestCase):
    """--no-resolve flag produces parse-only output."""

    def test_baseline_with_no_resolve_flag(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\nclick==8.1.7\n", encoding="utf-8")

            payload = cli.baseline(manifest, "uv", no_resolve=True)
            self.assertEqual(payload["status"], "ok")
            self.assertIsNone(payload["resolution"])
            self.assertIsNone(payload["cache_key"])
            self.assertEqual(len(payload["dependencies"]), 2)


class BaselineWithResolutionTests(unittest.TestCase):
    """Integration tests with mocked resolver."""

    @mock.patch("locklane_resolver.cli._detect_python_version", return_value="3.12.1")
    @mock.patch("locklane_resolver.cli.resolve")
    def test_baseline_with_resolution(self, mock_resolve: mock.Mock, mock_pyver: mock.Mock) -> None:
        mock_resolve.return_value = (
            "click==8.1.7\n    # via\n    #   -r requirements.txt\ncolorama==0.4.6\n    # via click\n",
            "uv",
            "uv 0.5.0",
        )

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("click==8.1.7\n", encoding="utf-8")

            payload = cli.baseline(manifest, "uv", no_cache=True)
            self.assertEqual(payload["status"], "ok")
            self.assertIsNotNone(payload["resolution"])
            packages = payload["resolution"]["packages"]
            self.assertTrue(len(packages) >= 1)

            click_pkg = next(p for p in packages if p["name"] == "click")
            self.assertTrue(click_pkg["is_direct"])

    @mock.patch("locklane_resolver.cli.resolve", side_effect=ResolverError("all tools failed"))
    def test_baseline_resolver_error(self, mock_resolve: mock.Mock) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("click==8.1.7\n", encoding="utf-8")

            payload = cli.baseline(manifest, "uv", no_cache=True)
            self.assertEqual(payload["status"], "error")
            self.assertIn("all tools failed", payload["error"])


class BaselineCachingTests(unittest.TestCase):
    """Cache round-trip via baseline."""

    @mock.patch("locklane_resolver.cli._detect_python_version", return_value="3.12.1")
    @mock.patch("locklane_resolver.cli.resolve")
    @mock.patch("locklane_resolver.cli.compute_cache_key")
    @mock.patch("locklane_resolver.cli.load_cached")
    @mock.patch("locklane_resolver.cli.save_to_cache")
    def test_baseline_caching_round_trip(
        self,
        mock_save: mock.Mock,
        mock_load: mock.Mock,
        mock_key: mock.Mock,
        mock_resolve: mock.Mock,
        mock_pyver: mock.Mock,
    ) -> None:
        from locklane_resolver.models import CacheKey

        fake_key = CacheKey("/usr/bin/python3", "3.12.1", "abc123")
        mock_key.return_value = fake_key
        mock_load.return_value = None  # Cache miss
        mock_resolve.return_value = (
            "click==8.1.7\n    # via -r requirements.txt\n",
            "uv",
            "uv 0.5.0",
        )

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("click==8.1.7\n", encoding="utf-8")

            payload = cli.baseline(manifest, "uv")
            self.assertEqual(payload["status"], "ok")
            mock_save.assert_called_once()

            # Simulate cache hit
            mock_load.return_value = payload
            cached_payload = cli.baseline(manifest, "uv")
            self.assertEqual(cached_payload, payload)


class SimulatePhase3Tests(unittest.TestCase):
    """Phase-3 simulate integration tests."""

    @mock.patch("locklane_resolver.cli.simulate_candidate")
    def test_safe_now_with_mocked_resolver(self, mock_sim: mock.Mock) -> None:
        mock_sim.return_value = SimulationResult(
            result="SAFE_NOW",
            explanation="Resolution succeeded with requests==2.31.1.",
            raw_logs={"stdout": "requests==2.31.1\n", "stderr": ""},
        )
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            payload = cli.simulate(manifest, "uv", "requests", "2.31.1")
            self.assertEqual(payload["result"], "SAFE_NOW")
            self.assertIsNone(payload["conflict_chain"])

    @mock.patch("locklane_resolver.cli.simulate_candidate")
    def test_blocked_with_conflict_chain(self, mock_sim: mock.Mock) -> None:
        chain = ConflictChain(
            summary="Because foo depends on bar(>=2.0)",
            links=[ConflictLink(package="bar", constraint=">=2.0", required_by="foo")],
            raw_stderr="Because foo depends on bar(>=2.0)",
        )
        mock_sim.return_value = SimulationResult(
            result="BLOCKED",
            explanation="Resolution failed.",
            conflict_chain=chain,
            raw_logs={"stdout": "", "stderr": "Because foo depends on bar(>=2.0)"},
        )
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            payload = cli.simulate(manifest, "uv", "requests", "2.31.1")
            self.assertEqual(payload["result"], "BLOCKED")
            self.assertIsNotNone(payload["conflict_chain"])
            self.assertEqual(payload["conflict_chain"]["links"][0]["package"], "bar")

    def test_package_not_in_manifest_still_blocked(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            payload = cli.simulate(manifest, "uv", "nonexistent", "1.0.0")
            self.assertEqual(payload["result"], "BLOCKED")
            self.assertIn("not found", payload["explanation"])

    def test_simulate_cli_accepts_python_and_timeout_args(self) -> None:
        parser = cli.build_parser()
        args = parser.parse_args([
            "simulate",
            "--manifest", "/tmp/test.txt",
            "--package", "requests",
            "--target-version", "2.31.1",
            "--python", "/usr/bin/python3",
            "--timeout", "60",
        ])
        self.assertEqual(args.python, "/usr/bin/python3")
        self.assertEqual(args.timeout, 60)


if __name__ == "__main__":
    unittest.main()

