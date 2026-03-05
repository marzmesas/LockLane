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

echo "[1/8] baseline (parse-only)"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver baseline \
  --manifest "$MANIFEST" \
  --resolver uv \
  --no-resolve > /dev/null

echo "[2/8] baseline (resolved)"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver baseline \
  --manifest "$MANIFEST" \
  --resolver uv \
  --no-cache > /dev/null

echo "[3/8] baseline (cached)"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver baseline \
  --manifest "$MANIFEST" \
  --resolver uv > /dev/null

echo "[4/8] simulate (real resolution)"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver simulate \
  --manifest "$MANIFEST" \
  --resolver uv \
  --package requests \
  --target-version 2.31.1 \
  --python "$(which python3)" \
  --timeout 120 > /dev/null

echo "[5/8] simulate (package not in manifest)"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver simulate \
  --manifest "$MANIFEST" \
  --resolver uv \
  --package nonexistent-pkg \
  --target-version 1.0.0 > /dev/null

echo "[6/8] verify"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver verify \
  --manifest "$MANIFEST" \
  --resolver uv \
  --command 'python3 -c "print(\"ok\")"' > /dev/null

echo "[7/8] plan"
PLAN_OUT="$TMP_DIR/plan.json"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver plan \
  --manifest "$MANIFEST" \
  --resolver uv \
  --python "$(which python3)" \
  --timeout 120 \
  --json-out "$PLAN_OUT" > /dev/null

echo "[8/8] verify-plan"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver verify-plan \
  --manifest "$MANIFEST" \
  --resolver uv \
  --plan-json "$PLAN_OUT" \
  --command 'python3 -c "print(\"ok\")"' \
  --python "$(which python3)" \
  --timeout 120 \
  --log-file "$TMP_DIR/verify.log" > /dev/null

echo "Smoke flow passed."
