# M6-E P7 Final Acceptance, Merge and Post-Main Gate

Status: `P7_PENDING_EXACT_PERMANENT_VALIDATION`

Date: `2026-08-04`

Tracking:

- parent milestone: Issue #62;
- roadmap rebaseline: Issue #78, Closed / Completed;
- workstream: Issue #80;
- Pull Request: #83;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- target: `main`;
- exact current main: `ff736dee3b02c6a9f087d92b2a176d9af2724886`;
- exact accepted P6-F Head: `61265b123ef688a9e81c90bdee3319abafad850b`;
- accepted P6-F final Run: `30896143997` / #1162, success.

This document creates the P7 final acceptance candidate for M6-E. P7 adds no product capability.
It freezes the complete adversarial, fault, concurrency, compatibility, Review, merge and post-main
evidence gate for the already accepted P0 through P6 scope.

This document is not PR Ready, merge authorization or Issue #80 closure. Those actions remain
conditional on a new exact permanent workflow for the commit containing this file and its permanent
boundary test.

## 1. Exact entry gate

P7 begins only after P6-F formal acceptance at exact current-main-rebaselined Head:

`61265b123ef688a9e81c90bdee3319abafad850b`

P6-F final permanent workflow:

`30896143997` / #1162 — all four jobs success.

P6-F formal acceptance is frozen by:

- PR #83 comment `issuecomment-5177186962`;
- Issue #80 comment `issuecomment-5177192851`.

The accepted P6-F rebaseline used a normal two-parent Merge Commit to incorporate current `main`.
No rebase or force push occurred.

At P7 creation:

- current `main`: `ff736dee3b02c6a9f087d92b2a176d9af2724886`;
- accepted branch Head: `61265b123ef688a9e81c90bdee3319abafad850b`;
- compare: ahead `203`, behind `0`;
- PR #83: Open, Draft, mergeable and not merged;
- changed files: `121`;
- requested reviewers: none;
- submitted Reviews: two P6-A evidence-only `COMMENTED` records, explicitly non-actionable;
- unresolved review threads: zero;
- PR reactions: zero;
- auto-merge: not enabled;
- Ready transition: not performed;
- Issues #80, #62, #13 and #14: Open;
- Issue #78: Closed / Completed;
- highest migration: the unique governed M6-E V49;
- no V50+ migration;
- one automatic workflow only:
  `.github/workflows/approval-platform-validation.yml`.

## 2. P7 change scope

P7 changes only:

1. this final acceptance document;
2. one permanent repository boundary test;
3. one import from the existing M6 AI transport-review test aggregator.

P7 makes no production Java, TypeScript, migration or workflow change.

P7 adds no Provider, Prompt, endpoint, retry, fallback, Queue, Worker or Scheduler. It adds no
client-selected model or policy, attachment extraction, RAG, embedding, vector store, automation
proposal or executable action.

The application remains default disabled unless all exact server-owned P6 activation controls are
present and current.

## 3. Complete accepted capability inventory

The final M6-E capability consists only of the accepted slices below:

| Slice | Accepted boundary |
| --- | --- |
| P0 | threat model, authority boundary and exact baseline |
| P1 | server-owned tenant/operator/task/form projection, masking and minimization |
| P2 | bounded advisory contract with provenance, confidence and limitations |
| P3 | synchronous at-most-one-Provider orchestration, no unsafe retry/fallback |
| P4 | tenant-safe hash-only durable evidence, retention, CAS and real PostgreSQL concurrency |
| P5 | zero-egress read API and advisory-only PC/Mobile presentation |
| P6-A | exact OpenAI Responses endpoint/model/profile audit |
| P6-B | server-owned callback-scoped Secret material source |
| P6-C | canonical request and strict bounded response codec |
| P6-D | DNS/TLS/SSRF-safe one-attempt HTTP sender and admission |
| R0 | current-main production invocation rebaseline |
| P6-E | default-disabled explicit generation POST and P4 binding |
| P6-F | complete deterministic fault matrix and incident/rollback runbook |

No accepted slice provides approval-command authority or autonomous processing.

## 4. P7 adversarial threat closure

The P0 threat model gates the following threats to P7. The final evidence closes each threat without
adding a new runtime path.

### T05 — prompt and tool injection

- form values and metadata remain data, not an authority channel;
- the server selects exact Prompt metadata;
- tools and function calling remain absent;
- Provider output cannot request or trigger another call or command;
- production and permanent boundary scans reject command paths.

### T06 — command injection

- P2 output has no executable command field;
- P6 strict decoding rejects unknown fields;
- AI modules and generation controllers have no approval mutation dependency;
- public responses retain `commandAvailable=false`.

### T07 — fabricated or unknown evidence reference

- P2 evidence references bind only server-projected field keys;
- unknown evidence reference IDs fail strict decoding or P2 revalidation;
- P4 stores the canonical result hash rather than trusting Provider citations.

### T08 and T22 — approval decision and UI authority confusion

- every result remains `ADVISORY` and `UNVERIFIED_ADVISORY`;
- `needsHumanReview=true` is mandatory;
- PC and Mobile display limitations and do not populate or execute approval opinions;
- no UI action proxies approve, reject, return, transfer, withdraw or terminate.

### T10 and T11 — oversized and malformed input/output

- P1/P3 enforce field, character, collection and depth limits;
- P6-C/P6-D enforce canonical bytes, strict JSON, response size and HTTP framing;
- malformed, incomplete, duplicated, unknown, deep or oversized output fails closed;
- partial advisory results are not displayed.

### T12 — timeout, cancellation and uncertain network result

- every Provider request is bounded by one deadline and one transport exchange;
- post-dispatch ambiguity remains `UNKNOWN`;
- no retry, alternate address, second Provider or fallback follows;
- P6-F exhaustively tests every transport failure mapping.

### T13 — stale circuit completion

- accepted generation-aware circuit behavior remains unchanged;
- P6 admission binds kill-switch, circuit, rate and cost evidence before dispatch;
- P7 introduces no independent circuit or bypass.

### T14 and T16 — stale state and version drift

- pending task state is queried twice before Provider binding;
- exact definition/form/task/Provider/model/Prompt/policy/schema versions are bound;
- task snapshot drift fails before network;
- Provider/model/Schema response drift fails strict decoding.

### T15 — replay and duplicate evidence

- P4 request/evidence hashes and uniqueness constraints reject conflicting identity;
- exact replay returns `REPLAYED` without a second evidence/event/state row;
- real PostgreSQL concurrent exact store produces one `STORED` and one `REPLAYED`;
- conflicting same request identity produces `CONFLICT` without partial writes;
- PC and Mobile suppress in-flight duplicate clicks.

### T17 — Secret leakage

- configuration does not read raw `OPENAI_API_KEY`;
- Secret material is acquired only after verified TLS inside one zeroized callback;
- public errors, logs, metrics, artifacts and P4 evidence contain no key or Authorization value;
- incident evidence permits only non-secret version references and hashes.

### T18 — SSRF, DNS rebinding and redirect

- endpoint is exact and server owned;
- private/local/special-purpose addresses are rejected;
- connection evidence binds one admitted address;
- trusted TLS chain, SNI and hostname verification are required;
- redirects and endpoint overrides are rejected.

### T19 — cost and rate exhaustion

- one request has one bounded token/output budget;
- tenant/global rate limits, cost policy, circuit and kill switch are server owned;
- blocked admission occurs before Secret access and dispatch;
- no retry or Provider fallback amplifies cost.

### T20 — feedback poisoning

- M6-E contains no feedback-to-training, Prompt mutation or automatic learning path;
- P7 adds no feedback storage or model update mechanism.

### T21 — raw evidence persistence

- V49 stores domain-separated hashes and bounded metadata only;
- no raw input, Prompt, Provider request/response or advisory text column exists;
- database tests reject physical mutation/deletion of immutable evidence and events.

### T25 — Provider compromise

- Provider receives only authorized minimized fields;
- output is strict, bounded and untrusted;
- immediate kill switch and incident runbook are accepted;
- Provider cannot supply identity, authority, command or recovery approval.

### T26 — hash ambiguity and tamper resistance

- request, projection, route, version, outcome and final evidence hashes are domain separated and
  length framed;
- database constraints bind state and events bidirectionally;
- direct evidence/event mutation and deletion are rejected.

No unresolved threat in the P0 register requires a new M6-E product capability.

## 5. Concurrency and replay evidence

P7 accepts the following exact concurrency guarantees.

### Provider invocation

For one server request, the production orchestrator starts at most one Provider invocation. Every
transport failure test proves one exchange maximum and non-retryable failure.

A P4 conflict or store outage after Provider completion does not cause a second Provider call.

### Client interaction

PC and Mobile require an explicit click. While a generation is in flight, the same panel rejects
another click. Page load, watch handlers, use-case selection and polling never start generation.

### Durable evidence

Real PostgreSQL tests prove:

- concurrent exact store produces one `STORED` and one `REPLAYED`;
- concurrent exact tombstone produces one `TOMBSTONED` and one `REPLAYED`;
- same request identity with a different evidence identity conflicts without partial writes;
- same evidence ID is tenant isolated;
- evidence, state and event history rejects physical mutation or deletion.

### Explicit retained limitation

Exactly-once external Provider execution across two distinct explicit HTTP requests is not claimed.
Two independently authenticated and admitted user requests are separate requests and may each start
one advisory Provider call before P4 detects a conflicting request identity.

This is not an automatic retry or background replay. The Provider operation is advisory and
non-mutating. Client in-flight suppression plus tenant/global rate, cost and circuit controls bound
that residual case. P7 does not add a V50 reservation protocol or an unsafe process-local singleflight
claim. Any future cross-node pre-dispatch reservation requires a separate roadmap gate and schema
ownership decision.

This limitation does not create approval authority or duplicate process transition risk.

## 6. Current-main compatibility

P6-F was rebaselined onto current `main` through Merge Commit:

`61265b123ef688a9e81c90bdee3319abafad850b`

The merge retained:

- the M6-E `approval-ai-core` dependency in `approval-persistence-jdbc`;
- `approval.persistence.test.fork-count=4`;
- reusable Surefire forks;
- JaCoCo append mode.

The exact rebaselined Run #1162 proved:

- Maven aggregate `1551 / 0 / 0 / 0`;
- AI SPI `12 / 12`;
- AI Core `156 / 156`;
- OpenAI `57 / 57`;
- approval-application `233 / 233`;
- Persistence JDBC `295 / 295`;
- architecture `139 / 139`;
- approval-server `172 / 172`;
- all 26 reactor projects success;
- Persistence JDBC `03:43 min`;
- full Maven `05:52 min`;
- P6 permanent transport review `23 / 23`;
- Web and Mobile success;
- all four artifacts independently SHA-256 exact.

P7 preserves these settings and introduces no compatibility override.

## 7. Migration and workflow closure

M5-owned migrations remain frozen through V48. M6-E owns exactly one migration:

`V49__create_ai_approval_assistance_durable_evidence.sql`

There is no V50 or later migration.

The only automatic PR/main workflow remains:

`.github/workflows/approval-platform-validation.yml`

P7 creates no temporary or second workflow and no committed artifact payload.

## 8. Review and evidence closure requirements

Before Ready transition, P7 requires:

1. the exact commit containing this document and permanent boundary test;
2. one natural pull-request workflow, attempt 1, all four jobs success;
3. four artifacts tied to the exact Head and Run;
4. independent local size and SHA-256 equality for every artifact;
5. recalculated Maven/module/P7 boundary statistics;
6. current `main` unchanged, or an ordinary Merge Commit rebaseline followed by a new exact Run;
7. PR Open + Draft + mergeable, behind zero;
8. no requested change, actionable comment, unresolved thread or reaction;
9. Issues #80, #62, #13 and #14 Open and Issue #78 Completed;
10. no auto-merge and no prior Ready transition.

The two existing P6-A `COMMENTED` reviews are evidence-only and explicitly non-actionable.

## 9. Conditional Ready and Merge Commit gate

Only after every pre-merge condition succeeds may P7:

1. update the PR metadata to the exact final Head and evidence;
2. mark PR #83 Ready for review;
3. immediately recheck mergeability, main, Reviews, threads, comments, reactions and workflow;
4. merge with an ordinary Merge Commit only;
5. never squash, rebase or force push.

If any gate changes after Ready, do not merge. Return the PR to Draft when supported or leave it
unmerged with an explicit blocker record.

The merge commit must have the accepted branch Head and then-current `main` as its parents.

## 10. Mandatory post-main verification

The merge itself does not close M6-E.

After Merge Commit:

1. query exact new `main` and verify it equals the PR merge commit;
2. wait for the natural `push -> main` permanent workflow;
3. require all four jobs success;
4. download four post-main artifacts;
5. independently verify every size and SHA-256;
6. recalculate Maven/module/P7 boundary statistics;
7. verify V49 uniqueness, no V50, one workflow and no authority drift;
8. verify PR merged/closed and no unresolved actionable Review evidence;
9. append exact post-main evidence to PR #83 and Issue #80;
10. close Issue #80 only after all prior items succeed.

M6-F remains gated until M6-E post-main closure is complete.

Parent Issue #62 remains Open because later M6-F and M6-G work remains.

## 11. Permanent authority exclusions

P7 preserves:

- AI is never an operator;
- AI cannot manufacture tenant, operator, permission, audit, worker, lease or engine identity;
- every output remains advisory, unverified and human reviewed;
- no Provider-to-command dependency;
- no approve, reject, return, transfer, withdraw, terminate, migrate, publish or activate command;
- no arbitrary HTTP, SQL or script execution;
- no client-selected Provider, model, Prompt, endpoint, policy or Secret;
- no second Provider, retry, fallback, redirect following or streaming;
- no previous-response or conversation state;
- no tools, function calling, RAG, embeddings or vector storage;
- no attachment-content extraction;
- no automatic approval-opinion population;
- no Queue, Worker, Scheduler, listener, polling or autonomous continuation;
- no automation proposal or executable action;
- no milestone M6-F capability;
- no live paid/customer Provider request in CI.

## 12. Current decision

P7 final acceptance is pending the exact permanent workflow generated by the commit that adds this
file and its permanent boundary test.

PR #83 remains Draft and unmerged.

Issue #80 remains Open.

`P7_PENDING_EXACT_PERMANENT_VALIDATION`

`P7_NOT_READY_OR_MERGED`

`M6_F_REMAINS_GATED_UNTIL_M6_E_POST_MAIN_CLOSURE`

`AI_IS_NOT_AN_OPERATOR`
