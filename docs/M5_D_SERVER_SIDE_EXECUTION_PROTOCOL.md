# M5-D Server-Side Governed Migration Execution Protocol

Status:

- M5-D1: `PERMANENTLY_VALIDATED`
- M5-D2: `ACCEPTED / PERMANENTLY_VALIDATED`
- M5-D3: `COMPLETE / PERMANENTLY_VALIDATED`
- M5-D4: `COMPLETE / PERMANENTLY_VALIDATED`
- M5-D5: `COMPLETE / PERMANENTLY_VALIDATED`
- M5-D6: `COMPLETE / PERMANENTLY_VALIDATED`
- M5-D7: `COMPLETE / PERMANENTLY_VALIDATED`
- M5-D8: `COMPLETE / PERMANENTLY_VALIDATED`
- M5-D overall: `COMPLETE_CANDIDATE / PERMANENTLY_VALIDATED`
- Production migration execution: `NOT_AUTHORIZED`

PR #58 remains Open + Draft. Issues #13, #14 and #56 remain Open.
M5 as a whole remains `IN_PROGRESS` because M5-E, M5-F and M5-G are not complete.

## Permanent invariants

1. Production code never queries or modifies Flowable `ACT_*` tables.
2. Only Flowable 8 public APIs may be used.
3. A Flowable migration/readback call never participates in a platform database transaction.
4. An engine API return is not verified migration success.
5. An exception does not prove that Flowable made no change.
6. Ambiguous outcomes persist durable `UNKNOWN` and are never automatically retried.
7. A started migration has no fabricated rollback.
8. One Attempt owns one approval instance only.
9. Runtime binding changes only through exact D5 target verification and revision CAS.
10. Binding CAS conflict enters reconciliation; it never redispatches migration.
11. Audit failure fails closed and rolls back the current platform transaction.
12. Tenant, operator, worker, lease and engine identity are server-owned.
13. Execution, worker, orchestration, aggregation and reconciliation are default disabled.
14. Evidence is bounded, redacted and append-only.
15. Metrics use only closed low-cardinality labels.
16. The repository has one automatic PR/main workflow only.
17. Browser and Mobile clients cannot mutate Attempt, verification, binding, reconciliation,
    orchestration, aggregate or completion evidence.
18. Detect-only, API return or inferred evidence is never described as migration success.
19. No `UNKNOWN` path regains dispatch authority.
20. A Kill Switch blocks only future dispatch and cannot claim cancellation of an in-flight call.
21. Plan aggregation cannot authorize execution, rewrite per-instance evidence or hide unresolved work.

## Transaction boundary

The protocol is an ordered composition of short platform transactions and transaction-free public
Flowable operations:

```text
D1 short transaction
  consume one exact authorized immutable plan
  create one governed intent/event
  append consumption and audit evidence
commit

D2 short transaction
  provision exact initial Attempts
  bounded claim and shared command fence
commit

D3 short request transaction
  lock exact Attempt/fence/binding/target authority
  persist one engine request and attempt transition
commit

no platform transaction
  exactly one public Flowable migration call for one engine instance

D3 short outcome transaction
  persist returned/rejected/durable UNKNOWN outcome
  transition exact Attempt revision
  append audit
commit

D4 short prepare transaction
  persist exact verification request authority
commit

no platform transaction
  one bounded public runtime/task/job/timer/subscription/history readback

D4 short finalize transaction
  persist bounded snapshot and server-derived classification
  never mutate runtime binding
commit

D5 outer session advisory lock
  serialize tenant/attempt exact replay before stale reads
  D5 short platform transaction
    require complete non-truncated EXACT_TARGET_RUNTIME
    runtime-binding and projection CAS
    immutable binding history/completion or conflict evidence
    attempt/fence/audit transition
  commit
  explicitly release advisory lock

D6 short prepare transaction
  require durable AMBIGUOUS_UNKNOWN lineage
  append OPEN reconciliation
  UNKNOWN -> RECONCILING
  acquire independent reconciliation lease/event and audit
commit

no platform transaction
  one bounded public verification readback

D6 short finalize transaction
  revalidate Attempt/request/lease authority
  append immutable observation and disposition
  append terminal/manual reconciliation
  preserve the original ambiguous request
  release reconciliation lease and append audit
commit

D7 outer session advisory lock
  serialize tenant/intent replay before preparation
  D7 short preparation transaction
    select/replay deterministic canonical canary
    append immutable run/event and audit
  commit
  explicitly release advisory lock

D7 bounded one-shot loop
  reuse D2 bounded claim and command fence
  before every new dispatch:
    serialize tenant/run authorization
    record immutable Kill Switch observation/event/audit
  for each admitted Attempt:
    invoke existing D3 -> D4 -> D5 pipeline
  stop at first unresolved/non-exact disposition

D7 outer session advisory lock
  D7 short finalization transaction
    append immutable bounded-batch and final pause/completion event
    append audit
  commit
  explicitly release advisory lock

D8 outer session advisory lock
  serialize tenant/intent aggregate replay before evidence reads
  D8 short repeatable-read platform transaction
    lock consumed immutable plan and exact governed intent
    read canonical immutable D1-D7 evidence
    derive closed plan status, exact counts and deterministic hashes
    append aggregate, event, optional completion and audit atomically
  commit
  explicitly release advisory lock
```

D8 performs no Flowable call. No protocol phase claims two-phase commit or cross-system atomicity.

## D1 — exact authorized-plan admission

D1 accepts only a current authorized immutable plan with exact tenant, plan hash, authorization
evidence, selected count, source/target release identity and expiry.

One short transaction:

- consumes the exact plan revision;
- creates one governed migration intent and initial intent event;
- appends immutable plan-consumption evidence;
- appends the plan transition event and audit;
- supports exact replay;
- rejects changed payload, stale authorization, cross-tenant identity and concurrent second
  consumption.

D1 invokes no Flowable API.

## D2 — exact provisioning, bounded claim and shared command fence

D2 provisions one initial Attempt for every instance in the sealed canonical selection. Each Attempt
is bound to one approval instance, source binding evidence, source/target definition identity and
attempt number.

Bounded claim:

- accepts a limit from 1 through 100;
- uses tenant/intent filtering, canonical order and `FOR UPDATE SKIP LOCKED`;
- creates one claim batch and one shared tenant/instance migration fence per claimed Attempt;
- supports same-owner renewal before expiry and strict extension;
- supports different-owner takeover only after expiry;
- rejects stale worker, lease, Attempt revision and cross-tenant authority.

Business commands and migration use the same command-fence boundary. D2 invokes no Flowable API.
The retained 5,000-attempt query-plan test proves bounded index use and canonical ordering.

## D3 — governed single-instance migration dispatch

D3 permits at most one migration request for one Attempt.

The request transaction requires:

- exact tenant, Attempt and server worker;
- exact Attempt and active command-fence revisions;
- current source runtime-binding evidence;
- exact target deployed release identity;
- no previous engine request for the Attempt.

It persists one immutable engine request and commits before invoking
`ProcessInstanceMigrationPort.migrateOne`.

The public Flowable call is transaction-free and targets one engine instance. Definition-wide and
multi-instance engine migration are forbidden.

The outcome transaction records exactly one of:

- returned and awaiting exact verification;
- rejected before or by the engine;
- durable ambiguous `UNKNOWN`.

Timeout, response loss, connection reset, interruption or crash ambiguity never dispatches again.

## D4 — exact bounded public verification

D4 uses one short prepare transaction, one transaction-free public readback and one short finalize
transaction.

The public adapter uses bounded runtime, task, job, timer, subscription and history APIs only.
Classification is recomputed by the server from immutable source/target identity and the bounded
snapshot.

The closed classifications distinguish exact target runtime, exact source runtime, terminal history,
mixed, missing, stale, truncated, incomplete and read failure evidence.

D4 cannot:

- mutate runtime binding or approval projection;
- mark an Attempt successful;
- infer success from D3 API return;
- authorize retry or production execution.

## D5 — exact-target runtime-binding CAS

Only one complete, non-truncated `EXACT_TARGET_RUNTIME` verification may enter D5.

A dedicated cross-node tenant/attempt session advisory lock is acquired before any replay read.
Within one short transaction D5:

- revalidates Attempt, verification, command fence and source binding authority;
- performs runtime-binding revision CAS;
- performs approval projection CAS;
- appends immutable runtime-binding history and per-instance completion evidence;
- transitions the Attempt to exact success;
- releases the shared migration fence;
- appends audit atomically.

Binding drift records immutable conflict evidence, preserves the observed values and enters
reconciliation. It does not modify binding and never redispatches migration.

## D6 — durable UNKNOWN reconciliation

D6 accepts only an Attempt with preserved D3 `AMBIGUOUS_UNKNOWN` request/outcome lineage.
Its reconciliation lease is independent from the migration command fence and cannot authorize
migration.

Prepare atomically:

- appends an `OPEN` reconciliation;
- transitions `UNKNOWN -> RECONCILING`;
- acquires or validly takes over one independent lease;
- appends lease event and audit.

After commit, one bounded public verification read executes outside every platform transaction.

Finalize atomically rechecks current worker, Attempt, request and lease revision, appends one
immutable observation and one closed disposition, preserves the original request reference, releases
the lease and appends audit.

Closed dispositions:

- `SOURCE_CONFIRMED_NO_RETRY`;
- `SOURCE_TERMINAL_CONFIRMED_NO_RETRY`;
- `TARGET_CONFIRMED_BINDING_CAS_REQUIRED`;
- `TARGET_TERMINAL_BINDING_CAS_REQUIRED`;
- `MANUAL_REVIEW_REQUIRED`.

Source evidence never creates retry authority. Target evidence requires a separate governed D5
compatible handoff. Mixed, missing, stale, truncated, incomplete and failed-read evidence remains
manual.

## D7 — deterministic canary, bounded orchestration and Kill Switch

### Deterministic canary

D7 uses `CANONICAL_FIRST_V1`. The canary is sequence 1 in the sealed plan's canonical
selected-instance sequence.

The selection is server-computed, immutable and unique per plan/intent. Exact replay and a two-node
race return the same selection. A client cannot choose, replace or rotate the canary after failure.

V47 validates:

- exact consumed plan and governed intent;
- exact plan hash;
- sequence 1;
- exact approval instance;
- exact selected-instance evidence hash;
- payload/durable-column equality.

### Canary gate

The remaining selection is eligible only when the canary has:

- completed the D3 call path;
- one complete non-truncated exact target verification;
- successful D5 runtime-binding/projection CAS;
- immutable per-instance completion evidence;
- released command fence;
- no unresolved reconciliation, manual review or binding conflict;
- no Kill Switch block;
- no orchestration revision drift.

The plan pauses on `UNKNOWN`, `RECONCILING`, manual review, binding conflict, non-target evidence,
terminal failure, missing/incomplete evidence, stale worker, stale lease, empty batch, active/stale
Kill Switch or stale orchestration revision.

### Bounded one-shot execution

`ApprovalMigrationBoundedOrchestrationService` reuses D2 claim and the D3-D5 per-instance pipeline.

It is:

- tenant-, plan- and intent-scoped;
- limited to 1 through 100 Attempts;
- ordered by the existing canonical D2 claim;
- finite and one-shot;
- stopped at the first non-exact disposition;
- append-only and audit atomic.

It contains no scheduler, polling loop, automatic tenant scan, automatic retry, automatic
reconciliation, public execute endpoint or browser/mobile command.

### Kill Switch

Before every new dispatch, D7 records expected and observed Kill Switch revision, enabled state,
server-derived result, request hash and evidence hash.

Dispatch is allowed only when the switch is disabled and revisions match. A block appends immutable
observation, event and audit evidence.

The Kill Switch does not delete or mutate engine requests/outcomes, verification, runtime binding or
existing processing paths. It cannot convert `UNKNOWN`, fabricate cancellation/rollback or claim that
an already-issued Flowable request was cancelled.

### Cross-node replay

`PostgresSerializedApprovalMigrationOrchestrationStore` acquires a dedicated session advisory lock
before delegate replay reads:

- tenant/intent for preparation;
- tenant/run for dispatch authorization and finalization.

The delegate retains its short transaction boundary. The lock is explicitly released before pooled
connection return. Failure fails closed and requires exact replay.

### V47 evidence

V47 creates:

- `ap_process_migration_canary_selection`;
- `ap_process_migration_orchestration_run`;
- `ap_process_migration_orchestration_event`;
- `ap_process_migration_orchestration_batch`;
- `ap_process_migration_kill_switch_observation`.

All are append-only. Payloads are bound to durable columns. Run/event predecessor hashes, exact D2
claim ownership and Kill Switch run/Attempt/revision lineage are enforced.

## D8 — deterministic plan-level completion aggregation

D8 reads immutable D1-D7 evidence only. It does not invoke Flowable, dispatch migration, mutate
runtime binding, update Attempts or rewrite per-instance evidence.

### Closed status precedence

The plan-level status vocabulary is:

- `NOT_STARTED`;
- `CANARY_PENDING`;
- `CANARY_RUNNING`;
- `BOUNDED_EXECUTION_RUNNING`;
- `PAUSED`;
- `KILL_SWITCH_BLOCKED`;
- `UNKNOWN_PRESENT`;
- `RECONCILIATION_PRESENT`;
- `MANUAL_REVIEW_PRESENT`;
- `TERMINAL_FAILURE_PRESENT`;
- `PARTIALLY_COMPLETED`;
- `ALL_INSTANCES_EXACTLY_COMPLETED`;
- `COMPLETED_WITH_MANUAL_DISPOSITION`;
- `COMPLETION_CONFLICT`;
- `INVALID_INCOMPLETE_EVIDENCE`.

Invalid/incomplete evidence and completion conflict precede completion. `UNKNOWN`, reconciliation,
manual review and terminal failure remain visible and cannot be converted into success.

### Canonical facts, counts and hashes

D8 derives one fact for every selected instance in the sealed plan sequence. It computes exact
selected, terminal, succeeded and unresolved counts. Normal evidence requires an exact continuous
canonical sequence; a gap is retained as invalid evidence.

The deterministic input-evidence hash binds plan/intent evidence, D7 plan signals and every
per-instance fact. Aggregate hashes bind the closed status, exact counts, input hash, revision and
predecessor. Random aggregate/event/completion UUIDs are not hash inputs.

The first aggregate requires revision 1 and the zero predecessor. Every later revision must be the
exact next revision and its predecessor must equal the previous aggregate hash. Exact replay returns
the stored result; changed or stale replay fails closed. A tenant/intent session advisory lock
serializes two-node replay before evidence reads.

### Completion gate

A plan completion is appended only when unresolved count is zero and every selected instance has an
allowed immutable terminal disposition:

- `ALL_INSTANCES_EXACTLY_COMPLETED` requires terminal and succeeded counts equal selected count;
- `COMPLETED_WITH_MANUAL_DISPOSITION` requires terminal count equal selected count and at least one
  explicit non-exact manual disposition.

No completion record is created for `UNKNOWN`, reconciliation, manual review, terminal failure,
conflict, missing/incomplete evidence, pause or Kill Switch block.

### V48 evidence and atomicity

V48 creates:

- `ap_process_migration_plan_aggregate`;
- `ap_process_migration_plan_aggregate_event`;
- `ap_process_migration_plan_completion`.

All are append-only. Payloads are bound to durable columns. Consumed-plan/intent lineage, exact
selected count, revision/predecessor chain, event lineage and completion status/count/hash lineage
are enforced.

Within one short repeatable-read transaction D8 appends aggregate, event, optional completion and
audit. Audit failure rolls back the whole D8 transaction. V48 creates no execution side effect and
rewrites no D1-D7 evidence.

## Default-disabled configuration

The effective defaults remain:

```properties
approval.migration.execution.enabled=false
approval.migration.worker.enabled=false
approval.migration.orchestration.enabled=false
approval.migration.aggregation.enabled=false
approval.migration.reconciliation.automatic.enabled=false
approval.migration.kill-switch.enabled=false
approval.migration.kill-switch.revision=1
```

Missing, malformed or incomplete authority fails closed.

## Flyway ownership and validation

M5 owns the continuous sequence V33 through V48.

- V33-V37: migration persistence, tenant/lineage, lease and UNKNOWN guards;
- V38: immutable plans;
- V39: exact plan consumption and intent admission;
- V40: provisioning, bounded claims and shared command fence;
- V41-V42: immutable single-instance request/outcome and payload guards;
- V43: exact bounded verification evidence;
- V44: runtime-binding CAS, history, completion and conflict;
- V45: independent reconciliation lease/event and immutable observation;
- V46: UNKNOWN-derived terminal request-lineage preservation;
- V47: canary, orchestration, bounded batch and Kill Switch evidence;
- V48: immutable plan aggregate, aggregate event and plan completion evidence.

Fresh migration, historical upgrades, explicit V47-to-V48 upgrade and the retained 5,000-instance
path are validated. Migration creates no Flowable call or execution side effect. V1 through V47
remain unchanged.

## Permanent evidence

- D1: `docs/M5_D1_AUTHORIZED_PLAN_ADMISSION_EVIDENCE.md`
- D2 implementation: `docs/M5_D2_SHARED_COMMAND_FENCE_CLAIM_EVIDENCE.md`
- D2 acceptance: `docs/M5_D2_GOVERNANCE_ACCEPTANCE.md`
- D3: `docs/M5_D3_SINGLE_INSTANCE_EXECUTOR_PERMANENT_EVIDENCE.md`
- D4: `docs/M5_D4_EXACT_VERIFICATION_PERMANENT_EVIDENCE.md`
- D5: `docs/M5_D5_RUNTIME_BINDING_CAS_PERMANENT_EVIDENCE.md`
- D6: `docs/M5_D6_DURABLE_UNKNOWN_RECONCILIATION_PERMANENT_EVIDENCE.md`
- D7: `docs/M5_D7_CANARY_BOUNDED_ORCHESTRATION_PERMANENT_EVIDENCE.md`
- D8: `docs/M5_D8_PLAN_LEVEL_AGGREGATION_PERMANENT_EVIDENCE.md`

## Stop condition

M5-D1 through D8 have reached their permanent validation gates. M5-D overall is
`COMPLETE_CANDIDATE / PERMANENTLY_VALIDATED`, while M5 as a whole remains `IN_PROGRESS`.

Work may proceed only to M5-E1 read-only Operations API and Web/Mobile visibility under the current
authorization. M5-E2 executable commands, M5-F2 production-grade exercise, M5-G merge readiness,
production execution, Ready-for-review, auto-merge, merge and issue closure remain blocked.
