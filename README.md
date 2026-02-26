# Locklane

Locklane is a planned PyCharm plugin that computes safe, patch-only Python dependency updates and explains blockers before any file changes are applied.

## Directory Structure

```text
Locklane/
  docs/
    ARCHITECTURE.md
    IMPLEMENTATION_PLAN_AGENT.md
  plugin/
    README.md
    src/
      main/
        kotlin/io/locklane/
        resources/META-INF/
      test/
        kotlin/io/locklane/
  resolver/
    README.md
    src/locklane_resolver/
    tests/
  schemas/
    README.md
  scripts/
    README.md
```

## Scope For MVP

- Resolver stack: `uv` + `pip-tools` fallback
- Policy: patch-only safe updates
- Verification lane included in MVP

See [IMPLEMENTATION_PLAN_AGENT.md](./docs/IMPLEMENTATION_PLAN_AGENT.md) for the execution runbook.

## Phase 1 Bootstrap Status

- Plugin scaffold bootstrapped in `plugin/` with Gradle wrapper and tool window.
- Python resolver worker bootstrapped in `resolver/` with CLI commands:
  - `baseline`
  - `simulate`
  - `verify`
- Initial JSON schemas added in `schemas/`.
- Local developer scripts added in `scripts/`.
