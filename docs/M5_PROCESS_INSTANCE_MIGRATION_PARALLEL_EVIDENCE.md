# M5 Process Instance Migration Parallel Evidence

M5-A PARALLEL SLICE: `CAPABILITY_VALIDATION_ONLY`

Overall conclusion remains: `SUPPORTED_WITH_LIMITATIONS`

This evidence addendum supplements `M5_PROCESS_INSTANCE_MIGRATION_FEASIBILITY.md`. It does not
approve M5-B, production migration execution, a database schema, a worker, a browser command, or
automatic retry. The consolidated M5 feasibility record must include this delta before formal
M5-A acceptance.

## 1. Baseline

- Current verified `main`: `d769722cf7dd5418739a91ad4c45ca1a1c147502`.
- PR #61 corrected encoding corruption in two living documentation files only.
- PR #61 was incorporated into the M5 branch with merge commit
  `c8df0bd446f6d9f286f83c499dfe15f4e98140c2`.
- Flowable dependency baseline remains `org.flowable:flowable-bom:8.0.0`.
- Flyway remains continuous through `V32`; this slice adds no `V33`.
- All migration execution code in this slice remains under test sources.
- The automatic validation path remains `.github/workflows/approval-platform-validation.yml`.

## 2. Official public API boundary

Flowable 8.0.0 exposes explicit activity mappings for:

- one source activity to multiple target activities;
- multiple source activities to one target activity;
- one source activity to one target activity.

The official migration guide identifies migration from a single execution into multiple parallel
or inclusive executions and migration from multiple parallel or inclusive executions to one
execution as supported cases.

The same guide lists multi-instance collection migration, migration into a multi-instance
activity, and selected call-activity structures as upcoming rather than supported capability for
this engine line. M5 must not emulate those cases with internal classes or SQL.

Primary references:

- https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/migration/ActivityMigrationMapping.html
- https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/migration/ActivityMigrationMapping.OneToManyMapping.html
- https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/migration/ActivityMigrationMapping.ManyToOneMapping.html
- https://www.flowable.com/open-source/docs/bpmn/ch08-ProcessInstanceMigration

## 3. Isolated engine evidence

`FlowableProcessInstanceMigrationParallelMappingCapabilityTest`,
`FlowableProcessInstanceMigrationCompletedBranchCapabilityTest` and
`FlowableProcessInstanceMigrationStaleValidationCapabilityTest` use only public Flowable services
and in-memory engines. It establishes the following exact shapes:

1. A single active user-task execution can be migrated to two active user-task executions inside
   a parallel gateway by an explicit one-to-many mapping.
2. The source task is removed and two new tasks are created. Task and execution identity must not
   be assumed to survive an execution-recreating mapping.
3. Two active parallel user-task executions can be collapsed into one active user task by an
   explicit many-to-one mapping.
4. Both source tasks are removed and a new merged task is created.
5. A parallel instance with one completed branch and one still-active branch can be validated and
   migrated to the same parallel topology. The completed branch remains completed and the active
   branch remains active.
6. A successful validation is only point-in-time evidence. If the task completes before engine
   invocation, revalidation becomes invalid and the migration invocation throws rather than
   migrating the ended instance.

These tests do not establish safety for inclusive gateways, nested scopes, multi-instance
executions, call activities, event subscriptions, jobs, timers, or a true race occurring inside
the engine migration command.

## 4. Capability matrix delta

This table supersedes the corresponding rows in the initial feasibility matrix.

| # | Scenario | M5-A status | Evidence and limitation |
|---:|---|---|---|
| 3 | Multiple active activities | `SUPPORTED_WITH_LIMITATIONS` | Two active parallel user-task executions are verified for explicit many-to-one collapse. Other execution-tree shapes remain unproven. |
| 4 | Parallel branches | `SUPPORTED_WITH_LIMITATIONS` | Explicit split and merge mappings are verified for a simple parallel gateway with user-task waits. Inclusive and nested parallel scopes remain unproven. |
| 5 | Parallel gateway with completed branch evidence | `SUPPORTED_WITH_LIMITATIONS` | A same-topology migration with one completed and one active branch is verified through runtime and history public APIs. More complex completed-branch shapes remain blocked. |
| 6 | Multi-instance approval activity | `UNSUPPORTED` | Flowable 8.0.0 documentation lists collection multi-instance migration and migration into multi-instance activities as upcoming support. M5 must prohibit it. |
| 23 | One source activity to multiple target activities | `SUPPORTED_WITH_LIMITATIONS` | Explicit one-to-many mapping creates two target executions and tasks in the tested parallel topology; original task identity is not retained. |
| 24 | Multiple source activities to one target activity | `SUPPORTED_WITH_LIMITATIONS` | Explicit many-to-one mapping collapses two tested parallel executions into one new task; source task identity is not retained. |
| 25 | Concurrent task completion during migration | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | Completion after validation but before invocation invalidates revalidation and causes invocation failure. A race during the engine command and external timeout semantics remain unproven. |

## 5. Governance implications

- A sealed plan must bind the exact expected active activity set and expected execution count.
- One-to-many and many-to-one mappings must be explicit, ordered and included in the mapping hash.
- Verification must compare active activities and task count, not only the target process definition.
- Task IDs and execution IDs cannot be treated as stable across execution-recreating mappings.
- Local variables and identity links require mapping-specific verification because direct-mapping
  preservation evidence does not apply to recreated executions or tasks.
- Validation must be repeated immediately before invocation.
- A stale validation result must never authorize continued execution.
- Multi-instance migration remains prohibited for Flowable 8.0.0.

## 6. Remaining blocked evidence

- inclusive gateways and nested parallel scopes;
- embedded subprocess migration;
- Call Activity parent/child migration;
- boundary events, timers, async jobs and pending jobs;
- multi-instance nodes;
- complete, withdraw, reject or terminate races during engine invocation;
- duplicate invocation and idempotency behavior;
- timeout with engine-side completion;
- verification mismatch and reconciliation decision rules.

## 7. Slice decision

Current M5-A decision remains `SUPPORTED_WITH_LIMITATIONS`.

This slice permits additional M5-A experiments only. It does not authorize M5-B, `V33`, a
production executor, a migration worker, execution controls, automatic retry, or production
enablement.
