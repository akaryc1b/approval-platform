# M5 Process Instance Migration Duplicate and Reconciliation Evidence

M5-A DUPLICATE/RECONCILIATION SLICE: `CAPABILITY_VALIDATION_ONLY`

Overall conclusion remains: `SUPPORTED_WITH_LIMITATIONS`

This addendum records duplicate invocation and response-loss experiments. It does not authorize M5-B,
production execution, a persistence protocol, a migration worker, automatic retry, or any browser
or mobile execution control.

## 1. Baseline and public API boundary

- Current verified `main`: `d769722cf7dd5418739a91ad4c45ca1a1c147502`.
- Flowable dependency baseline remains `org.flowable:flowable-bom:8.0.0`.
- Flyway remains continuous through `V32`; this slice adds no `V33`.
- All executable experiments remain under test sources.
- Only public `ProcessMigrationService`, `RuntimeService`, `TaskService` and `ManagementService`
  readback is used.
- No code queries or modifies Flowable `ACT_*` tables.

The public migration builder exposes validation and migration but no idempotency key, invocation
receipt, compare-and-set token, rollback operation, or documented exactly-once contract.

Primary references:

- https://www.flowable.com/open-source/docs/bpmn/ch08-ProcessInstanceMigration
- https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/migration/ProcessInstanceMigrationBuilder.html

## 2. Duplicate invocation evidence

`FlowableProcessInstanceMigrationDuplicateInvocationCapabilityTest` replays one exact public
`ProcessInstanceMigrationDocument` after the selected instance has already reached the target
process definition.

For the tested renamed single-user-task shape:

1. the first validation and migration succeed;
2. the process instance reaches the target definition and target task;
3. a new builder created from the exact same migration document validates successfully again;
4. the second migration invocation is accepted rather than rejected as a duplicate;
5. the process remains on the target definition with one active target task.

This is not evidence of an idempotency contract. It is evidence that Flowable cannot be relied on
to reject a replay. A confirmed successful platform attempt must return its stored result and must
not invoke the engine again.

A timed-out or disconnected attempt must never be retried merely because the same document can be
submitted again.

## 3. Response-loss and reconciliation evidence

`FlowableProcessInstanceMigrationUnknownOutcomeCapabilityTest` simulates loss of the caller-visible
response at two exact points and classifies public readback:

- response lost after `migrate` returns: the process definition and active activity set match the
  sealed target snapshot, so the tested state is `TARGET_STATE_CONFIRMED`;
- response lost before engine invocation: the process definition and active activity set match the
  sealed source snapshot, so the tested state is `SOURCE_STATE_CONFIRMED`;
- target process definition combined with a pending job still bound to the source definition is
  `RECONCILIATION_REQUIRED`.

The classifier compares all available authoritative evidence. Process-definition equality alone is
not enough. Active activities and dependent job definition bindings must also match the same sealed
side. Any mixed, missing, concurrently changed, or otherwise non-unique state remains
`RECONCILIATION_REQUIRED`.

`SOURCE_STATE_CONFIRMED` describes the current authoritative state only. It must not be represented
as proof that no invocation was ever attempted when competing migrations or other later commands
could have occurred.

## 4. Capability and protocol delta

| Concern | M5-A status | Evidence and limitation |
|---|---|---|
| Confirmed-success duplicate invocation | `UNSUPPORTED` | Engine replay is accepted in the tested shape. The platform must return stored success and prohibit a second engine call. |
| Duplicate invocation after timeout or disconnect | `UNSUPPORTED` | No public idempotency or receipt contract exists. Reconcile first; never retry UNKNOWN blindly. |
| Exact target snapshot after response loss | `SUPPORTED_WITH_LIMITATIONS` | Public readback can confirm target state for the tested direct wait-state shape when definition, activities and dependencies all match. |
| Exact source snapshot after response loss | `SUPPORTED_WITH_LIMITATIONS` | Public readback can confirm current source state for the tested direct wait-state shape; competing later commands still require evidence. |
| Mixed source/target public evidence | `SUPPORTED_WITH_LIMITATIONS` | The mismatch is detectable and must enter reconciliation; it is never completed success. |
| Timeout with non-unique readback | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | No automatic retry or forced completion is safe. |

## 5. Governance implications

- Every future platform attempt needs a server-generated idempotency key and immutable attempt
  identity before any external call.
- Confirmed success must be terminal for that attempt and migration plan; a duplicate request returns
  stored evidence without invoking Flowable.
- `UNKNOWN` is an explicit durable outcome, not a transient exception to retry automatically.
- Reconciliation must compare process definition, active activities, task state, jobs, timers,
  identity links, variables and history as applicable to the sealed graph shape.
- Only a unique exact target match may become verified success.
- Exact source state may support a governed not-applied decision only when competing-command evidence
  has also been excluded.
- Mixed evidence must remain `RECONCILIATION_REQUIRED` and fail closed.
- No production executor or persistence model is authorized by this test-only classifier.

## 6. Remaining evidence gaps

- a real network timeout during the engine call;
- a true concurrent engine command while migration is executing;
- duplicate requests arriving simultaneously;
- later migration or compensation returning an instance to a source-like state;
- missing instances, terminated instances and history-only reconciliation;
- timer firing, task completion, withdraw, reject or terminate during migration;
- reconciliation across nested call trees and multiple dependent entities.

## 7. Slice decision

Current M5-A decision remains `SUPPORTED_WITH_LIMITATIONS`.

This slice permits further M5-A experiments and capability-matrix consolidation only. It does not
authorize M5-B, `V33`, production persistence, a migration worker, execute/force/rollback APIs,
automatic migration, automatic retry of `UNKNOWN`, or production enablement.
