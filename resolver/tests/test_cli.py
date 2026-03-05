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


class PlanPhase4Tests(unittest.TestCase):
    """Phase-4 plan command tests."""

    @mock.patch("locklane_resolver.cli.compose_upgrade_plan")
    def test_plan_command_safe_and_blocked(self, mock_plan: mock.Mock) -> None:
        mock_plan.return_value = {
            "manifest_path": "/tmp/requirements.txt",
            "resolver": "uv",
            "safe_updates": [
                {"package": "click", "from_version": "8.1.7", "to_version": "8.1.8"},
            ],
            "blocked_updates": [
                {"package": "requests", "target_version": "2.31.1", "reason": "conflict"},
            ],
            "inconclusive_updates": [
                {"package": "httpx", "target_version": "0.28.1", "reason": "version missing"},
            ],
            "ordered_steps": [
                {"step": 1, "description": "Apply all 1 safe updates: click 8.1.7->8.1.8"},
            ],
        }

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("click==8.1.7\nrequests==2.31.0\nhttpx==0.28.0\n", encoding="utf-8")

            payload = cli.plan(manifest, "uv")

            self.assertEqual(payload["status"], "ok")
            self.assertIn("schema_version", payload)
            self.assertIn("timestamp_utc", payload)
            self.assertEqual(len(payload["safe_updates"]), 1)
            self.assertEqual(len(payload["blocked_updates"]), 1)
            self.assertEqual(len(payload["inconclusive_updates"]), 1)
            self.assertEqual(len(payload["ordered_steps"]), 1)

    def test_plan_cli_accepts_python_and_timeout_args(self) -> None:
        parser = cli.build_parser()
        args = parser.parse_args([
            "plan",
            "--manifest", "/tmp/test.txt",
            "--python", "/usr/bin/python3",
            "--timeout", "60",
        ])
        self.assertEqual(args.python, "/usr/bin/python3")
        self.assertEqual(args.timeout, 60)
        self.assertEqual(args.command, "plan")

    @mock.patch("locklane_resolver.cli.compose_upgrade_plan")
    def test_plan_json_output_contains_expected_keys(self, mock_plan: mock.Mock) -> None:
        mock_plan.return_value = {
            "manifest_path": "/tmp/requirements.txt",
            "resolver": "uv",
            "safe_updates": [],
            "blocked_updates": [],
            "inconclusive_updates": [],
            "ordered_steps": [],
        }

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("", encoding="utf-8")
            out_file = Path(tmp) / "plan.json"

            exit_code = cli.main([
                "plan",
                "--manifest", str(manifest),
                "--resolver", "uv",
                "--json-out", str(out_file),
            ])

            self.assertEqual(exit_code, 0)
            payload = json.loads(out_file.read_text(encoding="utf-8"))
            for key in ("schema_version", "timestamp_utc", "status",
                        "safe_updates", "blocked_updates", "ordered_steps",
                        "inconclusive_updates", "manifest_path", "resolver"):
                self.assertIn(key, payload, f"Missing key: {key}")


class VerifyPlanPhase5Tests(unittest.TestCase):
    """Phase-5 verify-plan CLI tests."""

    def test_parser_accepts_verify_plan_with_all_flags(self) -> None:
        parser = cli.build_parser()
        args = parser.parse_args([
            "verify-plan",
            "--manifest", "/tmp/test.txt",
            "--plan-json", "/tmp/plan.json",
            "--command", "pytest",
            "--python", "/usr/bin/python3",
            "--timeout", "60",
            "--log-file", "/tmp/verify.log",
        ])
        self.assertEqual(args.command, "verify-plan")
        self.assertEqual(args.plan_json, Path("/tmp/plan.json"))
        self.assertEqual(args.verify_command, "pytest")
        self.assertEqual(args.python, "/usr/bin/python3")
        self.assertEqual(args.timeout, 60)
        self.assertEqual(args.log_file, Path("/tmp/verify.log"))

    @mock.patch("locklane_resolver.cli.run_verify_plan")
    def test_verify_plan_cmd_returns_correct_envelope(self, mock_vp: mock.Mock) -> None:
        from locklane_resolver.verifier import VerificationReport
        mock_vp.return_value = VerificationReport(
            passed=True, steps=[], summary="No safe updates to verify.",
        )

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")
            plan_file = Path(tmp) / "plan.json"
            plan_file.write_text(json.dumps({"safe_updates": []}), encoding="utf-8")

            payload = cli.verify_plan_cmd(manifest, "uv", plan_file)
            for key in ("schema_version", "timestamp_utc", "status",
                        "manifest_path", "plan_path", "resolver", "verification"):
                self.assertIn(key, payload, f"Missing key: {key}")
            self.assertEqual(payload["status"], "ok")

    @mock.patch("locklane_resolver.cli.run_verify_plan")
    def test_main_dispatches_verify_plan(self, mock_vp: mock.Mock) -> None:
        from locklane_resolver.verifier import VerificationReport
        mock_vp.return_value = VerificationReport(
            passed=True, steps=[], summary="No safe updates to verify.",
        )

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")
            plan_file = Path(tmp) / "plan.json"
            plan_file.write_text(json.dumps({"safe_updates": []}), encoding="utf-8")
            out_file = Path(tmp) / "result.json"

            exit_code = cli.main([
                "verify-plan",
                "--manifest", str(manifest),
                "--plan-json", str(plan_file),
                "--json-out", str(out_file),
            ])
            self.assertEqual(exit_code, 0)
            payload = json.loads(out_file.read_text(encoding="utf-8"))
            self.assertIn("verification", payload)
            self.assertEqual(payload["status"], "ok")

    @mock.patch("locklane_resolver.cli.run_verify_plan")
    def test_json_output_contains_all_required_keys(self, mock_vp: mock.Mock) -> None:
        from locklane_resolver.verifier import VerificationReport, VerificationStep
        mock_vp.return_value = VerificationReport(
            passed=False,
            steps=[VerificationStep(
                name="create_venv", command="python -m venv",
                passed=False, exit_code=1, stdout="", stderr="failed",
                duration_seconds=0.5,
            )],
            summary="Venv creation failed.",
            venv_path="/tmp/.venv",
        )

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")
            plan_file = Path(tmp) / "plan.json"
            plan_file.write_text(json.dumps({
                "safe_updates": [{"package": "requests", "from_version": "2.31.0", "to_version": "2.31.1"}],
            }), encoding="utf-8")
            out_file = Path(tmp) / "result.json"

            cli.main([
                "verify-plan",
                "--manifest", str(manifest),
                "--plan-json", str(plan_file),
                "--json-out", str(out_file),
            ])
            payload = json.loads(out_file.read_text(encoding="utf-8"))
            self.assertEqual(payload["status"], "error")
            self.assertIn("steps", payload["verification"])
            self.assertIn("passed", payload["verification"])
            self.assertIn("summary", payload["verification"])

    @mock.patch("locklane_resolver.cli.write_log_file")
    @mock.patch("locklane_resolver.cli.run_verify_plan")
    def test_log_file_produced(self, mock_vp: mock.Mock, mock_log: mock.Mock) -> None:
        from locklane_resolver.verifier import VerificationReport
        mock_vp.return_value = VerificationReport(
            passed=True, steps=[], summary="ok",
        )

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")
            plan_file = Path(tmp) / "plan.json"
            plan_file.write_text(json.dumps({"safe_updates": []}), encoding="utf-8")
            log_file = Path(tmp) / "verify.log"

            cli.verify_plan_cmd(
                manifest, "uv", plan_file, log_file=log_file,
            )
            mock_log.assert_called_once()


class ApplyPhase6Tests(unittest.TestCase):
    """Phase-6 apply CLI tests."""

    def test_parser_accepts_apply_with_all_flags(self) -> None:
        parser = cli.build_parser()
        args = parser.parse_args([
            "apply",
            "--manifest", "/tmp/test.txt",
            "--plan-json", "/tmp/plan.json",
            "--output", "/tmp/updated.txt",
            "--dry-run",
        ])
        self.assertEqual(args.command, "apply")
        self.assertEqual(args.plan_json, Path("/tmp/plan.json"))
        self.assertEqual(args.output, Path("/tmp/updated.txt"))
        self.assertTrue(args.dry_run)

    @mock.patch("locklane_resolver.cli.run_apply_plan")
    def test_apply_cmd_returns_correct_envelope(self, mock_apply: mock.Mock) -> None:
        from locklane_resolver.applier import ApplyResult
        mock_apply.return_value = ApplyResult(
            applied=False,
            manifest_path="/tmp/requirements.txt",
            output_path=None,
            patch_preview="--- a\n+++ b\n",
            updates_applied=[],
            rollback={"original_content": "requests==2.31.0\n", "reverse_updates": []},
        )

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")
            plan_file = Path(tmp) / "plan.json"
            plan_file.write_text(json.dumps({"safe_updates": []}), encoding="utf-8")

            payload = cli.apply_cmd(manifest, plan_file, dry_run=True)
            for key in ("schema_version", "timestamp_utc", "status",
                        "manifest_path", "plan_path", "dry_run", "apply"):
                self.assertIn(key, payload, f"Missing key: {key}")
            self.assertTrue(payload["dry_run"])

    @mock.patch("locklane_resolver.cli.run_apply_plan")
    def test_main_dispatches_apply(self, mock_apply: mock.Mock) -> None:
        from locklane_resolver.applier import ApplyResult
        mock_apply.return_value = ApplyResult(
            applied=False,
            manifest_path="/tmp/requirements.txt",
            output_path=None,
            patch_preview="",
            updates_applied=[],
            rollback={"original_content": "", "reverse_updates": []},
        )

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")
            plan_file = Path(tmp) / "plan.json"
            plan_file.write_text(json.dumps({"safe_updates": []}), encoding="utf-8")
            out_file = Path(tmp) / "result.json"

            exit_code = cli.main([
                "apply",
                "--manifest", str(manifest),
                "--plan-json", str(plan_file),
                "--dry-run",
                "--json-out", str(out_file),
            ])
            self.assertEqual(exit_code, 0)
            payload = json.loads(out_file.read_text(encoding="utf-8"))
            self.assertIn("apply", payload)
            self.assertTrue(payload["dry_run"])

    @mock.patch("locklane_resolver.cli.run_apply_plan")
    def test_dry_run_flag_passed_through(self, mock_apply: mock.Mock) -> None:
        from locklane_resolver.applier import ApplyResult
        mock_apply.return_value = ApplyResult(
            applied=False,
            manifest_path="/tmp/requirements.txt",
            output_path=None,
            patch_preview="",
            updates_applied=[],
            rollback={"original_content": "", "reverse_updates": []},
        )

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")
            plan_file = Path(tmp) / "plan.json"
            plan_file.write_text(json.dumps({"safe_updates": []}), encoding="utf-8")

            cli.apply_cmd(manifest, plan_file, dry_run=True)
            mock_apply.assert_called_once()
            _, kwargs = mock_apply.call_args
            self.assertTrue(kwargs["dry_run"])

    @mock.patch("locklane_resolver.cli.run_apply_plan")
    def test_json_output_contains_all_keys(self, mock_apply: mock.Mock) -> None:
        from locklane_resolver.applier import ApplyResult
        mock_apply.return_value = ApplyResult(
            applied=True,
            manifest_path="/tmp/requirements.txt",
            output_path="/tmp/out.txt",
            patch_preview="-requests==2.31.0\n+requests==2.31.1\n",
            updates_applied=[{"package": "requests", "from_version": "2.31.0", "to_version": "2.31.1"}],
            rollback={"original_content": "requests==2.31.0\n", "reverse_updates": [
                {"package": "requests", "from_version": "2.31.1", "to_version": "2.31.0"},
            ]},
        )

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")
            plan_file = Path(tmp) / "plan.json"
            plan_file.write_text(json.dumps({
                "safe_updates": [{"package": "requests", "from_version": "2.31.0", "to_version": "2.31.1"}],
            }), encoding="utf-8")
            out_file = Path(tmp) / "result.json"

            cli.main([
                "apply",
                "--manifest", str(manifest),
                "--plan-json", str(plan_file),
                "--output", str(Path(tmp) / "out.txt"),
                "--json-out", str(out_file),
            ])
            payload = json.loads(out_file.read_text(encoding="utf-8"))
            self.assertIn("apply", payload)
            apply_data = payload["apply"]
            for key in ("applied", "manifest_path", "output_path",
                        "patch_preview", "updates_applied", "rollback"):
                self.assertIn(key, apply_data, f"Missing apply key: {key}")


if __name__ == "__main__":
    unittest.main()

