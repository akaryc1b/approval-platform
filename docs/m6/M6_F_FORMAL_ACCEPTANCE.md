# M6-F Formal Acceptance — Controlled Automation and AI Governance

Status: `P8_G2_DOCUMENTED_PENDING_EXACT_HEAD_PERMANENT_VALIDATION`

Permanent conclusion: `AI_IS_NOT_AN_OPERATOR`

## 1. Scope

M6-F formally accepts the safe, governed and deliberately non-executing foundation that connects an AI advisory result to typed Proposal, fresh server evaluation, explicit human Confirmation evidence, durable Lineage and read-only governance operations.

This acceptance does not claim that M6-F contains executable production automation. The production Action Whitelist remains empty, P5-A remains skipped, Production Reauthentication remains unavailable, and no application command is admitted from an AI result.

Accepted stages:

1. R0 — current-main rebaseline and authority threat model;
2. P0 — existing-command audit and empty Action Whitelist decision;
3. P1 — typed server-created non-executable Proposal;
4. P2 — fresh tenant/operator/policy/authorization/state evaluation;
5. P3 — explicit non-executable Confirmation and Production Reauthentication boundary;
6. P4 — durable hash-only Lineage, idempotency, CAS, Replay, Cancellation, PARTIAL and UNKNOWN;
7. P6-A — Provider/model/Prompt/policy/output-schema inventory;
8. P6-B — deterministic non-executable Canary/Rollout/Rollback review plans;
9. P6-C — shared production Runtime Control Health;
10. P6-D — process-local tenant Usage observability;
11. P6-E — V49-backed durable governance History;
12. P6-F — single-snapshot composite Incident Readiness;
13. P7-R0 — Threat / Fault / Race / Incident matrix;
14. P7-A — adversarial identity, HTTP, Proposal, Confirmation and evidence acceptance;
15. P7-B — Provider, Transport, Circuit, Usage, History and Lineage fault acceptance;
16. P7-C — deterministic Replay, CAS, Usage, Circuit and composite-snapshot concurrency acceptance;
17. P7-D — nine manual Incident / Rollback rehearsals and P7 Gate;
18. P8-R0 — final rebaseline and functional scope freeze;
19. P8-G1 — completeness, security, compatibility, upgrade and production-readiness audit;
20. P8-G2 — this Formal Acceptance and its exact permanent evidence contract.

M6-F does not develop unrelated M6-G or M7 capability.

## 2. Exact repository baseline

| Item | Exact value |
| --- | --- |
| Repository | `akaryc1b/approval-platform` |
| Target branch | `main` |
| Exact Base SHA | `492a428627d3be707d5723350506302ca04841b0` |
| Formal branch | `agent/m6-f-controlled-automation-and-ai-governance` |
| Formal Acceptance temporary branch | `agent/m6-f-p8-g2-formal-acceptance` |
| Pull Request | `#88 — M6-F: controlled automation and AI governance` |
| Issue | `#81 — [M6-F] Controlled Automation and AI Governance` |
| Parent Issue | `#62 — [M6] Ecosystem and AI` |
| Later gated Issue | `#82 — [M6-G] M6 Overall Formal Acceptance and Production Readiness` |
| Exact accepted G1 Head / final functional Head | `31278be0243f9ddb80d76dbd009631d8e844ea88` |
| Compare at G2 start | ahead `174`, behind `0`; merge base equals current `main` |
| PR state at G2 start | Open / Draft / mergeable / not merged |
| Commit count at G2 start | `174` |
| Changed files at G2 start | `143` |
| Additions / deletions at G2 start | `28245 / 28` |
| Highest governed migration | unique `V50`; no `V51+` |
| Automatic PR/main workflow | only `.github/workflows/approval-platform-validation.yml` |
| Reviews | none |
| `REQUEST_CHANGES` | none |
| Unresolved Review Threads | none |
| Auto-merge | disabled and not configured |

The exact Formal Acceptance document Head is the commit containing this document, its permanent boundary test and its permanent import. Its exact SHA, natural `pull_request` Run, nine Job IDs, four Artifact IDs, independently calculated ZIP byte counts and SHA-256 values, reconstructed test totals and final Review state are recorded after validation in immutable PR #88 and Issue #81 Acceptance comments.

This avoids a circular self-referencing commit and avoids a second documentation-only workflow whose sole purpose would be copying the result of the first workflow into the file that initiated it.

The exact accepted G2 validation Head becomes the only Head eligible for the later P8-G3 Ready Gate. Any later commit invalidates this acceptance and requires a new complete permanent Run.

## 3. Authority chain and AI safety boundary

The permanent authority chain is:

`AI advisory -> typed non-executable proposal -> fresh server policy/precondition evaluation -> fresh authorization preview -> explicit human confirmation -> existing application command service -> immutable audited result`

The permanently prohibited shortcut is:

`Provider -> direct command`

The permanent safety statement is:

`AI_IS_NOT_AN_OPERATOR`

AI cannot manufacture or replace:

- tenant identity;
- operator identity;
- authorization;
- permission;
- policy;
- process or task authority;
- audit identity;
- Runtime identity;
- Worker, Queue, Scheduler or lease identity;
- application-command admission.

No AI output is an approval decision or execution token.

## 4. Action Whitelist and P5 decision

Production Action Whitelist:

`EMPTY_PENDING_EXISTING_COMMAND_AUDIT`

P5 decision:

`P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND`

The repository contains no qualifying existing low-risk business command that satisfies all required server authorization, idempotency, stale-state, audit and rollback or compensation guarantees.

M6-F does not invent, test-authorize or document-authorize a command merely to claim automation completeness.

The empty whitelist means:

- Proposal creation cannot produce executable authority;
- fresh evaluation returns `ACTION_NOT_WHITELISTED` for production execution;
- Confirmation remains non-executable evidence;
- Lineage can record only governed non-executing state and bounded terminal evidence;
- no application command is called;
- no process state is advanced.

## 5. Accepted Controlled Automation contracts

### 5.1 Typed Proposal

The Proposal is:

- created by trusted server code;
- tenant and operator evidence-bound;
- tied to one source advisory evidence ID and hash;
- tied to exact Provider/model/Prompt/knowledge/policy/output-schema version references;
- tied to one canonical closed Action type;
- limited to bounded typed parameters;
- tied to an expected resource identity, state, version and observation time;
- tied to an expiry;
- tied to risk classification and full side-effect summary;
- marked `NON_EXECUTABLE_PROPOSAL`;
- required to receive explicit human Confirmation;
- unable to carry credential, Secret, URL, SQL, Shell, script, class or module authority.

### 5.2 Fresh Governance Evaluation

Every evaluation reloads and checks:

- trusted tenant context;
- trusted operator context;
- Proposal status and Lineage hash;
- Proposal expiry;
- source evidence existence, identity and integrity;
- current Action Whitelist version and exact Action definition;
- policy version and evidence;
- feature enablement;
- kill-switch posture;
- permission;
- resource authorization;
- exact resource identity;
- exact resource state;
- exact resource version;
- separation of duties;
- command preconditions;
- Production Reauthentication status.

The result is a read-only authorization preview. It does not invoke a Provider, Connector, Flowable, persistence mutation or application command.

### 5.3 Explicit Confirmation

Confirmation requires:

- explicit click intent;
- exact Proposal ID and Lineage hash;
- exact evaluation evidence hash;
- exact tenant and operator evidence;
- active, unexpired Proposal;
- eligible fresh evaluation;
- matching whitelist version;
- exact reauthentication challenge binding;
- accepted and current step-up verification.

The current production verifier returns `UNAVAILABLE`. This blocks production Confirmation.

Even an accepted test Confirmation is:

- single-use evidence;
- `NON_EXECUTABLE_CONFIRMATION`;
- command-free;
- not proof of command success.

### 5.4 Durable Lineage

V50-backed Lineage provides:

- tenant/operator isolation;
- registration idempotency;
- exact Replay versus Conflict;
- append-only Event identity;
- row locking;
- revision CAS;
- one terminal transition;
- terminal winner uniqueness;
- Cancellation with zero command attempt;
- SUCCESS, FAILURE, PARTIAL and UNKNOWN with exactly one bounded attempt;
- permanent `automatic_retry_allowed=false`;
- event/state atomicity with deferred verification constraints;
- immutable identity and evidence fields;
- auditable predecessor and current hashes.

UNKNOWN remains terminal and non-retryable. PARTIAL cannot be converted to SUCCESS. CANCELLED cannot claim an attempt.

## 6. Accepted AI Governance and Provider safety

### 6.1 Versioned inventory

The read-only inventory binds each supported advisory capability to:

- Provider ID and version;
- model Provider ID, model ID and model version;
- Prompt template ID, version and hash;
- knowledge-source ID, version and hash;
- policy ID, version and hash;
- output-schema ID and version.

Knowledge Source remains `none` and is recorded as containing no customer data.

### 6.2 Production Provider boundary

The selected production adapter remains the OpenAI Responses advisory Provider established by M6-E.

Provider safety guarantees include:

- trusted server-owned tenant and request context;
- masking, minimization and field-permission enforcement before invocation;
- server-owned Secret reference and callback-scoped material access;
- DNS and verified TLS before Secret material access;
- no raw Secret in configuration, evidence, logs or operations views;
- one synchronous Provider attempt at most;
- no retry or post-invocation fallback;
- exact Admission before dispatch;
- post-dispatch ambiguity becomes terminal UNKNOWN;
- no raw response body in durable evidence;
- reasoning content and encrypted reasoning content are never decoded, persisted or surfaced;
- original transport response body remains bound to transport evidence;
- advisory output remains human-review-required and unverified as authority.

CI uses no real external Provider and no real Secret.

### 6.3 One shared Runtime control plane

Production wiring owns exactly one:

- Runtime factory;
- Circuit Breaker;
- RateLimiter;
- Usage Ledger;
- Cost Policy;
- Kill Switch snapshot.

Read-only operations observe the shared Runtime. They do not construct a second Runtime, Circuit, RateLimiter or Usage Ledger.

Control and Usage snapshots do not reserve a permit, read a Secret, create a tenant Binding or call the Provider.

## 7. Tenant isolation and authorization

The governance API is protected by:

- raw-request validation before trusted identity normalization;
- exactly one canonical `X-Tenant-Id` header;
- tenant-scoped management `READ` permission;
- exact resource scope `TENANT`;
- exact query-name and single-value validation;
- canonical UTC `Instant` validation;
- bounded history windows;
- GET-only enforcement;
- no request body;
- no method override;
- `Cache-Control: no-store`;
- authorization before source access;
- tenant-specific Usage and History sources;
- redaction of exact global and other-tenant Usage.

A malformed, duplicate, conflicting or cross-tenant request fails closed before Runtime Binding, Secret read, Provider call or persistence write.

## 8. Read-only Operations acceptance

Exactly six accepted endpoints exist:

1. `GET /api/approval/management/ai-governance/snapshot`;
2. `GET /api/approval/management/ai-governance/change-plan?operation=<CANARY|ROLLOUT|ROLLBACK>`;
3. `GET /api/approval/management/ai-governance/control-health`;
4. `GET /api/approval/management/ai-governance/usage`;
5. `GET /api/approval/management/ai-governance/history?from=<canonical Instant>&to=<canonical Instant>`;
6. `GET /api/approval/management/ai-governance/incident-readiness?from=<canonical Instant>&to=<canonical Instant>`.

All six are:

- tenant-scoped;
- management `READ` only;
- GET-only;
- no-store;
- non-mutating;
- non-executing;
- unable to read Secret material;
- unable to create a Runtime Binding;
- unable to call a Provider;
- unable to execute a Change Plan.

Change Plans keep planned traffic, apply, deployment, mutation and command execution unavailable.

Accepted Incident Readiness states are:

- `RUNTIME_NOT_CONFIGURED`;
- `OBSERVATION_READY_ADVISORY_ONLY`;
- `ACTION_REQUIRED`;
- `INCIDENT_BLOCKED`.

These are evidence postures, not actions.

## 9. Persistence guarantees and migration ownership

### 9.1 V49 durable advisory evidence

V49 owns:

- immutable hash-only approval-assistance evidence;
- ACTIVE or TOMBSTONED state;
- append-only state Events;
- bounded retention timestamps;
- exact Provider-attempt and invocation consistency;
- retry and fallback prohibition;
- exact aggregate fields;
- deferred Event/State consistency constraints;
- tenant-scoped indexes;
- manual Tombstone transition only.

V49 stores no raw Prompt, Provider request, Provider response, advisory text, Secret or customer-field value.

### 9.2 V50 controlled-automation Lineage

V50 owns:

- controlled-automation Lineage;
- controlled-automation append-only Events;
- immutable Proposal/Confirmation identity hashes;
- registration idempotency key and payload hashes;
- exact revision, status, outcome and attempt constraints;
- one terminal transition;
- deferred state/Event consistency;
- tenant/status/resource/Event indexes.

V50 stores no raw Proposal parameter, command payload, Provider body, Secret or other-tenant detail.

### 9.3 Upgrade and installation

Accepted PostgreSQL tests verify:

- fresh installation reaches unique V50;
- historical upgrade paths reach V50;
- release-lifecycle and migration evidence remains unchanged;
- five-thousand-instance/task upgrade rehearsal remains bounded;
- no execution side effect is introduced by migration;
- V49/V50 ownership remains unique;
- no V51 exists.

Historical migrations are not modified by P8.

## 10. Fault acceptance

P7-B accepted:

- DNS, TLS, connection, Secret, Admission, Cost and Rate pre-dispatch failures with zero Usage;
- connection and read ambiguity after dispatch as terminal UNKNOWN;
- one exchange only;
- no retry and no fallback;
- malformed JSON, oversized response and output-schema mismatch fail-closed behavior;
- stale and future Secret windows;
- stale and future Cost Policy windows;
- kill-switch rejection;
- cost-envelope rejection;
- Circuit CLOSED, OPEN and HALF_OPEN transitions;
- exactly one HALF_OPEN probe;
- OPEN and HALF_OPEN as `INCIDENT_BLOCKED`;
- tenant and global rate saturation;
- exact global Usage redaction;
- original rate-window ownership;
- read-only `REPEATABLE_READ` History;
- aggregate mismatch and overflow rejection;
- PostgreSQL Lineage event/state rollback;
- PARTIAL and UNKNOWN preservation.

No failure can create authority, a second Provider attempt, an approval command or a synthetic success.

## 11. Concurrency acceptance

P7-C accepted deterministic concurrency for:

- identical Registration Replay;
- conflicting Registration payloads;
- same Proposal and same idempotency key races;
- same external identity across different tenants;
- append-only Event uniqueness;
- operator and duplicate Confirmation races;
- Confirmation versus Expiry, Cancellation, stale version and policy change;
- SUCCESS versus FAILED, UNKNOWN, PARTIAL and CANCELLED;
- unique terminal winner;
- replay after row-lock release;
- same-tenant and multi-tenant Usage admission;
- tenant/global saturation boundaries;
- concurrent Usage Snapshot and record;
- capacity and four-window retention;
- duplicate dispatch;
- original rate-window ownership;
- concurrent Circuit threshold failures;
- OPEN admission;
- exactly one HALF_OPEN probe;
- competing probe outcomes;
- monotonic Circuit generation;
- Snapshot/Control/Usage/History/Rollback replacement;
- Runtime drift during composition;
- retry-splice rejection;
- cross-cycle and healthier-component selection rejection.

Concurrency tests use controlled clocks, latches, virtual-thread executors, controlled Transport and real PostgreSQL transactions. They do not rely on random probability or `Thread.sleep`.

P7-C found and corrected two real production races:

1. Circuit state and generation are read in one synchronized control-snapshot critical section.
2. Incident Readiness captures Runtime Control and Usage before composition and revalidates stable governance fields afterward. Drift fails closed without retry, component selection, Binding creation, Secret read or Provider call.

## 12. Incident and Rollback rehearsal

P7-D manually rehearsed:

1. Runtime not configured -> `RUNTIME_NOT_CONFIGURED`, Rollback `ALREADY_DISABLED`;
2. healthy advisory Runtime -> `OBSERVATION_READY_ADVISORY_ONLY` with empty whitelist and no Production Reauthentication;
3. Circuit OPEN -> `INCIDENT_BLOCKED` and manual non-executable Rollback review;
4. Circuit HALF_OPEN -> `INCIDENT_BLOCKED`, no active probe and no traffic recovery;
5. tenant rate saturation -> `INCIDENT_BLOCKED`, no limit reset and no Provider call;
6. global rate saturation -> `INCIDENT_BLOCKED`, boolean posture only and exact global Usage redacted;
7. durable-history version drift -> `ACTION_REQUIRED`, no automatic version restore or Provider mutation;
8. retention due -> `ACTION_REQUIRED`, manual Tombstone review and no Scheduler;
9. post-dispatch timeout -> terminal UNKNOWN, one exchange, one Usage record, no second Provider request and no command.

Rollback plans are review documents only. There is no automatic or executable rollback path.

## 13. Web and Mobile acceptance

PC and Mobile both display:

- AI advisory status;
- `AI_IS_NOT_AN_OPERATOR`;
- `NON_EXECUTABLE`;
- `NOT_AUTHORIZED`;
- `ACTION_NOT_WHITELISTED`;
- empty Action Whitelist version;
- Production Reauthentication `UNAVAILABLE`;
- risk and side-effect limitations;
- expected state/version limitation;
- disabled Confirmation control;
- explicit statement that Confirmation success is not command success.

Neither client contains an enabled execution action or a misleading “confirm and execute” path.

Template comments, implementation notes and test markers are not rendered to users.

## 14. Workflow and repository acceptance

The only automatic PR/main workflow remains:

`.github/workflows/approval-platform-validation.yml`

It contains nine physical Jobs:

1. Java 21 / Maven core;
2. Persistence JDBC shard 0;
3. Persistence JDBC shard 1;
4. Persistence JDBC shard 2;
5. Persistence JDBC shard 3;
6. Java 21 / Maven / PostgreSQL aggregate;
7. Vben TypeScript / production build;
8. UniApp TypeScript / H5 / WeChat;
9. Repository hygiene.

The workflow has read-only repository permissions, no broad test skip and no `continue-on-error` bypass.

Repository hygiene rejects:

- generated build output;
- temporary workflows and patch payloads;
- tracked logs and ZIPs;
- credential-like content;
- local absolute paths;
- frozen M4 governance drift;
- missing permanent M5/M6 boundary wiring.

P8 adds no second automatic workflow and makes no direct `main` modification.

## 15. P7 permanent evidence

### 15.1 P7-B

- exact Head: `c5ef700b20e4c99d84e79c3345179ca12baabb08`;
- Run `31070932544` / #1302: success;
- new Java fault tests: `26/26`;
- new Node assertions: `10/10`;
- reconstructed Maven core `1426`, JDBC `310`, aggregate `1736`.

### 15.2 P7-C

- exact Head: `bfffe838ea8e68829a3a656635aca644a7a83d91`;
- Run `31078769144` / #1303: success;
- new Java concurrency tests: `36/36`;
- new Node assertions: `11/11`;
- permanent M6 boundary `143/143`;
- reconstructed Maven core `1454`, JDBC `318`, aggregate `1772`.

### 15.3 P7-D and P7 Gate

- exact P7 Head: `71bfd111d4c73c9b467dd5702b56c87e29add51a`;
- Run `31079997571` / #1304: success;
- new Java rehearsal tests: `9/9`;
- new Node assertions: `5/5`;
- permanent M6 boundary `148/148`;
- reconstructed Maven core `1463`, JDBC `318`, aggregate `1781`;
- all nine Jobs and all four Artifacts succeeded and were independently verified;
- PR Acceptance comment `5201607304`;
- Issue #81 Acceptance comment `5201612285`;
- conclusion `P7_GATE_PASSED`.

## 16. P8-R0 permanent evidence

- exact Head: `59b2f161982f30bdfc56ba0bf34c26e23d5b35ad`;
- natural Run `31080901527` / #1305: success;
- all nine Jobs succeeded;
- Maven Artifact `8959468394`;
- Vben Artifact `8959444011`;
- Mobile Artifact `8959428417`;
- Hygiene Artifact `8959408311`;
- reconstructed Maven core `1463`, JDBC `318`, aggregate `1781`;
- permanent M6 boundary `154/154`;
- P8-R0 assertions `6/6`;
- Acceptance comment `5201740440`.

P8-R0 froze the functional scope. G1 and G2 may add only audit and Formal Acceptance evidence or minimal deterministic blocker Corrections.

## 17. P8-G1 audit and permanent evidence

### 17.1 Audit conclusion

The complete audit found no blocking completeness, security, compatibility, upgrade or production-readiness defect inside the frozen M6-F scope.

### 17.2 Preserved failed Runs and Corrections

1. Run `31082175396` / #1306 at `a0e6607d2dc65593519473284b536817e5838c1d`:
   - classification `Test Bug`;
   - audit test self-matched the existing credential scanner;
   - Correction `05510389b6d8b1ecd88663dfd7ba344d959c9c6a`.
2. Run `31084456213` / #1307 at `05510389b6d8b1ecd88663dfd7ba344d959c9c6a`:
   - classification `Test Bug`;
   - two assertions did not match exact reauthentication and V50 contracts;
   - Correction `893b745a6bdfdd248a645390f0467653733e65e0`.
3. Run `31084843048` / #1308 at `893b745a6bdfdd248a645390f0467653733e65e0`:
   - classification `Test Bug`;
   - two remaining literal mismatches;
   - Correction and accepted G1 Head `31278be0243f9ddb80d76dbd009631d8e844ea88`.

All failures remain visible. All Corrections changed only the G1 Node audit test and did not change product authority or safety behavior.

### 17.3 Final G1 Run and Job IDs

Natural Run `31085215490` / #1309 completed success at exact Head `31278be0243f9ddb80d76dbd009631d8e844ea88`.

| Job | ID | Conclusion |
| --- | ---: | --- |
| Java 21 / Maven core | `92562951620` | success |
| Persistence JDBC shard 0 | `92562951685` | success |
| Persistence JDBC shard 1 | `92562951853` | success |
| Persistence JDBC shard 2 | `92562951671` | success |
| Persistence JDBC shard 3 | `92562951681` | success |
| Java 21 / Maven / PostgreSQL aggregate | `92563531928` | success |
| Vben TypeScript / production build | `92562951702` | success |
| UniApp TypeScript / H5 / WeChat | `92562951628` | success |
| Repository hygiene | `92562951626` | success |

### 17.4 G1 Artifact IDs, sizes, SHA-256 and expiry

| Artifact | ID | Bytes | SHA-256 | Expires |
| --- | ---: | ---: | --- | --- |
| Maven | `8961168294` | `354499` | `3d95726a57c1fdad573063dc0d15869d8b9e0fee5689499de2839cc0f3abc2ef` | `2026-11-04T08:31:01Z` |
| Vben | `8961142159` | `18938` | `6e2de7f94e2b38b6f273e3a5bf7b556665302901184c913f9d9f2c99fda43d93` | `2026-11-04T08:31:01Z` |
| Mobile | `8961125127` | `9806` | `e48e71e687483955059744a7ff1834c68194bd04060db08d9c75707f4f792565` | `2026-11-04T08:31:01Z` |
| Hygiene | `8961101718` | `15304` | `22ce5d6ac871a54ecf0dc96b24a83e3ebd699c031013d1ccb2209c9d52e99b8c` | `2026-11-04T08:31:01Z` |

All four ZIPs were independently downloaded. Local byte counts and SHA-256 values exactly matched GitHub metadata.

### 17.5 Independently reconstructed G1 counts

- Maven core: `1463 / 0 failures / 0 errors / 0 skipped`;
- Persistence JDBC: `318 / 0 / 0 / 0`;
- aggregate: `1781 / 0 / 0 / 0`;
- architecture: `159/159`;
- server: `266/266`;
- AI SPI: `12/12`;
- AI Core: `204/204`;
- OpenAI: `102/102`;
- application: `233/233`;
- P8-G1 Node audit assertions: `9/9`;
- permanent M6 transport/G1 boundary: `163/163`;
- JDBC selected classes: `79`;
- JDBC unique selected classes: `79`;
- Surefire reports: `78`;
- expected abstract class without report: `1`;
- duplicate selections: `0`;
- non-abstract selected classes without report: `0`;
- Vben client boundary: `10/10`;
- Vben renderer: `3/3`;
- Vben designer: `2/2`;
- Web type-check/build: success;
- Mobile type-check/H5/WeChat: success.

P8-G1 Acceptance comments:

- PR #88 `5202364889`;
- Issue #81 `5202368056`.

## 18. Formal Acceptance document validation contract

The exact G2 document Head must naturally trigger `.github/workflows/approval-platform-validation.yml` and must complete with all nine physical Jobs successful.

The immutable PR #88 and Issue #81 G2 Acceptance comments must record:

- exact G2 Head;
- final commit count, changed-file count and additions/deletions;
- Run ID and Run Number;
- event, status and conclusion;
- all nine Job IDs and conclusions;
- Maven, Vben, Mobile and Hygiene Artifact IDs;
- each Artifact byte count, locally calculated SHA-256 and expiry time;
- Maven core, JDBC and aggregate totals;
- Architecture, Server, AI Core, OpenAI and G2 Node counts;
- permanent Node boundary total;
- JDBC selected/report/abstract/duplicate/no-report reconciliation;
- Web and Mobile results;
- final Review state;
- final PR and Issue state;
- Action Whitelist and P5 decision;
- Ready Gate decision.

Copying only the workflow summary is insufficient.

## 19. Review state and PR metadata requirement

At G2 start:

- PR #88 is Open + Draft + mergeable and not merged;
- main remains unchanged;
- behind equals zero;
- no Review exists;
- no `REQUEST_CHANGES` exists;
- no unresolved Review Thread exists;
- Issues #81, #82, #62, #13 and #14 remain Open;
- PR #83 remains Merged / Closed;
- auto-merge remains disabled.

These facts must be re-read after the exact G2 Run and before P8-G3.

The PR description must identify:

- the exact accepted G2 Head;
- final Run and Artifacts;
- all failed Runs and Corrections;
- Action Whitelist and P5 decision;
- honest limitations;
- whether Ready is authorized.

## 20. Honest limitations

M6-F does not provide:

- a non-empty production Action Whitelist;
- an executable production Action;
- Production Reauthentication;
- approve, reject/return, transfer, withdraw, terminate or migrate commands;
- any process-state command;
- application-command admission from AI output;
- automatic Retry or fallback;
- automatic Rollback;
- automatic Incident Notification;
- automatic Retention Tombstone;
- actual Provider billing;
- durable P6-D cost-upper-bound history;
- durable Circuit or Control Health time-series;
- Canary, rollout, deployment or traffic mutation;
- Provider, model, Prompt, Policy or Secret mutation;
- direct Flowable or `ACT_*` access;
- arbitrary HTTP, SQL, Shell, script or Connector execution;
- Queue, Worker, Scheduler, Listener, Polling or autonomous execution.

The absence of executable production automation is an accepted and permanent safety limitation for this milestone. It is not hidden by the phrase “Controlled Automation.”

## 21. Merge Gate

P8-G3 is prohibited until the exact G2 document Head satisfies all of the following:

1. PR #88 remains Open and Draft;
2. PR #88 remains mergeable;
3. behind remains zero;
4. main remains the exact accepted Base SHA;
5. the exact G2 Head is unchanged;
6. the complete natural PR workflow succeeds;
7. all nine Jobs succeed;
8. all four Artifacts are independently downloaded and verified;
9. all test counts are reconstructed from Artifacts;
10. no Review requests changes;
11. no unresolved Review Thread exists;
12. no Security Blocker exists;
13. P7, P8-R0, G1 and G2 are accepted;
14. this Formal Acceptance document exists at the exact validated Head;
15. PR metadata and Acceptance comments are current;
16. Action Whitelist and P5 status remain honestly documented;
17. auto-merge remains disabled;
18. Issues #81, #82, #62, #13 and #14 remain Open.

Only then may P8-G3 mark the PR Ready and re-read every Gate before an ordinary Merge Commit.

## 22. Merge method restriction

The only permitted merge method is an ordinary Merge Commit:

- `merge_method=merge`;
- exact `expected_head_sha` required;
- no squash;
- no rebase;
- no auto-merge;
- no force merge;
- no direct push to `main`.

Any new commit, Review blocker, Check failure, mergeability change or main drift after Ready cancels merge authorization.

## 23. Post-main verification requirement

A successful PR Run is not post-main evidence.

After merge, P8-G4 must verify the natural:

`push -> main`

workflow at the exact ordinary Merge Commit.

P8-G4 must re-read:

- PR merged and closed state;
- exact Merge Commit;
- exact `main` SHA equal to that Merge Commit;
- merge method evidence;
- Review state;
- Issue state.

The natural main Run must produce all nine successful Jobs and four new main-bound Artifacts. Old PR Artifacts cannot substitute for main Artifacts.

Post-main Maven/JDBC/Node/Web/Mobile evidence must be reconstructed independently.

## 24. Issue #81 closure condition

Issue #81 must remain Open until all of the following are true:

- PR #88 merged using ordinary Merge Commit;
- `main` equals the Merge Commit;
- natural main workflow succeeded;
- all nine main Jobs succeeded;
- all four main Artifacts were independently verified;
- main test counts were independently reconstructed;
- no Review blocker remains;
- final PR comment exists;
- final Issue #81 closure comment exists;
- Issue #82 remains Open;
- Issues #62, #13 and #14 remain Open.

Issue #81 must close with state reason `completed`.

Issue #82 may be unblocked only after Issue #81 is re-read as Closed / Completed. M6-G implementation remains outside this task and must not begin in this branch or PR.

## 25. Current Gate conclusion

At this document commit point:

- P7 Gate: accepted;
- P8-R0: accepted;
- P8-G1: accepted;
- P8-G2 document implementation: complete pending exact-Head permanent validation;
- P8-G3 Ready and Merge: prohibited;
- P8-G4 post-main verification: prohibited;
- P8-G5 Issue closure: prohibited;
- M6-G implementation: prohibited.

`P8_G2_DOCUMENTED_PENDING_EXACT_HEAD_PERMANENT_VALIDATION`

`READY_MERGE_ISSUE_CLOSURE_PROHIBITED`

`AI_IS_NOT_AN_OPERATOR`
