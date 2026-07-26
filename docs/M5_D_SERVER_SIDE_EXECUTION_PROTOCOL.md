# M5-D — Governed Server-side Migration Execution Protocol

## Governance status

- M5-A: `ACCEPTED` / `SUPPORTED_WITH_LIMITATIONS`
- M5-B: `ACCEPTED / PERMANENTLY_VALIDATED`
- M5-C: `ACCEPTED / PERMANENTLY_VALIDATED`
- M5-D1: `PERMANENTLY_VALIDATED`
- M5-D2: `ACCEPTED / PERMANENTLY_VALIDATED`
- M5-D3: `COMPLETE / PERMANENTLY_VALIDATED`
- M5-D4: `COMPLETE / PERMANENTLY_VALIDATED`
- M5-D5 through M5-D8: not started
- Current M5-D overall result: `IN_PROGRESS`
- Production migration execution: `NOT_AUTHORIZED`
- M5-E, M5-F and M5-G: not started

M5-D is server-owned execution infrastructure. D4 completion does not authorize production execution, a public execution endpoint, a Web or Mobile execution control, an automatic scheduler, force success, rollback, runtime-binding mutation without D5 CAS, or automatic retry of an ambiguous engine result.

## Verified baseline and migration decision

The accepted persistence protocol is Flyway V33–V37. The accepted immutable-plan protocol is Flyway V38. M5-D1 adds V39 for exact plan consumption and intent admission. M5-D2 adds V40 for exact attempt provisioning support, bounded claim evidence and the shared tenant/instance command fence. M5-D3 adds V41 for immutable engine request/outcome evidence and V42 to bind database guards to the immutable attempt payload identity and exact consumed target plan. M5-D4 adds V43 for exact verification request/evidence lineage, deterministic replay and append-only guards. V1–V43 remain immutable.

Before V41, V42 and V43 were allocated, active M6 pull requests #67–#70 were rechecked and contained no Flyway migration. M5 and M6 remain independent branches and code lines.

Immediately before every later M5 migration or final ref update, active M6 pull requests #67–#70 must be rechecked again.

## Permanent invariants

1. Production code never reads or writes Flowable `ACT_*` tables.
2. Flowable 8.0.0 public APIs are the only engine integration boundary.
3. A Flowable call or readback never runs inside a platform database transaction.
4. A successful API response is not a verified migration result.
5. An exception does not prove that the engine made no change.
6. `UNKNOWN` is durable and never automatically retried.
7. A started migration has no fabricated rollback semantics.
8. Only exact selected instances in one sealed plan may be processed.
9. One attempt processes exactly one approval instance.
10. Stale workers and reconcilers are fenced by revision and lease evidence.
11. Runtime binding changes only after exact target verification and D5 CAS.
12. Runtime-binding CAS failure after engine mutation enters reconciliation.
13. Audit failure fails closed.
14. Tenant, operator, worker, engine identity and verification result are server-owned.
15. Execution, worker and automatic reconciliation default to disabled.
16. Evidence is bounded, redacted and append-only.
17. Metrics use closed low-cardinality labels only.
18. M5 remains independent from all M6 branches and code.

## Architecture boundaries

### Implemented application services

- `ApprovalMigrationExecutionAdmissionService`: exact plan consumption and M5-B intent creation; no engine call.
- `ApprovalMigrationAttemptClaimService`: exact initial-attempt provisioning followed by one bounded tenant claim; server-owned worker identity; no engine call.
- `ApprovalMigrationOneShotClaimRunner`: internal one-shot claim adapter; disabled by default; no scheduler.
- `ApprovalMigrationSingleInstanceExecutor`: prepares one immutable request in short transaction A, invokes one Flowable instance outside the platform transaction, then performs one fenced outcome finalization in short transaction B.
- `ApprovalMigrationSingleInstanceExecutor.OneShotRunner`: internal one-shot execution gate; both execution and worker flags must be explicitly enabled; no loop or scheduler.
- `ApprovalMigrationExactVerificationService`: prepares one exact verification read in short transaction A, invokes one bounded public Flowable readback outside the platform transaction, then appends immutable verification evidence and audit in short transaction B.
- `ApprovalMigrationExactVerificationService.OneShotRunner`: internal default-disabled verification gate; no loop, scheduler or public surface.

### Implemented ports and adapters

- PostgreSQL owns admission atomicity, attempt provisioning, claim replay, revision CAS, lease fencing and immutable evidence.
- `ApprovalMigrationAttemptProvisioningStore` derives initial attempts only from one current consumed plan, exact selections and current runtime bindings.
- `ApprovalMigrationAttemptClaimStore` owns bounded claim batches, renewal and expiry takeover.
- `ApprovalInstanceCommandFence` is shared by migration claims and approval business-command mutations.
- `ProcessInstanceMigrationPort` accepts one exact instance and exposes no definition-wide or multi-instance request.
- `FlowableProcessInstanceMigrationAdapter` uses Flowable 8.0.0 public APIs to create, validate and invoke one single-instance migration builder.
- `ApprovalMigrationEngineExecutionStore` owns the two short execution transactions and does not invoke Flowable.
- `JdbcApprovalMigrationEngineExecutionStore` writes immutable request/outcome evidence, attempt events and audits while rechecking attempt, lease, fence, binding, intent and consumed plan identity.
- `ProcessInstanceVerificationPort` accepts one exact tenant and engine instance, and exposes only bounded readback.
- `FlowableProcessInstanceVerificationAdapter` uses public runtime, task, management and history APIs only.
- `ApprovalMigrationExactVerificationStore` owns the two short verification transactions and does not read Flowable.
- `JdbcApprovalMigrationExactVerificationStore` writes exact request/evidence lineage and audit atomically, supports exact replay and rejects changed-payload replay.
- No M5-D Controller, REST route, Web action or Mobile action is permitted.

### Future D5–D8 boundaries

Runtime-binding CAS, completion evidence, durable `UNKNOWN` reconciliation, canary/bounded batch control and plan-level completion aggregation remain separate future slices. D4 does not implement or imply them.

## D1 plan consumption and intent admission

### Trusted input

The D1 service accepts authenticated `RequestContext`, exact plan ID, exact plan hash and a bounded reason. The caller cannot provide tenant, operator, authorizer, trusted permission, selected count, release identity, deployment identity, runtime binding, engine identity or intent status.

### Admission validation

Admission requires:

- an exact tenant-scoped plan ID and plan hash;
- current `AUTHORIZED` state;
- unexpired plan and authorization;
- exact current authorization evidence;
- exact current source and target release packages;
- exact active target release;
- exact deployed target deployment, engine deployment, engine definition and version;
- one unused plan and one unchanged idempotency request hash.

### Atomic short transaction

One PostgreSQL transaction:

1. reads and locks the exact plan;
2. creates one initial `PENDING` M5-B intent and event;
3. inserts immutable plan-consumption evidence;
4. transitions `AUTHORIZED revision 2 -> CONSUMED revision 3`;
5. appends the plan event;
6. appends the audit event;
7. validates deferred plan/intent/consumption links before commit.

V39 rejects consumed plans or governed intents without exact counterpart evidence, mismatched identity or hashes, second consumption, changed-payload replay, evidence mutation and expired admission.

Exact replay returns the authoritative existing plan, intent and consumption and adds no duplicate event or audit. D1 never creates attempts, claims work, invokes Flowable or mutates runtime binding.

## D2 initial-attempt provisioning

D2 first creates claimable work from exact governed evidence rather than relying on pre-seeded attempts.

One short PostgreSQL transaction:

1. locks the exact tenant-scoped intent;
2. requires the linked plan to be current `CONSUMED` evidence;
3. reads every exact selected instance in canonical sequence;
4. requires the approval instance to remain `RUNNING`;
5. requires current runtime binding to match the plan source release, expected binding hash and source engine definition;
6. inserts one initial `PENDING` attempt and initial event per exact selected instance;
7. appends one bounded audit record;
8. commits before claim.

Exact replay returns the authoritative initial attempts and creates no duplicate attempt, event or audit. Binding drift, instance-state drift, partial pre-existing provisioning, plan/intent mismatch and audit failure fail closed.

## D2 shared command fence and bounded claim

Migration cannot use a private worker-only lock while user commands bypass it. D2 uses one shared tenant/approval-instance serialization key for migration claims and approval business commands.

The claim query:

- begins with tenant and intent identity;
- uses deterministic `ORDER BY created_at, attempt_id`;
- is bounded to 1–100 rows;
- uses `FOR UPDATE SKIP LOCKED`;
- is supported by `idx_process_migration_attempt_claim_v40`;
- records an immutable claim batch even when the exact result is empty;
- commits attempt, fence, fence event, claim-batch and audit evidence before an engine call.

The closed command vocabulary covers complete, approve, reject, return, withdraw, retrieve, transfer, terminate and migration. Current projection mutations are wrapped by `CommandFencedApprovalProjectionStore` and consult the same active migration fence.

`JdbcApprovalInstanceCommandFence` and the migration claim store both acquire:

```text
pg_advisory_xact_lock(hashtextextended(
  'approval-instance-command:v1:' || tenantId || ':' || approvalInstanceId,
  0
))
```

An unexpired active migration lease rejects the business command. At exact expiry the old lease no longer fences business work.

## D2 lease renewal, expiry takeover and stale-owner fencing

- same-owner renewal is permitted only before current expiry and only when the new lease strictly extends the old lease;
- different-owner takeover is permitted only at or after expiry;
- each attempt and fence advances exactly one revision;
- current worker identity is generated by the server and is not accepted in a public command record;
- attempt transitions receive the current worker as explicit lease actor evidence;
- stale revision, stale actor, stale owner or stale lease evidence is rejected;
- attempt event, fence event and audit evidence are written atomically.

The first worker form remains an internal one-shot runner. M5-D adds no resident scheduler.

## D2 performance evidence

The permanent D2 claim-plan test loads 5,000 attempt rows and executes the production claim shape with `EXPLAIN (FORMAT JSON)`.

The verified plan:

- has a bounded `Limit` with plan rows no greater than 10;
- uses `idx_process_migration_attempt_claim_v40`;
- retains tenant and intent prefix conditions;
- contains no `Seq Scan`.

## D3 governed single-instance engine request

Short platform transaction A:

1. locks the exact tenant-scoped attempt;
2. requires `CLAIMED`, the expected revision, current worker ownership and an unexpired lease;
3. acquires the shared tenant/instance command serialization lock;
4. locks and checks the exact active migration fence, worker and fence revision;
5. locks and verifies the current runtime binding evidence hash, engine instance and source definition;
6. verifies the current `RUNNING` intent and exact `CONSUMED` plan target release, package, deployment and definition;
7. writes one immutable bounded engine request with deterministic request/evidence hashes;
8. transitions the attempt to `ENGINE_REQUESTED` and appends attempt-event and audit evidence;
9. commits before the engine adapter is called.

A duplicate request for the attempt, a changed request hash, a stale binding, target drift, worker spoofing, fence drift, lease expiry or audit failure fails closed.

## D3 Flowable public-API dispatch

Outside every platform database transaction, the adapter:

1. reads one exact runtime instance through public APIs;
2. reads the source and target definitions through `RepositoryService`;
3. creates a bounded, value-free pre-dispatch snapshot;
4. rejects unsupported or stale evidence before dispatch;
5. creates one `ProcessInstanceMigrationBuilder` targeting one exact process definition;
6. invokes public `validateMigration(engineInstanceId)`;
7. invokes `migrate(engineInstanceId)` at most once only when validation succeeds.

Pre-dispatch rejection covers missing or suspended runtime, tenant/source/target mismatch, target deployment drift, truncated evidence, unsupported parallel/multi-instance/subprocess/call-activity shape, unsafe executable/timer/suspended/dead-letter jobs, stale task/activity mapping and unsupported active-token shape.

The adapter never changes platform plan, intent, attempt or runtime binding. It cannot return platform success.

## D3 outcome finalization and UNKNOWN

Short platform transaction B:

1. locks the exact `ENGINE_REQUESTED` attempt and request lineage;
2. reacquires the tenant/instance command serialization lock;
3. rechecks worker identity, fence revision and unexpired lease;
4. appends one immutable bounded engine outcome;
5. transitions the attempt and appends attempt-event and audit evidence;
6. commits atomically.

Result semantics:

- pre-dispatch or public engine validation rejection records no migration call and a governed terminal rejection;
- a returned migration call transitions only to `VERIFYING` with `EngineOutcome.ACCEPTED`;
- timeout, connection reset, interruption, response loss, incomplete response or another exception that cannot prove non-dispatch produces durable `UNKNOWN` and `ENGINE_OUTCOME_UNKNOWN`;
- stale-owner, audit or persistence finalization failure propagates and never causes a second outcome write;
- no ambiguity path automatically calls migration again;
- no rollback or force-success behavior exists.

## D4 exact verification request

Short platform transaction A:

1. locks the exact tenant-scoped attempt;
2. requires current `VERIFYING` state and the expected attempt revision;
3. locks the exact engine request and unique returned engine outcome;
4. requires one returned engine call awaiting verification;
5. checks tenant, intent, attempt, engine instance, source definition, target definition and request/outcome lineage;
6. creates one server-owned deterministic verification request identity and hash;
7. commits before any Flowable readback.

Exact replay of an already persisted verification returns the authoritative evidence without another Flowable read. Changed-payload replay fails closed.

## D4 bounded Flowable public readback

Outside every platform database transaction, the verification adapter reads one exact instance through public APIs only.

The bounded snapshot includes:

- runtime presence, process-definition identity, deployment identity and suspension;
- active activity IDs;
- runtime execution identities combined with public unfinished historic-activity process-definition evidence;
- active task keys and definition identity;
- executable, timer, suspended and dead-letter job type/state/definition evidence;
- relevant event subscriptions and definition evidence;
- allowlisted variable hashes only;
- bounded identity-link hashes only;
- process history identity, end time and bounded delete reason;
- bounded historic task evidence;
- deterministic snapshot hash and truncation indicator.

Every collection is bounded by `limit + 1`. Unsupported variable values, excess rows or excess combined job/execution evidence set truncation. Truncated evidence cannot produce exact verification success.

The public `Execution` interface does not expose process-definition identity. D4 therefore correlates the public execution ID/activity ID with public unfinished historic-activity evidence. Missing correlation, conflicting definitions or incomplete evidence fails closed; no implementation class or database table is used.

## D4 deterministic classification and persistence

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

Short platform transaction B:

1. re-locks and revalidates the exact attempt/request/outcome lineage;
2. checks the same deterministic verification request hash;
3. appends one immutable exact verification evidence record;
4. appends audit in the same transaction;
5. retains the attempt in `VERIFYING` only for exact target so D5 can perform binding CAS;
6. routes every non-exact or failed read to reconciliation-required semantics;
7. commits atomically.

D4 never modifies `ap_process_runtime_binding`, releases a completed command fence, or marks an attempt `SUCCEEDED`. Those actions remain D5 scope.

## Implemented cross-system transaction boundary

```text
short database transaction A — execution
  validate exact plan/intent/attempt/binding/fence/lease evidence
  persist immutable engine request evidence
  transition CLAIMED -> ENGINE_REQUESTED
commit

no platform database transaction
  Flowable public validateMigration / migrate for one instance at most once

short database transaction B — engine outcome
  fence stale owner/revision/lease
  persist bounded engine outcome
  returned call -> VERIFYING
  ambiguous call -> UNKNOWN
commit

short database transaction A — verification
  validate exact VERIFYING attempt/request/outcome lineage
  persist deterministic verification request identity
commit

no platform database transaction
  Flowable public bounded readback for one exact instance

short database transaction B — verification evidence
  revalidate attempt/request/outcome/request-hash lineage
  append immutable exact verification evidence and audit
  exact target remains VERIFYING pending D5 CAS
  all other results require reconciliation or manual review
commit
```

## Sensitive-data boundary

Credentials, tokens, secret values, attachment bytes, arbitrary serialized objects, unfiltered variable values, complete sensitive business data, unbounded variables, unbounded identity links and unbounded history are never stored.

Pre-dispatch truncation rejects dispatch. Post-dispatch verification truncation produces manual-review-required evidence and cannot be exact target verified.

## D5 runtime-binding CAS — planned only

Runtime binding may change only after exact target verification with complete non-truncated evidence. CAS must check tenant, instance, binding revision, original binding evidence hash, source and target release, source and target engine definition, attempt identity and verification evidence hash. Audit failure must roll back the platform update. CAS conflict after engine mutation must enter reconciliation and cannot be called success or rollback.

## D6 durable UNKNOWN and reconciliation — planned only

Timeout, connection reset, lost response, crash after dispatch, incomplete result, contradictory runtime/history evidence and stale-worker finalization preserve durable `UNKNOWN`. Reconciliation must use public runtime, task, job, timer, subscription and history APIs, acquire an independent fenced lease, append immutable evidence, support exact replay, reject changed-payload replay and never redispatch migration.

## D7 canary, bounded execution and kill switch — planned only

A later bounded executor must select one deterministic canary. Remaining instances become eligible only after exact canary target verification and successful binding CAS. `UNKNOWN`, `RECONCILING` or manual review pauses the plan by default.

A kill switch may prevent new engine calls but cannot pretend to cancel a call already dispatched.

## Default-disabled configuration

Defaults remain equivalent to:

```properties
approval.migration.execution.enabled=false
approval.migration.worker.enabled=false
approval.migration.reconciliation.automatic.enabled=false
```

Missing, malformed or incomplete configuration fails closed.

## Permanent evidence

- D1 evidence: `docs/M5_D1_AUTHORIZED_PLAN_ADMISSION_EVIDENCE.md`
- D2 implementation evidence: `docs/M5_D2_SHARED_COMMAND_FENCE_CLAIM_EVIDENCE.md`
- D2 formal acceptance: `docs/M5_D2_GOVERNANCE_ACCEPTANCE.md`
- D3 implementation evidence: `docs/M5_D3_SINGLE_INSTANCE_EXECUTOR_PERMANENT_EVIDENCE.md`
- D4 implementation evidence: `docs/M5_D4_EXACT_VERIFICATION_PERMANENT_EVIDENCE.md`

D4 permanent evidence includes domain/application/Flowable/PostgreSQL tests, V43 historical and 5,000-row upgrade matrices, permanent Node boundaries, raw Actions logs and independently verified Maven/Vben/Mobile/Hygiene artifact digests.

## Stop conditions

M5-D2 is `ACCEPTED / PERMANENTLY_VALIDATED`. M5-D3 and M5-D4 are `COMPLETE / PERMANENTLY_VALIDATED`. M5-D overall remains `IN_PROGRESS`.

Work stops at the M5-D5 gate until the D4 documentation head has a successful natural permanent workflow run. D5–D8, M5-E management API/UI, M5-F full fault-injection and observability, M5-G merge readiness, production execution authorization, Ready-for-review, auto-merge, merge and issue closure remain blocked.
