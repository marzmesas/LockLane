# Changelog

## 0.1.0

Initial release.

- Upgrade plan generation with safe/blocked/inconclusive classification
- Conflict chain visualization for blocked upgrades
- Verification lane — test proposed upgrades in disposable venvs
- Apply upgrades to lockfile with dry-run support
- Bundled Python resolver — works out of the box
- Configurable resolver preference (uv / pip-tools), timeouts, and private indexes
- Auto-detection of Python interpreter from project venv or system PATH
- Pre-flight validation of resolver tools with actionable install instructions
