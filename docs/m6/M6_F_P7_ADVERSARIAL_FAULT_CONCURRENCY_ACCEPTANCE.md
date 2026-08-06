# M6-F P7 — Adversarial, Fault, Concurrency and Incident Acceptance

Status: `P7_D_IMPLEMENTED_PENDING_EXACT_HEAD_PERMANENT_VALIDATION`

Permanent conclusion: `AI_IS_NOT_AN_OPERATOR`

## 1. Scope

P7 accepts the already implemented M6-F non-executing controlled-automation and AI-governance foundation. It does not add a production Action, command adapter, autonomous execution path, Provider activation, traffic mutation, automatic retry, automatic rollback, automatic notification or automatic retention operation.

P7 is executed as five strictly ordered Gates:

1. P7-R0 — exact baseline and Threat / Fault / Race / Incident matrix;
2. P7-A — adversarial input, identity, authorization, HTTP and evidence-hash acceptance;
3. P7-B — Provider, Transport, Runtime Control, Usage, History and Lineage fault acceptance;
4. P7-C — deterministic Replay, CAS, Confirmation, Usage, Circuit and composite-snapshot concurrency acceptance;
5. P7-D — manual Incident / Rollback rehearsal and P7 formal closure.

A later Gate does not replace the evidence of an earlier Gate. P8 remains prohibited until the exact P7-D document Head passes the permanent workflow, all four Artifacts are independently rebuilt and Review state is rechecked.

## 2. Exact baseline

| Item | Exact value |
| --- | --- |
| Repository | `akaryc1b/approval-platform` |
| Target branch | `main` |
| Exact current `main` at P7-D start | `492a428627d3be707d5723350506302ca04841b0` |
| Pull Request | `#88 — M6-F: controlled automation and AI governance` |
| Formal branch | `agent/m6-f-controlled-automation-and-ai-governance` |
| Exact accepted P7-C Head | `bfffe838ea8e68829a3a656635aca644a7a83d91` |
| P7-D temporary branch | `agent/m6-f-p7-d-incident-rollback-closure` |
| PR state at P7-D start | Open / Draft / mergeable / not merged |
| Compare at P7-D start | ahead `157`, behind `0`; merge base equals current `main` |
| Reviews | none |
| `REQUEST_CHANGES` | none |
| Unresolved Review Threads | none |
| Highest governed migration | unique `V50`; no V51 |
| Automatic PR/main workflow | only `.github/workflows/approval-platform-validation.yml` |
| Action Whitelist | `EMPTY_PENDING_EXISTING_COMMAND_AUDIT` |
| P5 decision | `P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND` |

The exact P7-D document Head is the commit containing this document, the nine rehearsal tests and their permanent boundary assertions. Its SHA, natural `pull_request` Run, nine Job IDs, four Artifact IDs, local byte counts, local SHA-256 values, reconstructed test totals and final Review state are recorded after validation in the immutable PR #88 and Issue #81 P7 Acceptance comments. This avoids a circular documentation commit and avoids an additional workflow triggered only to copy the result of the workflow into the file that initiated it.

## 3. Permanent authority and safety boundary

The immutable authority chain remains:

`AI advisory -> typed non-executable proposal -> fresh server policy/precondition evaluation -> fresh authorization preview -> explicit human confirmation -> existing application command service -> immutable audited result`

The prohibited shortcut remains:

`Provider -> direct command`

No qualifying existing command exists. The production Action Whitelist remains empty, and P5-A remains skipped. Test-only contracts verify shapes and state machines but do not authorize a production Action.

P7 does not authorize:

- approve, reject/return, transfer, withdraw, terminate or migrate;
- any process-state transition or template activation;
- Provider, model, Prompt, policy, Secret, deployment or traffic mutation;
- direct Flowable or `ACT_*` access;
- arbitrary HTTP, SQL, Shell, script or Connector execution;
- Queue, Worker, Scheduler, Listener, Polling or autonomous execution;
- retry, fallback, rollback, notification or retention automation.

## 4. Threat / Fault / Race acceptance

The complete matrix is maintained in:

`docs/m6/M6_F_P7_ADVERSARIAL_FAULT_CONCURRENCY_MATRIX.md`

The matrix identifies each scenario, pre-P7 evidence, missing coverage, target module, PostgreSQL requirement, production-semantics relevance, fail-closed posture, legitimate UNKNOWN possibility and whether a production correction is permitted after a deterministic failure.

### 4.1 P7-R0

P7-R0 established the exact pre-P7 baseline and complete matrix without adding product capability. It fixed the following execution rules:

- no real external Provider;
- no real Secret;
- no new executable Action;
- no V51 unless a separately approved database-security design proves V49/V50 insufficient;
- deterministic concurrency based on latches, barriers, controlled clocks and real PostgreSQL transactions;
- independent Gate evidence and permanent validation.

P7-R0 Acceptance comment: `5199996329`.

### 4.2 P7-A adversarial acceptance

P7-A accepts the following fail-closed boundaries:

- raw HTTP validation before identity normalization;
- exactly one canonical `X-Tenant-Id` header;
- exact allowed query parameters with duplicate and pollution rejection;
- GET-only, no request body and no method-override header;
- strict canonical UTC `Instant` values;
- closed Change Plan operation enumeration;
- tenant-scoped management READ authorization before source access;
- lowercase exact SHA-256 evidence without attacker-input normalization;
- Proposal, Confirmation, snapshot, control, usage, history and rollback evidence tamper rejection;
- Prompt, command, HTTP, SQL, Shell, Flowable and Connector injection remains data and never becomes authority.

P7-A accepted real request-boundary and evidence-validation corrections. They did not widen permission, whitelist, command or Provider authority.

P7-A Acceptance comment: `5200193388`.

### 4.3 P7-B fault acceptance

P7-B accepts:

- exact pre-dispatch DNS, TLS, connection, Secret, admission, cost and rate failure classification with zero Usage;
- post-dispatch transport ambiguity as terminal non-retryable `UNKNOWN`;
- one Provider exchange, no retry and no fallback;
- Secret zeroization and body-free failure evidence;
- Circuit threshold, OPEN/HALF_OPEN transitions, one probe and Incident blocking;
- exactly-once Usage accounting in the original rate window;
- tenant/global saturation boundaries and global-exact-usage redaction;
- stale/future Cost and Secret windows;
- read-only `REPEATABLE_READ` durable-History queries with stable failure normalization;
- checked aggregate consistency and overflow rejection;
- real PostgreSQL Lineage event/state atomic rollback;
- PARTIAL preservation, CANCELLED zero-attempt semantics and UNKNOWN terminal semantics.

P7-B produced `26/26` new Java tests and `10/10` new permanent Node assertions. Its exact accepted Head was `c5ef700b20e4c99d84e79c3345179ca12baabb08`; natural Run `31070932544` / #1302 succeeded. Rebuilt totals were Maven core `1426`, JDBC `310`, aggregate `1736`, with zero failures, errors or skips.

P7-B Acceptance comment: `5200374680`.

### 4.4 P7-C concurrency acceptance

P7-C accepts deterministic races for:

- PostgreSQL Registration Replay/Conflict and tenant isolation;
- append-only Event identity and row-lock release;
- terminal CAS pairs and unique terminal winner;
- UNKNOWN against a second attempt;
- operator identity, duplicate Confirmation, Expiry, policy and stale-version races;
- tenant/global Usage saturation, concurrent Snapshot/Record, capacity and four-window retention;
- duplicate dispatch and original admission-window ownership;
- Circuit threshold, OPEN rejection, exactly one HALF_OPEN probe, competing probe outcomes and generation observations;
- cross-cycle snapshot/control/usage/history/rollback replacement;
- runtime changes during composite History reads;
- retry attempts that try to splice a healthier component set.

P7-C corrected two deterministic production defects:

1. Circuit state and generation are read in one synchronized control-snapshot critical section.
2. Incident Readiness captures Runtime Control and Usage before composition and verifies their stable governance fields afterward. Drift fails closed without retry, component selection, Binding creation, Secret read or Provider invocation.

P7-C produced `36/36` new Java tests and `11/11` new permanent Node assertions. Exact accepted Head: `bfffe838ea8e68829a3a656635aca644a7a83d91`. Natural Run `31078769144` / #1303 succeeded with all nine Jobs. Rebuilt totals were Maven core `1454`, JDBC `318`, aggregate `1772`, all with zero failures, errors or skips. Permanent M6 transport boundary: `143/143`.

P7-C Acceptance comment: `5201428730`.

## 5. P7-D manual Incident / Rollback rehearsal

P7-D does not implement an Incident executor or Rollback executor. It proves that each operator-facing posture remains read-only, evidence-bound and manual.

### Scenario 1 — Runtime not configured

Test:

`ControlledAutomationGovernanceIncidentRollbackRehearsalTest.scenario1RuntimeNotConfiguredIsAlreadyDisabledAndRequiresNoReleaseAction`

Expected and asserted:

- `RUNTIME_NOT_CONFIGURED`;
- Rollback posture `ALREADY_DISABLED`;
- operator confirms the Runtime is disabled;
- no Provider invocation;
- no Runtime Binding;
- no release mutation;
- no command execution.

### Scenario 2 — Healthy advisory Runtime

Test:

`ControlledAutomationGovernanceIncidentRollbackRehearsalTest.scenario2HealthyRuntimeRemainsAdvisoryOnlyWithEmptyWhitelistAndNoReauth`

Expected and asserted:

- `OBSERVATION_READY_ADVISORY_ONLY`;
- Action Whitelist remains `EMPTY_PENDING_EXISTING_COMMAND_AUDIT`;
- P5-A remains `P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND`;
- Production Reauthentication remains unavailable;
- command execution remains unavailable;
- operator may continue read-only monitoring only.

### Scenario 3 — Circuit OPEN

Test:

`ControlledAutomationGovernanceIncidentRollbackRehearsalTest.scenario3CircuitOpenIsIncidentBlockedWithManualRollbackReviewOnly`

Expected and asserted:

- `INCIDENT_BLOCKED`;
- Control Health verification step;
- non-executable Rollback Plan review;
- no rollback action;
- no retry;
- no notification automation.

### Scenario 4 — Circuit HALF_OPEN

Test:

`ControlledAutomationGovernanceIncidentRollbackRehearsalTest.scenario4CircuitHalfOpenIsNotHealthyAndDoesNotInitiateAProbe`

Expected and asserted:

- `INCIDENT_BLOCKED`;
- HALF_OPEN is not healthy;
- a read-only Control Snapshot does not acquire a probe;
- no traffic recovery;
- no Provider call.

### Scenario 5 — Tenant rate saturation

Test:

`ControlledAutomationGovernanceIncidentRollbackRehearsalTest.scenario5TenantRateSaturationDoesNotResetLimitsOrInvokeProvider`

Expected and asserted:

- `INCIDENT_BLOCKED`;
- tenant committed requests equal the exact configured limit;
- remaining requests equal zero;
- the RateLimiter is not reset or enlarged;
- no Provider call.

### Scenario 6 — Global rate saturation

Test:

`ControlledAutomationGovernanceIncidentRollbackRehearsalTest.scenario6GlobalRateSaturationExposesOnlyTheBooleanGlobalPosture`

Expected and asserted:

- `INCIDENT_BLOCKED`;
- global saturation is exposed only as a boolean;
- no exact global counter is exposed;
- no other-tenant Usage is exposed;
- no Provider call.

### Scenario 7 — Durable-history version drift

Test:

`ControlledAutomationGovernanceIncidentRollbackRehearsalTest.scenario7VersionDriftRequiresReviewWithoutRestoringOrMutatingRuntime`

Expected and asserted:

- `ACTION_REQUIRED`;
- manual version-history review step;
- no automatic version restore;
- no Provider configuration mutation;
- no command execution.

### Scenario 8 — Retention due

Test:

`ControlledAutomationGovernanceIncidentRollbackRehearsalTest.scenario8RetentionDueRequiresManualTombstoneReviewWithoutScheduler`

Expected and asserted:

- `ACTION_REQUIRED`;
- manual Tombstone review step;
- no automatic Tombstone;
- no Scheduler, Worker or polling path.

### Scenario 9 — Provider sent, outcome UNKNOWN

Test:

`OpenAiResponsesPostDispatchUnknownIncidentRehearsalTest.scenario9PostDispatchUnknownRemainsSingleAttemptAuditableAndNonRetryable`

Expected and asserted:

- injected post-dispatch timeout becomes `UNKNOWN`;
- exactly one resolve, connect and exchange;
- exactly one committed Usage record;
- no second Provider request;
- no secret disclosure;
- no approval command;
- evidence remains auditable.

## 6. P7-D test and static evidence

P7-D adds exactly:

- eight Server Incident / Rollback rehearsal tests;
- one OpenAI post-dispatch UNKNOWN rehearsal test;
- five permanent Node boundary assertions.

The Node boundary binds all nine Scenario names, all four Readiness states, manual-only authority flags, the empty whitelist, skipped P5-A, V50 migration ceiling, the sole automatic workflow and the absence of command, Worker and Scheduler authority.

The rehearsal tests do not use `Thread.sleep`, random probability, a real Provider or a real Secret.

## 7. First failures and Corrections

P7 preserves every real failed permanent Run from prior Gates in GitHub Actions history. P7-D itself is developed on an isolated temporary branch so compilation and boundary assumptions can be corrected before the single formal branch update.

One P7-D test-contract correction was made before formal validation:

- initial Scenario 9 attempted to call a nonexistent `retryable()` method on the low-level transport exception;
- root cause: non-retryability belongs to the one-exchange transport evidence and upper provider outcome contract, not the transport exception object;
- correction: assert `exchangeCount == 1`, `UNKNOWN`, one Usage record and no retry/secret text;
- no production code, safety assertion or permission was weakened.

At the document commit point there is no P7-D production Correction. Any permanent Run failure must remain visible and be corrected by a minimal independent commit before P7 can close.

## 8. Permanent validation contract

The exact document Head must naturally trigger `.github/workflows/approval-platform-validation.yml` and must complete with these nine physical Jobs successful:

1. Java 21 / Maven core;
2. Persistence JDBC shard 0;
3. Persistence JDBC shard 1;
4. Persistence JDBC shard 2;
5. Persistence JDBC shard 3;
6. Java 21 / Maven / PostgreSQL aggregate;
7. Vben TypeScript / production build;
8. UniApp TypeScript / H5 / WeChat;
9. Repository hygiene.

The final P7 Acceptance comments must record:

- exact P7-D Head;
- Run ID and Run Number;
- all nine Job IDs and conclusions;
- Maven, Vben, Mobile and Hygiene Artifact IDs;
- local Artifact byte counts and SHA-256 values;
- Artifact expiry times;
- independently reconstructed Maven core, JDBC, aggregate, AI Core, OpenAI, Server and Architecture test counts;
- P7-D Java and Node counts;
- JDBC selected-class/report/abstract/duplicate/no-report reconciliation;
- final Review, PR and Issue states.

Copying only the workflow summary is insufficient.

## 9. Review, PR and Issue state before final Gate

Before the P7-D formal branch update:

- PR #88 remains Open + Draft + mergeable and not merged;
- `main` remains `492a428627d3be707d5723350506302ca04841b0`;
- the accepted P7-C Head is behind `0`;
- no Review, `REQUEST_CHANGES` or unresolved Review Thread exists;
- Issues #81, #82, #62, #13 and #14 remain Open;
- PR #83 remains Merged / Closed and unchanged;
- auto-merge is disabled and not configured.

These facts must be re-read after the P7-D Run and before the P7 Gate conclusion.

## 10. Honest limitations

P7 verifies a safe non-executing foundation. It does not provide:

- a production Action or non-empty Action Whitelist;
- Production Reauthentication;
- approval, rejection/return, transfer, withdrawal, termination or migration commands;
- application-command admission from AI output;
- actual Provider billing;
- durable P6-D cost-upper-bound history;
- durable Circuit or control-health time series;
- Canary, rollout or traffic mutation;
- executable rollback;
- incident notification automation;
- retention automation;
- Provider, model, Prompt, policy or Secret mutation;
- direct Flowable, arbitrary HTTP, SQL, Shell, script or Connector execution;
- Queue, Worker, Scheduler, Listener, Polling or autonomous execution.

The absence of executable production automation is an accepted safety limitation, not a hidden implementation detail.

## 11. P8 entry gate

P8-R0 may start only after all of the following are true:

1. P7-R0, P7-A, P7-B, P7-C and P7-D are independently accepted;
2. the exact P7-D document Head has a successful permanent Run;
3. all nine Jobs succeed;
4. all four Artifacts are downloaded and independently verified;
5. Maven/JDBC/Node/JDBC-shard totals are independently reconstructed;
6. no `REQUEST_CHANGES` or unresolved Review blocker exists;
7. PR #88 remains Open + Draft;
8. `main` has not drifted, or a separately accepted Merge Commit rebaseline has completed;
9. Action Whitelist remains empty;
10. P5-A remains skipped;
11. no executable command, retry, rollback, notification or retention automation exists;
12. no new Provider, real Secret, V51 or second automatic workflow exists;
13. exact P7 Acceptance is written to both PR #88 and Issue #81.

Until those conditions are re-read and proven:

`P7_GATE_PENDING`

`P8_PROHIBITED`

`AI_IS_NOT_AN_OPERATOR`
