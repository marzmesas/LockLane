#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESOLVER_DIR="$ROOT_DIR/resolver"

cd "$RESOLVER_DIR"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m unittest discover -s tests -p "test_*.py"

