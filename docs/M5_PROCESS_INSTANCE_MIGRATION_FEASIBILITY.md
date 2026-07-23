# M5 Process Instance Migration Feasibility

M5-A STATUS: `CAPABILITY_VALIDATION_ONLY`

Overall conclusion: `SUPPORTED_WITH_LIMITATIONS`

This document is the first isolated M5-A evidence slice. It does not approve production
migration execution, does not create a migration plan protocol, and does not authorize M5-B.
The conclusion must be re-evaluated after the remaining capability matrix tests are complete.

## 1. Verified repository and engine baseline

- Repository baseline: `main` at `58efb4255394fe3911700719669c4423a3ab212e`.
- M4 was merged by merge commit; PR #55 is closed and must remain frozen.
- Flowable dependency baseline: `org.flowable:flowable-bom:8.0.0`.
- Flyway remains continuous through `V32`; M5-A adds no `V33`.
- The only permanent workflow remains
  `.github/workflows/approval-platform-validation.yml`.
- Production code must not query or modify Flowable `ACT_*` tables.
- Migration capability code in this slice exists only under test sources.
- No production migration Controller, worker, browser command, mobile command, or execution
  configuration is introduced.

## 2. Public API finding

Flowable 8.0.0 exposes process instance migration through public engine APIs:

- `ProcessEngine#getProcessMigrationService()`
- `ProcessMigrationService#createProcessInstanceMigrationBuilder()`
- `ProcessInstanceMigrationBuilder#validateMigration(String)`
- `ProcessInstanceMigrationBuilder#migrate(String)`
- definition-wide synchronous and batch migration methods
- explicit one-to-one, one-to-many, and many-to-one activity mappings
- migration documents and validation results

Primary references:

- https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/ProcessMigrationService.html
- https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/migration/ProcessInstanceMigrationBuilder.html
- https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/migration/ActivityMigrationMapping.html
- https://www.flowable.com/open-source/docs/bpmn/ch08-ProcessInstanceMigration

The public migration builder does not expose a rollback operation. No public API contract
reviewed in this slice declares migration calls idempotent or supplies an idempotency token,
invocation receipt, compare-and-set precondition, or atomic transaction shared with the
platform database.

## 3. First isolated engine evidence

`FlowableProcessInstanceMigrationCapabilityTest` uses an in-memory Flowable engine and public
services only. It establishes:

1. Flowable 8.0.0 exposes validation, exact single-instance migration, definition-wide
   migration, and batch migration methods.
2. An active single user-task wait state can be validated and migrated to a target definition
   when the activity ID is unchanged.
3. A renamed activity is not accepted by automatic ID mapping and requires an explicit
   source-to-target activity mapping.
4. After the tested migration, the runtime process definition ID changes to the target
   definition, the target active task is observable, and a process variable remains readable.
5. The public migration surface has no method represented as rollback.

This evidence is deliberately narrow. It does not yet prove preservation of local variables,
task-local variables, identity links, candidate assignments, jobs, timers, call activities,
multi-instance executions, or all history semantics.

## 4. Capability matrix

Status vocabulary:

- `SUPPORTED`: directly verified in the isolated test or unambiguously supported by the
  Flowable 8.0.0 public API.
- `SUPPORTED_WITH_LIMITATIONS`: public API support exists, but platform restrictions,
  structure restrictions, or additional verification are required.
- `UNSUPPORTED`: outside the safe scope or inconsistent with the public runtime contract.
- `UNKNOWN_REQUIRES_MORE_EVIDENCE`: no safe conclusion is accepted yet.

| # | Scenario | M5-A status | Current evidence and required limitation |
|---:|---|---|---|
| 1 | Single user task activity mapping | `SUPPORTED` | Same-ID migration and explicit one-to-one mapping are verified with public APIs. |
| 2 | Same activity name but different ID | `SUPPORTED_WITH_LIMITATIONS` | Names are not authoritative; an explicit source activity ID to target activity ID mapping is required. |
| 3 | Multiple active activities | `SUPPORTED_WITH_LIMITATIONS` | Public one-to-many and many-to-one mappings exist, but exact execution-tree behavior still requires isolated tests. |
| 4 | Parallel branches | `SUPPORTED_WITH_LIMITATIONS` | Flowable documents selected parallel/inclusive execution mappings; platform support must be restricted to tested graph shapes. |
| 5 | Parallel gateway with completed branch evidence | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | Requires history plus active-execution verification without internal tables. |
| 6 | Multi-instance approval activity | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | Public mapping types do not by themselves prove safe multi-instance collection preservation. |
| 7 | Embedded subprocess | `SUPPORTED_WITH_LIMITATIONS` | Flowable documents migration into and out of embedded and nested subprocess scopes; permanent tests are still required. |
| 8 | Call Activity | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | Mapping options expose call-activity metadata, but end-to-end parent/child preservation is not yet proven. |
| 9 | Boundary Event | `SUPPORTED_WITH_LIMITATIONS` | Flowable documents timer, signal, and message boundary-event migration cases; exact subscription verification is required. |
| 10 | Timer | `SUPPORTED_WITH_LIMITATIONS` | Timer wait-state and boundary-timer shapes need job/timer evidence before acceptance. |
| 11 | Async Job | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | No evidence yet proves safe preservation or recreation of async execution jobs. |
| 12 | Pending job | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | Public ManagementService readback and job identity semantics must be tested. |
| 13 | Process variables | `SUPPORTED_WITH_LIMITATIONS` | Existing process variable preservation is verified for the simple user-task case; complex serialization and transient variables are not. |
| 14 | Execution local variable | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | Must be snapshotted and compared through public RuntimeService APIs. |
| 15 | Task local variable | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | Must be snapshotted and compared through public TaskService APIs. |
| 16 | Identity Link | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | Requires public TaskService/RuntimeService identity-link evidence before and after migration. |
| 17 | Candidate User / Candidate Group | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | Target assignment rules are not assumed to be re-evaluated; preservation behavior must be proven. |
| 18 | Suspended instance | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | M5 must fail closed until validation and migration behavior are tested explicitly. |
| 19 | Ended instance | `UNSUPPORTED` | The public operation is process-instance runtime migration; an ended instance is not an eligible running instance. |
| 20 | Historic tasks exist but no active task | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | The instance may be at another wait state; eligibility must be based on authoritative active executions, not task count alone. |
| 21 | Source and target DSL structures incompatible | `UNSUPPORTED` | Platform policy must reject incompatible compiled structures even if individual engine mappings could be forced. |
| 22 | Source activity absent from target | `SUPPORTED_WITH_LIMITATIONS` | Automatic validation fails; an explicit reviewed mapping is mandatory and must reference compiled immutable artifacts. |
| 23 | One source activity to multiple target activities | `SUPPORTED_WITH_LIMITATIONS` | Public one-to-many mapping exists; safe graph shapes and resulting execution count require permanent tests. |
| 24 | Multiple source activities to one target activity | `SUPPORTED_WITH_LIMITATIONS` | Public many-to-one mapping exists; completed-branch and scope semantics require permanent tests. |
| 25 | Concurrent task completion during migration | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | No platform/engine shared CAS is established; execution must revalidate and reconcile rather than assume atomicity. |
| 26 | Concurrent withdraw, reject, or terminate | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | These competing commands can invalidate the sealed snapshot; no automatic continuation is safe. |
| 27 | Timeout although engine migration completed | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | Public readback can inspect target definition and active state, but no invocation receipt or idempotency guarantee exists. |
| 28 | Engine returns success but post-verification differs | `SUPPORTED_WITH_LIMITATIONS` | Platform can detect inconsistency through public readback, but must enter reconciliation and must not record completion. |

## 5. Minimum safe scope indicated by current evidence

The current evidence supports only a candidate minimum scope for later review:

- one explicitly selected running process instance per engine invocation;
- exact tenant, source definition, target definition, release package, and runtime-binding
  evidence resolved by the server;
- active wait states limited to graph shapes covered by permanent engine tests;
- deterministic explicit activity mapping by immutable activity IDs;
- validation immediately before execution;
- engine call outside the platform database transaction;
- public API readback after the call;
- completion only after verification matches;
- `UNKNOWN` on timeout or connection loss;
- no automatic retry of `UNKNOWN`;
- no batch or definition-wide execution in the first production scope.

Flowable exposes definition-wide and batch migration methods, but their existence does not make
them safe for the platform's first governed implementation. M5 should prefer exact
single-instance calls so each instance has independent intent, attempt, verification, and
reconciliation evidence.

## 6. Answers to mandatory M5-A questions

### Does the official API support migration?

Yes. Flowable 8.0.0 provides public validation and migration APIs for running process
instances. Support is not equivalent to universal safe migration across every BPMN shape.

### What node types must currently be prohibited?

Until isolated evidence exists, prohibit multi-instance activities, call-activity trees,
async/pending jobs, unverified timer/subscription shapes, and any graph whose active execution
mapping is not covered by a permanent test. Ended instances and incompatible compiled DSL
structures are prohibited.

### Can it migrate multiple instances?

The public API exposes definition-wide synchronous methods and batch methods. M5 must not use
them in the initial safe scope because they weaken per-instance isolation and make UNKNOWN and
partial-result handling harder.

### Can it migrate one exact instance?

Yes. `migrate(String processInstanceId)` and `validateMigration(String processInstanceId)`
provide exact single-instance operations.

### Is there an official rollback?

No rollback operation was found in the public Flowable 8.0.0 process migration API. M5 must
not expose a fake rollback. A future attempt to move an instance back would be a separate
governed compensation migration with separate evidence.

### Is migration idempotent?

No idempotency guarantee is documented in the reviewed public API. M5 must treat invocation as
non-idempotent unless later tests and an explicit upstream contract prove otherwise.

### What happens on duplicate invocation?

Not yet safely established. A confirmed successful attempt must never be invoked again.
A timed-out or disconnected attempt must enter `UNKNOWN` and be reconciled through engine
readback rather than retried blindly.

### How can a timeout be evaluated?

Read the authoritative engine state using public services:

- runtime process instance and target process definition ID;
- active executions and activities;
- active tasks;
- process and local variables;
- jobs, timers, and subscriptions where applicable;
- identity links and candidate assignments;
- history queries needed to explain completed work.

If the state cannot uniquely prove pre-migration or post-migration status, the result remains
`UNKNOWN` and requires manual reconciliation.

### How is activity mapping proven?

Bind the plan to immutable compiled source and target artifacts, record an ordered mapping,
hash the mapping, validate it immediately before execution, and compare public engine active
state against the exact mapped target activities after the call. Activity names are not
authoritative.

### How are task, variable, job, and identity-link losses detected?

Capture bounded before snapshots and after snapshots using public Flowable services. Compare
entity type, authoritative identifiers where stable, semantic values, active state, and
counts. The current first slice only proves the simple active task, target definition, and one
process variable. Other evidence categories remain blocked.

### Does migration affect history?

Flowable documentation states that the instance is migrated including historic information.
This must not be interpreted as history remaining byte-for-byte unchanged. Exact history
semantics and preservation must be tested through HistoryService before M5 acceptance.

### Can verification avoid internal tables?

Partially, yes. RuntimeService, TaskService, ManagementService, HistoryService, and repository
queries provide public readback. Whether every required invariant can be proven without
internal tables remains open for complex jobs, subscriptions, call activities, and
multi-instance structures. Any invariant that cannot be proven through public APIs must remain
unsupported.

## 7. External-call and reconciliation risk

Flowable migration and the platform database cannot share one atomic transaction. Therefore a
future executor must separate:

1. committed execution intent and immutable attempt evidence;
2. external Flowable invocation;
3. invocation response evidence;
4. public engine readback;
5. verification;
6. completion or reconciliation evidence.

The following outcomes must remain distinct:

- not started;
- engine explicitly rejected;
- invocation failed before a response;
- timeout or connection loss;
- engine state changed but platform completion not recorded;
- platform intent/attempt recorded but engine state unchanged;
- verified success;
- verification mismatch;
- reconciliation required;
- unsafe for automatic recovery.

## 8. M5-A boundaries after this slice

Allowed next work within M5-A:

- add isolated tests for parallel, multi-instance, subprocess, call activity, boundary event,
  timer/job, variable, task-local, identity-link, suspension, concurrency, timeout simulation,
  duplicate invocation, and history evidence;
- refine this matrix based on reproducible evidence;
- add test-only adapters or fixtures using public Flowable APIs.

Still prohibited:

- `V33`;
- production migration persistence;
- a production execute, force, rollback, or reconciliation endpoint;
- a migration worker;
- browser or mobile execution controls;
- automatic migration;
- automatic retry of `UNKNOWN`;
- direct Flowable table access;
- a second permanent workflow.

## 9. Stage decision

Current M5-A decision: `SUPPORTED_WITH_LIMITATIONS`.

This decision means Flowable 8.0.0 has a real public migration capability and therefore further
M5-A testing is justified. It does not authorize M5-B or production implementation. M5-A must
stop and report again after the remaining high-risk scenarios have permanent evidence.
