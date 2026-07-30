# M5-D3 Single-Instance Flowable Executor Permanent Evidence

## Decision

- M5-D2: `ACCEPTED / PERMANENTLY_VALIDATED`
- M5-D3: `COMPLETE / PERMANENTLY_VALIDATED`
- M5-D4 through M5-D8: `NOT_STARTED` at this evidence boundary
- M5-D overall: `IN_PROGRESS`
- Production migration execution: `NOT_AUTHORIZED`
- PR #58 remains `Open / Draft`.

This evidence freezes the first real engine-dispatch boundary. It does not authorize production execution, does not treat a returned Flowable call as verified completion, and does not authorize D7 canary or batch behavior.

## Exact implementation head

- branch: `agent/m5-governed-process-instance-migration`
- implementation head: `001dea671ac2f8328fd3161547fa89f7362779b3`
- base `main`: `d769722cf7dd5418739a91ad4c45ca1a1c147502`
- latest Flyway at this boundary: `V42`

No M6 source or migration was included. PRs #67, #68, #69 and #70 remained independent Draft PRs and had no Flyway migration in their changed-file sets when V41 and V42 were allocated.

## Implemented boundary

M5-D3 implements exactly one governed dispatch for:

- one tenant;
- one approval instance;
- one migration attempt;
- one source runtime binding evidence hash;
- one source engine process definition;
- one target release, deployment and process definition;
- one current worker-held command fence and lease.

The product-owned `ProcessInstanceMigrationPort` accepts no collection of instances and exposes no definition-wide migration method. The Flowable adapter uses Flowable 8.0.0 public services and the public single-instance migration builder only. It neither reads nor writes `ACT_*` tables and no business/application module imports Flowable implementation classes.

The adapter fails closed before dispatch for unsupported or stale evidence, including parallel, multi-instance, subprocess and call-activity model shapes, suspended runtime, unsafe executable/timer/suspended/dead-letter jobs, stale source definition, stale task/activity mapping, missing target definition, target tenant mismatch, target deployment drift and truncated pre-dispatch evidence.

## Cross-system transaction boundary

The execution sequence is fixed as:

1. short platform transaction A locks and validates the exact attempt, binding, intent, consumed plan, lease and command fence;
2. transaction A appends immutable bounded engine-request evidence and transitions the attempt to `ENGINE_REQUESTED`;
3. Flowable validation and at most one single-instance dispatch occur outside any platform database transaction;
4. short platform transaction B revalidates attempt revision, worker identity, fence revision and lease;
5. transaction B appends immutable engine-outcome evidence, attempt event and audit evidence.

The executor deliberately places outcome finalization outside the engine-exception catch boundary. A stale-owner, audit or persistence conflict is propagated and cannot trigger a second outcome write.

## Result semantics

D3 reuses the accepted domain vocabulary:

- pre-dispatch rejection: no engine call and terminal governed rejection;
- public engine validation rejection: no migration call and terminal governed rejection;
- returned migration call: attempt enters `VERIFYING`, never `SUCCEEDED`;
- timeout, reset, response loss, interruption, incomplete call or unexpected post-request exception where no authoritative non-dispatch proof exists: durable `UNKNOWN` with `ENGINE_OUTCOME_UNKNOWN`;
- stale-owner finalization: fenced and not retried.

There is no automatic migration retry for ambiguity. There is no fabricated Flowable rollback and no force-success path.

## Persistence and Flyway

### V41

`V41__create_single_instance_engine_dispatch_evidence.sql` creates append-only:

- `ap_process_migration_engine_request`;
- `ap_process_migration_engine_outcome`.

The schema binds request and outcome evidence to tenant, intent, attempt, approval instance, worker, attempt revision, command fence, fence revision, source binding evidence, source/target engine identity, timestamps and deterministic hashes.

### V42

`V42__bind_engine_dispatch_guards_to_payload_identity.sql` corrects the V41 guard to use the immutable engine identity held in `ApprovalMigrationAttempt.payload_json` and additionally checks the exact consumed plan target release, package, deployment and definition. No historical migration was edited or renamed.

The historical upgrade matrix covers fresh, V1, V13, V23, V31, V36, V37, V38, V39, V40 and V41 baselines through V42. The existing 5,000-instance/task upgrade case remains enabled and verifies that migration tables remain side-effect free.

## Default-disabled operation

The permanent production defaults remain:

```properties
approval.migration.execution.enabled=false
approval.migration.worker.enabled=false
approval.migration.reconciliation.automatic.enabled=false
```

Only an internal one-shot runner is wired. Both execution and worker flags must be explicitly enabled. D3 adds no scheduler, `@Scheduled` task, public REST execution route, Web control, Mobile control, batch execution or retry loop.

## Permanent committed-head validation

The exact implementation head naturally triggered the only permanent workflow:

- workflow: `.github/workflows/approval-platform-validation.yml`
- Run ID: `30195783801`
- Run number: `#590`
- head SHA: `001dea671ac2f8328fd3161547fa89f7362779b3`
- conclusion: `success`

All jobs succeeded:

| Job | Job ID | Conclusion |
| --- | ---: | --- |
| Repository hygiene | `89776976001` | success |
| Java 21 / Maven / PostgreSQL | `89776976026` | success |
| Vben TypeScript / production build | `89776975982` | success |
| UniApp TypeScript / H5 / WeChat | `89776975980` | success |

### Test totals

- Maven aggregate: `595` tests, `0` failures, `0` errors, `0` skipped;
- focused D3 Maven: `9` tests:
  - executor/transaction/UNKNOWN/finalization: `4`;
  - isolated Flowable single-instance adapter: `4`;
  - PostgreSQL request/outcome guard: `1`;
- focused D3 permanent Node boundary: `7/7`;
- complete M5 permanent boundary sequence in the hygiene artifact:
  - D1/D2 core: `38/38`;
  - parallel/subprocess/job capability isolation: `8/8`;
  - history/concurrent-command capability isolation: `2/2`;
  - D3 execution boundary: `7/7`.

### Artifact integrity

Each artifact ZIP was downloaded independently. The local ZIP SHA-256 exactly matched GitHub's artifact digest:

| Artifact | ID | GitHub digest / local ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30195783801` | `8630117354` | `d62598ffed86918317cd4176aa30e98d85100d42feb37993b0db1e3f0174d925` — exact match |
| `approval-vben-30195783801` | `8630071639` | `a7130ca1050b36c463ff967004d9e042a5f14060c8327a4d3c469584cb9963a8` — exact match |
| `approval-mobile-30195783801` | `8630064567` | `0fc233056b269e810ec09fe37f234aa6e35f504622f3bc4f5e5bb4ed25b3d820` — exact match |
| `approval-hygiene-30195783801` | `8630056926` | `ccc3ffe2adbd5054edb2e29bf8a26c8ee9017e90dfb7e979c0c12cf5a236f128` — exact match |

## Preserved failure evidence

The following failures remain visible and were not deleted, rerun to hide evidence, rebased away or force-pushed over:

- Run `30194930737` (#577): the legacy M5-A boundary rejected any production migration adapter; corrected by allowing exactly one named governed D3 adapter while retaining all other prohibitions;
- Run `30194991695` (#578): two Node assertions falsely matched an existing non-migration scheduler and crossed file boundaries while scanning endpoint text; assertions were narrowed without weakening the migration boundary;
- Run `30195051300` (#579): Maven found three stale tests that still expected Flyway V40 after V41; the historical upgrade matrix was advanced normally;
- Run `30195528650` (#586): an older D1 permanent assertion still expected V40 after V42; it was updated to retain the D1 boundary through V42.

Intermediate runs superseded by later ordinary commits were allowed to follow the repository's permanent workflow concurrency policy. No failed run was canceled or manually rerun to conceal evidence.

## Explicitly not implemented or authorized

- no D4 exact post-migration verification evidence yet;
- no D5 runtime-binding CAS or completion evidence yet;
- no D6 reconciliation runner or reconciliation lease yet;
- no D7 canary, bounded batch, pause or kill switch;
- no D8 plan-level completion aggregation;
- no operations API or migration console;
- no production migration authorization;
- no Ready transition, auto-merge, merge or Issue closure.

## Next gate

The next and only authorized slice is M5-D4 exact migration verification. It must read bounded public-API runtime/history evidence, classify source/target/mixed/missing/terminal/truncated outcomes deterministically, append immutable server-generated verification evidence, and must not mutate the runtime binding.
