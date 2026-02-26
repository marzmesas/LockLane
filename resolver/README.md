# Locklane Resolver Module

Python worker responsible for dependency resolution and verification commands.

## Planned Responsibilities

1. Baseline resolution.
2. Patch-candidate simulation.
3. Conflict-chain extraction.
4. Verification environment execution.
5. Structured JSON output for plugin consumption.

## Bootstrap Commands

```bash
cd resolver
python3 -m unittest discover -s tests -p "test_*.py"
python3 -m locklane_resolver baseline --manifest ../fixtures/requirements.txt --resolver uv
```
