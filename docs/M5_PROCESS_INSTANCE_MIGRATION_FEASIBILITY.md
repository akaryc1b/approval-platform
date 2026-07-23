# M5 Process Instance Migration Feasibility

M5-A STATUS: `CAPABILITY_VALIDATION_ONLY`

Overall conclusion: `SUPPORTED_WITH_LIMITATIONS`

This document records the first two isolated M5-A evidence slices. It does not approve production
migration execution, does not create a migration plan protocol, and does not authorize M5-B.
The conclusion must be re-evaluated after the remaining high-risk capability tests are complete.

## 1. Verified repository and engine baseline

- Initial M5 baseline: `main` at `58efb4255394fe3911700719669c4423a3ab212e`.
- M5 incorporated documentation-only PR #59 at `main`
  `9ce91f0fb71e12cd4eff04597aef3d612133d539` with merge commit
  `150da2029249ac0d701922255837d24d92b727cd`.
- Current M5 baseline after canonical-roadmap PR #60: `main` at
  `906a4fcf784f22a329102a423fe9b4ab0ba1bdc4`.
- The PR #60 baseline was incorporated with merge commit
  `48d472e90fae3fe07b3bbef404e32b95fa492475`; no rebase or force update was used.
- M4 was merged by merge commit; PR #55 is closed and must remain frozen.
- Flowable dependency baseline: `org.flowable:flowable-bom:8.0.0`.
- Flyway remains continuous through `V32`; M5-A adds no `V33`.
- The automatic PR/main validation path remains
  `.github/workflows/approval-platform-validation.yml`.
- Pre-existing manual `workflow_dispatch` utility workflows are unchanged and contain no M5
  validation.
- Production code must not query or modify Flowable `ACT_*` tables.
- Migration capability code in these slices exists only under test sources.
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
- process variables supplied for migration-time expression evaluation

Primary references:

- https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/ProcessMigrationService.html
- https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/migration/ProcessInstanceMigrationBuilder.html
- https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/migration/ActivityMigrationMapping.html
- https://www.flowable.com/open-source/docs/bpmn/ch08-ProcessInstanceMigration

The public migration builder does not expose a rollback operation. No public API contract reviewed
in M5-A declares migration calls idempotent or supplies an idempotency token, invocation receipt,
compare-and-set precondition, or atomic transaction shared with the platform database.

The official migration guide lists user tasks, receive tasks and intermediate catch events among
supported wait states. It also explicitly limits multi-instance and target-assignment behavior.
Public API availability is therefore not evidence that every BPMN graph can be migrated safely.

## 3. Isolated engine evidence

### 3.1 First slice: public surface and basic user-task mapping

`FlowableProcessInstanceMigrationCapabilityTest` uses an in-memory Flowable engine and public
services only. It establishes:

1. Flowable 8.0.0 exposes validation, exact single-instance migration, definition-wide migration,
   and batch migration methods.
2. An active single user-task wait state can be validated and migrated to a target definition when
   the activity ID is unchanged.
3. A renamed activity is not accepted by automatic ID mapping and requires an explicit
   source-to-target activity mapping.
4. After the tested migration, the runtime process definition ID changes to the target definition,
   the target active task is observable, and a process variable remains readable.
5. The public migration surface has no method represented as rollback.

### 3.2 Second slice: runtime evidence, suspension, ended instances and receive tasks

`FlowableProcessInstanceMigrationEvidenceCapabilityTest` remains test-only and uses
`RuntimeService`, `TaskService`, `HistoryService`, `RepositoryService` and
`ProcessMigrationService`. It establishes, for the exact tested shapes:

1. Direct same-ID user-task migration keeps the existing task and execution identity.
2. Existing execution-local and task-local variables remain readable after that direct migration.
3. Existing candidate-user and candidate-group identity links remain observable through the public
   task identity-link API.
4. Runtime task and process-definition references and public historic process/task references
   change to the target process definition.
5. Flowable validation accepts a suspended direct user-task instance, migration completes, and the
   process instance and task remain suspended. Platform policy must still prohibit suspended
   execution in the initial safe scope unless an explicit governed operating rule is accepted.
6. An ended instance is no longer a runtime process instance and public migration validation returns
   an invalid result.
7. A process with a completed historic user task and no active task can still be eligible when it is
   waiting at a receive task. Eligibility must use authoritative active activities, not task count.

This evidence is deliberately narrow. It does not prove preservation across execution recreation,
one-to-many or many-to-one mappings, multi-instance scopes, call activities, jobs, timers, boundary
subscriptions, arbitrary task property changes, all identity-link types, or all history semantics.

## 4. Capability matrix

Status vocabulary:

- `SUPPORTED`: directly verified in an isolated test or unambiguously supported by the Flowable
  8.0.0 public API.
- `SUPPORTED_WITH_LIMITATIONS`: public API support exists, but platform restrictions, graph-shape
  restrictions, or additional verification are required.
- `UNSUPPORTED`: outside the safe scope or inconsistent with the public runtime contract.
- `UNKNOWN_REQUIRES_MORE_EVIDENCE`: no safe conclusion is accepted yet.

| # | Scenario | M5-A status | Current evidence and required limitation |
|---:|---|---|---|
| 1 | Single user task activity mapping | `SUPPORTED` | Same-ID migration and explicit one-to-one mapping are verified with public APIs. |
| 2 | Same activity name but different ID | `SUPPORTED_WITH_LIMITATIONS` | Names are not authoritative; an explicit source activity ID to target activity ID mapping is required. |
| 3 | Multiple active activities | `SUPPORTED_WITH_LIMITATIONS` | Public one-to-many and many-to-one mappings exist, but exact execution-tree behavior still requires isolated tests. |
| 4 | Parallel branches | `SUPPORTED_WITH_LIMITATIONS` | Flowable documents selected parallel/inclusive execution mappings; platform support must be restricted to tested graph shapes. |
| 5 | Parallel gateway with completed branch evidence | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | Requires history plus active-execution verification without internal tables. |
| 6 | Multi-instance approval activity | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | Public mappings do not prove safe collection, completion-condition or child-execution preservation. |
| 7 | Embedded subprocess | `SUPPORTED_WITH_LIMITATIONS` | Flowable documents migration into and out of embedded and nested subprocess scopes; permanent tests are still required. |
| 8 | Call Activity | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | Public mapping metadata exists, but parent/child runtime and history preservation are not yet proven. |
| 9 | Boundary Event | `SUPPORTED_WITH_LIMITATIONS` | Flowable documents timer, signal and message boundary-event cases; exact subscription verification is required. |
| 10 | Timer | `SUPPORTED_WITH_LIMITATIONS` | Timer wait-state and boundary-timer shapes need job/timer evidence before acceptance. |
| 11 | Async Job | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | No evidence yet proves safe job preservation, replacement or post-migration ownership. |
| 12 | Pending job | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | Public ManagementService readback and job identity semantics must be tested. |
| 13 | Process variables | `SUPPORTED_WITH_LIMITATIONS` | Existing process-variable preservation is verified for a simple user-task case; complex serialization and transient variables are not. |
| 14 | Execution local variable | `SUPPORTED_WITH_LIMITATIONS` | Preservation is verified only for direct same-ID single user-task migration where the execution identity is retained. |
| 15 | Task local variable | `SUPPORTED_WITH_LIMITATIONS` | Preservation is verified only for direct same-ID single user-task migration where the task identity is retained. |
| 16 | Identity Link | `SUPPORTED_WITH_LIMITATIONS` | Candidate task identity links are preserved in the direct user-task case; other link types and recreated tasks remain unproven. |
| 17 | Candidate User / Candidate Group | `SUPPORTED_WITH_LIMITATIONS` | Existing candidates are preserved in the tested direct case; target assignment-rule re-evaluation must not be assumed. |
| 18 | Suspended instance | `SUPPORTED_WITH_LIMITATIONS` | Flowable accepts and preserves suspension in the tested direct case, but the platform initial safe scope must reject suspended instances unless separately governed. |
| 19 | Ended instance | `UNSUPPORTED` | Public validation rejects the ended instance because no runtime process instance remains. |
| 20 | Historic tasks exist but no active task | `SUPPORTED_WITH_LIMITATIONS` | A receive-task wait state is verified; eligibility must inspect active activities and wait-state type rather than task count. |
| 21 | Source and target DSL structures incompatible | `UNSUPPORTED` | Platform policy must reject incompatible compiled structures even if individual engine mappings could be forced. |
| 22 | Source activity absent from target | `SUPPORTED_WITH_LIMITATIONS` | Automatic validation fails; an explicit reviewed mapping is mandatory and must reference immutable compiled artifacts. |
| 23 | One source activity to multiple target activities | `SUPPORTED_WITH_LIMITATIONS` | Public one-to-many mapping exists; safe graph shapes and resulting execution count require permanent tests. |
| 24 | Multiple source activities to one target activity | `SUPPORTED_WITH_LIMITATIONS` | Public many-to-one mapping exists; completed-branch and scope semantics require permanent tests. |
| 25 | Concurrent task completion during migration | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | No platform/engine shared CAS is established; execution must revalidate and reconcile rather than assume atomicity. |
| 26 | Concurrent withdraw, reject or terminate | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | Competing commands can invalidate sealed evidence; no automatic continuation is safe. |
| 27 | Timeout although engine migration completed | `UNKNOWN_REQUIRES_MORE_EVIDENCE` | Public readback can inspect target definition and active state, but no invocation receipt or idempotency guarantee exists. |
| 28 | Engine returns success but post-verification differs | `SUPPORTED_WITH_LIMITATIONS` | The platform can detect inconsistency through public readback, but must enter reconciliation and must not record completion. |

## 5. Minimum safe scope indicated by current evidence

The current evidence supports only a candidate minimum scope for later review:

- one explicitly selected running process instance per engine invocation;
- instance must be active, not suspended, ended, terminated or otherwise stale under platform
  policy, even where the engine technically accepts a suspended migration;
- source and target definitions, release packages and runtime-binding evidence resolved by the
  server;
- active wait states limited to exact graph shapes covered by permanent engine tests;
- deterministic explicit activity mapping by immutable activity IDs;
- validation immediately before execution;
- engine call outside the platform database transaction;
- public API before/after snapshots for runtime state, task state, variables, identity links,
  jobs/subscriptions where applicable, and history;
- completion only after verification matches;
- `UNKNOWN` on timeout or connection loss;
- no automatic retry of `UNKNOWN`;
- no batch or definition-wide execution in the first production scope.

Flowable exposes definition-wide and batch migration methods, but their existence does not make
them safe for the platform's first governed implementation. M5 should prefer exact
single-instance calls so each instance has independent intent, attempt, verification and
reconciliation evidence.

## 6. Answers to mandatory M5-A questions

### Does the official API support migration?

Yes. Flowable 8.0.0 provides public validation and migration APIs for running process instances.
Support is not equivalent to universal safe migration across every BPMN shape.

### What node types must currently be prohibited?

Until isolated evidence exists, prohibit multi-instance activities, call-activity trees,
async/pending jobs, unverified timer/subscription shapes and any graph whose active execution
mapping is not covered by a permanent test. Ended instances and incompatible compiled DSL
structures are prohibited. Suspended instances are also prohibited by platform policy in the
candidate initial scope even though the tested engine operation preserved suspension.

### Can it migrate multiple instances?

The public API exposes definition-wide synchronous methods and batch methods. M5 must not use them
in the initial safe scope because they weaken per-instance isolation and make UNKNOWN and
partial-result handling harder.

### Can it migrate one exact instance?

Yes. `migrate(String processInstanceId)` and `validateMigration(String processInstanceId)` provide
exact single-instance operations.

### Is there an official rollback?

No rollback operation was found in the public Flowable 8.0.0 process migration API. M5 must not
expose a fake rollback. A future attempt to move an instance back would be a separate governed
compensation migration with separate evidence.

### Is migration idempotent?

No idempotency guarantee is documented in the reviewed public API. M5 must treat invocation as
non-idempotent unless later tests and an explicit upstream contract prove otherwise.

### What happens on duplicate invocation?

Not yet safely established. A confirmed successful attempt must never be invoked again. A
timed-out or disconnected attempt must enter `UNKNOWN` and be reconciled through engine readback
rather than retried blindly.

### How can a timeout be evaluated?

Read the authoritative engine state using public services:

- runtime process instance and target process definition ID;
- active executions and activities;
- active tasks and suspension state;
- process, execution-local and task-local variables;
- jobs, timers and subscriptions where applicable;
- identity links and candidate assignments;
- history queries needed to explain completed work.

If the state cannot uniquely prove pre-migration or post-migration status, the result remains
`UNKNOWN` and requires manual reconciliation.

### How is activity mapping proven?

Bind the plan to immutable compiled source and target artifacts, record an ordered mapping, hash
the mapping, validate it immediately before execution, and compare public engine active state
against the exact mapped target activities after the call. Activity names are not authoritative.

### How are task, variable, job, and identity-link losses detected?

Capture bounded before and after snapshots using public Flowable services. Compare entity type,
authoritative identifiers where stable, semantic values, active/suspended state and counts.
The second slice proves process variables plus execution-local variables, task-local variables and
candidate identity links for a direct single user-task migration. Jobs, timers, subscriptions,
recreated tasks and complex scopes remain blocked.

### Does migration affect history?

Yes. In the tested direct user-task and receive-task cases, public `HistoryService` queries show
that historic process and task references are updated to the target process definition.
This is not byte-for-byte immutability of engine history. The platform must preserve its own
source release/runtime-binding evidence and must record migration as additional immutable platform
evidence. Complex history semantics remain unproven.

### Can verification avoid internal tables?

Partially, yes. The first two slices verify runtime definitions, active activities, tasks,
process/execution/task-local variables, candidate task identity links, suspension and selected
history references through public services only. Whether every required invariant can be proven
without internal tables remains open for jobs, subscriptions, call activities, multi-instance and
complex parallel structures. Any invariant that cannot be proven through public APIs must remain
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

- add isolated tests for parallel and completed-branch structures;
- add isolated tests for multi-instance, subprocess and call-activity trees;
- add isolated tests for boundary events, timers, async jobs and pending jobs;
- add concurrency tests for task completion, withdraw, reject and terminate races;
- simulate duplicate invocation, timeout and verification mismatch;
- refine this matrix based on reproducible public API evidence;
- add test-only adapters or fixtures using public Flowable APIs.

Still prohibited:

- `V33`;
- production migration persistence;
- a production execute, force, rollback or reconciliation endpoint;
- a migration worker;
- browser or mobile execution controls;
- automatic migration;
- automatic retry of `UNKNOWN`;
- direct Flowable table access;
- a temporary or second M5 workflow.

## 9. Stage decision

Current M5-A decision: `SUPPORTED_WITH_LIMITATIONS`.

This decision means Flowable 8.0.0 has a real public migration capability and further M5-A testing
is justified. It does not authorize M5-B or production implementation. M5-A must stop and report
again after the remaining high-risk scenarios have permanent evidence.
