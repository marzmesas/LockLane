#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESOLVER_DIR="$ROOT_DIR/resolver"
TMP_DIR="$(mktemp -d)"
MANIFEST="$TMP_DIR/requirements.txt"

trap 'rm -rf "$TMP_DIR"' EXIT

cat > "$MANIFEST" <<'EOF'
requests==2.31.0
fastapi==0.115.0
EOF

echo "[1/5] baseline (parse-only)"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver baseline \
  --manifest "$MANIFEST" \
  --resolver uv \
  --no-resolve > /dev/null

echo "[2/5] baseline (resolved)"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver baseline \
  --manifest "$MANIFEST" \
  --resolver uv \
  --no-cache > /dev/null

echo "[3/5] baseline (cached)"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver baseline \
  --manifest "$MANIFEST" \
  --resolver uv > /dev/null

echo "[4/5] simulate"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver simulate \
  --manifest "$MANIFEST" \
  --resolver uv \
  --package requests \
  --target-version 2.31.1 > /dev/null

echo "[5/5] verify"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver verify \
  --manifest "$MANIFEST" \
  --resolver uv \
  --command 'python3 -c "print(\"ok\")"' > /dev/null

echo "Smoke flow passed."
