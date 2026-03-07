# Locklane

A JetBrains IDE plugin that plans safe Python dependency upgrades for requirements files. It analyses pinned dependencies, simulates upgrades, detects conflicts, and generates verified update plans — all from inside your editor.

## Features

- **Upgrade planning** — scans all pinned dependencies for available updates and classifies each as safe, blocked, or inconclusive
- **Conflict detection** — shows exactly which transitive dependencies block an upgrade, with full conflict chain details
- **Verification** — runs your test suite against proposed changes in a disposable venv before touching any files
- **Apply** — writes the approved upgrades to your requirements file, with dry-run support
- **Resolver flexibility** — uses `uv` by default with `pip-tools` as automatic fallback

## Requirements

- IntelliJ-based IDE (PyCharm, IntelliJ IDEA, etc.) 2025.2+
- Python 3 on PATH (or configured in settings)
- [`uv`](https://docs.astral.sh/uv/) or [`pip-tools`](https://pip-tools.readthedocs.io/) installed

The Python resolver is bundled inside the plugin — no extra setup needed beyond the above.

## Usage

1. Open a project that contains a `requirements.in` (or similar manifest)
2. Open the **Locklane** tool window (right panel)
3. **Select Manifest** — pick your requirements file
4. **Run Plan** — generates an upgrade plan showing safe, blocked, and inconclusive updates
5. **Verify Plan** (optional) — runs verification against the proposed changes
6. **Apply** — writes the approved upgrades to your lockfile

## Settings

Available under **Settings > Tools > Locklane**:

| Setting | Description | Default |
|---------|-------------|---------|
| Python interpreter path | Path to `python3`. Leave empty for auto-detection (venv, then PATH) | Auto-detect |
| Resolver | `uv` or `pip-tools` | `uv` |
| Timeout (seconds) | Max time for resolver operations | 120 |
| Resolver source path | Override bundled resolver with a local path (for development) | Bundled |
| Extra index URLs | Private package index URLs, one per line | — |
| Verify command | Custom verification command (e.g., `pytest`) | — |

## Development

### Prerequisites

- JDK 17+
- Python 3.10+
- `uv` and/or `pip-tools`

### Project structure

```text
Locklane/
  plugin/              # Kotlin/JetBrains plugin
    build.gradle.kts
    src/main/kotlin/io/locklane/
      action/          # Toolbar actions (select, plan, verify, apply)
      model/           # Data classes matching resolver JSON contracts
      service/         # ResolverService, ProcessRunner, PythonDiscovery
      settings/        # Persistent project settings
      ui/              # Tool window panels and tables
    src/test/kotlin/io/locklane/
  resolver/            # Python resolver worker
    src/locklane_resolver/
      cli.py           # CLI entry point (baseline, plan, verify-plan, apply, simulate, verify)
      planner.py       # Upgrade plan composition
      resolver.py      # uv/pip-tools subprocess invocation
      simulator.py     # Single-candidate upgrade simulation
      verifier.py      # Verification lane execution
      applier.py       # Lockfile update writer
      graph.py         # Dependency graph analysis
      cache.py         # Resolution caching
      pypi.py          # PyPI version queries
      models.py        # Shared data models
    tests/
  schemas/             # JSON schema contracts between plugin and resolver
  scripts/             # Dev scripts (bootstrap, test runners, smoke test)
  docs/                # Architecture docs and implementation plan
```

### Build and run

```bash
cd plugin

# Run the plugin in a sandboxed IDE
./gradlew runIde

# Run unit tests
./gradlew test

# Run integration tests (requires Python + resolver tools)
./gradlew integrationTest

# Build distributable
./gradlew build
```

The build automatically bundles the Python resolver sources from `resolver/src/` into the plugin JAR under `bundled_resolver/`. At runtime, these are extracted to a cache directory on first use.

### Resolver CLI (standalone)

The resolver can also be used independently:

```bash
cd resolver
python -m locklane_resolver plan --manifest path/to/requirements.in --resolver uv --python python3
```

Available commands: `baseline`, `plan`, `verify-plan`, `apply`, `simulate`, `verify`.

## Architecture

The plugin has two layers communicating via JSON over subprocess I/O:

- **Plugin (Kotlin)** — UI, state management, background task orchestration, settings
- **Resolver (Python)** — dependency resolution, upgrade simulation, conflict detection, verification

See [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md) for details.

## License

Copyright Mario Mesas. All rights reserved.
