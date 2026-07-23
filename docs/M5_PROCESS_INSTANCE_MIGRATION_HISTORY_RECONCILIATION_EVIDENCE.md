# M5 Process Instance Migration History-Only Reconciliation Evidence

M5-A HISTORY-ONLY SLICE: `CAPABILITY_VALIDATION_ONLY`

Overall conclusion remains: `SUPPORTED_WITH_LIMITATIONS`

This addendum records public runtime/history readback after normal completion, deletion termination,
migration followed by completion or termination, and a completely absent identifier. It does not
authorize M5-B, production execution, persistence, a worker, automatic retry, or browser/mobile
execution controls.

## 1. Baseline and public API boundary

- Current verified `main`: `d769722cf7dd5418739a91ad4c45ca1a1c147502`.
- Flowable dependency baseline remains `org.flowable:flowable-bom:8.0.0`.
- Flyway remains continuous through `V32`; this slice adds no `V33`.
- All executable experiments remain under test sources.
- Only public `RuntimeService`, `TaskService`, `ProcessMigrationService` and `HistoryService` APIs
  are used.
- No code queries or modifies Flowable `ACT_*` tables.
- The test does not delete historic data or manufacture history records.

Flowable runtime queries represent current executable state. `HistoryService` retains information
about ongoing and past process instances after runtime state is gone.

Primary references:

- https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/HistoryService.html
- https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/history/HistoricProcessInstance.html
- https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/RuntimeService.html

## 2. Permanent test evidence

`FlowableProcessInstanceMigrationHistoryOnlyReconciliationCapabilityTest` verifies five exact
public-API states:

1. an unknown identifier with neither runtime nor history is `MISSING_NO_EVIDENCE`;
2. deleting an active source instance with an explicit reason produces
   `HISTORY_ONLY_SOURCE_TERMINATED`;
3. normally completing the source instance produces `HISTORY_ONLY_SOURCE_COMPLETED`;
4. migrating to the target and then normally completing produces
   `HISTORY_ONLY_TARGET_COMPLETED`;
5. migrating to the target and then deleting the runtime instance produces
   `HISTORY_ONLY_TARGET_TERMINATED`.

The history-only classifications require:

- no runtime process instance;
- one historic process instance;
- a non-null historic end time;
- a process-definition reference matching the sealed source or target definition;
- delete-reason presence for terminated outcomes and absence for normal completion.

## 3. Reconciliation decisions

| Public evidence | M5-A classification | Safe interpretation |
|---|---|---|
| No runtime and no history | `MISSING_NO_EVIDENCE` | `RECONCILIATION_REQUIRED`; it cannot distinguish a never-existing identifier from purged, unavailable or incorrectly scoped evidence. |
| Source history, ended, no delete reason | `HISTORY_ONLY_SOURCE_COMPLETED` | The source instance ended normally. It is not proof that migration was never attempted. |
| Source history with delete reason | `HISTORY_ONLY_SOURCE_TERMINATED` | A terminal source-side deletion is confirmed. Never retry migration automatically. |
| Target history, ended, no delete reason | `HISTORY_ONLY_TARGET_COMPLETED` | Target-side terminal completion is confirmed for the tested shape; it is not an active migrated instance. |
| Target history with delete reason | `HISTORY_ONLY_TARGET_TERMINATED` | Target-side deletion after migration is confirmed. It must not be represented as migration success. |
| History exists but definition, end state or termination semantics are not unique | `RECONCILIATION_REQUIRED` | Fail closed and preserve `UNKNOWN`. |

History-only target evidence can confirm that the instance reached the target definition before
ending, but a terminal history record must not be converted into an active migration success.
Completion and termination are distinct terminal outcomes.

## 4. Governance implications

- Runtime absence alone is never enough to classify an attempt.
- No runtime and no history is not `NOT_APPLIED`; it is missing evidence.
- A delete reason is authoritative termination evidence for the tested public shape.
- Normal completion and deletion termination must remain distinct closed-set outcomes.
- Target history may support a terminal target-side reconciliation result only after definition,
  end time, delete reason, historic tasks/activities and attempt correlation agree.
- Source history cannot prove that no migration call happened when a later command could return the
  instance to a source-like or terminal source state.
- No history-only outcome permits automatic retry of `UNKNOWN`.
- A terminal instance is not eligible for another migration invocation.
- Production reconciliation must preserve the raw public evidence used for classification.

## 5. Concurrency boundary

This slice does not claim a true migration-versus-complete/withdraw/reject/terminate race. A broad
test that merely accepts several possible final states would not prove command ordering or safety.
A future concurrency slice requires deterministic command coordination and permanent evidence of
the winning command, losing exception, runtime/history result and dependent entities.

## 6. Remaining gaps

- true engine-command concurrency during migration;
- history retention, purge and tenant-scope failures;
- missing historic tasks or activities while the process history remains;
- nested Call Activity and subprocess history-only trees;
- timer firing or async job execution during migration;
- later compensation or migration returning to a source-like definition;
- production attempt correlation and immutable reconciliation evidence.

## 7. Slice decision

Current M5-A decision remains `SUPPORTED_WITH_LIMITATIONS`.

This slice permits further M5-A evidence and final feasibility consolidation only. It does not authorize M5-B, `V33`, production persistence, a migration worker, execute/force/rollback APIs,
automatic migration, automatic retry of `UNKNOWN`, or production enablement.
