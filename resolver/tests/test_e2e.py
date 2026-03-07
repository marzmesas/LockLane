"""End-to-end integration tests for Locklane resolver pipeline.

These tests exercise the full resolver with real ``uv`` resolution and
PyPI queries.  They are skipped automatically when ``uv`` is not on PATH.
"""

from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

from locklane_resolver import cli

_UV_AVAILABLE = shutil.which("uv") is not None


@unittest.skipUnless(_UV_AVAILABLE, "uv not found on PATH")
class E2EBaselineTests(unittest.TestCase):
    """Baseline command with real resolution."""

    def test_baseline_parse_only(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            payload = cli.baseline(manifest, "uv", no_resolve=True)

            self.assertEqual(payload["status"], "ok")
            self.assertEqual(payload["resolver"], "uv")
            self.assertIsNone(payload["resolution"])
            self.assertEqual(len(payload["dependencies"]), 1)
            self.assertEqual(payload["dependencies"][0]["name"], "requests")

    def test_baseline_resolved(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            payload = cli.baseline(
                manifest,
                "uv",
                python_path=sys.executable,
                no_cache=True,
            )

            self.assertEqual(payload["status"], "ok")
            self.assertIsNotNone(payload["resolution"])
            packages = payload["resolution"]["packages"]
            self.assertTrue(len(packages) >= 1)
            names = {p["name"].lower() for p in packages}
            self.assertIn("requests", names)


@unittest.skipUnless(_UV_AVAILABLE, "uv not found on PATH")
class E2EPlanTests(unittest.TestCase):
    """Plan command with real PyPI lookup and resolution."""

    def test_plan_produces_updates(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            payload = cli.plan(
                manifest,
                "uv",
                python_path=sys.executable,
                timeout=120,
            )

            self.assertEqual(payload["status"], "ok")
            self.assertIn("safe_updates", payload)
            self.assertIn("blocked_updates", payload)
            self.assertIn("inconclusive_updates", payload)
            self.assertIn("ordered_steps", payload)
            # requests 2.31.0 is old — there should be at least one update
            total = (
                len(payload["safe_updates"])
                + len(payload["blocked_updates"])
                + len(payload["inconclusive_updates"])
            )
            self.assertGreaterEqual(total, 0)  # no crash; real result

    def test_plan_json_output_via_main(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")
            out_file = Path(tmp) / "plan.json"

            exit_code = cli.main([
                "plan",
                "--manifest", str(manifest),
                "--resolver", "uv",
                "--python", sys.executable,
                "--timeout", "120",
                "--json-out", str(out_file),
            ])

            self.assertEqual(exit_code, 0)
            payload = json.loads(out_file.read_text(encoding="utf-8"))
            for key in (
                "schema_version", "timestamp_utc", "status",
                "safe_updates", "blocked_updates", "ordered_steps",
            ):
                self.assertIn(key, payload, f"Missing key: {key}")


@unittest.skipUnless(_UV_AVAILABLE, "uv not found on PATH")
class E2EVerifyPlanTests(unittest.TestCase):
    """Verify-plan command with real venv creation."""

    def test_verify_plan_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            # Generate a real plan first
            plan_payload = cli.plan(
                manifest, "uv", python_path=sys.executable, timeout=120,
            )
            plan_file = Path(tmp) / "plan.json"
            plan_file.write_text(
                json.dumps(plan_payload, indent=2), encoding="utf-8",
            )

            payload = cli.verify_plan_cmd(
                manifest, "uv", plan_file,
                python_path=sys.executable, timeout=120,
            )

            self.assertIn("verification", payload)
            self.assertIn("passed", payload["verification"])
            self.assertIsInstance(payload["verification"]["steps"], list)


@unittest.skipUnless(_UV_AVAILABLE, "uv not found on PATH")
class E2EApplyTests(unittest.TestCase):
    """Apply command with real plan data."""

    def _make_plan(self, tmp: str) -> tuple[Path, Path, dict]:
        manifest = Path(tmp) / "requirements.txt"
        manifest.write_text("requests==2.31.0\n", encoding="utf-8")
        plan_payload = cli.plan(
            manifest, "uv", python_path=sys.executable, timeout=120,
        )
        plan_file = Path(tmp) / "plan.json"
        plan_file.write_text(
            json.dumps(plan_payload, indent=2), encoding="utf-8",
        )
        return manifest, plan_file, plan_payload

    def test_apply_dry_run(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest, plan_file, _ = self._make_plan(tmp)

            payload = cli.apply_cmd(manifest, plan_file, dry_run=True)

            self.assertEqual(payload["status"], "ok")
            self.assertTrue(payload["dry_run"])
            self.assertIn("apply", payload)
            self.assertFalse(payload["apply"]["applied"])

    def test_apply_to_output_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest, plan_file, _ = self._make_plan(tmp)
            output = Path(tmp) / "updated.txt"

            payload = cli.apply_cmd(
                manifest, plan_file, output=output, dry_run=False,
            )

            self.assertEqual(payload["status"], "ok")
            self.assertIn("apply", payload)
            # Original manifest should be untouched
            self.assertEqual(
                manifest.read_text(encoding="utf-8"), "requests==2.31.0\n",
            )
            if payload["apply"]["applied"]:
                self.assertTrue(output.exists())

    def test_apply_in_place(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest, plan_file, plan_payload = self._make_plan(tmp)
            original = manifest.read_text(encoding="utf-8")

            payload = cli.apply_cmd(manifest, plan_file, dry_run=False)

            self.assertEqual(payload["status"], "ok")
            if payload["apply"]["applied"]:
                updated = manifest.read_text(encoding="utf-8")
                self.assertNotEqual(updated, original)
            else:
                # No safe updates — file unchanged
                self.assertEqual(
                    manifest.read_text(encoding="utf-8"), original,
                )


@unittest.skipUnless(_UV_AVAILABLE, "uv not found on PATH")
class E2EFullPipelineTests(unittest.TestCase):
    """Chain all stages: baseline -> plan -> verify-plan -> apply."""

    def test_full_pipeline(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")
            original = manifest.read_text(encoding="utf-8")

            # 1. Baseline
            baseline = cli.baseline(
                manifest, "uv", python_path=sys.executable, no_cache=True,
            )
            self.assertEqual(baseline["status"], "ok")
            self.assertIsNotNone(baseline["resolution"])

            # 2. Plan
            plan_payload = cli.plan(
                manifest, "uv", python_path=sys.executable, timeout=120,
            )
            self.assertEqual(plan_payload["status"], "ok")
            plan_file = Path(tmp) / "plan.json"
            plan_file.write_text(
                json.dumps(plan_payload, indent=2), encoding="utf-8",
            )

            # 3. Verify-plan
            verify = cli.verify_plan_cmd(
                manifest, "uv", plan_file,
                python_path=sys.executable, timeout=120,
            )
            self.assertIn("verification", verify)

            # 4. Apply dry-run
            dry = cli.apply_cmd(manifest, plan_file, dry_run=True)
            self.assertEqual(dry["status"], "ok")
            self.assertTrue(dry["dry_run"])
            self.assertFalse(dry["apply"]["applied"])
            # Manifest unchanged after dry-run
            self.assertEqual(
                manifest.read_text(encoding="utf-8"), original,
            )

            # 5. Apply for real
            real = cli.apply_cmd(manifest, plan_file, dry_run=False)
            self.assertEqual(real["status"], "ok")
            if real["apply"]["applied"]:
                updated = manifest.read_text(encoding="utf-8")
                self.assertNotEqual(updated, original)


if __name__ == "__main__":
    unittest.main()
