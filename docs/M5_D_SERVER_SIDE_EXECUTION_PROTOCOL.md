# M5-D — Governed Server-side Migration Execution Protocol

## Governance status

- M5-A: `ACCEPTED` / `SUPPORTED_WITH_LIMITATIONS`
- M5-B: `ACCEPTED / PERMANENTLY_VALIDATED`
- M5-C: `ACCEPTED / PERMANENTLY_VALIDATED`
- M5-D: `AUTHORIZED_TO_BEGIN`
- Current M5-D result: `IN_PROGRESS`
- Production migration execution: `NOT_AUTHORIZED`
- M5-E, M5-F and M5-G: not started

M5-D is server-owned execution infrastructure. It does not authorize production execution, a public execution endpoint, a Web or Mobile execution control, an automatic scheduler, force success, rollback, or automatic retry of an ambiguous engine result.

## Verified baseline and migration decision

The accepted persistence protocol is Flyway V33–V37. The accepted immutable-plan protocol is Flyway V38. V1–V38 remain immutable.

The M5-C domain and V38 table vocabulary already reserve terminal plan state `CONSUMED`. The V38 database transition guard intentionally permits only `PROPOSED -> AUTHORIZED`, and the accepted plan store exposes no consumption transaction. M5-D1 therefore adds V39 rather than changing V38.

At the D0 branch audit, active M6 pull requests #67–#70 did not contain Flyway migrations. V39 was the next continuous unoccupied version. This must be rechecked immediately before every M5 ref update.

## Permanent invariants

1. Production code never reads or writes Flowable `ACT_*` tables.
2. Flowable 8.0.0 public APIs are the only engine integration boundary.
3. A Flowable call never runs inside a long platform database transaction.
4. A successful API response is not a verified migration result.
5. An exception does not prove that the engine made no change.
6. `UNKNOWN` is durable and never automatically retried.
7. A started migration has no fabricated rollback semantics.
8. Only exact selected instances in one sealed plan may be processed.
9. One attempt processes exactly one approval instance.
10. Stale workers and reconcilers are fenced by revision and lease evidence.
11. Runtime binding changes only after exact target verification and CAS.
12. Runtime-binding CAS failure after engine mutation enters reconciliation.
13. Audit failure fails closed.
14. Tenant, operator, worker, engine identity and verification result are server-owned.
15. Execution, worker and automatic reconciliation default to disabled.
16. Evidence is bounded, redacted and append-only.
17. Metrics use closed low-cardinality labels only.
18. M5 remains independent from all M6 branches and code.

## Architecture boundaries

### Application services

- `ApprovalMigrationExecutionAdmissionService`: exact plan consumption and M5-B intent creation; no engine call.
- `ApprovalMigrationAttemptClaimService`: bounded tenant claim, command fence and lease ownership; no engine call.
- `ApprovalMigrationSingleInstanceExecutor`: one attempt and one instance, with engine invocation outside platform transactions.
- `ApprovalMigrationVerificationService`: exact sealed-plan comparison and target/source/history classification.
- `ApprovalMigrationReconciliationService`: durable `UNKNOWN` recovery without migration replay.
- `ApprovalMigrationBoundedRunner`: one-shot, canary-first, bounded and default disabled.

### Ports and adapters

- PostgreSQL owns admission atomicity, revision CAS, lease fencing and immutable evidence.
- A shared instance-command fence must cover complete, approve, reject, return, withdraw, retrieve, terminate and migrate.
- The Flowable adapter lives in `approval-engine-flowable`, uses public APIs only, and never updates platform persistence.
- No M5-D Controller, REST route, Web action or Mobile action is permitted.

## D1 plan consumption and intent admission

### Trusted input

The service accepts authenticated `RequestContext`, exact plan ID, exact plan hash and a bounded reason. The caller cannot provide tenant, operator, authorizer, trusted permission, selected count, release identity, deployment identity, runtime binding, engine identity or intent status.

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

V39 rejects:

- consumed plan without exact intent and consumption evidence;
- governed-plan intent without exact consumption evidence;
- mismatched tenant, plan, hash, authorization, intent or evidence hashes;
- second plan consumption;
- changed-payload idempotency replay;
- update or delete of consumption evidence;
- a consumption after plan or authorization expiry.

Exact replay returns the authoritative existing plan, intent and consumption and adds no event or audit duplicate.

D1 never creates attempts, claims work, invokes Flowable or mutates runtime binding.

## Shared command fence and claim

Migration cannot use a private worker-only lock while user commands bypass it. The shared fence is tenant scoped, instance scoped, revision protected, lease-owner protected and bounded.

A claim query must begin with tenant identity, use deterministic ordering and bounded `LIMIT`, use a supporting index, and commit claim plus lease event before any engine call. Same-owner renewal is allowed only under the current lease contract. Takeover is allowed only after expiry. A stale owner cannot submit results.

The first worker form is an internal one-shot runner. M5-D adds no resident scheduler.

## Transaction boundary

```text
short database transaction A
  validate current plan/intent/attempt/fence evidence
  persist immutable request evidence
commit A

no platform database transaction
  Flowable public validateMigration / migrate / readback

short database transaction B
  fence stale owner
  persist bounded engine outcome
  append verification or reconciliation evidence
  perform runtime-binding CAS only when target is exact
commit B
```

## Flowable adapter contract

The engine port supports one exact runtime process instance and one exact target definition. It provides bounded before, after and reconciliation snapshots, public validation and one exact migration call.

It rejects definition-wide migration, batch migration, multi-instance activity, executable/pending/failed/dead-letter jobs, suspended or absent runtime, unsupported boundary/subscription shapes, ungoverned call-activity trees, stale mappings, stale active task keys and stale runtime binding.

The adapter returns bounded invocation evidence. It does not return platform success and cannot update plan, intent, attempt or runtime binding.

## Bounded snapshots and sensitive data

Allowed snapshot fields include runtime presence, process definition identity, active activities, active task keys and count, suspension, bounded allowlisted variable hashes, bounded identity links and candidates, jobs, timers, relevant subscriptions, history presence, historic definition, end time and bounded delete reason.

Credentials, tokens, secret values, attachment bytes, arbitrary serialized objects, unbounded variables and oversized payloads are never stored. Truncation prevents exact verification and must be classified.

## Verification and runtime-binding CAS

Verification distinguishes exact target runtime, exact source runtime, source/target completed or terminated history, mixed evidence, missing evidence and reconciliation required. A target runtime with a source-bound job, timer, task, execution or relevant subscription is not target verified.

Runtime binding changes only after exact target verification. CAS checks tenant, instance, existing revision, original binding evidence hash, source release, target release and target engine definition. It preserves old binding evidence and appends transition and audit evidence. Audit failure rolls back the update. CAS conflict after target migration enters reconciliation and cannot be called success.

## Durable UNKNOWN and reconciliation

Timeout, connection reset, lost response, crash after dispatch, incomplete result, contradictory runtime/history evidence and stale-worker finalization can enter `UNKNOWN`. Immutable evidence includes attempt ID, server-owned engine request reference, start time, bounded request hash, bounded failure class, trace ID and before-snapshot hash.

`UNKNOWN` cannot create a retry attempt, call migration again, become ordinary retryable failure or be changed to success by direct SQL.

Reconciliation uses public runtime, task, job, timer, subscription and history APIs. It appends immutable evidence and distinguishes source confirmed, target confirmed, source/target history terminal, mixed state, missing evidence, manual review and unsafe automatic recovery. Target confirmation still requires binding CAS. Source confirmation never reuses the original attempt. A later retry needs a new attempt, new idempotency identity and separate human authorization.

## Canary, bounded execution and kill switch

One deterministic selected instance is the canary. Remaining instances become eligible only after exact canary target verification and successful binding CAS. `UNKNOWN`, `RECONCILING` or manual review pauses the plan by default.

The kill switch prevents new engine calls but cannot pretend to cancel a call already dispatched. Internal commands may pause, resume, cancel not-started work, stop after current, request reconciliation and acknowledge manual review. M5-D does not expose them through HTTP, Web, Mobile or direct database controls.

## Default-disabled configuration

Defaults remain equivalent to:

```properties
approval.migration.execution.enabled=false
approval.migration.worker.enabled=false
approval.migration.reconciliation.automatic.enabled=false
```

Missing, malformed or incomplete configuration fails closed.

## Permanent evidence plan

M5-D evidence includes domain/application tests, PostgreSQL 16 integration and concurrency tests, Flowable public-API tests, upgrade tests, 5,000-instance/task scale evidence, tenant-prefixed bounded claim `EXPLAIN (FORMAT JSON)`, Node boundaries, raw Actions logs and downloaded Maven/Vben/Mobile/Hygiene artifact digests.

## Stop conditions

M5-D stops before M5-E management API/UI, M5-F full fault-injection and observability program, M5-G merge readiness, production execution authorization, Ready-for-review, auto-merge, merge, or issue closure.

Only after D1–D8 implementation and permanent evidence may the stage be marked `COMPLETE_PENDING_EXPLICIT_ACCEPTANCE`. It cannot be marked accepted without explicit user acceptance.
