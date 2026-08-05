# M6-E P6-F Fault, Security and Incident Acceptance

Status: `M6_E_P6_F_PENDING_DOCUMENTED_HEAD_VALIDATION`

Date: `2026-08-04`

Tracking:

- parent milestone: Issue #62;
- roadmap rebaseline: Issue #78, Closed / Completed;
- workstream: Issue #80;
- Pull Request: #83;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- target: `main`;
- exact base/current main: `fcf031da9e6e04b15a1255044021a7fdd6637421`;
- accepted P6-E documented Head: `ecc811261324498bf7c70eaa147249f9bfe26dd6`;
- P6-F implementation Head: `2ed82b1e41e54e5792481276be953a5061f006f6`;
- P6-F implementation Run: `30894377665` / #1159, success.

This record accepts for documented-Head validation only the P6-F deterministic fault/security matrix,
incident response and rollback runbook, public failure-contract verification and final P6 Provider
acceptance evidence.

P6-F adds no production capability and changes no production Java source. It adds no Provider,
endpoint, model, Prompt, request field, runtime route, retry, fallback, command, migration or workflow.
It does not authorize P7, PR Ready, merge, Issue #80 closure or milestone M6-F controlled automation.

## 1. Entry gate

P6-F began only after P6-E was formally accepted at exact documented Head:

`ecc811261324498bf7c70eaa147249f9bfe26dd6`

P6-E documented-Head Run:

`30892287318` / #1156 — all four jobs success and all four artifacts independently SHA-256 exact.

P6-E formal acceptance is frozen by:

- PR #83 comment `issuecomment-5176650284`;
- Issue #80 comment `issuecomment-5176654168`.

Immediately before this P6-F acceptance document was created, GitHub reported:

- current `main`: `fcf031da9e6e04b15a1255044021a7fdd6637421`;
- implementation Head: `2ed82b1e41e54e5792481276be953a5061f006f6`;
- compare: ahead `201`, behind `0`;
- PR #83: Open, Draft, mergeable and not merged;
- commits: `201`;
- changed files: `120`;
- requested reviewers: none;
- submitted Reviews: two P6-A evidence-only `COMMENTED` records, explicitly non-actionable;
- unresolved review threads: zero;
- PR reactions: zero;
- no `REQUEST_CHANGES` review;
- auto-merge: not enabled;
- Ready transition: not performed.

Issue state remained:

- Issue #80: Open;
- Issue #62: Open;
- Issue #13: Open;
- Issue #14: Open;
- Issue #78: Closed / Completed.

No newer `main` Merge Commit was required because the branch remained behind zero.

## 2. Exact P6-F change scope

P6-F implementation contains only:

1. `OpenAiResponsesProductionFaultMatrixTest`;
2. `ApprovalAssistanceGenerationFailureContractTest`;
3. `ApprovalAssistanceProductionIncidentConfigurationTest`;
4. `M6_E_P6_F_OPENAI_INCIDENT_RUNBOOK.md`;
5. `m6-e-p6-openai-fault-incident-boundary.test.mjs`;
6. one import from the existing permanent M6 AI transport-review aggregator.

The correction after the first implementation commit changed only one static regular expression from a
fixed space to `\s+` so a safely wrapped Markdown sentence remained detectable. It changed no
runbook meaning, production code or runtime behavior.

The only automatic PR/main workflow remained:

`.github/workflows/approval-platform-validation.yml`

The highest migration remained the unique governed V49. No V50+ migration was added.

## 3. Complete deterministic Provider fault matrix

The P6-F Provider fault matrix iterates every accepted
`OpenAiResponsesTransportException.Failure` value and proves:

- exactly one transport exchange is attempted for each post-admission transport failure;
- no failure is retryable;
- no advisory result is exposed on failure;
- only a closed stable classification is returned;
- only a stable low-information code and fixed safe message are returned;
- raw exception details are not propagated.

The exact mapping remains:

| Failure class | Classification | Stable code |
| --- | --- | --- |
| kill switch disabled | `DISABLED` | `AI_OPENAI_DISABLED` |
| kill-switch drift, circuit, rate or cost policy block | `POLICY_BLOCKED` | `AI_OPENAI_POLICY_BLOCKED` |
| cancellation or timeout | `TIMEOUT` | `AI_OPENAI_TIMEOUT` |
| invalid request or endpoint rejection | `REJECTED` | `AI_OPENAI_REQUEST_REJECTED` |
| DNS, connection, TLS, Secret, HTTP framing, redirect, response-size or I/O failure | `PROVIDER_UNAVAILABLE` | `AI_OPENAI_PROVIDER_UNAVAILABLE` |
| unknown transport/runtime failure | `UNKNOWN` | `AI_OPENAI_UNKNOWN` |

The matrix also deterministically covers HTTP 401, 403, 429, 500 and 503 with exactly one exchange.
Provider bodies and raw request IDs are proven absent from the resulting failure object.

No test accesses the network or environment Secret.

## 4. Public failure contract

The server failure-contract test covers every production generation failure status:

- `DISABLED`;
- `NOT_FOUND`;
- `STALE_TASK`;
- `POLICY_BLOCKED`;
- `PROVIDER_UNAVAILABLE`;
- `TIMEOUT`;
- `INVALID_OUTPUT`;
- `UNKNOWN`;
- `EVIDENCE_CONFLICT`;
- `EVIDENCE_UNAVAILABLE`.

For every failure, it proves:

- the exact HTTP status and stable code;
- `Cache-Control: no-store`;
- `authority=ADVISORY`;
- `assertionStatus=UNVERIFIED_ADVISORY`;
- `needsHumanReview=true`;
- `commandAvailable=false`;
- `providerSelectable=false`;
- `retryAttempted=false`;
- `fallbackAttempted=false`;
- no evidence ID;
- no partial advisory result.

A failure cannot expose Provider/model/Prompt/Secret details, raw bodies or a partial result.

## 5. Configuration and emergency-disable drills

P6-F proves that exact `APPROVAL_AI_OPENAI_ENABLED=false` returns no runtime before parsing broken or
malformed Provider settings. Emergency disablement therefore does not depend on a healthy Secret,
cost policy, rate policy or Provider configuration.

P6-F also proves:

- an expired Secret version window blocks runtime activation;
- a future cost-policy window blocks runtime activation;
- the failure remains a stable server-owned version-policy error;
- Secret rotation requires a changed exact non-secret `OPENAI_API_KEY_VERSION` reference;
- configuration tests never reference or read raw `OPENAI_API_KEY`.

Disablement, Secret rotation and recovery remain deployment-owned. A browser, mobile client, Provider
response or AI component cannot enable the runtime.

## 6. Incident response runbook

The accepted incident runbook freezes:

- severity classification for Secret/network trust, Provider/policy/persistence integrity and isolated
  bounded failures;
- emergency disablement through exact `APPROVAL_AI_OPENAI_ENABLED=false` and full instance rollout;
- verification that generation returns `AI_ASSISTANCE_DISABLED` before task query, DNS, TLS, Secret
  lease or P4 store access;
- callback-scoped Secret rotation using a new exact version reference;
- response to 401, 403, 429, 5xx, refusal, timeout and cancellation;
- malformed, unknown, drifted or oversized output handling;
- DNS/TLS/SSRF incident handling without trust bypass;
- cost, rate, circuit and kill-switch handling;
- P4 conflict/store-outage handling with no second Provider call;
- rollback while preserving V49 durable evidence;
- a two-human recovery gate;
- zero-egress deterministic drills;
- a strict safe-evidence allowlist and sensitive-evidence prohibition.

The runbook explicitly prohibits storing:

- `OPENAI_API_KEY` or Secret bytes;
- Authorization header values;
- raw request or response bodies;
- Prompt text or Provider-safe values;
- raw Provider request IDs;
- arbitrary exception text or customer-data screenshots.

The runbook requires preservation only of bounded status, version, deployment and hash evidence.

## 7. Disablement, ambiguity and rollback semantics

P6-F permanently records that a request already dispatched before disablement cannot be recalled by
this slice. It must resolve to success, a bounded failure or `UNKNOWN` according to accepted evidence.

No incident procedure may send a second Provider request automatically to resolve ambiguity.

Rollback requires:

1. disablement before application rollback;
2. preservation of exact failing and target commit/deployment revisions;
3. application rollback only to V49-compatible code;
4. no V49 down migration, evidence-table deletion or evidence-row mutation;
5. no floating model, alternate endpoint or fallback Provider;
6. deterministic validation before re-enablement.

V49 remains additive durable evidence during application rollback.

## 8. Recovery gate

Re-enablement requires all of the following:

- root cause and affected UTC interval recorded;
- two independent human approvals;
- exact application and deployment revisions;
- exact endpoint/model/Prompt/policy/schema versions;
- current Secret version after required rotation;
- current Secret and cost-policy validity intervals;
- coherent kill-switch generation/policy revision;
- coherent positive rate, cost and circuit settings;
- zero-egress deterministic fault/security tests success;
- permanent workflow success and independently exact artifacts;
- no unresolved actionable Review/thread/reaction;
- deployment starts disabled;
- exact enablement followed by complete instance rollout;
- first validation is one explicit human-triggered advisory request, never an automated probe.

Provider output cannot approve recovery.

## 9. Permanent authority and scope exclusions

P6-F retains all accepted P6 authority boundaries:

- AI is never an operator;
- all results remain advisory, unverified and human-reviewed;
- no Provider-to-command path;
- no approve, reject, return, transfer, withdraw, terminate, migrate, publish or activate command;
- no tenant, operator, permission, audit, worker, lease or engine identity manufacture;
- no arbitrary HTTP, SQL or script execution;
- no client-selected Provider, model, Prompt, endpoint, policy or Secret;
- no second Provider, retry, fallback, redirect following or streaming;
- no previous-response/conversation state;
- no tools, function calling, RAG, embeddings or vector storage;
- no attachment-content extraction;
- no approval-opinion population;
- no Queue, Worker, Scheduler, listener, polling or background continuation;
- no automation proposal or executable action;
- no milestone M6-F capability;
- no live paid/customer Provider request in CI.

P6-F is test/document-only and does not claim general production deployment readiness.

## 10. Implementation permanent verification

Implementation Head:

`2ed82b1e41e54e5792481276be953a5061f006f6`

Permanent Run:

`30894377665` / #1159 — all four jobs success.

| Job | Job ID | Result |
| --- | ---: | --- |
| Java 21 / Maven / PostgreSQL | `91943723153` | success |
| Vben TypeScript / production build | `91943723070` | success |
| UniApp TypeScript / H5 / WeChat | `91943723010` | success |
| Repository hygiene | `91943723024` | success |

Recalculated implementation evidence:

- Maven aggregate: `1551 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `156 / 156`;
- OpenAI Provider module: `57 / 57`;
- P6-F OpenAI fault matrix: `3 / 3`;
- approval-application: `233 / 233`;
- Persistence JDBC: `295 / 295`;
- architecture module: `139 / 139`;
- approval-server: `172 / 172`;
- P6-F new server tests: `5 / 5`;
- all 26 Maven reactor projects: success;
- `BUILD SUCCESS`: present;
- Maven time: `08:32 min`;
- repository hygiene: `29 / 29`;
- M6 AI foundation: `10 / 10`;
- M6 AI activation review: `6 / 6`;
- M6 AI transport review including P6-E and P6-F: `23 / 23`;
- Vben type-check and production build: success;
- UniApp type-check, H5 and WeChat builds: success.

Implementation artifacts were independently downloaded and SHA-256 matched:

| Artifact | ID | Size | SHA-256 | Match |
| --- | ---: | ---: | --- | --- |
| Maven | `8886642406` | `27547` | `f6bc69f5cb4fe9c3100df4423c32347e9eddaca498a2c7715d4d9f949ee2db53` | exact |
| Vben | `8886426776` | `18949` | `308d2b340d0d9214998a3cb60f70e56d9e7ff3e94d881df02b3d878a40d2782c` | exact |
| Mobile | `8886407093` | `9784` | `868224dc0518ded59f89d0ba7e6980c53ec3b3c980d98e8939f77c483dd31298` | exact |
| Hygiene | `8886377175` | `9943` | `13c185c5a8deb6029a5a0b5a43f084f5a66884277a72d4ed54ace65987a5bb9b` | exact |

All four are unexpired and expire `2026-11-02T09:00:15Z`.

## 11. Retained append-only failure evidence

No failed or superseded Run was rerun, hidden or used as acceptance evidence.

| Head | Run | Number | Retained outcome |
| --- | ---: | ---: | --- |
| `f255ea2a5f6d433e35b0edba3c2e0b3d50629689` | `30894170507` | #1158 | Web and Mobile success; OpenAI fault matrix and Checkstyle success before Maven supersession; Hygiene found one wrapped-runbook static regex mismatch; Maven cancelled by correction |
| `2ed82b1e41e54e5792481276be953a5061f006f6` | `30894377665` | #1159 | final implementation Head; all four jobs success |

The #1158 correction changed only:

`Never send a second Provider request automatically`

static evidence from fixed-space matching to whitespace-tolerant matching. The runbook already
contained the required prohibition and no runtime behavior changed.

## 12. Documented-Head gate

This file creates a new exact documented Head. P6-F is not formally accepted until:

1. a new natural pull-request workflow for the exact documented Head succeeds;
2. all four jobs are successful;
3. four new artifacts are present, unexpired and tied to the exact Run and Head;
4. every artifact is independently downloaded and SHA-256 matched;
5. Maven, Web, Mobile and hygiene evidence are recalculated;
6. `main` remains exact or is merged normally and fully revalidated;
7. PR #83 remains Open + Draft + mergeable and behind zero;
8. Reviews, threads, comments and reactions contain no actionable finding;
9. Issues #80, #62, #13 and #14 remain Open and Issue #78 remains Completed;
10. an append-only PR and Issue evidence record freezes successful documented-Head results.

Only after those conditions may P6-F be marked formally accepted and P7 be separately authorized.

This record is not PR Ready, merge authorization or Issue #80 closure.

`M6_E_P6_F_NOT_YET_FORMALLY_ACCEPTED`

`P7_REMAINS_GATED`

`MILESTONE_M6_F_REMAINS_PROHIBITED`

`PR_83_REMAINS_DRAFT`

`AI_IS_NOT_AN_OPERATOR`
