# M5 Process Instance Migration Subprocess Evidence

M5-A SUBPROCESS SLICE: `CAPABILITY_VALIDATION_ONLY`

Overall conclusion remains: `SUPPORTED_WITH_LIMITATIONS`

This evidence addendum supplements `M5_PROCESS_INSTANCE_MIGRATION_FEASIBILITY.md` and
`M5_PROCESS_INSTANCE_MIGRATION_PARALLEL_EVIDENCE.md`. It does not approve M5-B, production
migration execution, persistence, a worker, browser or mobile controls, or automatic retry.

## 1. Baseline

- Current verified `main`: `d769722cf7dd5418739a91ad4c45ca1a1c147502`.
- Current M5 branch baseline before this slice:
  `e122cc77400a3db8b07636b7f9c27d0b05a9e533`.
- Flowable dependency baseline remains `org.flowable:flowable-bom:8.0.0`.
- Flyway remains continuous through `V32`; this slice adds no `V33`.
- All migration execution in this slice remains under test sources.
- The automatic validation path remains `.github/workflows/approval-platform-validation.yml`.

## 2. Official public API boundary

Flowable 8.0.0 documents these supported migration cases:

- moving a wait state into an embedded subprocess or nested embedded subprocess;
- moving a wait state from an embedded subprocess to the root process or another nested scope;
- moving a wait state from a called process into its parent process.

The public `ActivityMigrationMappingOptions` API exposes
`inParentProcessOfCallActivityId(...)` and `inSubProcessOfCallActivityId(...)`. API metadata alone
is not sufficient evidence that both runtime directions are safe. The open-source migration guide
still lists moving a wait state into a called subprocess when call activities are present as
upcoming support for this engine line. M5 therefore keeps parent-to-child Call Activity migration
prohibited until an exact runtime shape is permanently proven.

Primary references:

- https://www.flowable.com/open-source/docs/bpmn/ch08-ProcessInstanceMigration
- https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/migration/ActivityMigrationMapping.html
- https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/migration/ActivityMigrationMappingOptions.html
- https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/runtime/ProcessInstanceQuery.html

## 3. Isolated engine evidence

All tests use in-memory engines and public Flowable services only.

### 3.1 Embedded subprocess scope movement

`FlowableProcessInstanceMigrationEmbeddedSubprocessCapabilityTest` proves two exact shapes:

1. A root-level active user task can be explicitly mapped to a user task inside one embedded
   subprocess. Validation succeeds, the target definition becomes authoritative, and only the
   nested target task remains active.
2. An active user task inside one embedded subprocess can be explicitly mapped back to a
   root-level user task. The embedded wait state is removed and only the target root task remains
   active.

This does not prove nested multi-level scopes, event subprocesses, boundary subscriptions,
parallel executions inside subprocesses, or local-variable behavior when scope executions are
recreated.

### 3.2 Exact called child migration

`FlowableProcessInstanceMigrationCalledChildCapabilityTest` proves that an exact called child
process instance can be migrated independently to a newer child definition while its parent
process remains on the original parent definition. The child process instance id and
`superProcessInstanceId` relationship remain observable through public runtime queries.

This is not evidence that migrating a parent automatically migrates every called child. Parent and
child definition bindings must be snapshotted and verified independently.

### 3.3 Called child to parent movement

`FlowableProcessInstanceMigrationCallActivityExitCapabilityTest` proves one exact child-to-parent
shape. A child user-task wait state is explicitly mapped to a root-level task in the target parent
definition with `inParentProcessOfCallActivityId("callChild")`. After migration:

- the parent process points to the target parent definition;
- the target parent task is active;
- the called child runtime process instance is no longer active;
- the original child task is no longer active.

The reverse parent-to-child creation direction is not executed or approved in this slice.

## 4. Capability matrix delta

This table supersedes the corresponding rows in the initial feasibility matrix.

| # | Scenario | M5-A status | Evidence and limitation |
|---:|---|---|---|
| 7 | Embedded subprocess | `SUPPORTED_WITH_LIMITATIONS` | Root-to-embedded and embedded-to-root mappings are verified for one simple embedded scope with a single user-task wait state. Nested, event, parallel and boundary-event subprocess shapes remain unproven. |
| 8 | Call Activity | `SUPPORTED_WITH_LIMITATIONS` | Exact called-child migration preserving the parent relation and one child-to-parent mapping are verified. Parent-to-child creation, multiple call activities, nested call trees and mixed-version trees remain prohibited. |

Additional Call Activity boundary:

- parent-to-child Call Activity migration: `UNSUPPORTED` in the candidate safe scope;
- automatic recursive migration of a parent and all children: `UNSUPPORTED`;
- independently approved exact child migration: `SUPPORTED_WITH_LIMITATIONS` for the tested shape.

## 5. Governance implications

- A plan must identify whether the exact target is a root process instance or a called child.
- Parent and child source/target definition ids and runtime bindings require separate evidence.
- A child migration must not silently imply a parent migration or release rebinding.
- Child-to-parent movement must verify that the called child runtime is gone and the expected parent
  wait state is active.
- Parent-to-child creation remains fail-closed even though public mapping metadata exists.
- Scope movement requires before/after active-activity, task, variable and identity-link evidence.
- No code may query or modify Flowable `ACT_*` tables to infer call-tree state.

## 6. Remaining blocked evidence

- nested embedded subprocesses and event subprocesses;
- embedded scopes with parallel branches or boundary events;
- parent-to-child Call Activity creation;
- multiple or nested Call Activity trees;
- Call Activity version expressions and tenant resolution;
- boundary events, timers, async jobs and pending jobs;
- local-variable and identity-link semantics on recreated scope executions;
- duplicate invocation, timeout, UNKNOWN and verification mismatch.

## 7. Slice decision

Current M5-A decision remains `SUPPORTED_WITH_LIMITATIONS`.

This slice permits additional isolated M5-A experiments only. It does not authorize M5-B, `V33`,
a production executor, a migration worker, execution controls, automatic retry, or production
enablement.
