"""Tests for uv.lock parsing."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from locklane_resolver.uv_lock import parse_uv_lock


SAMPLE = """\
version = 1
requires-python = ">=3.12"

[[package]]
name = "fastapi"
version = "0.128.8"
source = { registry = "https://pypi.org/simple" }

[[package]]
name = "pydantic"
version = "2.11.2"
source = { registry = "https://pypi.org/simple" }

[[package]]
name = "Starlette"
version = "0.40.0"
source = { registry = "https://pypi.org/simple" }
"""


class ParseUvLockTests(unittest.TestCase):

    def _write(self, tmp: str, content: str) -> Path:
        path = Path(tmp) / "uv.lock"
        path.write_text(content, encoding="utf-8")
        return path

    def test_returns_name_to_version_map(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = self._write(tmp, SAMPLE)
            locks = parse_uv_lock(path)
            self.assertEqual(locks["fastapi"], "0.128.8")
            self.assertEqual(locks["pydantic"], "2.11.2")

    def test_package_names_are_lowercased(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = self._write(tmp, SAMPLE)
            locks = parse_uv_lock(path)
            self.assertIn("starlette", locks)
            self.assertNotIn("Starlette", locks)

    def test_missing_file_returns_empty(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            locks = parse_uv_lock(Path(tmp) / "missing.lock")
            self.assertEqual(locks, {})

    def test_malformed_toml_returns_empty(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = self._write(tmp, "not valid = toml = junk")
            self.assertEqual(parse_uv_lock(path), {})

    def test_entries_without_version_are_skipped(self) -> None:
        body = """\
[[package]]
name = "complete"
version = "1.0.0"

[[package]]
name = "nover"
"""
        with tempfile.TemporaryDirectory() as tmp:
            path = self._write(tmp, body)
            locks = parse_uv_lock(path)
            self.assertEqual(locks, {"complete": "1.0.0"})

    def test_empty_package_array_returns_empty(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = self._write(tmp, "version = 1\n")
            self.assertEqual(parse_uv_lock(path), {})


if __name__ == "__main__":
    unittest.main()
