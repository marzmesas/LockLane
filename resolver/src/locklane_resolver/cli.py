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

from .cache import compute_cache_key
from .cache import load_cached
from .cache import save_to_cache
from .graph import parse_resolver_output
from .models import DependencyGraph
from .models import ParsedDependency
from .models import ResolverError
from .models import SCHEMA_VERSION
from .models import ToolAvailability
from .models import now_utc_iso
from .resolver import _detect_python_version
from .resolver import resolve
from .planner import compose_upgrade_plan
from .simulator import simulate_candidate

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


def baseline(
    manifest: Path,
    resolver: str,
    *,
    python_path: str | None = None,
    no_cache: bool = False,
    no_resolve: bool = False,
) -> dict[str, Any]:
    """Produce baseline parse, resolution graph, and tooling metadata."""
    dependencies = parse_requirements(manifest)

    payload: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "timestamp_utc": now_utc_iso(),
        "resolver": resolver,
        "status": "ok",
        "manifest_path": str(manifest),
        "dependencies": [dep.to_dict() for dep in dependencies],
        "tooling": tooling_availability(),
        "resolution": None,
        "cache_key": None,
    }

    if no_resolve:
        return payload

    # Check cache
    cache_key = None
    if not no_cache:
        try:
            cache_key = compute_cache_key(manifest, python_path)
            cached = load_cached(cache_key)
            if cached is not None:
                return cached
        except Exception:
            pass  # Cache miss or error — proceed with resolution

    # Run resolver
    try:
        direct_names = {dep.name for dep in dependencies}
        raw_output, tool_name, tool_version = resolve(manifest, preferred=resolver, python_path=python_path)

        py_version = _detect_python_version(python_path or sys.executable)

        packages = parse_resolver_output(raw_output, direct_names)
        graph = DependencyGraph(
            packages=packages,
            resolver_tool=tool_name,
            resolver_version=tool_version,
            python_version=py_version,
            raw_output=raw_output,
        )

        payload["resolution"] = graph.to_dict()
        if cache_key is not None:
            payload["cache_key"] = cache_key.to_dict()

        # Save to cache
        if not no_cache and cache_key is not None:
            try:
                save_to_cache(cache_key, payload)
            except Exception:
                pass  # Cache write failure is non-fatal

    except ResolverError as exc:
        payload["status"] = "error"
        payload["error"] = str(exc)

    return payload


def simulate(
    manifest: Path,
    resolver: str,
    package: str,
    target_version: str,
    *,
    python_path: str | None = None,
    timeout: int = 120,
) -> dict[str, Any]:
    """Simulate resolution with a bumped candidate version."""
    dependencies = parse_requirements(manifest)
    normalized = {dep.name.lower(): dep for dep in dependencies}
    found = normalized.get(package.lower())

    if found is None:
        return {
            "schema_version": SCHEMA_VERSION,
            "timestamp_utc": now_utc_iso(),
            "resolver": resolver,
            "status": "ok",
            "manifest_path": str(manifest),
            "candidate": {"package": package, "target_version": target_version},
            "result": "BLOCKED",
            "explanation": f"Package '{package}' was not found in manifest.",
            "conflict_chain": None,
            "raw_logs": None,
        }

    sim = simulate_candidate(
        manifest_path=manifest,
        dependencies=dependencies,
        package=package,
        target_version=target_version,
        preferred_resolver=resolver,
        python_path=python_path,
        timeout=timeout,
    )

    payload: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "timestamp_utc": now_utc_iso(),
        "resolver": resolver,
        "status": "ok",
        "manifest_path": str(manifest),
        "candidate": {"package": package, "target_version": target_version},
        "result": sim.result,
        "explanation": sim.explanation,
        "conflict_chain": sim.conflict_chain.to_dict() if sim.conflict_chain else None,
        "raw_logs": sim.raw_logs,
    }
    return payload


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


def plan(
    manifest: Path,
    resolver: str,
    *,
    python_path: str | None = None,
    timeout: int = 120,
) -> dict[str, Any]:
    """Compose a full upgrade plan for all pinned dependencies."""
    dependencies = parse_requirements(manifest)

    try:
        plan_data = compose_upgrade_plan(
            manifest_path=manifest,
            dependencies=dependencies,
            resolver=resolver,
            python_path=python_path,
            timeout=timeout,
        )
        status = "ok"
    except Exception as exc:
        plan_data = {
            "manifest_path": str(manifest),
            "resolver": resolver,
            "safe_updates": [],
            "blocked_updates": [],
            "inconclusive_updates": [],
            "ordered_steps": [],
        }
        status = "error"
        plan_data["error"] = str(exc)

    return {
        "schema_version": SCHEMA_VERSION,
        "timestamp_utc": now_utc_iso(),
        "status": status,
        **plan_data,
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
    baseline_parser.add_argument("--python", type=str, default=None, help="Path to Python interpreter for resolution")
    baseline_parser.add_argument("--no-cache", action="store_true", help="Skip cache lookup and storage")
    baseline_parser.add_argument("--no-resolve", action="store_true", help="Parse-only mode — skip resolver invocation")

    simulate_parser = subparsers.add_parser("simulate", help="Simulate one candidate update")
    add_common(simulate_parser)
    simulate_parser.add_argument("--package", required=True, help="Candidate package name")
    simulate_parser.add_argument("--target-version", required=True, help="Candidate target version")
    simulate_parser.add_argument("--python", type=str, default=None, help="Path to Python interpreter for resolution")
    simulate_parser.add_argument("--timeout", type=int, default=120, help="Resolution timeout in seconds")

    verify_parser = subparsers.add_parser("verify", help="Run verification command")
    add_common(verify_parser)
    verify_parser.add_argument(
        "--command",
        default='python -c "import pkgutil; print(\'ok\')"',
        help="Verification command to run in manifest directory",
    )

    plan_parser = subparsers.add_parser("plan", help="Compose upgrade plan for all pinned dependencies")
    add_common(plan_parser)
    plan_parser.add_argument("--python", type=str, default=None, help="Path to Python interpreter for resolution")
    plan_parser.add_argument("--timeout", type=int, default=120, help="Resolution timeout in seconds")

    return parser


def main(argv: list[str] | None = None) -> int:
    """CLI entrypoint."""
    parser = build_parser()
    args = parser.parse_args(argv)

    if not args.manifest.exists():
        parser.error(f"Manifest path does not exist: {args.manifest}")

    payload: dict[str, Any]
    if args.command == "baseline":
        payload = baseline(
            args.manifest,
            args.resolver,
            python_path=args.python,
            no_cache=args.no_cache,
            no_resolve=args.no_resolve,
        )
    elif args.command == "simulate":
        payload = simulate(
            args.manifest,
            args.resolver,
            args.package,
            args.target_version,
            python_path=args.python,
            timeout=args.timeout,
        )
    elif args.command == "plan":
        payload = plan(
            args.manifest,
            args.resolver,
            python_path=args.python,
            timeout=args.timeout,
        )
    else:
        payload = verify(args.manifest, args.resolver, args.command)

    write_json(payload, args.json_out)
    return 0


if __name__ == "__main__":
    sys.exit(main())

