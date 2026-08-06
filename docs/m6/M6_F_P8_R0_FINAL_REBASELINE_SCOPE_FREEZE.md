# M6-F P8-R0 — Final Rebaseline and Scope Freeze

Status: `P8_R0_IMPLEMENTED_PENDING_EXACT_HEAD_PERMANENT_VALIDATION`

Permanent conclusion: `AI_IS_NOT_AN_OPERATOR`

## 1. Purpose

P8-R0 re-reads the complete post-P7 repository, Pull Request, Review, workflow, Artifact, migration and Issue state and freezes the M6-F functional scope before the final completeness and production-readiness audit.

P8-R0 adds no product capability. It does not authorize Ready, auto-merge, merge, Issue closure or M6-G. P8-G1 may start only after the exact P8-R0 document Head passes the permanent workflow, its four Artifacts are independently verified and the repository state is re-read.

## 2. Exact final P7 baseline

| Item | Exact re-read result |
| --- | --- |
| Repository | `akaryc1b/approval-platform` |
| Target branch | `main` |
| Exact current `main` | `492a428627d3be707d5723350506302ca04841b0` |
| Pull Request | `#88 — M6-F: controlled automation and AI governance` |
| Formal PR branch | `agent/m6-f-controlled-automation-and-ai-governance` |
| Exact accepted P7 Head | `71bfd111d4c73c9b467dd5702b56c87e29add51a` |
| Compare | ahead `163`, behind `0`; merge base equals current `main` |
| PR state | Open / Draft / mergeable / not merged |
| Commits | `163` |
| Changed files | `139` |
| Additions / deletions | `27108 / 28` |
| Reviews | none |
| `REQUEST_CHANGES` | none |
| Unresolved Review Threads | none |
| Highest governed migration | unique `V50`; no V51 |
| Automatic PR/main workflow | only `.github/workflows/approval-platform-validation.yml` |
| Auto-merge | disabled and not configured |
| PR #83 | Merged / Closed and unchanged |
| Issue #81 | Open |
| Issue #82 | Open and still blocked by #81 post-main closure |
| Issue #62 | Open |
| Issue #13 | Open |
| Issue #14 | Open |

No `main` drift occurred during P7. A Merge Commit rebaseline is therefore not required in P8-R0. Rebase, force push, direct-main modification and history rewriting remain prohibited.

## 3. Final P7 evidence re-read

P7-R0, P7-A, P7-B, P7-C and P7-D are accepted.

Canonical P7 evidence:

- matrix: `docs/m6/M6_F_P7_ADVERSARIAL_FAULT_CONCURRENCY_MATRIX.md`;
- formal acceptance: `docs/m6/M6_F_P7_ADVERSARIAL_FAULT_CONCURRENCY_ACCEPTANCE.md`;
- PR #88 P7 Formal Acceptance comment: `5201607304`;
- Issue #81 P7 Acceptance comment: `5201612285`.

The final P7 permanent `pull_request` Run was:

- Run ID: `31079997571`;
- Run Number: `1304`;
- exact Head: `71bfd111d4c73c9b467dd5702b56c87e29add51a`;
- status: completed;
- conclusion: success.

All nine physical Jobs succeeded:

- Java 21 / Maven core `92546416981`;
- Persistence JDBC shard 0 `92546417507`;
- shard 1 `92546418976`;
- shard 2 `92546417534`;
- shard 3 `92546417140`;
- Java 21 / Maven / PostgreSQL aggregate `92546895038`;
- Vben `92546417245`;
- UniApp `92546416976`;
- Repository hygiene `92546417059`.

The downloaded P7 Artifacts were independently verified:

| Artifact | ID | Bytes | SHA-256 | Expires |
| --- | ---: | ---: | --- | --- |
| Maven | `8959119034` | `354448` | `02750445bb2384c77c9afa9695ffc55383734c34d16492fb6857d1baab487aa8` | `2026-11-04T07:11:51Z` |
| Vben | `8959101188` | `18890` | `ba9099b24cdcd69d310681a9462b74a9125f65ce0c376e87c6c2c898a8b1cc49` | `2026-11-04T07:11:51Z` |
| Mobile | `8959079296` | `9800` | `50f2fb0d532e337b34db3d8460c5079fe1dd35793ea7560c8b121e6c12be71e1` | `2026-11-04T07:11:51Z` |
| Hygiene | `8959063576` | `14709` | `59e2464186ad351a9678024e88b346fbb1ef72a3e7f032327966433bece3b4c9` | `2026-11-04T07:11:51Z` |

Independent report reconstruction produced:

- Maven core `1463 / 0 failures / 0 errors / 0 skipped`;
- Persistence JDBC `318 / 0 / 0 / 0`;
- aggregate `1781 / 0 / 0 / 0`;
- AI SPI `12`, AI Core `204`, OpenAI `102`, application `233`, architecture `159`, server `266`;
- permanent M6 transport boundary `148/148`;
- JDBC selected classes `79`, Surefire report classes `78`, expected abstract without report `1`, duplicate selections `0`, non-abstract selected without report `0`.

## 4. Permanent authority boundary

The authority chain remains:

`AI advisory -> typed non-executable proposal -> fresh server policy/precondition evaluation -> fresh authorization preview -> explicit human confirmation -> existing application command service -> immutable audited result`

The prohibited shortcut remains:

`Provider -> direct command`

The exact production Action Whitelist remains:

`EMPTY_PENDING_EXISTING_COMMAND_AUDIT`

The exact P5 decision remains:

`P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND`

No executable production Action or application-command admission is present. The absence of executable automation is an accepted safety limitation and must remain explicit in P8-G1, P8-G2, Ready, Merge and Issue closure evidence.

## 5. Frozen M6-F functional scope

The accepted M6-F scope is frozen to the already implemented non-executing foundation and read-only governance surfaces:

- typed server-created non-executable Proposal;
- fresh tenant/operator/permission/policy/state/SOD evaluation;
- explicit non-executable Confirmation boundary;
- unavailable Production Reauthentication boundary;
- durable hash-only Lineage, idempotency, CAS, Replay, Cancellation, PARTIAL and UNKNOWN evidence;
- exact Provider/model/Prompt/policy/output-schema inventory;
- non-executable Canary/Rollout/Rollback review plans;
- shared Runtime Control Health;
- tenant-scoped process-local Usage;
- durable V49 History;
- composite Incident Readiness;
- read-only PC and Mobile advisory semantics;
- deterministic adversarial, fault, concurrency and manual Incident / Rollback acceptance.

The six existing read-only management endpoints remain the complete P8 operations surface:

1. `GET /api/approval/management/ai-governance/snapshot`;
2. `GET /api/approval/management/ai-governance/change-plan?operation=<CANARY|ROLLOUT|ROLLBACK>`;
3. `GET /api/approval/management/ai-governance/control-health`;
4. `GET /api/approval/management/ai-governance/usage`;
5. `GET /api/approval/management/ai-governance/history?from=<canonical Instant>&to=<canonical Instant>`;
6. `GET /api/approval/management/ai-governance/incident-readiness?from=<canonical Instant>&to=<canonical Instant>`.

All responses remain tenant-scoped, GET-only, `no-store`, non-mutating and non-executing.

## 6. Explicit exclusions retained after scope freeze

P8 may not add or authorize:

- approve, reject/return, transfer, withdraw, terminate, migrate or process-state mutation;
- a non-empty Action Whitelist or test-only production Action;
- Production Reauthentication;
- direct Provider-to-command or Flowable execution;
- direct `ACT_*` access;
- arbitrary HTTP, SQL, Shell, script or Connector command;
- Provider, model, Prompt, policy, Secret, deployment or traffic mutation;
- Canary, rollout or rollback execution;
- automatic retry, fallback, Incident notification or Retention Tombstone;
- Queue, Worker, Scheduler, Listener, Polling or autonomous execution;
- actual Provider billing or durable P6-D cost history;
- durable Circuit or control-health time-series;
- a second Runtime, Circuit, RateLimiter, Usage Ledger or automatic workflow;
- a real Provider call or real Secret in CI.

## 7. Allowed P8 changes after freeze

After P8-R0 acceptance, only the following changes are permitted:

1. P8-G1/G2 audit and Formal Acceptance documents;
2. permanent static assertions binding the accepted repository facts;
3. a minimal Correction that fixes a deterministic P8-G1 or P8-G2 acceptance blocker;
4. PR and Issue metadata updates that record exact evidence;
5. conditional Ready, ordinary Merge Commit and post-main closure actions after every Gate passes.

Any code Correction must:

- be independently committed;
- preserve the first failure evidence;
- fix only the proven root cause;
- retain fail-closed behavior;
- not expand the whitelist, command surface, Provider authority or operational automation;
- pass a new complete permanent workflow;
- cause P8-G1 and P8-G2 to be re-executed before Ready.

## 8. P8-R0 permanent validation contract

The exact P8-R0 document Head must pass the sole permanent workflow with all nine physical Jobs successful. The four resulting Artifacts must be independently downloaded and verified by local bytes and SHA-256. Maven/JDBC/Node/JDBC-shard counts must be rebuilt from the Artifact contents.

P8-R0 is a separate Gate even though its internal document and static assertions may be committed together before one formal branch fast-forward. A successful P7 Run cannot substitute for the P8-R0 Head Run.

## 9. Next Gate

Until the exact P8-R0 Head is permanently validated, Artifact-verified, Review-rechecked and accepted in the PR:

`P8_R0_PENDING`

`P8_G1_PROHIBITED`

`READY_MERGE_ISSUE_CLOSURE_PROHIBITED`

`AI_IS_NOT_AN_OPERATOR`
