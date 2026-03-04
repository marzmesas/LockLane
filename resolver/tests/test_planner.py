"""Unit tests for plan composition engine."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest import mock

from locklane_resolver.models import ParsedDependency
from locklane_resolver.planner import (
    _extract_pinned_version,
    _simulate_combined,
    compose_upgrade_plan,
)
from locklane_resolver.simulator import ConflictChain, ConflictLink, SimulationResult


class ExtractPinnedVersionTests(unittest.TestCase):
    """Tests for _extract_pinned_version()."""

    def test_pinned_version(self) -> None:
        self.assertEqual(_extract_pinned_version("==2.31.0"), "2.31.0")
        self.assertEqual(_extract_pinned_version("==0.0.1"), "0.0.1")

    def test_range_returns_none(self) -> None:
        self.assertIsNone(_extract_pinned_version(">=2.31.0"))
        self.assertIsNone(_extract_pinned_version("~=2.31.0"))
        self.assertIsNone(_extract_pinned_version(">=1.0,<2.0"))

    def test_unpinned_returns_none(self) -> None:
        self.assertIsNone(_extract_pinned_version(""))

    def test_non_semver_pinned_returns_none(self) -> None:
        self.assertIsNone(_extract_pinned_version("==1.2"))
        self.assertIsNone(_extract_pinned_version("==1.2.3.4"))

    def test_whitespace_stripped(self) -> None:
        self.assertEqual(_extract_pinned_version(" ==1.0.0 "), "1.0.0")


class ComposeUpgradePlanTests(unittest.TestCase):
    """Tests for compose_upgrade_plan()."""

    @mock.patch("locklane_resolver.planner.enumerate_patch_candidates")
    @mock.patch("locklane_resolver.planner.simulate_candidate")
    @mock.patch("locklane_resolver.planner._simulate_combined", return_value=True)
    def test_all_safe(
        self, mock_combined: mock.Mock, mock_sim: mock.Mock, mock_enum: mock.Mock,
    ) -> None:
        mock_enum.side_effect = lambda pkg, ver, **kw: {
            "requests": ["2.31.1", "2.31.2"],
            "click": ["8.1.8"],
        }.get(pkg, [])

        mock_sim.side_effect = lambda **kw: SimulationResult(
            result="SAFE_NOW",
            explanation=f"Resolution succeeded with {kw['package']}=={kw['target_version']}.",
        )

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("click==8.1.7\nrequests==2.31.0\n", encoding="utf-8")

            deps = [
                ParsedDependency("click", "==8.1.7", "click==8.1.7", 1),
                ParsedDependency("requests", "==2.31.0", "requests==2.31.0", 2),
            ]

            result = compose_upgrade_plan(manifest, deps, "uv")

            self.assertEqual(len(result["safe_updates"]), 2)
            self.assertEqual(len(result["blocked_updates"]), 0)
            self.assertEqual(len(result["inconclusive_updates"]), 0)
            # Combined check succeeds => single step
            self.assertEqual(len(result["ordered_steps"]), 1)
            self.assertIn("2 safe updates", result["ordered_steps"][0]["description"])

            # Verify deterministic ordering (alphabetical by package name)
            packages = [u["package"] for u in result["safe_updates"]]
            self.assertEqual(packages, ["click", "requests"])

            # Highest candidate picked
            self.assertEqual(result["safe_updates"][0]["to_version"], "8.1.8")
            self.assertEqual(result["safe_updates"][1]["to_version"], "2.31.2")

    @mock.patch("locklane_resolver.planner.enumerate_patch_candidates")
    @mock.patch("locklane_resolver.planner.simulate_candidate")
    def test_mixed_results(
        self, mock_sim: mock.Mock, mock_enum: mock.Mock,
    ) -> None:
        mock_enum.side_effect = lambda pkg, ver, **kw: {
            "click": ["8.1.8"],
            "requests": ["2.31.1"],
        }.get(pkg, [])

        chain = ConflictChain(
            summary="conflict", links=[ConflictLink("bar", ">=2.0", "foo")],
        )

        def sim_side_effect(**kw: object) -> SimulationResult:
            if kw["package"] == "click":
                return SimulationResult(result="SAFE_NOW", explanation="ok")
            return SimulationResult(
                result="BLOCKED", explanation="conflict", conflict_chain=chain,
            )

        mock_sim.side_effect = sim_side_effect

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("click==8.1.7\nrequests==2.31.0\n", encoding="utf-8")

            deps = [
                ParsedDependency("click", "==8.1.7", "click==8.1.7", 1),
                ParsedDependency("requests", "==2.31.0", "requests==2.31.0", 2),
            ]

            result = compose_upgrade_plan(manifest, deps, "uv")

            self.assertEqual(len(result["safe_updates"]), 1)
            self.assertEqual(result["safe_updates"][0]["package"], "click")
            self.assertEqual(len(result["blocked_updates"]), 1)
            self.assertEqual(result["blocked_updates"][0]["package"], "requests")
            self.assertIn("conflict_chain", result["blocked_updates"][0])

    @mock.patch("locklane_resolver.planner.enumerate_patch_candidates")
    @mock.patch("locklane_resolver.planner.simulate_candidate")
    def test_no_pinned_deps(
        self, mock_sim: mock.Mock, mock_enum: mock.Mock,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests>=2.31.0\n", encoding="utf-8")

            deps = [
                ParsedDependency("requests", ">=2.31.0", "requests>=2.31.0", 1),
            ]

            result = compose_upgrade_plan(manifest, deps, "uv")

            self.assertEqual(result["safe_updates"], [])
            self.assertEqual(result["blocked_updates"], [])
            self.assertEqual(result["ordered_steps"], [])
            mock_sim.assert_not_called()
            mock_enum.assert_not_called()

    @mock.patch("locklane_resolver.planner.enumerate_patch_candidates")
    @mock.patch("locklane_resolver.planner.simulate_candidate")
    def test_empty_manifest(
        self, mock_sim: mock.Mock, mock_enum: mock.Mock,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("", encoding="utf-8")

            result = compose_upgrade_plan(manifest, [], "uv")

            self.assertEqual(result["safe_updates"], [])
            self.assertEqual(result["blocked_updates"], [])
            self.assertEqual(result["inconclusive_updates"], [])
            self.assertEqual(result["ordered_steps"], [])

    @mock.patch("locklane_resolver.planner.enumerate_patch_candidates")
    @mock.patch("locklane_resolver.planner.simulate_candidate")
    @mock.patch("locklane_resolver.planner._simulate_combined", return_value=False)
    def test_combined_failure_yields_individual_steps(
        self, mock_combined: mock.Mock, mock_sim: mock.Mock, mock_enum: mock.Mock,
    ) -> None:
        mock_enum.side_effect = lambda pkg, ver, **kw: ["1.0.1"]
        mock_sim.side_effect = lambda **kw: SimulationResult(
            result="SAFE_NOW", explanation="ok",
        )

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("alpha==1.0.0\nbeta==1.0.0\n", encoding="utf-8")

            deps = [
                ParsedDependency("alpha", "==1.0.0", "alpha==1.0.0", 1),
                ParsedDependency("beta", "==1.0.0", "beta==1.0.0", 2),
            ]

            result = compose_upgrade_plan(manifest, deps, "uv")

            self.assertEqual(len(result["safe_updates"]), 2)
            # Combined failed => individual steps
            self.assertEqual(len(result["ordered_steps"]), 2)
            self.assertEqual(result["ordered_steps"][0]["step"], 1)
            self.assertEqual(result["ordered_steps"][1]["step"], 2)

    @mock.patch("locklane_resolver.planner.enumerate_patch_candidates")
    @mock.patch("locklane_resolver.planner.simulate_candidate")
    def test_determinism(
        self, mock_sim: mock.Mock, mock_enum: mock.Mock,
    ) -> None:
        """Same inputs produce identical output (ignoring external state)."""
        mock_enum.side_effect = lambda pkg, ver, **kw: ["1.0.1"]
        mock_sim.side_effect = lambda **kw: SimulationResult(
            result="SAFE_NOW", explanation="ok",
        )

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("beta==1.0.0\nalpha==1.0.0\n", encoding="utf-8")

            deps = [
                ParsedDependency("beta", "==1.0.0", "beta==1.0.0", 1),
                ParsedDependency("alpha", "==1.0.0", "alpha==1.0.0", 2),
            ]

            r1 = compose_upgrade_plan(manifest, deps, "uv")
            r2 = compose_upgrade_plan(manifest, deps, "uv")

            # Safe updates sorted alphabetically regardless of input order
            self.assertEqual(
                [u["package"] for u in r1["safe_updates"]],
                ["alpha", "beta"],
            )
            self.assertEqual(r1["safe_updates"], r2["safe_updates"])
            self.assertEqual(r1["blocked_updates"], r2["blocked_updates"])
            self.assertEqual(r1["ordered_steps"], r2["ordered_steps"])

    @mock.patch("locklane_resolver.planner.enumerate_patch_candidates")
    @mock.patch("locklane_resolver.planner.simulate_candidate")
    def test_inconclusive_result(
        self, mock_sim: mock.Mock, mock_enum: mock.Mock,
    ) -> None:
        mock_enum.return_value = ["1.0.1"]
        mock_sim.return_value = SimulationResult(
            result="INCONCLUSIVE",
            explanation="Version not found in output.",
        )

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("pkg==1.0.0\n", encoding="utf-8")

            deps = [ParsedDependency("pkg", "==1.0.0", "pkg==1.0.0", 1)]

            result = compose_upgrade_plan(manifest, deps, "uv")

            self.assertEqual(len(result["inconclusive_updates"]), 1)
            self.assertEqual(result["inconclusive_updates"][0]["package"], "pkg")


class SimulateCombinedTests(unittest.TestCase):
    """Tests for _simulate_combined()."""

    @mock.patch("locklane_resolver.planner.run_uv_compile", return_value="ok")
    def test_combined_success(self, mock_uv: mock.Mock) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\nclick==8.1.7\n", encoding="utf-8")

            deps = [
                ParsedDependency("requests", "==2.31.0", "requests==2.31.0", 1),
                ParsedDependency("click", "==8.1.7", "click==8.1.7", 2),
            ]
            updates = [
                {"package": "requests", "from_version": "2.31.0", "to_version": "2.31.1"},
                {"package": "click", "from_version": "8.1.7", "to_version": "8.1.8"},
            ]

            ok = _simulate_combined(manifest, updates, deps, "uv", None)
            self.assertTrue(ok)

    @mock.patch("locklane_resolver.planner.run_uv_compile")
    def test_combined_failure(self, mock_uv: mock.Mock) -> None:
        from locklane_resolver.models import ResolverError

        mock_uv.side_effect = ResolverError("conflict", stderr="err", exit_code=1)

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\nclick==8.1.7\n", encoding="utf-8")

            deps = [
                ParsedDependency("requests", "==2.31.0", "requests==2.31.0", 1),
                ParsedDependency("click", "==8.1.7", "click==8.1.7", 2),
            ]
            updates = [
                {"package": "requests", "from_version": "2.31.0", "to_version": "2.31.1"},
                {"package": "click", "from_version": "8.1.7", "to_version": "8.1.8"},
            ]

            ok = _simulate_combined(manifest, updates, deps, "uv", None)
            self.assertFalse(ok)


if __name__ == "__main__":
    unittest.main()
