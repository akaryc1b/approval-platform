# M5-D5 — Runtime-binding CAS Permanent Evidence

## Governance status

- M5-D2: `ACCEPTED / PERMANENTLY_VALIDATED`
- M5-D3: `COMPLETE / PERMANENTLY_VALIDATED`
- M5-D4: `COMPLETE / PERMANENTLY_VALIDATED`
- M5-D5 implementation: `COMPLETE / PERMANENTLY_VALIDATED`
- M5-D6 through M5-D8: not started by this evidence record
- M5-D overall: `IN_PROGRESS`
- Production migration execution: `NOT_AUTHORIZED`

This record freezes the committed D5 platform-side completion boundary after D4 has produced one exact, non-truncated `EXACT_TARGET_RUNTIME` verification. It does not authorize production execution, add a public execute/force/rollback/reconciliation endpoint, add a Web or Mobile execution control, add a resident scheduler, redispatch migration, retry `UNKNOWN`, or claim cross-system atomicity.

## Permanent committed head

- Branch: `agent/m5-governed-process-instance-migration`
- Implementation head: `1ade7b9ed19503ca95a555582815fda62696943d`
- Permanent workflow: `.github/workflows/approval-platform-validation.yml`
- Run ID: `30229476177`
- Run number: `#677`
- Run conclusion: `success`
- Run head: `1ade7b9ed19503ca95a555582815fda62696943d`

The committed implementation head and successful run head match exactly.

## Implemented D5 boundary

D5 adds the following server-owned components:

- `ApprovalMigrationRuntimeBindingCasStore`: exact tenant, attempt, verification, worker and expected revision request only;
- `ApprovalMigrationRuntimeBindingCasService`: internal one-shot platform completion service;
- `JdbcApprovalMigrationRuntimeBindingCasStore`: one short PostgreSQL transaction for authoritative replay, exact CAS, projection update, immutable evidence, attempt transition, fence release and audit;
- `PostgresSerializedApprovalMigrationRuntimeBindingCasStore`: cross-node tenant/attempt serialization before any replay read;
- `ApprovalMigrationRuntimeBindingEvidence`: immutable revisioned binding history;
- `ApprovalMigrationInstanceCompletionEvidence`: one immutable per-attempt exact completion record;
- `ApprovalMigrationBindingCasConflictEvidence`: immutable observed-conflict record requiring reconciliation;
- default-disabled internal D5 one-shot runner;
- Flyway `V44__complete_exact_migration_runtime_binding.sql`.

D5 performs no Flowable read or write. The engine migration dispatch remains exclusively D3, and exact public-API readback remains exclusively D4.

## Exact authority and fail-closed checks

The worker may provide only:

- tenant ID;
- attempt ID;
- verification ID;
- worker ID;
- expected attempt revision;
- expected command-fence revision;
- expected runtime-binding revision;
- happened-at, request ID and optional trace ID.

The worker cannot provide target release/package/deployment/definition identity, verification classification, observed binding identity, completion status, success status or conflict disposition.

The platform locks and revalidates:

1. the exact `VERIFYING` attempt and revision;
2. the exact immutable D4 verification and `EXACT_TARGET_RUNTIME` classification;
3. the verification request/outcome, attempt and fence lineage;
4. the exact consumed migration plan and target release/deployment authority;
5. the shared active command fence, current worker, fence revision and unexpired lease;
6. the current `RUNNING` approval-instance projection;
7. the current runtime binding, binding revision, original evidence hash, source release/package and source engine definition;
8. the exact target release package, lifecycle authority and deployed engine identity.

Any changed request, stale revision, stale worker/fence, expired lease, non-exact verification, plan/release/deployment drift, projection drift, binding drift, audit failure or persistence conflict fails closed.

## Successful exact-target transaction

One short PostgreSQL transaction:

1. serializes one tenant/attempt completion across nodes;
2. checks authoritative completion/conflict replay;
3. locks all exact D5 authority;
4. performs runtime-binding CAS from revision `n` to `n + 1`;
5. writes the new target release/package/deployment/definition and exact verification lineage;
6. lets the V44 trigger append immutable runtime-binding revision evidence;
7. updates the approval-instance release projection with version CAS;
8. appends one immutable migration-instance completion record;
9. transitions the attempt `VERIFYING -> SUCCEEDED` with `EngineOutcome.CONFIRMED`;
10. appends the attempt event;
11. releases the exact command fence and appends its event;
12. appends `PROCESS_MIGRATION_INSTANCE_COMPLETED` audit evidence;
13. commits all platform state together.

An audit failure rolls back the binding, binding history, approval-instance projection, completion, attempt, attempt event, fence, fence event and audit append.

## Replay and cross-node serialization

D5 accepts an exact same-payload replay and returns the authoritative immutable result:

- successful replay: `REPLAYED_COMPLETION`;
- recorded-conflict replay: `REPLAYED_CONFLICT`.

Changed-payload replay is rejected.

Before the delegate performs any replay read, `PostgresSerializedApprovalMigrationRuntimeBindingCasStore` acquires one PostgreSQL session advisory lock keyed by tenant and attempt. Two independent application nodes therefore cannot both pass the pre-lock replay read. The second caller enters the delegate only after the first transaction commits and returns the exact authoritative completion replay.

The advisory lock is explicitly released with `pg_advisory_unlock` before the connection is returned to a pool. Connection close is only a resource fallback, not the lock-release contract. Delegate failure also releases the attempt lock; an unlock failure is surfaced as an exact-replay-required persistence failure and cannot be called success.

## CAS conflict and reconciliation handoff

When the engine is already verified at the target but current platform binding authority no longer exactly matches the expected source CAS:

- D5 does not update the runtime binding;
- D5 does not update the approval-instance release projection;
- D5 records observed binding revision/hash/release/package/definition evidence;
- D5 appends one immutable binding-CAS conflict record;
- D5 transitions the attempt `VERIFYING -> RECONCILING` with `EngineOutcome.VERIFICATION_MISMATCH`;
- D5 appends audit evidence;
- the existing command fence remains active for D6 reconciliation;
- D5 never redispatches migration and never fabricates rollback.

## Flyway and PostgreSQL evidence

Flyway is continuous through V44:

- V41: immutable engine request/outcome evidence;
- V42: request/outcome guards bound to immutable attempt and consumed-plan payload identity;
- V43: exact bounded verification evidence and deterministic classification lineage;
- V44: runtime-binding revision/CAS authority, immutable binding history, per-instance completion evidence, observed CAS-conflict evidence and final-state guards.

V1 through V43 remain unchanged.

V44 enforces:

- positive monotonic binding revision;
- exact attempt and D4 verification lineage for revisions greater than one;
- immutable revision history and predecessor evidence hash;
- unique completion per attempt and verification;
- exact source/target release and engine-definition distinction;
- exact D4 `EXACT_TARGET_RUNTIME` authority;
- completion only with `SUCCEEDED / CONFIRMED`, updated binding and released fence;
- conflict only with `RECONCILING / VERIFICATION_MISMATCH`;
- append-only binding revision, completion and conflict evidence;
- fresh, historical and 5,000-row upgrade preservation through V44.

## Permanent test totals

Run #677 Maven aggregate:

- tests: `624`
- failures: `0`
- errors: `0`
- skipped: `0`

Focused D5 Maven tests:

- `ApprovalMigrationRuntimeBindingEvidenceTest`: 3/3
- `ApprovalMigrationRuntimeBindingCasServiceTest`: 2/2
- `JdbcApprovalMigrationRuntimeBindingCasStoreIntegrationTest`: 4/4
- `PostgresSerializedApprovalMigrationRuntimeBindingCasStoreIntegrationTest`: 2/2
- `ApprovalRuntimeBindingEvidenceConfigurationTest`: 1/1
- focused D5 total: `12/12`

The JDBC behavior tests permanently cover:

- exact-target CAS and sequential replay;
- two independent cross-node wrappers producing exactly one `COMPLETED` and one `REPLAYED_COMPLETION`;
- stale binding CAS conflict without binding mutation;
- exact conflict replay and changed-payload rejection;
- audit failure rollback of the entire platform transaction;
- append-only completion and binding-history tamper rejection;
- session advisory-lock serialization and explicit unlock after delegate failure.

Permanent M5 Node governance groups in the hygiene artifact:

- 39/39
- 8/8
- 2/2
- D3 single-instance execution boundary: 7/7
- D4 exact-verification boundary: 7/7
- D5 runtime-binding CAS boundary: 6/6
- aggregate M5 Node boundary total: `69/69`

All four permanent jobs succeeded:

- Repository hygiene: success
- Java 21 / Maven / PostgreSQL: success
- Vben TypeScript / production build: success
- UniApp TypeScript / H5 / WeChat: success

## Artifact digest verification

Each artifact ZIP was downloaded independently. Its local SHA-256 exactly matched the GitHub artifact digest.

| Artifact | Artifact ID | GitHub and local SHA-256 |
| --- | ---: | --- |
| Maven/PostgreSQL | `8639675517` | `af3404d02fba74de075d2ba782ff7854005c9142c49eaaf31d315f002412380f` |
| Vben | `8639614600` | `fc39ce2affc6502b7673fc9e0fa6cb41016030ea6d6db48d06d776afc1e013a7` |
| Mobile | `8639606485` | `3b369f2eb8d972f1488d51987ae63441f262c250676b6b114bd7a58b20e3df7e` |
| Repository hygiene | `8639597070` | `5c7576edc4dc2914768914d7e36502b112bc7601247d1a40a64e7ce105af4d06` |

## Retained failed evidence and minimal corrections

All failed and superseded runs remain visible. No failed run was rerun to overwrite evidence, deleted, hidden, repaired by rebase/amend/force-push, or treated as success.

Retained D5 lineage includes:

- Run #642 / `30208820434`: stale V43 boundary and one unused import;
- Run #648 / `30209149449`, #649 / `30209264175`: same unused import before deeper execution;
- Run #651 / `30209366019`: missing `definitionKey` JDBC mapper argument;
- Run #655 / `30209479753`: domain test exception-type mismatch;
- Run #657 / `30210139941`: unused import in the first D5 PostgreSQL behavior test;
- Run #658 / `30210615036`: test release artifact type mismatch;
- Run #660 / `30211036474`: target release fixture referenced unseeded artifact authority;
- Run #661 / `30211641399`: target package store correctly rejected missing definition authority;
- Run #663 / `30212052109`: legacy instance fixture used a nonexistent column;
- Run #664 / `30226476238`, #665 / `30226866920`: incomplete instance/verification fixtures during isolated diagnosis;
- Run #667 / `30227257253`: success/conflict paths reached production CAS; target lifecycle authority missing;
- Run #668 / `30227615244`: diagnostic test fixture green, retained but not permanent evidence;
- Run #674 / `30228700535`: zero-temporary-schema implementation green before permanent D5 Node boundary;
- Run #676 / `30229102145`: backend/Web/Mobile green; first D5 Node boundary used three imprecise V44 message phrases.

Every correction was an ordinary forward commit and was limited to the exposed invariant. Temporary diagnostic test-schema compatibility was fully removed before Run #674 and is forbidden by the permanent D5 Node boundary.

## Retained safety boundary

D5 permanently proves:

- runtime binding changes only after exact D4 target verification;
- one short platform transaction owns binding/projection/completion/attempt/fence/audit completion;
- binding CAS conflict is durable reconciliation, not success or rollback;
- exact replay is deterministic and cross-node serialized;
- pooled PostgreSQL sessions explicitly release advisory locks;
- no Flowable call occurs in D5;
- no migration redispatch occurs in D5;
- no public execute, completion, force, rollback or reconciliation endpoint exists;
- no Web or Mobile execution control exists;
- no resident scheduler exists;
- no automatic `UNKNOWN` retry exists;
- no fabricated rollback or cross-system transaction exists;
- execution, worker and automatic reconciliation remain disabled by default;
- production migration execution remains `NOT_AUTHORIZED`.

## Next gate

D6 may begin only after this documentation head itself receives a successful natural permanent workflow run. D6 is limited to durable `UNKNOWN` and reconciliation with an independent fenced lease, bounded public-engine readback, immutable reconciliation evidence, exact replay and no migration redispatch. D7 and later slices remain blocked.
