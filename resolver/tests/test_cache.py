"""Unit tests for baseline resolution cache."""

from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from locklane_resolver.cache import (
    _cache_dir,
    compute_cache_key,
    invalidate,
    load_cached,
    save_to_cache,
)
from locklane_resolver.models import CacheKey


class CacheKeyTests(unittest.TestCase):
    """Cache key computation and determinism."""

    @mock.patch("locklane_resolver.cache.subprocess.run")
    def test_deterministic_key(self, mock_run: mock.Mock) -> None:
        mock_run.return_value = mock.Mock(returncode=0, stdout="3.12.1\n", stderr="")

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"
            manifest.write_text("requests==2.31.0\n", encoding="utf-8")

            key1 = compute_cache_key(manifest, "/usr/bin/python3")
            key2 = compute_cache_key(manifest, "/usr/bin/python3")
            self.assertEqual(key1.to_hex(), key2.to_hex())

    @mock.patch("locklane_resolver.cache.subprocess.run")
    def test_different_content_different_key(self, mock_run: mock.Mock) -> None:
        mock_run.return_value = mock.Mock(returncode=0, stdout="3.12.1\n", stderr="")

        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "requirements.txt"

            manifest.write_text("requests==2.31.0\n", encoding="utf-8")
            key1 = compute_cache_key(manifest, "/usr/bin/python3")

            manifest.write_text("requests==2.32.0\n", encoding="utf-8")
            key2 = compute_cache_key(manifest, "/usr/bin/python3")

            self.assertNotEqual(key1.to_hex(), key2.to_hex())

    def test_to_hex_is_sha256(self) -> None:
        key = CacheKey(
            interpreter_path="/usr/bin/python3",
            python_version="3.12.1",
            manifest_sha256="abc123",
        )
        hex_val = key.to_hex()
        self.assertEqual(len(hex_val), 64)  # SHA-256 hex length


class CacheDirTests(unittest.TestCase):
    """XDG-compliant cache directory."""

    def test_xdg_env_override(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.dict(os.environ, {"XDG_CACHE_HOME": tmp}):
                result = _cache_dir()
                self.assertEqual(result, Path(tmp) / "locklane" / "baselines")

    def test_default_without_xdg(self) -> None:
        with mock.patch.dict(os.environ, {}, clear=False):
            env = os.environ.copy()
            env.pop("XDG_CACHE_HOME", None)
            with mock.patch.dict(os.environ, env, clear=True):
                result = _cache_dir()
                self.assertIn("locklane", str(result))
                self.assertIn("baselines", str(result))


class CacheRoundTripTests(unittest.TestCase):
    """Cache read/write/invalidate."""

    def setUp(self) -> None:
        self._tmp = tempfile.mkdtemp()
        self._patcher = mock.patch("locklane_resolver.cache._cache_dir", return_value=Path(self._tmp))
        self._patcher.start()

    def tearDown(self) -> None:
        self._patcher.stop()
        import shutil
        shutil.rmtree(self._tmp, ignore_errors=True)

    def _make_key(self) -> CacheKey:
        return CacheKey(
            interpreter_path="/usr/bin/python3",
            python_version="3.12.1",
            manifest_sha256="deadbeef",
        )

    def test_miss_returns_none(self) -> None:
        key = self._make_key()
        self.assertIsNone(load_cached(key))

    def test_round_trip(self) -> None:
        key = self._make_key()
        payload = {"status": "ok", "dependencies": []}
        save_to_cache(key, payload)

        loaded = load_cached(key)
        self.assertIsNotNone(loaded)
        self.assertEqual(loaded["status"], "ok")

    def test_invalidation(self) -> None:
        key = self._make_key()
        save_to_cache(key, {"status": "ok"})
        self.assertTrue(invalidate(key))
        self.assertIsNone(load_cached(key))

    def test_invalidate_missing_returns_false(self) -> None:
        key = self._make_key()
        self.assertFalse(invalidate(key))


if __name__ == "__main__":
    unittest.main()
