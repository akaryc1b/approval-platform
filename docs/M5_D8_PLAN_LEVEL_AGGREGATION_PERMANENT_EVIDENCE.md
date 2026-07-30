# M5-D8 Plan-Level Completion Aggregation Permanent Evidence

M5-D8 status: `COMPLETE / PERMANENTLY_VALIDATED`

M5-D overall is a completion candidate, but M5 remains `IN_PROGRESS` because M5-E, M5-F and M5-G
are not complete. Production migration execution remains `NOT_AUTHORIZED`.

PR #58 remains Open + Draft. Issues #13, #14 and #56 remain Open.

## Scope

D8 derives one deterministic plan-level aggregate from the consumed immutable migration plan and the
append-only per-instance evidence produced by D1 through D7.

D8 does not:

- invoke Flowable;
- dispatch migration;
- modify runtime binding or approval projection;
- mutate an Attempt, verification, reconciliation or D7 orchestration record;
- retry `UNKNOWN`;
- authorize production migration execution;
- expose a public execute, retry, rollback, force-success or reconcile command.

The accepted D8 execution boundary is:

1. a server-owned one-shot request enters the default-disabled internal service;
2. a tenant/intent PostgreSQL session advisory lock serializes replay across nodes;
3. one short repeatable-read platform transaction locks the consumed plan and exact intent;
4. the store reads canonical immutable D1-D7 evidence, derives status/counts/hashes, and appends the
   aggregate, event, optional completion and audit atomically;
5. the transaction commits and the advisory lock is explicitly released.

There is no external-engine call between prepare and finalize because D8 performs no engine work.

## Authoritative inputs

D8 reads only server-owned durable evidence:

- the consumed immutable plan and exact canonical selected-instance sequence;
- the governed migration intent;
- migration Attempts and their durable status/revision/outcome lineage;
- D4 exact verification evidence;
- D5 per-instance completion and binding-conflict evidence;
- D6 reconciliation status and evidence;
- D7 deterministic Canary, orchestration run/event, bounded-batch and Kill Switch evidence;
- immutable audit lineage.

The caller supplies only tenant/intent identity, the expected next aggregate revision and bounded
request correlation. It cannot supply or override:

- plan status;
- per-instance status;
- selected, terminal, succeeded or unresolved counts;
- Canary identity;
- aggregate status;
- aggregate/input/completion hash;
- runtime identity;
- completion authority.

Cross-tenant lookup fails closed without exposing another tenant's resource existence.

## Closed plan-level status vocabulary

D8 uses the following closed set:

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

The status precedence fails closed. Invalid/incomplete evidence and completion conflict are evaluated
before any completion state. `UNKNOWN`, manual review, reconciliation and terminal failure also
precede progress or completion. A Kill Switch block and a paused D7 run remain visible.

An engine API return is never an aggregation-success input. Detect-only or inferred evidence is not
converted to exact completion.

## Canonical per-instance facts

Each selected instance produces one D8 fact in the sealed plan sequence. The fact binds:

- sequence number;
- approval-instance identity;
- Canary membership;
- server-derived closed instance status;
- a deterministic hash of the selected-instance evidence and all relevant Attempt, completion,
  conflict and reconciliation lineage.

The aggregate store orders facts by the persisted plan sequence. Normal evidence requires an exact
continuous sequence. A missing or gapped sequence is retained as
`INVALID_INCOMPLETE_EVIDENCE`; it is not silently repaired or ignored.

The closed instance statuses are:

- `NOT_STARTED`;
- `IN_FLIGHT`;
- `EXACTLY_COMPLETED`;
- `MANUALLY_DISPOSED`;
- `UNKNOWN`;
- `RECONCILING`;
- `MANUAL_REVIEW_REQUIRED`;
- `TERMINAL_FAILURE`;
- `COMPLETION_CONFLICT`;
- `INVALID_INCOMPLETE_EVIDENCE`.

A D5 completion counts as exact only when it belongs to the authoritative latest Attempt and that
Attempt is durably `SUCCEEDED`. Duplicate completion or binding-conflict evidence becomes
`COMPLETION_CONFLICT`.

## Exact counts and deterministic hashes

D8 computes:

- exact selected count;
- exact terminal count;
- exact succeeded count;
- exact unresolved count, where `unresolved = selected - terminal`.

The deterministic input-evidence hash includes:

- tenant, plan and intent identity;
- plan and intent evidence hashes;
- selected count;
- D7 plan signals and their evidence hash;
- every canonical per-instance fact in sequence.

The aggregate hash includes the closed status, all four counts, input-evidence hash, aggregate
revision and predecessor hash. It does not depend on randomly generated aggregate/event/completion
UUIDs.

Revision rules are closed:

- the first aggregate requires revision 1 and the zero predecessor hash;
- every later aggregate requires exactly the previous revision plus one;
- every later predecessor must equal the previous aggregate hash;
- stale revision fails closed;
- exact request replay returns the stored authoritative aggregate/event/completion;
- changed replay fails closed;
- two-node concurrent aggregation creates one authoritative result.

A later aggregation over unchanged evidence produces the same input-evidence hash while preserving a
new revision and the exact previous aggregate hash as predecessor.

## Completion authority

D8 appends a plan-completion record only for one of two closed states:

### `ALL_INSTANCES_EXACTLY_COMPLETED`

Required:

- selected count is positive;
- terminal count equals selected count;
- succeeded count equals selected count;
- unresolved count is zero;
- every selected instance has authoritative exact D5 completion evidence.

### `COMPLETED_WITH_MANUAL_DISPOSITION`

Required:

- selected count is positive;
- terminal count equals selected count;
- unresolved count is zero;
- at least one instance has explicit durable manual disposition evidence;
- succeeded count is less than selected count.

`UNKNOWN`, reconciliation, manual review, terminal failure, conflict, missing evidence, incomplete
evidence, pause or Kill Switch block cannot produce a plan completion.

The completion record does not claim rollback, cross-system atomicity or production authorization.

## Atomic append-only persistence

Flyway V48 creates:

- `ap_process_migration_plan_aggregate`;
- `ap_process_migration_plan_aggregate_event`;
- `ap_process_migration_plan_completion`.

V48 also creates bounded query indexes for plan/revision, status/time, event/intent and completion/time.

The V48 trigger validates:

- payload JSON equals every durable column;
- consumed-plan and exact intent lineage;
- exact selected count and canonical plan-instance count;
- aggregate revision and predecessor chain;
- aggregate-event identity/status/hash lineage;
- completion identity/status/count/input/aggregate lineage;
- completion unresolved count is zero.

All three tables reject update and delete. Aggregate, event, optional completion and audit are written
in one short transaction. Audit failure rolls back every D8 row in that transaction.

V48 creates no execution row or Flowable side effect and does not rewrite D1-D7 evidence.

## Default-disabled internal gate

`ApprovalMigrationPlanAggregationService.OneShotRunner` requires:

`approval.migration.aggregation.enabled=true`

The repository default is `false`.

There is no scheduler, polling loop, cross-tenant scanner, public Controller/REST command or
Web/Mobile execution control.

## Upgrade and scale validation

The repository Flyway sequence is continuous through V48.

Validation covers:

- fresh V1-to-V48 migration;
- historical V1, V13, V23, V31 and V36 through V47 upgrades;
- explicit V47-to-V48 upgrade;
- the retained V27 path with 5,000 approval instances and tasks;
- zero D1-D8 execution/evidence side effects during schema-only migration;
- deterministic aggregation of 5,000 canonical facts within the bounded test limit.

V1 through V47 are unchanged.

## Permanent test boundary

D8-focused committed-head tests close:

- domain status/count/precedence/canonical-order/5,000-fact behavior: 6/6;
- application default-disabled/server-owned one-shot gate: 3/3;
- PostgreSQL exact replay, changed replay, revision chain, two-node race, audit rollback,
  cross-tenant/stale rejection and append-only tamper rejection: 6/6;
- permanent Node architecture/governance boundary: 5/5.

The full Maven reactor remains green and includes all D1-D7 compatibility tests.

## Retained validation lineage

No failed Run was rerun, cancelled, deleted, hidden or rewritten.

- Run `30278266880` / #729 at feature Head
  `5450c4325774bb8e7f08e0c4972cf91ea00e91ab` retained two failures:
  - the old D6 Node gate still expected latest Flyway V47;
  - invalid sequence-gap evidence was correctly classified but the first Summary constructor still
    rejected retaining that invalid evidence.
- Minimal fix commit `c30e18b580c8d27634bebac687c5a96ccaac830f` changed only the D6 latest-version gate and the invalid
  evidence constructor rule.
- Run `30278722609` / #730 at the fix Head completed successfully.

Run #729 failure artifacts were downloaded and exactly matched GitHub digests:

| Artifact | ID | SHA-256 |
| --- | ---: | --- |
| `approval-maven-30278266880` | `8657694266` | `f56be5cc24a79e4b4628189abe342b7087395d264af09af6550f9ba67c4b1d10` |
| `approval-hygiene-30278266880` | `8657687147` | `47f6beb90ddbb0bb5a679008967a5db1b02a6f71014ae54e6aca8b6702edd5fa` |

## Permanent successful validation

Approval Platform Validation Run `30278722609` / #730 at Head
`c30e18b580c8d27634bebac687c5a96ccaac830f` completed successfully.

All four permanent jobs succeeded:

- Repository hygiene;
- Java 21 / Maven / PostgreSQL;
- Vben TypeScript / production build;
- UniApp TypeScript / H5 / WeChat.

Test evidence:

- Maven aggregate: 667 tests, 0 failures, 0 errors, 0 skipped;
- D8 domain/application/PostgreSQL focused tests: 15/15;
- D8 PostgreSQL scenarios: 6/6;
- Flyway upgrade scenarios: 2/2;
- D8 permanent Node boundaries: 5/5;
- all permanent M5 Node boundary groups: 84/84;
- Vben type-check and production build passed;
- UniApp type-check, H5 build and WeChat Mini Program build passed.

All four Artifact ZIPs were downloaded. Local SHA-256 values exactly matched GitHub digests:

| Artifact | ID | SHA-256 |
| --- | ---: | --- |
| `approval-maven-30278722609` | `8658062467` | `133bea3a306eeea305e4ae6d7a519804012acd2b3ddec852b8489e21619a20bd` |
| `approval-vben-30278722609` | `8657920416` | `a4645698418fc48829a27834d31b08eb7e6625191cee8f13de8c92e86dab064f` |
| `approval-mobile-30278722609` | `8657900232` | `a06b486d6e34a3f5eb2b45c314d42d41ee89fdf2dffb6f3a22a8a96a7244cdae` |
| `approval-hygiene-30278722609` | `8657872885` | `228c29ff1c4bfa2f5d0b4dd2326b3adea13635f4801fec2276edc6c148be88cd` |

## Explicit absences

D8 provides or authorizes none of the following:

- a Flowable call or migration dispatch;
- a runtime-binding or approval-projection mutation;
- an Attempt, verification, reconciliation or D7 evidence rewrite;
- automatic retry of `UNKNOWN`;
- a public aggregate/execute/retry/force/rollback/reconcile command;
- a Web or Mobile execution control;
- a scheduler, resident worker or automatic cross-tenant scan;
- fake rollback, force success or cross-system transaction claims;
- production execution authorization;
- M5-E, M5-F or M5-G completion;
- M6 changes;
- a second automatic workflow;
- Ready, auto-merge, merge or issue closure.
