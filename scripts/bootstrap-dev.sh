#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

check_cmd() {
  local cmd="$1"
  if command -v "$cmd" >/dev/null 2>&1; then
    echo "[ok] $cmd"
  else
    echo "[missing] $cmd"
  fi
}

echo "Locklane bootstrap checks"
echo "Root: $ROOT_DIR"

check_cmd java
check_cmd python3
check_cmd uv
check_cmd pip-compile

echo
echo "Phase 1 bootstrap complete. Next commands:"
echo "  $ROOT_DIR/scripts/run-resolver-tests.sh"
echo "  $ROOT_DIR/scripts/run-plugin-tests.sh"

