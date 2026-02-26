# Locklane Architecture

## Product Intent

Locklane should answer one question for PyCharm users:
"What patch-level package updates can I apply now without dependency conflicts?"

## System Components

1. Plugin UI Layer (Kotlin, IntelliJ Platform)
- Tool window for plan visualization.
- Actions for simulation, apply, and verification.
- Notifications and editor integrations for requirements files.

2. Planning Core (Kotlin)
- Orchestrates resolver calls.
- Computes `SAFE_NOW`, `BLOCKED`, and `NEEDS_SEQUENCE`.
- Applies patch-only update policy.

3. Resolver Adapter Layer (Kotlin -> Python process)
- Prefers `uv`.
- Falls back to `pip-tools` when needed.
- Runs in isolated temp workspace.

4. Resolver Worker (Python CLI package)
- Thin command surface for:
  - baseline resolution
  - candidate simulation
  - verification run
- Emits structured JSON results.

5. Patch Engine (Kotlin)
- Produces deterministic edits for requirements/constraints files.
- Previews and applies changes.
- Saves rollback patch artifact.

6. Verification Runner
- Creates disposable environment.
- Installs proposed result.
- Executes configured smoke checks and optional tests.
- Returns confidence result and logs.

## Core Data Contracts

1. `InputManifest`
- Parsed dependency declarations from requirements and constraints files.

2. `ResolutionResult`
- Resolver status, lock output, normalized dependency graph, conflict traces.

3. `UpgradeCandidate`
- Package, current version, patch target, simulation result.

4. `UpgradePlan`
- Safe set, blocked set, execution order, expected file edits, verification status.

## Execution Model

1. Parse input files.
2. Build baseline graph.
3. Enumerate patch targets.
4. Simulate each update and classify.
5. Merge to maximal safe patch set.
6. Generate file patch preview.
7. Run verification lane.
8. Apply only on explicit user action.

## Guardrails

- Never mutate user files during simulation.
- Never run network calls from plugin code directly; always through resolver adapter.
- Always keep resolver raw logs for support/debugging.
- Always support cancellation and timeout for long-running operations.
