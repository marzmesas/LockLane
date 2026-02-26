"""Unit tests for Locklane resolver CLI."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from locklane_resolver import cli


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

            payload = cli.baseline(manifest, "uv")
            self.assertEqual(payload["status"], "ok")
            self.assertEqual(payload["resolver"], "uv")
            self.assertEqual(payload["dependencies"][0]["name"], "requests")

    def test_simulate_blocked_when_package_missing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            payload = cli.simulate(manifest, "uv", "httpx", "0.28.1")
            self.assertEqual(payload["result"], "BLOCKED")

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
                    "--json-out",
                    str(out_file),
                ]
            )
            self.assertEqual(exit_code, 0)
            payload = json.loads(out_file.read_text(encoding="utf-8"))
            self.assertEqual(payload["status"], "ok")


if __name__ == "__main__":
    unittest.main()

