# M5-D6 Durable UNKNOWN Reconciliation Permanent Evidence

M5-D6 status: `COMPLETE / PERMANENTLY_VALIDATED`

M5-D remains `IN_PROGRESS`. Production migration execution remains `NOT_AUTHORIZED`.
PR #58 must remain Open + Draft. Issues #13, #14 and #56 remain Open.
M5-D7 and M5-D8 are not started.

## Scope

D6 closes the server-owned reconciliation boundary for a migration attempt whose one permitted
Flowable migration invocation has an ambiguous outcome. It does not add another migration dispatch,
a rollback mechanism, a public endpoint, a scheduler, a resident worker, or runtime-binding mutation.

The accepted execution order is:

1. one short PostgreSQL prepare transaction;
2. one bounded public-API engine read outside every platform transaction;
3. one short PostgreSQL finalize transaction.

The only production engine interaction in D6 is
`ProcessInstanceVerificationPort.readOne(VerificationCommand)`. D6 has no dependency on
`ProcessInstanceMigrationPort` and no access to Flowable implementation classes or `ACT_*` tables.

## Durable authority

D6 requires all of the following before preparation:

- exact tenant and attempt identity;
- current attempt state `UNKNOWN` or an eligible `RECONCILING` lease takeover;
- `EngineOutcome.UNKNOWN`;
- preserved immutable engine request reference;
- one D3 `AMBIGUOUS_UNKNOWN` engine outcome proving the call may have occurred;
- exact expected attempt revision;
- server-owned worker identity and bounded request correlation.

The caller cannot supply an engine snapshot, classification, disposition, reconciliation result,
terminal attempt status, or runtime-binding result.

## Independent reconciliation lease

Flyway V45 adds:

- `ap_process_migration_reconciliation_lease`;
- `ap_process_migration_reconciliation_lease_event`;
- `ap_process_migration_reconciliation_observation`.

The reconciliation lease is independent from the migration command fence because D6 performs a
read-only engine observation and must never regain migration-dispatch authority.

Lease rules are closed:

- an initial lease starts `ACTIVE`, revision 1, with a future expiry;
- same-owner renewal requires the current owner before expiry and a strict extension;
- different-owner takeover requires the previous lease to be expired;
- finalization requires the exact current unexpired owner and revision;
- release advances exactly one revision and appends a matching event;
- lease, lease-event and observation identity/evidence are immutable;
- lease events and observations are append-only;
- direct update or deletion fails closed.

Prepare atomically appends an `OPEN` reconciliation sequence, transitions
`UNKNOWN -> RECONCILING`, creates the independent lease and initial lease event, and appends audit.
Audit failure rolls back the entire prepare transaction.

## Transaction-free observation

After prepare commits, D6 invokes one bounded `ProcessInstanceVerificationPort.readOne` call.
The same public runtime/task/job/timer/subscription/history evidence boundary used by D4 is reused.
No platform transaction is open while this read executes.

A stable read failure is converted to bounded server-owned failure evidence. An unexpected runtime
failure is converted to the closed `ENGINE_READ_UNEXPECTED` code. Neither path dispatches migration.

Classification is recomputed by the server from the observed snapshot and immutable source/target
definition identities. Caller-supplied classification or disposition cannot be persisted.

## Closed dispositions

D6 persists exactly one of these dispositions:

- `SOURCE_CONFIRMED_NO_RETRY`;
- `SOURCE_TERMINAL_CONFIRMED_NO_RETRY`;
- `TARGET_CONFIRMED_BINDING_CAS_REQUIRED`;
- `TARGET_TERMINAL_BINDING_CAS_REQUIRED`;
- `MANUAL_REVIEW_REQUIRED`.

The outcomes are:

| Observation | Reconciliation | Attempt | Runtime binding |
| --- | --- | --- | --- |
| exact source runtime | `RESOLVED_SOURCE` | `BLOCKED_STALE` | unchanged |
| source terminal history | `RESOLVED_TERMINAL` | `FAILED_TERMINAL` | unchanged |
| exact target runtime | `MANUAL_REVIEW_REQUIRED` | `RECONCILING` | unchanged; separate D5 CAS required |
| target terminal history | `MANUAL_REVIEW_REQUIRED` | `RECONCILING` | unchanged; manual terminal handling required |
| mixed, missing, stale, truncated, incomplete or read failure | `MANUAL_REVIEW_REQUIRED` | `RECONCILING` | unchanged |

Source evidence is never retry authority. D6 does not create a new migration request or outcome and
never invokes migration again.

## Ambiguous terminal request lineage

An UNKNOWN-derived terminal result must preserve the exact original engine request reference.
It is evidence that the one migration call may have occurred, not permission to retry it.

Flyway V46 replaces the earlier V37 request-reference check with
`ck_process_migration_attempt_request_v46`. It permits a request reference on
`BLOCKED_STALE` or `FAILED_TERMINAL` only where `engine_outcome='UNKNOWN'`, while preserving all
existing required and forbidden combinations.

Domain transition normalization inherits the current `RECONCILING` request reference before final
attempt construction. The final durable attempt, matching attempt event and V46 database constraint
therefore carry the same original lineage.

## Exact replay and conflict

A completed D6 observation is unique per tenant and attempt.

- exact same request hash returns stored evidence with `replayed=true` and performs no second engine read;
- changed request ID, worker, expected revision or other request material fails closed;
- an active unexpired different-owner lease rejects preparation;
- an expired lease may be taken over exactly once through revision CAS;
- stale finalization authority is rejected;
- no replay creates duplicate reconciliation, lease-event, observation or audit evidence.

## Atomic finalization

Finalize atomically:

1. locks the exact attempt, OPEN reconciliation and active lease;
2. rechecks worker, attempt revision, lease revision/expiry and request hash;
3. rechecks the original D3 `AMBIGUOUS_UNKNOWN` outcome;
4. inserts one immutable observation;
5. appends the server-derived reconciliation conclusion;
6. applies only the closed attempt outcome above;
7. releases the independent reconciliation lease and appends its event;
8. appends one audit fact;
9. commits all platform evidence together.

Audit failure rolls back observation, conclusion, attempt transition, attempt event, lease release,
lease event and audit.

## Default-disabled internal gate

`ApprovalMigrationReconciliationService.OneShotRunner` requires all three server-owned switches:

- `approval.migration.execution.enabled=true`;
- `approval.migration.worker.enabled=true`;
- `approval.migration.reconciliation.automatic.enabled=true`.

All default to `false`. There is no scheduler, polling loop, Controller, REST route, Web action or
Mobile action.

## Upgrade validation

The repository Flyway sequence is continuous through V46.

Permanent PostgreSQL validation covers:

- fresh schema to V46;
- historical V1, V13, V23, V31, V36, V37, V38, V39, V40, V41, V42, V43, V44 and V45 upgrades;
- explicit V45-to-V46 upgrade;
- V27 upgrade with 5,000 approval instances and tasks while preserving projection evidence;
- zero reconciliation rows or execution side effects on migration-only upgrades.

## Permanent test boundary

The D6-focused tests close these paths:

- domain classification and disposition: 5/5;
- application prepare/read/finalize ordering, replay, read failure and three-switch gate: 4/4;
- PostgreSQL source no-retry, target CAS-required, read failure, expired-lease takeover,
  prepare-audit rollback, finalize-audit rollback and append-only tamper rejection: 7/7;
- D6 permanent Node boundaries: 5/5.

The earlier durable UNKNOWN/lease suite remains green at 8/8, proving D6 did not weaken B4.

## Retained validation lineage

No failed run was rerun or rewritten. Each failure remains available and received only an isolated fix:

- Run #690 / `30232509048`: the first domain test supplied a valid release timestamp while expecting rejection;
- Run #691 / `30232639650`: upgrade assertions still expected V44;
- Run #695 / `30233210311`: V45 Maven/upgrade validation succeeded; the D1 Node gate still expected V44;
- Run #697 / `30233662224`: the first D6 fixture named a nonexistent V41 `target_engine_version` column;
- Run #698 / `30234197687`: real transactions exposed request-lineage preservation and an overstrict reconciliation-ID result check;
- Run #700 / `30234625569`: an application fixture exposed a duplicate sequence restriction not owned by the port result;
- Run #701 / `30234742761`: the old V37 physical request-reference constraint did not express UNKNOWN-derived terminal lineage;
- Run #707 / `30235431308`: the older B4 helper omitted the request reference before transition normalization;
- Run #710 / `30236482673`: the first permanent Node gate treated safety-comment words as execution capability;
- Run #711 / `30236917884`: the Node gate assumed a fixed lease-delete error message instead of checking trigger structure.

## Permanent successful validation

Approval Platform Validation Run `30237337164` / #712 at head
`e7e05576a4d3341da0f4014724fa07aca7bd356c` completed successfully.

All four permanent jobs succeeded:

- Repository hygiene;
- Java 21 / Maven / PostgreSQL;
- Vben TypeScript / production build;
- UniApp TypeScript / H5 / WeChat.

Test evidence:

- Maven aggregate: 640 tests, 0 failures, 0 errors, 0 skipped;
- D6 domain/application/PostgreSQL focused tests: 16/16;
- D6 PostgreSQL reconciliation execution scenarios: 7/7;
- durable UNKNOWN/lease compatibility scenarios: 8/8;
- Flyway upgrade scenarios: 2/2;
- permanent M5 Node boundary groups: 74/74;
- D6 permanent Node boundaries: 5/5;
- Vben type-check and production build passed;
- UniApp type-check, H5 build and WeChat Mini Program build passed.

All four artifacts were downloaded. Local ZIP SHA-256 values exactly matched GitHub digests:

| Artifact | ID | SHA-256 |
| --- | ---: | --- |
| `approval-maven-30237337164` | `8642160352` | `674b213e936fa66e7e922c643e9b862f1f0c39745ec9fa07b522fd1af9fb0da2` |
| `approval-vben-30237337164` | `8642077305` | `e5e077ad9205d4c4bc6d319bf1878255a9e91a433790ac9ca71146305f4f3e1c` |
| `approval-mobile-30237337164` | `8642066519` | `b2465e33e874d48fba5e4ca3d892d1b38ffcd333d78b7f915d8b9e03cc23754a` |
| `approval-hygiene-30237337164` | `8642054512` | `3f2891b7b8d4872cedc650b8a39d7e9747223b6e8ca48b3063b5d0cb97c729d8` |

## Explicit absences

D6 provides or authorizes none of the following:

- a second migration dispatch or automatic retry of `UNKNOWN`;
- a rollback, compensation fiction, force-success path or cross-system transaction;
- D6 runtime-binding mutation or platform success from target observation alone;
- a public execute, force, rollback or reconciliation endpoint;
- a scheduler, polling loop or resident migration worker;
- direct Flowable `ACT_*` access or implementation-class access;
- definition-wide or batch engine migration;
- Web or Mobile execution controls;
- production migration execution;
- M5-D7, M5-D8, M5-E, M5-F or M5-G;
- M6 changes;
- a second automatic workflow;
- Ready, auto-merge, merge or issue closure.
