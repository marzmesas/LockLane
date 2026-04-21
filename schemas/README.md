# Locklane Schemas

JSON schema contracts shared between plugin and resolver modules.

## Planned Schema Files

1. `baseline_result.schema.json`
2. `candidate_result.schema.json`
3. `upgrade_plan.schema.json`
4. `verification_report.schema.json`

These are now bootstrapped in phase 1 and should be evolved in place with backward-compatible changes when possible.

## upgrade_plan: interdependent safe updates

Each entry in `safe_updates[]` may carry an optional `group_id` string. Updates that share the same `group_id` are *interdependent*: the planner verified the graph resolves when they are applied together but not when any proper subset is applied. Consumers (plugin UI, apply action) MUST either apply the whole group or none of it — cascading selection in the UI is the expected pattern. Updates without a `group_id` (or with `group_id` absent) are independent and may be applied individually.
