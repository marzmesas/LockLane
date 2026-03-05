"""Apply engine: patch preview generation, rollback artifact, manifest writing."""

from __future__ import annotations

import os
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .models import SCHEMA_VERSION, now_utc_iso
from .simulator import _find_dependency_line, _build_replacement_line


# ---------------------------------------------------------------------------
# Data types
# ---------------------------------------------------------------------------

@dataclass(slots=True)
class PatchLine:
    """A single line-level change in the patch preview."""

    line_number: int
    old_line: str
    new_line: str
    package: str
    from_version: str
    to_version: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "line_number": self.line_number,
            "old_line": self.old_line,
            "new_line": self.new_line,
            "package": self.package,
            "from_version": self.from_version,
            "to_version": self.to_version,
        }


@dataclass(slots=True)
class ApplyResult:
    """Result of an apply operation."""

    applied: bool
    manifest_path: str
    output_path: str | None
    patch_preview: str
    updates_applied: list[dict[str, str]]
    rollback: dict[str, Any] | None

    def to_dict(self) -> dict[str, Any]:
        return {
            "applied": self.applied,
            "manifest_path": self.manifest_path,
            "output_path": self.output_path,
            "patch_preview": self.patch_preview,
            "updates_applied": self.updates_applied,
            "rollback": self.rollback,
        }


# ---------------------------------------------------------------------------
# Core functions
# ---------------------------------------------------------------------------

def generate_patch_preview(
    manifest_path: Path,
    safe_updates: list[dict[str, str]],
) -> tuple[str, list[PatchLine]]:
    """Generate a unified-diff-style preview and structured patch lines.

    Returns ``(diff_text, patch_lines)`` where *diff_text* is a
    human-readable unified diff and *patch_lines* is a list of
    :class:`PatchLine` instances.
    """
    lines = manifest_path.read_text(encoding="utf-8").splitlines(keepends=True)
    patch_lines: list[PatchLine] = []
    diff_parts: list[str] = [
        f"--- {manifest_path.name}\n",
        f"+++ {manifest_path.name}\n",
    ]

    for update in safe_updates:
        pkg = update["package"]
        from_ver = update["from_version"]
        to_ver = update["to_version"]

        match = _find_dependency_line(lines, pkg)
        if match is None:
            continue

        idx, stripped = match
        new_content = _build_replacement_line(stripped, pkg, to_ver)

        patch_lines.append(PatchLine(
            line_number=idx + 1,
            old_line=stripped,
            new_line=new_content,
            package=pkg,
            from_version=from_ver,
            to_version=to_ver,
        ))

        diff_parts.append(f"@@ -{idx + 1},1 +{idx + 1},1 @@\n")
        diff_parts.append(f"-{stripped}\n")
        diff_parts.append(f"+{new_content}\n")

    return "".join(diff_parts), patch_lines


def build_rollback_artifact(
    manifest_path: Path,
    safe_updates: list[dict[str, str]],
) -> dict[str, Any]:
    """Build a rollback artifact containing the original file content.

    The artifact stores the full original content (for exact restore) and
    a ``reverse_updates`` list (from/to swapped, for human readability).
    """
    original_content = manifest_path.read_text(encoding="utf-8")

    reverse_updates = [
        {
            "package": u["package"],
            "from_version": u["to_version"],
            "to_version": u["from_version"],
        }
        for u in safe_updates
    ]

    return {
        "schema_version": SCHEMA_VERSION,
        "created_utc": now_utc_iso(),
        "manifest_path": str(manifest_path),
        "original_content": original_content,
        "reverse_updates": reverse_updates,
    }


def apply_plan(
    manifest_path: Path,
    plan_data: dict[str, Any],
    *,
    output: Path | None = None,
    dry_run: bool = False,
) -> ApplyResult:
    """Orchestrate apply: preview -> rollback -> write.

    If *dry_run* is ``True``, returns preview and rollback without writing.
    If *output* is provided, writes the modified manifest there.
    Otherwise writes in-place using atomic ``os.replace()``.
    """
    safe_updates: list[dict[str, str]] = plan_data.get("safe_updates", [])

    # Generate preview and rollback regardless of dry_run
    preview_text, patch_lines = generate_patch_preview(manifest_path, safe_updates)
    rollback = build_rollback_artifact(manifest_path, safe_updates)

    updates_applied = [
        {"package": p.package, "from_version": p.from_version, "to_version": p.to_version}
        for p in patch_lines
    ]

    if not safe_updates or not patch_lines:
        return ApplyResult(
            applied=False,
            manifest_path=str(manifest_path),
            output_path=str(output) if output else None,
            patch_preview=preview_text,
            updates_applied=updates_applied,
            rollback=rollback,
        )

    if dry_run:
        return ApplyResult(
            applied=False,
            manifest_path=str(manifest_path),
            output_path=str(output) if output else None,
            patch_preview=preview_text,
            updates_applied=updates_applied,
            rollback=rollback,
        )

    # Build modified manifest content using verifier's iterative rewrite
    from .verifier import build_modified_manifest
    from .cli import parse_requirements

    dependencies = parse_requirements(manifest_path)
    dest_dir = Path(tempfile.mkdtemp(prefix="locklane-apply-"))
    try:
        modified = build_modified_manifest(manifest_path, safe_updates, dependencies, dest_dir)
        new_content = modified.read_text(encoding="utf-8")
    finally:
        import shutil
        shutil.rmtree(dest_dir, ignore_errors=True)

    # Write output
    if output is not None:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(new_content, encoding="utf-8")
    else:
        # Atomic in-place write
        tmp_fd, tmp_path = tempfile.mkstemp(
            dir=manifest_path.parent,
            prefix=".locklane-",
            suffix=".tmp",
        )
        try:
            with os.fdopen(tmp_fd, "w", encoding="utf-8") as f:
                f.write(new_content)
            os.replace(tmp_path, manifest_path)
        except BaseException:
            if Path(tmp_path).exists():
                os.unlink(tmp_path)
            raise

    return ApplyResult(
        applied=True,
        manifest_path=str(manifest_path),
        output_path=str(output) if output else None,
        patch_preview=preview_text,
        updates_applied=updates_applied,
        rollback=rollback,
    )
