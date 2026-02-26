"""Command-line entrypoint for Locklane resolver worker."""

from __future__ import annotations

import argparse
import json
import shlex
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

from .models import ParsedDependency
from .models import SCHEMA_VERSION
from .models import ToolAvailability
from .models import now_utc_iso

SUPPORTED_RESOLVERS = {"uv": "uv", "pip-tools": "pip-compile"}


def parse_requirements(manifest_path: Path) -> list[ParsedDependency]:
    """Parse basic dependency spec lines from a requirements-style file."""
    dependencies: list[ParsedDependency] = []

    for line_number, raw in enumerate(manifest_path.read_text(encoding="utf-8").splitlines(), start=1):
        stripped = raw.strip()

        if not stripped or stripped.startswith("#"):
            continue
        if stripped.startswith(("-r ", "--requirement", "-c ", "--constraint", "--index-url", "--extra-index-url")):
            continue
        if stripped.startswith("-"):
            continue

        token = stripped.split(";", 1)[0].strip()
        name = token
        specifier = ""

        for op in ("==", "~=", "!=", ">=", "<=", ">", "<"):
            if op in token:
                idx = token.index(op)
                name = token[:idx].strip()
                specifier = token[idx:].strip()
                break

        # Remove extras for stable package matching in this phase.
        if "[" in name and name.endswith("]"):
            name = name.split("[", 1)[0]

        dependencies.append(
            ParsedDependency(
                name=name,
                specifier=specifier,
                raw_line=stripped,
                line_number=line_number,
            )
        )

    return dependencies


def tooling_availability() -> dict[str, dict[str, Any]]:
    """Discover resolver tooling availability on PATH."""
    result: dict[str, dict[str, Any]] = {}

    for resolver, binary in SUPPORTED_RESOLVERS.items():
        found = shutil.which(binary)
        availability = ToolAvailability(available=found is not None, binary=binary)
        result[resolver] = availability.to_dict()

    return result


def write_json(payload: dict[str, Any], json_out: Path | None) -> None:
    """Write payload to stdout and optional output file."""
    encoded = json.dumps(payload, indent=2, sort_keys=True)
    if json_out is not None:
        json_out.parent.mkdir(parents=True, exist_ok=True)
        json_out.write_text(encoded + "\n", encoding="utf-8")
    print(encoded)


def baseline(manifest: Path, resolver: str) -> dict[str, Any]:
    """Produce baseline parse and tooling metadata."""
    dependencies = parse_requirements(manifest)

    return {
        "schema_version": SCHEMA_VERSION,
        "timestamp_utc": now_utc_iso(),
        "resolver": resolver,
        "status": "ok",
        "manifest_path": str(manifest),
        "dependencies": [dep.to_dict() for dep in dependencies],
        "tooling": tooling_availability(),
    }


def simulate(manifest: Path, resolver: str, package: str, target_version: str) -> dict[str, Any]:
    """Return phase-1 simulation classification for a single candidate."""
    dependencies = parse_requirements(manifest)
    normalized = {dep.name.lower(): dep for dep in dependencies}
    found = normalized.get(package.lower())

    if found is None:
        result = "BLOCKED"
        explanation = f"Package '{package}' was not found in manifest."
    else:
        result = "INCONCLUSIVE"
        explanation = (
            "Simulation engine is not enabled yet in phase 1. "
            "Candidate was detected and queued for phase-3 resolver simulation."
        )

    return {
        "schema_version": SCHEMA_VERSION,
        "timestamp_utc": now_utc_iso(),
        "resolver": resolver,
        "status": "ok",
        "manifest_path": str(manifest),
        "candidate": {
            "package": package,
            "target_version": target_version,
        },
        "result": result,
        "explanation": explanation,
    }


def verify(manifest: Path, resolver: str, command: str) -> dict[str, Any]:
    """Run verification command and return execution metadata."""
    args = shlex.split(command)

    completed = subprocess.run(  # noqa: S603
        args,
        cwd=manifest.parent,
        capture_output=True,
        text=True,
        check=False,
    )
    passed = completed.returncode == 0

    return {
        "schema_version": SCHEMA_VERSION,
        "timestamp_utc": now_utc_iso(),
        "resolver": resolver,
        "status": "ok",
        "manifest_path": str(manifest),
        "verification": {
            "command": command,
            "passed": passed,
            "exit_code": completed.returncode,
            "stdout": completed.stdout,
            "stderr": completed.stderr,
        },
    }


def build_parser() -> argparse.ArgumentParser:
    """Create CLI parser."""
    parser = argparse.ArgumentParser(prog="locklane-resolver", description="Locklane resolver worker")
    subparsers = parser.add_subparsers(dest="command", required=True)

    def add_common(sub: argparse.ArgumentParser) -> None:
        sub.add_argument("--manifest", required=True, type=Path, help="Path to requirements/constraints file")
        sub.add_argument(
            "--resolver",
            default="uv",
            choices=sorted(SUPPORTED_RESOLVERS.keys()),
            help="Resolver implementation to use",
        )
        sub.add_argument("--json-out", type=Path, help="Optional output path for JSON result")

    baseline_parser = subparsers.add_parser("baseline", help="Generate baseline dependency view")
    add_common(baseline_parser)

    simulate_parser = subparsers.add_parser("simulate", help="Simulate one candidate update")
    add_common(simulate_parser)
    simulate_parser.add_argument("--package", required=True, help="Candidate package name")
    simulate_parser.add_argument("--target-version", required=True, help="Candidate target version")

    verify_parser = subparsers.add_parser("verify", help="Run verification command")
    add_common(verify_parser)
    verify_parser.add_argument(
        "--command",
        default='python -c "import pkgutil; print(\'ok\')"',
        help="Verification command to run in manifest directory",
    )

    return parser


def main(argv: list[str] | None = None) -> int:
    """CLI entrypoint."""
    parser = build_parser()
    args = parser.parse_args(argv)

    if not args.manifest.exists():
        parser.error(f"Manifest path does not exist: {args.manifest}")

    payload: dict[str, Any]
    if args.command == "baseline":
        payload = baseline(args.manifest, args.resolver)
    elif args.command == "simulate":
        payload = simulate(args.manifest, args.resolver, args.package, args.target_version)
    else:
        payload = verify(args.manifest, args.resolver, args.command)

    write_json(payload, args.json_out)
    return 0


if __name__ == "__main__":
    sys.exit(main())

