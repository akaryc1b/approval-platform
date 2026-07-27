# M5-D — Governed Server-side Migration Execution Protocol

## Governance status

- M5-A: `ACCEPTED` / `SUPPORTED_WITH_LIMITATIONS`
- M5-B: `ACCEPTED / PERMANENTLY_VALIDATED`
- M5-C: `ACCEPTED / PERMANENTLY_VALIDATED`
- M5-D1: `PERMANENTLY_VALIDATED`
- M5-D2: `ACCEPTED / PERMANENTLY_VALIDATED`
- M5-D3: `COMPLETE / PERMANENTLY_VALIDATED`
- M5-D4: `COMPLETE / PERMANENTLY_VALIDATED`
- M5-D5: `COMPLETE / PERMANENTLY_VALIDATED`
- M5-D6: `COMPLETE / PERMANENTLY_VALIDATED`
- M5-D7 through M5-D8: not started
- Current M5-D overall result: `IN_PROGRESS`
- Production migration execution: `NOT_AUTHORIZED`
- M5-E, M5-F and M5-G: not started

M5-D is server-owned execution infrastructure. D6 completion does not authorize production execution, a public execution/reconciliation endpoint, a Web or Mobile execution control, a resident scheduler, definition-wide migration, automatic `UNKNOWN` retry, force success, rollback, or cross-system atomicity.

## Verified baseline and Flyway allocation

The accepted persistence protocol is Flyway V33–V37. The immutable-plan protocol is V38. Governed execution slices add:

- V39: exact authorized-plan consumption and intent admission;
- V40: exact initial-attempt provisioning, bounded claim evidence and the shared tenant/instance command fence;
- V41: immutable single-instance engine request/outcome evidence;
- V42: request/outcome guards bound to immutable attempt payload and exact consumed target plan;
- V43: exact bounded verification evidence and deterministic classification lineage;
- V44: exact-target runtime-binding CAS, immutable binding-revision history, per-instance completion evidence and observed CAS-conflict evidence;
- V45: independent reconciliation lease, append-only lease events and immutable UNKNOWN observation evidence;
- V46: UNKNOWN-derived terminal engine-request lineage preservation.

V1–V46 are immutable. Before each later migration allocation or final ref update, active M6 pull requests #67–#70 must be rechecked. M5 and M6 remain independent branches and code lines.

## Permanent invariants

1. Production code never reads or writes Flowable `ACT_*` tables.
2. Flowable 8 public APIs are the only engine integration boundary.
3. A Flowable call or readback never runs inside a platform database transaction.
4. A successful engine API return is not a verified migration result.
5. An exception does not prove that the engine made no change.
6. `UNKNOWN` is durable and never automatically retried.
7. A started migration has no fabricated rollback semantics.
8. Only exact selected instances in one sealed plan may be processed.
9. One attempt processes exactly one approval instance.
10. Stale workers and reconcilers are fenced by revision and lease evidence.
11. Runtime binding changes only after exact target verification and D5 CAS.
12. Runtime-binding CAS conflict after engine mutation enters reconciliation.
13. Audit failure fails closed.
14. Tenant, operator, worker, engine identity and verification result are server-owned.
15. Execution, worker and automatic reconciliation default to disabled.
16. Evidence is bounded, redacted and append-only.
17. Metrics may use closed low-cardinality labels only.
18. M5 remains independent from all M6 branches and code.
19. No D5 or D6 path may redispatch the migration call.
20. No platform state may claim rollback of a completed or ambiguous Flowable call.

## Implemented application services

- `ApprovalMigrationExecutionAdmissionService`: exact plan consumption and governed intent creation; no engine call.
- `ApprovalMigrationAttemptClaimService`: exact initial-attempt provisioning followed by one bounded tenant claim; server-owned worker identity; no engine call.
- `ApprovalMigrationOneShotClaimRunner`: internal one-shot claim adapter; default disabled; no scheduler.
- `ApprovalMigrationSingleInstanceExecutor`: short transaction A, one transaction-free Flowable instance call, short transaction B.
- `ApprovalMigrationSingleInstanceExecutor.OneShotRunner`: internal default-disabled execution gate; no loop or scheduler.
- `ApprovalMigrationExactVerificationService`: short transaction A, one transaction-free bounded Flowable readback, short transaction B.
- `ApprovalMigrationExactVerificationService.OneShotRunner`: internal default-disabled verification gate.
- `ApprovalMigrationRuntimeBindingCasService`: exact-target platform completion through D5 CAS; no Flowable call.
- `ApprovalMigrationRuntimeBindingCasService.OneShotRunner`: internal default-disabled CAS gate; no loop or scheduler.
- `ApprovalMigrationReconciliationService`: short prepare transaction, one transaction-free bounded public readback, short finalize transaction; no migration dispatch.
- `ApprovalMigrationReconciliationService.OneShotRunner`: internal three-switch reconciliation gate; no loop or scheduler.

## Implemented ports and adapters

- PostgreSQL owns admission atomicity, attempt provisioning, claim replay, revision CAS, lease fencing and immutable evidence.
- `ApprovalMigrationAttemptProvisioningStore` derives initial attempts only from one current consumed plan, exact selected instances and current runtime bindings.
- `ApprovalMigrationAttemptClaimStore` owns bounded claim batches, renewal and expiry takeover.
- `ApprovalInstanceCommandFence` is shared by migration claims and approval business-command mutations.
- `ProcessInstanceMigrationPort` accepts one exact instance and exposes no definition-wide or multi-instance request.
- `FlowableProcessInstanceMigrationAdapter` uses public Flowable APIs to validate and invoke one exact instance migration.
- `ApprovalMigrationEngineExecutionStore` owns the two short execution transactions and does not invoke Flowable.
- `JdbcApprovalMigrationEngineExecutionStore` writes immutable request/outcome, attempt-event and audit evidence while rechecking plan, intent, binding, fence and lease authority.
- `ProcessInstanceVerificationPort` accepts one exact tenant and engine instance and exposes only bounded readback.
- `FlowableProcessInstanceVerificationAdapter` uses public runtime, task, job, timer, subscription and history APIs only.
- `ApprovalMigrationExactVerificationStore` owns the two short verification transactions and does not read Flowable.
- `JdbcApprovalMigrationExactVerificationStore` writes exact request/evidence lineage and audit atomically, supports exact replay and rejects changed-payload replay.
- `ApprovalMigrationRuntimeBindingCasStore` accepts only server-owned exact lineage and expected revisions.
- `JdbcApprovalMigrationRuntimeBindingCasStore` owns one short D5 platform transaction and performs no engine call.
- `PostgresSerializedApprovalMigrationRuntimeBindingCasStore` serializes same tenant/attempt completion across nodes before any replay read and explicitly releases pooled PostgreSQL advisory-lock sessions.
- `ApprovalMigrationReconciliationStore` owns two short D6 platform transactions around one public read and exposes no dispatch operation.
- `JdbcApprovalMigrationReconciliationExecutionStore` owns independent reconciliation leases, immutable observations, exact replay, terminal/manual decisions and audit atomicity without reading or mutating Flowable.
- No M5-D Controller, REST route, Web action or Mobile action is permitted.

## D1 plan consumption and intent admission

D1 accepts authenticated request context, exact plan ID, exact plan hash and a bounded reason. The caller cannot provide tenant, operator, authorizer, selected count, release/deployment identity, runtime binding, engine identity or intent status.

One short PostgreSQL transaction:

1. locks the exact tenant-scoped plan;
2. requires current unexpired `AUTHORIZED` state and exact authorization evidence;
3. rechecks exact source/target release packages and target deployment identity;
4. creates one initial `PENDING` governed intent and event;
5. inserts immutable plan-consumption evidence;
6. transitions `AUTHORIZED revision 2 -> CONSUMED revision 3`;
7. appends plan event and audit;
8. validates deferred plan/intent/consumption links before commit.

Exact replay returns authoritative existing plan, intent and consumption with no duplicate event or audit. Changed payload, expired authority, second consumption or evidence mutation fails closed. D1 creates no attempts, claims no work, invokes no Flowable API and changes no runtime binding.

## D2 initial-attempt provisioning

One short transaction:

1. locks the exact tenant-scoped intent;
2. requires the linked plan to remain current `CONSUMED` evidence;
3. reads exact selected instances in canonical sequence;
4. requires each approval instance to remain `RUNNING`;
5. requires current runtime binding to match plan source release, expected binding hash and source engine definition;
6. inserts one initial `PENDING` attempt and event per exact selected instance;
7. appends one bounded audit record.

Exact replay creates no duplicate attempt, event or audit. Binding drift, instance-state drift, partial pre-existing provisioning, plan/intent mismatch and audit failure fail closed.

## D2 shared command fence and bounded claim

The claim query is tenant/intent scoped, deterministically ordered, bounded to 1–100 rows, uses `FOR UPDATE SKIP LOCKED`, is supported by `idx_process_migration_attempt_claim_v40`, and records immutable claim-batch evidence even for an empty result.

Migration claim and approval business-command mutation acquire the same tenant/instance serialization key:

```text
pg_advisory_xact_lock(hashtextextended(
  'approval-instance-command:v1:' || tenantId || ':' || approvalInstanceId,
  0
))
```

An unexpired active migration lease rejects business commands. At exact expiry the old lease no longer fences business work.

Same-owner renewal is allowed only before expiry and only with strict lease extension. Different-owner takeover is allowed only at or after expiry. Every attempt and fence transition advances exactly one revision, records explicit lease actor evidence, and appends attempt/fence/audit evidence atomically.

## D3 governed single-instance engine request

### Short transaction A

1. locks the exact `CLAIMED` attempt and expected revision;
2. requires current server-owned worker and unexpired lease;
3. acquires shared tenant/instance command serialization;
4. locks and verifies the active migration fence and fence revision;
5. verifies current runtime binding evidence hash, engine instance and source definition;
6. verifies current `RUNNING` intent and exact consumed target plan/release/deployment identity;
7. writes one immutable bounded engine request and deterministic evidence hashes;
8. transitions `CLAIMED -> ENGINE_REQUESTED` and appends attempt-event/audit evidence;
9. commits before the engine adapter is called.

### Transaction-free Flowable dispatch

Outside every platform transaction, the adapter reads one exact runtime instance and source/target definitions through public APIs, constructs a bounded value-free pre-dispatch snapshot, rejects unsupported or stale evidence, calls public `validateMigration(engineInstanceId)`, and invokes `migrate(engineInstanceId)` at most once only after validation succeeds.

Unsupported parallel/multi-instance/subprocess/call-activity shape, unsafe job/timer evidence, stale task/activity mapping, missing/suspended runtime, tenant/source/target mismatch, deployment drift or truncation rejects dispatch. The adapter cannot mark platform success.

### Short transaction B

The store re-locks exact attempt/request lineage, reacquires the shared fence, rechecks worker/fence/lease authority, appends one immutable bounded engine outcome and audit, and advances state atomically:

- returned call: `ENGINE_REQUESTED -> VERIFYING`, `EngineOutcome.ACCEPTED`;
- pre-dispatch rejection: governed terminal rejection with no migration call;
- timeout, response loss, interruption, connection reset, crash ambiguity or incomplete response: durable `UNKNOWN`, `ENGINE_OUTCOME_UNKNOWN`.

No ambiguity path calls migration again.

## D4 exact bounded verification

### Short transaction A

D4 locks exact `VERIFYING` attempt/request/outcome lineage, requires one returned engine call, verifies tenant/intent/attempt/instance/source/target identity, creates one deterministic server-owned verification request hash, and commits before readback.

Exact persisted verification replay performs no second Flowable read. Changed-payload replay fails closed.

### Transaction-free public readback

The bounded snapshot contains runtime/deployment identity, active activity IDs, execution/activity correlation, active tasks, executable/timer/suspended/dead-letter job evidence, event subscriptions, allowlisted variable hashes, bounded identity-link hashes, historic definition/end/delete evidence, bounded historic tasks, deterministic snapshot hash and truncation indicator.

Credentials, tokens, secret values, attachment bytes, arbitrary serialized objects, unfiltered variables, unbounded history and unbounded identity links are never stored.

The public `Execution` API does not expose process-definition identity. D4 correlates public execution ID/activity evidence with public unfinished historic-activity evidence. Missing or conflicting correlation fails closed; no Flowable implementation class or `ACT_*` table is used.

### Deterministic classification

The closed classification vocabulary is:

- `EXACT_TARGET_RUNTIME`;
- `EXACT_SOURCE_RUNTIME`;
- `SOURCE_HISTORY_TERMINAL`;
- `TARGET_HISTORY_TERMINAL`;
- `MIXED_SOURCE_TARGET_EVIDENCE`;
- `MISSING_NO_EVIDENCE`;
- `STALE_OR_CONTRADICTORY_EVIDENCE`;
- `TRUNCATED_MANUAL_REVIEW_REQUIRED`;
- `READ_FAILURE_RECONCILIATION_REQUIRED`;
- `INCOMPLETE_RECONCILIATION_REQUIRED`.

A target runtime with any source-bound execution/activity, task, job, timer or subscription evidence is not exact target verified.

### Short transaction B

D4 re-locks exact lineage, checks the same request hash, appends immutable verification and audit evidence, retains `VERIFYING` only for exact target, and routes every other result to reconciliation-required semantics. D4 never modifies runtime binding, releases a completed fence or marks an attempt `SUCCEEDED`.

## D5 exact-target runtime-binding CAS

D5 may execute only for one current exact D4 `EXACT_TARGET_RUNTIME` verification with complete, non-truncated evidence.

The worker provides only tenant, attempt, verification, worker and expected attempt/fence/binding revisions plus request correlation. Target release/package/deployment/definition identity and result status remain server-owned.

### Cross-node replay serialization

Before any replay read, `PostgresSerializedApprovalMigrationRuntimeBindingCasStore` acquires a PostgreSQL session advisory lock keyed by tenant and attempt:

```text
approval-migration-binding-cas:<tenantId>:<attemptId>
```

The lock is explicitly released with `pg_advisory_unlock` before the connection is returned to a pool. Two application nodes therefore cannot both pass a stale pre-lock replay read. Exact same-payload replay returns `REPLAYED_COMPLETION` or `REPLAYED_CONFLICT`; changed payload fails closed.

### Successful short transaction

One short PostgreSQL transaction:

1. checks authoritative completion/conflict replay;
2. locks exact `VERIFYING` attempt and revision;
3. locks exact D4 verification and requires `EXACT_TARGET_RUNTIME`;
4. locks exact consumed plan, target release package and deployment authority;
5. acquires shared instance command serialization;
6. locks the active worker-owned command fence and expected revision;
7. locks the current `RUNNING` approval-instance projection;
8. locks current runtime binding and checks binding revision, original evidence hash, source release/package and source engine definition;
9. performs runtime-binding CAS to revision `n + 1` and exact target identity;
10. appends immutable runtime-binding revision evidence;
11. updates approval-instance release projection with version CAS;
12. appends one immutable per-attempt completion record;
13. transitions `VERIFYING -> SUCCEEDED`, `EngineOutcome.CONFIRMED`;
14. appends attempt event;
15. releases the exact command fence and appends fence event;
16. appends completion audit evidence;
17. commits all platform state together.

Audit failure rolls back binding, binding evidence, projection, completion, attempt, attempt event, fence, fence event and audit.

### CAS conflict handoff

If exact engine target verification exists but platform binding/projection authority no longer exactly matches the expected source CAS:

- runtime binding and approval projection are not changed;
- observed binding revision/hash/release/package/definition evidence is recorded;
- one immutable binding-CAS conflict is appended;
- attempt transitions `VERIFYING -> RECONCILING` with `VERIFICATION_MISMATCH`;
- conflict audit evidence is appended;
- the migration command fence is not reused as D6 read authority;
- migration is never redispatched and rollback is never fabricated.

V44 guards enforce monotonic binding revision, exact D4 verification lineage, predecessor hash, append-only revision/completion/conflict evidence, completion only with updated binding plus `SUCCEEDED/CONFIRMED` and released fence, and conflict only with `RECONCILING/VERIFICATION_MISMATCH`.

## Implemented cross-system transaction boundary

```text
short database transaction A — D3 execution request
  validate exact plan/intent/attempt/binding/fence/lease evidence
  persist immutable engine request
  CLAIMED -> ENGINE_REQUESTED
commit

no platform database transaction
  Flowable public validateMigration / migrate one exact instance at most once

short database transaction B — D3 engine outcome
  fence stale owner/revision/lease
  persist bounded outcome
  returned call -> VERIFYING
  ambiguous call -> UNKNOWN
commit

short database transaction A — D4 verification request
  validate exact VERIFYING attempt/request/outcome lineage
  persist deterministic verification request identity
commit

no platform database transaction
  Flowable public bounded readback for one exact instance

short database transaction B — D4 verification evidence
  revalidate lineage and request hash
  append immutable verification and audit
  exact target remains VERIFYING pending D5 CAS
  every other result requires reconciliation/manual review
commit

cross-node D5 tenant/attempt advisory lock
short database transaction — D5 platform completion
  exact replay or exact authority checks
  runtime-binding CAS and immutable history
  projection CAS
  completion or observed conflict evidence
  attempt/fence/audit transition
commit
explicitly release D5 advisory lock before pooled connection return

short database transaction A — D6 reconciliation preparation
  require durable AMBIGUOUS_UNKNOWN request/outcome lineage
  append OPEN reconciliation evidence
  UNKNOWN -> RECONCILING when needed
  acquire independent reconciliation lease and event
commit

no platform database transaction
  one bounded public runtime/task/job/timer/subscription/history readback

short database transaction B — D6 reconciliation finalization
  revalidate attempt, request hash and independent lease authority
  append immutable observation and server-derived disposition
  append terminal or manual reconciliation evidence
  preserve original ambiguous engine request lineage
  release reconciliation lease and append audit
  never redispatch migration and never mutate runtime binding
commit
```

No Flowable operation participates in a platform transaction. There is no two-phase commit and no claimed rollback across Flowable and PostgreSQL.

## D6 durable UNKNOWN and reconciliation

Timeout, connection reset, lost response, crash after dispatch, incomplete result and every D3 `AMBIGUOUS_UNKNOWN` outcome preserve the fact that the one migration call may have occurred. D6 observes that attempt; it never repeats the call.

### Short preparation transaction

D6 accepts only tenant, attempt, server-owned worker, expected attempt revision and request correlation. The transaction:

1. locks the exact tenant-scoped attempt;
2. requires current `UNKNOWN`, or an eligible `RECONCILING` expired-lease takeover;
3. requires `EngineOutcome.UNKNOWN`, the original engine request reference and exact D3 `AMBIGUOUS_UNKNOWN` outcome;
4. appends the first `OPEN` reconciliation sequence when entering from `UNKNOWN`;
5. transitions `UNKNOWN -> RECONCILING` while preserving request evidence;
6. creates or takes over one independent reconciliation lease with revision fencing;
7. appends the matching lease event and audit;
8. commits before engine readback.

The independent lease cannot authorize migration dispatch. Same-owner renewal requires a strict extension before expiry. Different-owner takeover requires expiry. Stale owners and stale revisions fail closed.

### Transaction-free public readback

D6 invokes one `ProcessInstanceVerificationPort.readOne` call through the same bounded public runtime, task, job, timer, subscription and history boundary used by D4. No platform transaction is open.

Stable and unexpected read failures become bounded server-owned evidence. Snapshot, classification and disposition are never caller supplied. Classification is recomputed from immutable source/target definition identity and the observed bounded snapshot.

### Closed dispositions and outcomes

The persistent disposition vocabulary is:

- `SOURCE_CONFIRMED_NO_RETRY`;
- `SOURCE_TERMINAL_CONFIRMED_NO_RETRY`;
- `TARGET_CONFIRMED_BINDING_CAS_REQUIRED`;
- `TARGET_TERMINAL_BINDING_CAS_REQUIRED`;
- `MANUAL_REVIEW_REQUIRED`.

The only outcomes are:

| Observation | Reconciliation | Attempt | Runtime binding |
| --- | --- | --- | --- |
| exact source runtime | `RESOLVED_SOURCE` | `BLOCKED_STALE` | unchanged |
| source terminal history | `RESOLVED_TERMINAL` | `FAILED_TERMINAL` | unchanged |
| exact target runtime | `MANUAL_REVIEW_REQUIRED` | `RECONCILING` | unchanged; separate D5-compatible CAS handoff required |
| target terminal history | `MANUAL_REVIEW_REQUIRED` | `RECONCILING` | unchanged; manual terminal handling required |
| mixed, missing, stale, truncated, incomplete or read failure | `MANUAL_REVIEW_REQUIRED` | `RECONCILING` | unchanged |

Source evidence never creates retry authority. Target evidence alone never changes the platform binding or claims success. D6 does not itself invoke D5 CAS.

### Atomic finalization and replay

One short final transaction locks the exact attempt, OPEN reconciliation and active lease; rechecks worker, attempt revision, lease revision/expiry, request hash and original ambiguous outcome; appends one immutable observation and server-derived conclusion; applies only the closed result above; releases the reconciliation lease; appends lease event and audit; and commits all platform evidence together.

Exact same-payload replay returns stored evidence and performs no second engine read. Changed payload fails closed. An active different-owner lease rejects preparation; an expired lease may be taken over exactly once through revision CAS. Audit failure rolls back prepare or finalize platform state atomically.

V45 makes lease events and observations append-only and rejects direct mutation or deletion. V46 permits `BLOCKED_STALE` or `FAILED_TERMINAL` to retain an engine request reference only where `engine_outcome='UNKNOWN'`. UNKNOWN-derived terminal rows and events therefore preserve the exact original request lineage without authorizing redispatch.

D6 remains internal and default disabled. There is no scheduler, polling loop, Controller, REST route, Web action or Mobile action.

## D7 canary, bounded execution and kill switch — planned only

A later bounded executor must select one deterministic canary. Remaining instances become eligible only after exact canary target verification and successful D5 binding CAS. `UNKNOWN`, `RECONCILING` or manual review pauses the plan by default.

A kill switch may prevent new engine calls but cannot pretend to cancel a call already dispatched.

## D8 plan-level aggregation — planned only

Plan-level completion may aggregate only immutable per-instance terminal evidence. It may not infer success from an engine API return, hide unresolved instances, overwrite evidence or authorize production execution.

## Default-disabled configuration

Defaults remain equivalent to:

```properties
approval.migration.execution.enabled=false
approval.migration.worker.enabled=false
approval.migration.reconciliation.automatic.enabled=false
```

Missing, malformed or incomplete configuration fails closed.

## Permanent evidence

- D1: `docs/M5_D1_AUTHORIZED_PLAN_ADMISSION_EVIDENCE.md`
- D2 implementation: `docs/M5_D2_SHARED_COMMAND_FENCE_CLAIM_EVIDENCE.md`
- D2 formal acceptance: `docs/M5_D2_GOVERNANCE_ACCEPTANCE.md`
- D3: `docs/M5_D3_SINGLE_INSTANCE_EXECUTOR_PERMANENT_EVIDENCE.md`
- D4: `docs/M5_D4_EXACT_VERIFICATION_PERMANENT_EVIDENCE.md`
- D5: `docs/M5_D5_RUNTIME_BINDING_CAS_PERMANENT_EVIDENCE.md`
- D6: `docs/M5_D6_DURABLE_UNKNOWN_RECONCILIATION_PERMANENT_EVIDENCE.md`

D6 permanent evidence includes domain/application/PostgreSQL tests, source no-retry, target CAS-required/manual handling, exact replay, expiry takeover, prepare/finalize audit rollback, V45/V46 fresh and historical upgrades, 5,000-row preservation, permanent Node boundaries, raw Actions logs and independently verified Maven/Vben/Mobile/Hygiene artifact digests.

## Stop conditions

M5-D2 is `ACCEPTED / PERMANENTLY_VALIDATED`. M5-D3, D4, D5 and D6 are `COMPLETE / PERMANENTLY_VALIDATED`. M5-D overall remains `IN_PROGRESS`.

Work stops before M5-D7. D7–D8, M5-E management API/UI, M5-F full fault-injection and observability, M5-G merge readiness, production execution authorization, Ready-for-review, auto-merge, merge and issue closure remain blocked.
