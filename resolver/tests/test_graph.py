"""Unit tests for dependency graph parsing."""

from __future__ import annotations

import unittest

from locklane_resolver.graph import _normalize
from locklane_resolver.graph import parse_resolver_output


class NormalizeTests(unittest.TestCase):
    """PEP 503 normalization."""

    def test_lowercase_and_dashes(self) -> None:
        self.assertEqual(_normalize("My_Package.Name"), "my-package-name")

    def test_runs_collapsed(self) -> None:
        self.assertEqual(_normalize("a--b__c..d"), "a-b-c-d")

    def test_already_normalized(self) -> None:
        self.assertEqual(_normalize("requests"), "requests")


class ParseResolverOutputTests(unittest.TestCase):
    """Parse annotated resolver output."""

    def test_simple_direct_packages(self) -> None:
        output = (
            "click==8.1.7\n"
            "    # via\n"
            "    #   -r requirements.txt\n"
            "\n"
            "requests==2.31.0\n"
            "    # via\n"
            "    #   -r requirements.txt\n"
        )
        result = parse_resolver_output(output, {"click", "requests"})
        self.assertEqual(len(result), 2)

        click_pkg = result[0]
        self.assertEqual(click_pkg.name, "click")
        self.assertEqual(click_pkg.version, "8.1.7")
        self.assertTrue(click_pkg.is_direct)

        requests_pkg = result[1]
        self.assertEqual(requests_pkg.name, "requests")
        self.assertTrue(requests_pkg.is_direct)

    def test_transitive_dependencies(self) -> None:
        output = (
            "requests==2.31.0\n"
            "    # via\n"
            "    #   -r requirements.txt\n"
            "\n"
            "urllib3==2.2.1\n"
            "    # via\n"
            "    #   requests\n"
            "\n"
            "certifi==2024.2.2\n"
            "    # via requests\n"
        )
        result = parse_resolver_output(output, {"requests"})

        urllib3 = next(p for p in result if p.name == "urllib3")
        self.assertFalse(urllib3.is_direct)
        self.assertEqual(urllib3.required_by, ["requests"])

        certifi = next(p for p in result if p.name == "certifi")
        self.assertFalse(certifi.is_direct)
        self.assertEqual(certifi.required_by, ["requests"])

    def test_multi_via_parents(self) -> None:
        output = (
            "idna==3.6\n"
            "    # via\n"
            "    #   requests\n"
            "    #   httpx\n"
        )
        result = parse_resolver_output(output, set())
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0].required_by, ["httpx", "requests"])

    def test_direct_detection_by_name(self) -> None:
        output = "fastapi==0.110.0\n"
        result = parse_resolver_output(output, {"FastAPI"})
        self.assertTrue(result[0].is_direct)

    def test_normalization_in_via(self) -> None:
        output = (
            "starlette==0.36.3\n"
            "    # via Fast_API\n"
        )
        result = parse_resolver_output(output, set())
        self.assertEqual(result[0].required_by, ["fast-api"])

    def test_deterministic_ordering(self) -> None:
        output = (
            "zebra==1.0\n"
            "    # via -r requirements.txt\n"
            "\n"
            "alpha==2.0\n"
            "    # via -r requirements.txt\n"
        )
        result = parse_resolver_output(output, {"zebra", "alpha"})
        self.assertEqual([p.name for p in result], ["alpha", "zebra"])

    def test_empty_output(self) -> None:
        result = parse_resolver_output("", set())
        self.assertEqual(result, [])

    def test_constraint_via_marks_direct(self) -> None:
        output = (
            "click==8.1.7\n"
            "    # via\n"
            "    #   -c constraints.txt\n"
        )
        result = parse_resolver_output(output, set())
        self.assertTrue(result[0].is_direct)


if __name__ == "__main__":
    unittest.main()
