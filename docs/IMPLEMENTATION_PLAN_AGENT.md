# Locklane Agent Implementation Plan

This document is written as an execution contract for autonomous or semi-autonomous coding agents.

## Objective

Deliver a PyCharm plugin MVP that:

1. analyzes `requirements*.txt` and `constraints.txt`,
2. computes patch-only safe updates using `uv` with `pip-tools` fallback,
3. explains blockers using transitive dependency chains, and
4. verifies the proposed plan before apply.

## Non-Goals (MVP)

- Major/minor update planning as default behavior.
- Poetry/PDM/Pipenv lockfile management.
- Cross-platform matrix simulation.
- Cloud service dependencies.

## Global Rules

1. All simulation runs must occur in disposable directories.
2. File edits in user project happen only during explicit apply.
3. Every phase must end with executable validation.
4. Preserve deterministic output ordering for reproducible diffs.
5. Preserve raw resolver logs for troubleshooting.

## Phase Plan

## Phase 1: Project Bootstrap

### Inputs
- Current `Locklane/` structure.

### Actions
1. Initialize IntelliJ plugin project in `plugin/`.
2. Initialize Python worker package in `resolver/`.
3. Define JSON schema contracts in `schemas/`.
4. Add local scripts for dev bootstrap and smoke checks in `scripts/`.

### Outputs
- Buildable plugin skeleton.
- Installable Python worker package.
- Versioned schema files.

### Exit Criteria
- Plugin starts in sandbox IDE.
- Python worker CLI responds to `--help`.

## Phase 2: Input Parsing and Baseline Resolution

### Inputs
- Requirements and constraints files from a target project.

### Actions
1. Implement parser for requirements syntax (pins, ranges, markers, extras).
2. Implement resolver command: `baseline`.
3. Produce normalized graph JSON from baseline run.
4. Persist baseline result with cache key:
  - interpreter path
  - python version
  - file content hash

### Outputs
- `BaselineResult` JSON artifact.
- Cached baseline state.

### Exit Criteria
- Baseline graph generated successfully for at least 3 sample projects.

## Phase 3: Patch Candidate Simulation

### Inputs
- Baseline graph.

### Actions
1. Enumerate top-level dependency patch targets only.
2. Simulate one-package patch bump attempts.
3. Classify each candidate:
  - `SAFE_NOW`
  - `BLOCKED`
  - `INCONCLUSIVE`
4. Capture resolver failure trace and parse conflict chains.

### Outputs
- Candidate classification table.
- Conflict explanations per blocked candidate.

### Exit Criteria
- At least 90% of blocked cases include machine-parsed conflict chain.

## Phase 4: Plan Composition

### Inputs
- Candidate classification table.

### Actions
1. Compute maximal compatible set from `SAFE_NOW` candidates.
2. Detect `NEEDS_SEQUENCE` candidates with ordered step suggestions.
3. Build `UpgradePlan` JSON:
  - safe updates
  - blocked updates
  - ordered actions
  - expected file modifications

### Outputs
- Deterministic `UpgradePlan`.

### Exit Criteria
- Plan output is stable across repeated runs with same inputs.

## Phase 5: Verification Lane (MVP Required)

### Inputs
- `UpgradePlan`.

### Actions
1. Create disposable verification environment.
2. Install planned dependency set.
3. Run configured verification commands:
  - default: `python -c "import pkgutil; print('ok')"`
  - optional: project-specific smoke/test command.
4. Produce verification report with pass/fail and logs.

### Outputs
- `VerificationReport` JSON + text log file.

### Exit Criteria
- Failed install and failed test cases are both reported with actionable errors.

## Phase 6: Apply Workflow

### Inputs
- Verified `UpgradePlan`.

### Actions
1. Generate human-readable patch preview.
2. Apply updates to requirements/constraints files on user confirmation.
3. Emit rollback patch artifact.

### Outputs
- Updated dependency files.
- Rollback patch.

### Exit Criteria
- Rollback artifact can restore previous file state cleanly.

## Phase 7: UX and Hardening

### Inputs
- Completed end-to-end flow.

### Actions
1. Add PyCharm tool window sections:
  - Overview
  - Safe Updates
  - Blocked Updates
  - Verification
2. Add cancellation, timeout, and retry controls.
3. Add robust private-index and auth environment passthrough.

### Outputs
- User-facing MVP release candidate.

### Exit Criteria
- End-to-end flow validated on real projects with:
  - simple pins
  - ranges and markers
  - at least one private index case

## Agent Execution Checklist

Run in strict sequence:

1. Complete one phase.
2. Execute validation for that phase.
3. Commit phase artifacts.
4. Update this file with observed deviations.
5. Move to next phase.

If blocked:

1. Record blocker in `docs/EXECUTION_LOG.md`.
2. Propose minimum-scope workaround.
3. Continue with unblocked phases where possible.

## Suggested Initial Commands

```bash
cd Locklane
ls -la
```

```bash
cd Locklane/plugin
# initialize gradle/intellij plugin scaffold
```

```bash
cd Locklane/resolver
# initialize python package and test harness
```

## Definition of Done (MVP)

1. User can open tool window and run analysis for a project.
2. Locklane returns patch-only safe updates with blockers explained.
3. Verification lane runs and reports success/failure before apply.
4. User can apply updates and receive rollback artifact.
5. Core flow covered by automated tests in plugin and resolver layers.
