"""Unit tests for PyPI version fetching and patch candidate enumeration."""

from __future__ import annotations

import io
import json
import unittest
from unittest import mock

from locklane_resolver.pypi import (
    PyPIError,
    VersionInfo,
    enumerate_patch_candidates,
    fetch_versions,
    parse_version,
)


class ParseVersionTests(unittest.TestCase):
    """Tests for parse_version()."""

    def test_valid_semver(self) -> None:
        self.assertEqual(parse_version("1.2.3"), VersionInfo(1, 2, 3))
        self.assertEqual(parse_version("0.0.0"), VersionInfo(0, 0, 0))
        self.assertEqual(parse_version("10.20.30"), VersionInfo(10, 20, 30))

    def test_prerelease_rejected(self) -> None:
        self.assertIsNone(parse_version("1.2.3a1"))
        self.assertIsNone(parse_version("1.2.3b2"))
        self.assertIsNone(parse_version("1.2.3rc1"))
        self.assertIsNone(parse_version("1.2.3.dev4"))
        self.assertIsNone(parse_version("1.2.3.post1"))
        self.assertIsNone(parse_version("1.2.3alpha1"))
        self.assertIsNone(parse_version("1.2.3beta2"))

    def test_two_component_rejected(self) -> None:
        self.assertIsNone(parse_version("1.2"))

    def test_four_component_rejected(self) -> None:
        self.assertIsNone(parse_version("1.2.3.4"))

    def test_non_numeric_rejected(self) -> None:
        self.assertIsNone(parse_version("abc"))
        self.assertIsNone(parse_version(""))


class FetchVersionsTests(unittest.TestCase):
    """Tests for fetch_versions() with mocked urllib."""

    @mock.patch("locklane_resolver.pypi.urllib.request.urlopen")
    def test_fetch_versions_returns_release_keys(self, mock_urlopen: mock.Mock) -> None:
        pypi_response = {
            "releases": {
                "2.31.0": [],
                "2.31.1": [],
                "2.32.0": [],
            }
        }
        mock_resp = mock.MagicMock()
        mock_resp.read.return_value = json.dumps(pypi_response).encode("utf-8")
        mock_resp.__enter__ = mock.Mock(return_value=mock_resp)
        mock_resp.__exit__ = mock.Mock(return_value=False)
        mock_urlopen.return_value = mock_resp

        versions = fetch_versions("requests")
        self.assertEqual(sorted(versions), ["2.31.0", "2.31.1", "2.32.0"])

    @mock.patch("locklane_resolver.pypi.urllib.request.urlopen", side_effect=Exception("network error"))
    def test_fetch_versions_raises_pypi_error(self, mock_urlopen: mock.Mock) -> None:
        with self.assertRaises(PyPIError):
            fetch_versions("nonexistent-pkg")


class EnumeratePatchCandidatesTests(unittest.TestCase):
    """Tests for enumerate_patch_candidates()."""

    @mock.patch("locklane_resolver.pypi.fetch_versions")
    def test_filters_by_major_minor(self, mock_fetch: mock.Mock) -> None:
        mock_fetch.return_value = [
            "2.31.0", "2.31.1", "2.31.2", "2.32.0", "3.0.0",
        ]
        result = enumerate_patch_candidates("requests", "2.31.0")
        self.assertEqual(result, ["2.31.1", "2.31.2"])

    @mock.patch("locklane_resolver.pypi.fetch_versions")
    def test_empty_when_no_patches(self, mock_fetch: mock.Mock) -> None:
        mock_fetch.return_value = ["1.0.0", "2.0.0"]
        result = enumerate_patch_candidates("pkg", "1.0.0")
        self.assertEqual(result, [])

    @mock.patch("locklane_resolver.pypi.fetch_versions")
    def test_excludes_prereleases(self, mock_fetch: mock.Mock) -> None:
        mock_fetch.return_value = ["1.0.0", "1.0.1", "1.0.2a1", "1.0.3.dev1"]
        result = enumerate_patch_candidates("pkg", "1.0.0")
        self.assertEqual(result, ["1.0.1"])

    def test_invalid_current_version_returns_empty(self) -> None:
        result = enumerate_patch_candidates("pkg", "not-a-version")
        self.assertEqual(result, [])

    @mock.patch("locklane_resolver.pypi.fetch_versions")
    def test_sorted_ascending(self, mock_fetch: mock.Mock) -> None:
        mock_fetch.return_value = ["1.0.3", "1.0.1", "1.0.2", "1.0.0"]
        result = enumerate_patch_candidates("pkg", "1.0.0")
        self.assertEqual(result, ["1.0.1", "1.0.2", "1.0.3"])


if __name__ == "__main__":
    unittest.main()
