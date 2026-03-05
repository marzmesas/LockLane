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

echo "[1/10] baseline (parse-only)"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver baseline \
  --manifest "$MANIFEST" \
  --resolver uv \
  --no-resolve > /dev/null

echo "[2/10] baseline (resolved)"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver baseline \
  --manifest "$MANIFEST" \
  --resolver uv \
  --no-cache > /dev/null

echo "[3/10] baseline (cached)"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver baseline \
  --manifest "$MANIFEST" \
  --resolver uv > /dev/null

echo "[4/10] simulate (real resolution)"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver simulate \
  --manifest "$MANIFEST" \
  --resolver uv \
  --package requests \
  --target-version 2.31.1 \
  --python "$(which python3)" \
  --timeout 120 > /dev/null

echo "[5/10] simulate (package not in manifest)"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver simulate \
  --manifest "$MANIFEST" \
  --resolver uv \
  --package nonexistent-pkg \
  --target-version 1.0.0 > /dev/null

echo "[6/10] verify"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver verify \
  --manifest "$MANIFEST" \
  --resolver uv \
  --command 'python3 -c "print(\"ok\")"' > /dev/null

echo "[7/10] plan"
PLAN_OUT="$TMP_DIR/plan.json"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver plan \
  --manifest "$MANIFEST" \
  --resolver uv \
  --python "$(which python3)" \
  --timeout 120 \
  --json-out "$PLAN_OUT" > /dev/null

echo "[8/10] verify-plan"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver verify-plan \
  --manifest "$MANIFEST" \
  --resolver uv \
  --plan-json "$PLAN_OUT" \
  --command 'python3 -c "print(\"ok\")"' \
  --python "$(which python3)" \
  --timeout 120 \
  --log-file "$TMP_DIR/verify.log" > /dev/null

echo "[9/10] apply (dry-run)"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver apply \
  --manifest "$MANIFEST" \
  --resolver uv \
  --plan-json "$PLAN_OUT" \
  --dry-run > /dev/null

echo "[10/10] apply (to output)"
APPLY_OUT="$TMP_DIR/apply_result.json"
PYTHONPATH="$RESOLVER_DIR/src" python3 -m locklane_resolver apply \
  --manifest "$MANIFEST" \
  --resolver uv \
  --plan-json "$PLAN_OUT" \
  --output "$TMP_DIR/updated_requirements.txt" \
  --json-out "$APPLY_OUT" > /dev/null

echo "Smoke flow passed."
