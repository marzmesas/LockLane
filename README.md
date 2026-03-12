# LockLane

[![GitHub Release](https://img.shields.io/github/v/release/marzmesas/LockLane?logo=github)](https://github.com/marzmesas/LockLane/releases)
[![Build](https://img.shields.io/github/actions/workflow/status/marzmesas/LockLane/build.yml?logo=github)](https://github.com/marzmesas/LockLane/actions/workflows/build.yml)
[![License](https://img.shields.io/github/license/marzmesas/LockLane)](LICENSE)

A JetBrains IDE plugin that plans safe Python dependency upgrades with conflict-aware guidance. Supports `requirements.txt`, `requirements.in`, and `pyproject.toml` (PEP 621 and Poetry).

## Features

- **Baseline view** — see all current pinned and resolved dependency versions at a glance
- **Upgrade planning** — scans dependencies for available updates and classifies each as safe, blocked, or inconclusive
- **Conflict detection** — shows exactly which transitive dependencies block an upgrade, with full conflict chain details
- **Vulnerability scanning** — checks dependencies against the OSV database for known security issues
- **Changelog links** — quick links to changelogs and project pages from the plan view
- **Per-package selective apply** — choose exactly which updates to apply with checkboxes
- **Verification** — runs your test suite against proposed changes in a disposable venv before touching any files
- **Apply with dry-run** — preview changes before writing, with automatic lock file regeneration
- **Rollback history** — restore previous manifest versions if an update causes issues
- **Gutter icons** — see update status (patch/minor/major) next to each dependency in the editor
- **Auto-scan** — automatically checks for updates when you open a project
- **Ignore list** — exclude packages you don't want to upgrade
- **Resolver flexibility** — uses `uv` by default with `pip-tools` as automatic fallback

## Installation

**Manual install:**
Download the latest `.zip` from [Releases](https://github.com/marzmesas/LockLane/releases), then go to Settings > Plugins > gear icon > Install Plugin from Disk.

## Usage

1. Open a project that contains a `requirements.in`, `requirements.txt`, or `pyproject.toml`
2. Open the **LockLane** tool window (right panel)
3. **Select Manifest** — pick your requirements file (or let auto-detection find it)
4. **Baseline** — view current dependency versions
5. **Run Plan** — generates an upgrade plan showing safe, blocked, and inconclusive updates
6. **Verify Plan** (optional) — runs verification against the proposed changes
7. **Apply** — writes the approved upgrades to your manifest with dry-run preview

## Settings

Available under **Settings > Tools > LockLane**:

| Setting | Description | Default |
|---------|-------------|---------|
| Python interpreter path | Path to `python3`. Leave empty for auto-detection (venv, then PATH) | Auto-detect |
| Resolver | `uv` or `pip-tools` | `uv` |
| Timeout (seconds) | Max time for resolver operations | 120 |
| Resolver source path | Override bundled resolver with a local path (for development) | Bundled |
| Extra index URLs | Private package index URLs, one per line | — |
| Ignored packages | Package names to exclude from plans, one per line | — |
| Auto-scan | Scan dependencies on project open | Enabled |
| Verify command | Custom verification command (e.g., `pytest`) | — |

Use **Validate Setup** in settings to check that Python and resolver tools are correctly configured.

## Requirements

- JetBrains IDE (PyCharm, IntelliJ IDEA, etc.) **2024.2** or later
- Python 3 on PATH (or configured in settings)
- [`uv`](https://docs.astral.sh/uv/) or [`pip-tools`](https://pip-tools.readthedocs.io/) installed

The Python resolver is bundled inside the plugin — no extra setup needed beyond the above.

## Building from source

Prerequisites: JDK 17+, Python 3.10+

```bash
cd plugin

# Run tests
./gradlew test

# Build the plugin
./gradlew buildPlugin

# Launch a sandbox IDE with the plugin loaded
./gradlew runIde
```

## License

[Apache License 2.0](LICENSE)
