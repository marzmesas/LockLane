"""Tests for pyproject.toml parsing and line surgery."""

from pathlib import Path

import pytest

from locklane_resolver.pyproject_parser import (
    build_pyproject_replacement_line,
    find_pyproject_dependency_line,
    parse_pyproject_dependencies,
)
from locklane_resolver.cli import parse_manifest

FIXTURES = Path(__file__).parent / "fixtures"


# ---------------------------------------------------------------------------
# PEP 621 parsing
# ---------------------------------------------------------------------------

class TestPEP621Parsing:
    def test_parse_project_dependencies(self):
        deps = parse_pyproject_dependencies(FIXTURES / "pyproject_pep621.toml")
        names = {d.name.lower() for d in deps}
        assert "requests" in names
        assert "flask" in names
        assert "click" in names
        assert "pydantic" in names

    def test_parse_optional_dependencies(self):
        deps = parse_pyproject_dependencies(FIXTURES / "pyproject_pep621.toml")
        names = {d.name.lower() for d in deps}
        assert "pytest" in names
        assert "mypy" in names
        assert "sphinx" in names

    def test_specifiers_parsed(self):
        deps = parse_pyproject_dependencies(FIXTURES / "pyproject_pep621.toml")
        by_name = {d.name.lower(): d for d in deps}
        assert by_name["flask"].specifier == "==3.0.0"
        assert "2.31.0" in by_name["requests"].specifier

    def test_line_numbers_positive(self):
        deps = parse_pyproject_dependencies(FIXTURES / "pyproject_pep621.toml")
        for dep in deps:
            assert dep.line_number > 0, f"{dep.name} has line_number={dep.line_number}"

    def test_extras_stripped_from_name(self):
        deps = parse_pyproject_dependencies(FIXTURES / "pyproject_pep621.toml")
        by_name = {d.name.lower(): d for d in deps}
        assert "pydantic" in by_name  # not "pydantic[email]"


# ---------------------------------------------------------------------------
# Poetry parsing
# ---------------------------------------------------------------------------

class TestPoetryParsing:
    def test_parse_poetry_dependencies(self):
        deps = parse_pyproject_dependencies(FIXTURES / "pyproject_poetry.toml")
        names = {d.name.lower() for d in deps}
        assert "requests" in names
        assert "flask" in names
        assert "pydantic" in names

    def test_python_excluded(self):
        deps = parse_pyproject_dependencies(FIXTURES / "pyproject_poetry.toml")
        names = {d.name.lower() for d in deps}
        assert "python" not in names

    def test_parse_poetry_groups(self):
        deps = parse_pyproject_dependencies(FIXTURES / "pyproject_poetry.toml")
        names = {d.name.lower() for d in deps}
        assert "pytest" in names
        assert "mypy" in names
        assert "sphinx" in names

    def test_poetry_inline_table(self):
        deps = parse_pyproject_dependencies(FIXTURES / "pyproject_poetry.toml")
        by_name = {d.name.lower(): d for d in deps}
        assert "pydantic" in by_name


# ---------------------------------------------------------------------------
# parse_manifest dispatch
# ---------------------------------------------------------------------------

class TestParseManifestDispatch:
    def test_toml_dispatches_to_pyproject(self):
        deps = parse_manifest(FIXTURES / "pyproject_pep621.toml")
        names = {d.name.lower() for d in deps}
        assert "flask" in names

    def test_txt_dispatches_to_requirements(self):
        deps = parse_manifest(FIXTURES / "simple_pins.txt")
        assert len(deps) > 0
        assert all(d.line_number > 0 for d in deps)


# ---------------------------------------------------------------------------
# Line surgery — PEP 621
# ---------------------------------------------------------------------------

class TestPEP621Surgery:
    def test_find_dep_in_array(self):
        lines = [
            '[project]',
            'dependencies = [',
            '    "requests>=2.31.0",',
            '    "flask==3.0.0",',
            ']',
        ]
        match = find_pyproject_dependency_line(lines, "flask")
        assert match is not None
        idx, stripped = match
        assert idx == 3
        assert "flask" in stripped

    def test_replace_pep621_version(self):
        line = '    "flask==3.0.0",'
        result = build_pyproject_replacement_line(line.strip(), "flask", "3.1.0")
        assert "3.1.0" in result
        assert "flask" in result

    def test_replace_preserves_comma(self):
        line = '    "requests>=2.31.0",'
        result = build_pyproject_replacement_line(line.strip(), "requests", "2.32.0")
        assert "2.32.0" in result
        assert result.endswith(",") or result.endswith('",')


# ---------------------------------------------------------------------------
# Line surgery — Poetry
# ---------------------------------------------------------------------------

class TestPoetrySurgery:
    def test_find_poetry_simple(self):
        lines = [
            '[tool.poetry.dependencies]',
            'python = "^3.11"',
            'requests = "^2.31.0"',
            'flask = "3.0.0"',
        ]
        match = find_pyproject_dependency_line(lines, "requests")
        assert match is not None
        idx, stripped = match
        assert idx == 2

    def test_replace_poetry_caret(self):
        result = build_pyproject_replacement_line(
            'requests = "^2.31.0"', "requests", "2.32.0"
        )
        assert result == 'requests = "^2.32.0"'

    def test_replace_poetry_tilde(self):
        result = build_pyproject_replacement_line(
            'click = "~8.1.0"', "click", "8.2.0"
        )
        assert result == 'click = "~8.2.0"'

    def test_replace_poetry_exact(self):
        result = build_pyproject_replacement_line(
            'flask = "3.0.0"', "flask", "3.1.0"
        )
        assert result == 'flask = "3.1.0"'

    def test_replace_poetry_inline_table(self):
        result = build_pyproject_replacement_line(
            'pydantic = {version = "^2.0", extras = ["email"]}',
            "pydantic",
            "2.5.0",
        )
        assert "2.5.0" in result
        assert "email" in result  # extras preserved

    def test_find_poetry_inline_table(self):
        lines = [
            '[tool.poetry.dependencies]',
            'pydantic = {version = "^2.0", extras = ["email"]}',
        ]
        match = find_pyproject_dependency_line(lines, "pydantic")
        assert match is not None
        assert match[0] == 1


# ---------------------------------------------------------------------------
# Round-trip: parse -> modify -> re-parse
# ---------------------------------------------------------------------------

class TestRoundTrip:
    def test_pep621_roundtrip(self, tmp_path):
        src = FIXTURES / "pyproject_pep621.toml"
        content = src.read_text()
        toml_path = tmp_path / "pyproject.toml"
        toml_path.write_text(content)

        # Parse
        deps = parse_pyproject_dependencies(toml_path)
        by_name = {d.name.lower(): d for d in deps}
        assert "flask" in by_name

        # Modify
        lines = content.splitlines()
        match = find_pyproject_dependency_line(lines, "flask")
        assert match is not None
        idx, stripped = match
        new_line = build_pyproject_replacement_line(stripped, "flask", "3.1.0")
        lines[idx] = new_line
        toml_path.write_text("\n".join(lines) + "\n")

        # Re-parse
        deps2 = parse_pyproject_dependencies(toml_path)
        by_name2 = {d.name.lower(): d for d in deps2}
        assert "3.1.0" in by_name2["flask"].specifier

    def test_poetry_roundtrip(self, tmp_path):
        src = FIXTURES / "pyproject_poetry.toml"
        content = src.read_text()
        toml_path = tmp_path / "pyproject.toml"
        toml_path.write_text(content)

        # Parse
        deps = parse_pyproject_dependencies(toml_path)
        by_name = {d.name.lower(): d for d in deps}
        assert "requests" in by_name

        # Modify
        lines = content.splitlines()
        match = find_pyproject_dependency_line(lines, "requests")
        assert match is not None
        idx, stripped = match
        new_line = build_pyproject_replacement_line(stripped, "requests", "2.32.0")
        lines[idx] = new_line
        toml_path.write_text("\n".join(lines) + "\n")

        # Re-parse and verify version changed
        new_content = toml_path.read_text()
        assert "2.32.0" in new_content
