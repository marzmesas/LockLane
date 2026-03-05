"""Unit tests for apply engine: patch preview, rollback artifact, apply workflow."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from locklane_resolver.applier import (
    ApplyResult,
    PatchLine,
    apply_plan,
    build_rollback_artifact,
    generate_patch_preview,
)


class PatchPreviewTests(unittest.TestCase):
    """Tests for generate_patch_preview()."""

    def test_single_update_diff(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\nclick==8.1.7\n", encoding="utf-8")

            diff, patches = generate_patch_preview(manifest, [
                {"package": "requests", "from_version": "2.31.0", "to_version": "2.31.1"},
            ])

            self.assertIn("-requests==2.31.0", diff)
            self.assertIn("+requests==2.31.1", diff)
            self.assertEqual(len(patches), 1)
            self.assertEqual(patches[0].package, "requests")
            self.assertEqual(patches[0].from_version, "2.31.0")
            self.assertEqual(patches[0].to_version, "2.31.1")

    def test_multiple_updates(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\nclick==8.1.7\n", encoding="utf-8")

            diff, patches = generate_patch_preview(manifest, [
                {"package": "requests", "from_version": "2.31.0", "to_version": "2.31.1"},
                {"package": "click", "from_version": "8.1.7", "to_version": "8.1.8"},
            ])

            self.assertIn("-requests==2.31.0", diff)
            self.assertIn("+requests==2.31.1", diff)
            self.assertIn("-click==8.1.7", diff)
            self.assertIn("+click==8.1.8", diff)
            self.assertEqual(len(patches), 2)

    def test_markers_and_extras_preserved(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text(
                'uvicorn[standard]==0.29.0 ; python_version >= "3.9"\n',
                encoding="utf-8",
            )

            diff, patches = generate_patch_preview(manifest, [
                {"package": "uvicorn", "from_version": "0.29.0", "to_version": "0.30.0"},
            ])

            self.assertIn("[standard]", diff)
            self.assertIn('python_version >= "3.9"', diff)
            self.assertIn("+uvicorn[standard]==0.30.0", diff)
            self.assertEqual(len(patches), 1)

    def test_missing_package_skipped(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            diff, patches = generate_patch_preview(manifest, [
                {"package": "nonexistent", "from_version": "1.0.0", "to_version": "2.0.0"},
            ])

            self.assertEqual(len(patches), 0)
            self.assertNotIn("-", diff.split("\n", 2)[-1])  # No - lines after header

    def test_empty_updates(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            diff, patches = generate_patch_preview(manifest, [])

            self.assertEqual(len(patches), 0)
            self.assertIn("---", diff)
            self.assertIn("+++", diff)


class RollbackArtifactTests(unittest.TestCase):
    """Tests for build_rollback_artifact()."""

    def test_contains_original_content(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            content = "requests==2.31.0\nclick==8.1.7\n"
            manifest.write_text(content, encoding="utf-8")

            rollback = build_rollback_artifact(manifest, [
                {"package": "requests", "from_version": "2.31.0", "to_version": "2.31.1"},
            ])

            self.assertEqual(rollback["original_content"], content)

    def test_reverse_updates_inverted(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            rollback = build_rollback_artifact(manifest, [
                {"package": "requests", "from_version": "2.31.0", "to_version": "2.31.1"},
            ])

            self.assertEqual(len(rollback["reverse_updates"]), 1)
            rev = rollback["reverse_updates"][0]
            self.assertEqual(rev["from_version"], "2.31.1")
            self.assertEqual(rev["to_version"], "2.31.0")

    def test_has_schema_version_and_timestamp(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            rollback = build_rollback_artifact(manifest, [])

            self.assertIn("schema_version", rollback)
            self.assertIn("created_utc", rollback)
            self.assertIn("manifest_path", rollback)


class ApplyPlanTests(unittest.TestCase):
    """Tests for apply_plan()."""

    def test_dry_run_does_not_modify_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            original = "requests==2.31.0\nclick==8.1.7\n"
            manifest.write_text(original, encoding="utf-8")

            plan_data = {
                "safe_updates": [
                    {"package": "requests", "from_version": "2.31.0", "to_version": "2.31.1"},
                ],
            }

            result = apply_plan(manifest, plan_data, dry_run=True)

            self.assertFalse(result.applied)
            self.assertEqual(manifest.read_text(encoding="utf-8"), original)

    def test_dry_run_returns_preview_and_rollback(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            plan_data = {
                "safe_updates": [
                    {"package": "requests", "from_version": "2.31.0", "to_version": "2.31.1"},
                ],
            }

            result = apply_plan(manifest, plan_data, dry_run=True)

            self.assertIn("-requests==2.31.0", result.patch_preview)
            self.assertIn("+requests==2.31.1", result.patch_preview)
            self.assertIsNotNone(result.rollback)
            self.assertIn("original_content", result.rollback)

    def test_in_place_apply_modifies_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\nclick==8.1.7\n", encoding="utf-8")

            plan_data = {
                "safe_updates": [
                    {"package": "requests", "from_version": "2.31.0", "to_version": "2.31.1"},
                ],
            }

            result = apply_plan(manifest, plan_data)

            self.assertTrue(result.applied)
            content = manifest.read_text(encoding="utf-8")
            self.assertIn("requests==2.31.1", content)
            self.assertIn("click==8.1.7", content)

    def test_output_path_leaves_original_untouched(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            original = "requests==2.31.0\nclick==8.1.7\n"
            manifest.write_text(original, encoding="utf-8")
            output = Path(tmp) / "updated.txt"

            plan_data = {
                "safe_updates": [
                    {"package": "requests", "from_version": "2.31.0", "to_version": "2.31.1"},
                ],
            }

            result = apply_plan(manifest, plan_data, output=output)

            self.assertTrue(result.applied)
            self.assertEqual(manifest.read_text(encoding="utf-8"), original)
            out_content = output.read_text(encoding="utf-8")
            self.assertIn("requests==2.31.1", out_content)

    def test_empty_safe_updates_is_noop(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            original = "requests==2.31.0\n"
            manifest.write_text(original, encoding="utf-8")

            result = apply_plan(manifest, {"safe_updates": []})

            self.assertFalse(result.applied)
            self.assertEqual(manifest.read_text(encoding="utf-8"), original)

    def test_rollback_original_content_restores_exact_original(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            original = "# My deps\nrequests==2.31.0\nclick==8.1.7\n"
            manifest.write_text(original, encoding="utf-8")

            plan_data = {
                "safe_updates": [
                    {"package": "requests", "from_version": "2.31.0", "to_version": "2.31.1"},
                ],
            }

            result = apply_plan(manifest, plan_data)
            self.assertTrue(result.applied)
            self.assertNotEqual(manifest.read_text(encoding="utf-8"), original)

            # Restore from rollback
            manifest.write_text(result.rollback["original_content"], encoding="utf-8")
            self.assertEqual(manifest.read_text(encoding="utf-8"), original)

    def test_comments_and_blanks_preserved(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text(
                "# Header\n\nrequests==2.31.0\n\n# Footer\n",
                encoding="utf-8",
            )

            plan_data = {
                "safe_updates": [
                    {"package": "requests", "from_version": "2.31.0", "to_version": "2.31.1"},
                ],
            }

            result = apply_plan(manifest, plan_data)
            content = manifest.read_text(encoding="utf-8")
            self.assertIn("# Header", content)
            self.assertIn("# Footer", content)
            self.assertIn("requests==2.31.1", content)


if __name__ == "__main__":
    unittest.main()
