# M5-D — Governed Server-side Migration Execution Protocol

## Governance status

- M5-A: `ACCEPTED` / `SUPPORTED_WITH_LIMITATIONS`
- M5-B: `ACCEPTED / PERMANENTLY_VALIDATED`
- M5-C: `ACCEPTED / PERMANENTLY_VALIDATED`
- M5-D1: `PERMANENTLY_VALIDATED`
- M5-D2: `COMPLETE_PENDING_EXPLICIT_ACCEPTANCE`
- M5-D3 through M5-D8: not started
- Current M5-D overall result: `IN_PROGRESS`
- Production migration execution: `NOT_AUTHORIZED`
- M5-E, M5-F and M5-G: not started

M5-D is server-owned execution infrastructure. D2 completion does not authorize production execution, a public execution endpoint, a Web or Mobile execution control, an automatic scheduler, force success, rollback, or automatic retry of an ambiguous engine result.

## Verified baseline and migration decision

The accepted persistence protocol is Flyway V33–V37. The accepted immutable-plan protocol is Flyway V38. M5-D1 adds V39 for exact plan consumption and intent admission. M5-D2 adds V40 for exact attempt provisioning support, bounded claim evidence and the shared tenant/instance command fence. V1–V40 remain immutable.

The M5-C domain and V38 vocabulary reserve terminal plan state `CONSUMED`. V39 provides the guarded consumption transaction. V40 does not alter V38 or V39 evidence; it adds claim/fence state and supporting indexes after every active M6 pull request was checked for Flyway migration occupancy.

Immediately before every M5 ref update, active M6 pull requests #67–#70 must be rechecked. M5 and M6 remain independent branches and code lines.

## Permanent invariants

1. Production code never reads or writes Flowable `ACT_*` tables.
2. Flowable 8.0.0 public APIs are the only future engine integration boundary.
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

### Implemented application services

- `ApprovalMigrationExecutionAdmissionService`: exact plan consumption and M5-B intent creation; no engine call.
- `ApprovalMigrationAttemptClaimService`: exact initial-attempt provisioning followed by one bounded tenant claim; server-owned worker identity; no engine call.
- `ApprovalMigrationOneShotClaimRunner`: internal one-shot claim adapter; disabled by default; no scheduler.

### Implemented ports and adapters

- PostgreSQL owns admission atomicity, attempt provisioning, claim replay, revision CAS, lease fencing and immutable evidence.
- `ApprovalMigrationAttemptProvisioningStore` derives initial attempts only from one current consumed plan, exact selections and current runtime bindings.
- `ApprovalMigrationAttemptClaimStore` owns bounded claim batches, renewal and expiry takeover.
- `ApprovalInstanceCommandFence` is shared by migration claims and approval business-command mutations.
- No M5-D Controller, REST route, Web action or Mobile action is permitted.

### Future D3–D8 boundaries

The single-instance Flowable executor, exact verification, runtime-binding CAS, durable `UNKNOWN` reconciliation, canary runner and later operational surfaces remain design-only and are not started by D2.

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
- commits attempt, fence, fence event, claim-batch and audit evidence before any future engine call.

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

## Cross-system transaction boundary for future slices

```text
short database transaction A
  validate current plan/intent/attempt/fence evidence
  persist immutable request evidence
commit A

no platform database transaction
  future Flowable public validateMigration / migrate / readback

short database transaction B
  fence stale owner
  persist bounded engine outcome
  append verification or reconciliation evidence
  perform runtime-binding CAS only when target is exact
commit B
```

D2 implements only the database-side provisioning, claim and lease transactions. It invokes no Flowable API.

## Future Flowable adapter contract

A future engine port may support only one exact runtime process instance and one exact target definition. It must use public APIs and provide bounded before, after and reconciliation snapshots.

It must reject definition-wide migration, batch migration, multi-instance activity, executable/pending/failed/dead-letter jobs, suspended or absent runtime, unsupported boundary/subscription shapes, ungoverned call-activity trees, stale mappings, stale active task keys and stale runtime binding.

The adapter cannot return platform success or update plan, intent, attempt or runtime binding directly.

## Bounded snapshots and sensitive data

Allowed future snapshot fields include runtime presence, process definition identity, active activities, active task keys and count, suspension, bounded allowlisted variable hashes, bounded identity links and candidates, jobs, timers, relevant subscriptions, history presence, historic definition, end time and bounded delete reason.

Credentials, tokens, secret values, attachment bytes, arbitrary serialized objects, unbounded variables and oversized payloads are never stored. Truncation prevents exact verification and must be classified.

## Verification and runtime-binding CAS

Future verification must distinguish exact target runtime, exact source runtime, source/target completed or terminated history, mixed evidence, missing evidence and reconciliation required. A target runtime with a source-bound job, timer, task, execution or relevant subscription is not target verified.

Runtime binding may change only after exact target verification. CAS must check tenant, instance, existing revision, original binding evidence hash, source release, target release and target engine definition. Audit failure must roll back the platform update. CAS conflict after engine mutation must enter reconciliation and cannot be called success.

## Durable UNKNOWN and reconciliation

Timeout, connection reset, lost response, crash after dispatch, incomplete result, contradictory runtime/history evidence and stale-worker finalization may enter `UNKNOWN` in a future slice. `UNKNOWN` must preserve server-owned engine request evidence and must never automatically call migration again.

Reconciliation must use Flowable public runtime, task, job, timer, subscription and history APIs, append immutable evidence and distinguish source confirmed, target confirmed, source/target history terminal, mixed state, missing evidence, manual review and unsafe automatic recovery.

## Canary, bounded execution and kill switch

A future bounded executor must select one deterministic canary. Remaining instances become eligible only after exact canary target verification and successful binding CAS. `UNKNOWN`, `RECONCILING` or manual review pauses the plan by default.

A kill switch may prevent new engine calls but cannot pretend to cancel a call already dispatched. No such control is exposed by D2.

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
- D2 evidence: `docs/M5_D2_SHARED_COMMAND_FENCE_CLAIM_EVIDENCE.md`

D2 permanent evidence includes application tests, PostgreSQL 16 provisioning/claim/concurrency/rollback tests, 5,000-row tenant-prefixed bounded claim `EXPLAIN (FORMAT JSON)`, Node boundaries, raw Actions logs and downloaded Maven/Vben/Mobile/Hygiene artifact digests.

## Stop conditions

M5-D2 is `COMPLETE_PENDING_EXPLICIT_ACCEPTANCE`; it is not accepted without explicit user acceptance. M5-D overall remains `IN_PROGRESS`.

Work stops before M5-D3 through M5-D8, M5-E management API/UI, M5-F full fault-injection and observability, M5-G merge readiness, production execution authorization, Ready-for-review, auto-merge, merge or issue closure.