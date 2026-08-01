# M6-E P5 Read-only API and Presentation Acceptance

Status: `M6_E_P5_FORMALLY_ACCEPTED_PENDING_DOCUMENTED_HEAD_VALIDATION`

Date: `2026-08-01`

Tracking:

- parent milestone: Issue #62;
- roadmap rebaseline: Issue #78, closed / completed;
- workstream: Issue #80;
- Pull Request: #83;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- target: `main`;
- exact base/current main: `fcf031da9e6e04b15a1255044021a7fdd6637421`.

This record accepts only M6-E P5 read-only approval-assistance availability and presentation.
It does not authorize a production Provider, Prompt content, customer knowledge, runtime Secret,
network egress, paid/customer Provider call, automation or any approval command.

## 1. P4 entry gate

P5 began only after P4 completed its independent documented-Head acceptance.

Accepted P4 evidence:

- exact documented Head: `ab6139a09663a4e5342a5df425933358e4526b12`;
- permanent Run: `30707315394` / run number `1034`;
- conclusion: `success`;
- all four permanent jobs: `success`;
- all four artifacts: independently downloaded and SHA-256 matched;
- Maven aggregate: `1474 / 0 / 0 / 0`;
- AI Core: `156 / 156`;
- P4 Core: `8 / 8`;
- P4 PostgreSQL: `14 / 14`;
- Persistence JDBC: `295 / 295`;
- architecture module: `138 / 138`;
- ArchUnit `ModuleBoundariesTest`: `10 / 10`;
- P4 permanent boundary: `1 / 1`;
- combined repository hygiene: `15 / 15`;
- no actionable Review finding;
- PR #83 remained Open + Draft.

At P5 acceptance, current `main` remains
`fcf031da9e6e04b15a1255044021a7fdd6637421`. The M6-E branch is ahead of main and behind zero.
Issues #80, #62, #13 and #14 remain open.

## 2. P5 accepted scope

P5 adds one closed server presentation contract, one GET-only controller, one Web read client and
panel, and one Mobile read client and panel.

Server sources:

- `ApprovalAssistanceReadContracts`;
- `ApprovalAssistanceReadController`;
- `ApprovalAssistanceReadControllerTest`.

Web sources:

- `apps/web/overlay/apps/web-ele/src/api/approval/assistance.ts`;
- `apps/web/overlay/apps/web-ele/src/components/approval/ApprovalAssistancePanel.vue`;
- one isolated mount in the existing approval workbench task detail.

Mobile sources:

- `apps/mobile/overlay/src/api/approval/assistance.ts`;
- `apps/mobile/overlay/src/components/approval/ApprovalAssistancePanel.vue`;
- one isolated mount in the existing task-detail page.

P5 adds no Flyway migration. The unique governed M6-E V49 remains the repository maximum and no
V50 or later migration exists.

## 3. Closed use-case contract

The only client-selectable value is the already accepted P2 use case:

1. `SUMMARY`;
2. `MATERIAL_COMPLETENESS`;
3. `RISK_REVIEW`.

The server binds the available list directly to `UseCase.values()`. A client cannot submit or
manufacture:

- tenant ID;
- operator ID;
- permission evidence;
- resource authorization;
- Provider ID;
- route ID;
- model ID or version;
- Prompt template ID or version;
- policy ID or version;
- output-Schema ID or version;
- Secret reference;
- request evidence hash;
- durable evidence identity;
- approval command.

## 4. Server-owned task authorization

The endpoint is exactly:

`GET /api/approval/tasks/{taskId}/assistance?useCase=<closed-use-case>`

The controller receives tenant and operator only from the trusted identity-context headers
established by the existing authentication filter. It constructs an exact
`PendingTaskIdentity(tenantId, operatorId, taskId)` and calls the existing
`ApprovalTaskQuery.findPendingTask` boundary.

The endpoint returns the P5 view only when the task is a current pending task for that exact tenant
and operator.

A wrong tenant, wrong operator, unknown task or no-longer-pending task returns:

- HTTP `404`;
- `Cache-Control: no-store`;
- no task snapshot;
- no assistance metadata;
- no distinction that could reveal another tenant's task.

An authorized read returns HTTP `200` with `Cache-Control: no-store`.

The endpoint is not a management endpoint and does not use an unrelated management permission as a
substitute for participant task ownership.

## 5. Truthful pre-P6 availability state

P5 does not use the deterministic test Provider in production and does not manufacture sample
advisory content.

Before the P6 production-Provider gate, the only valid response state is:

- availability: `PROVIDER_NOT_CONFIGURED`;
- code: `AI_ASSISTANCE_P6_PROVIDER_REQUIRED`;
- authority: `ADVISORY`;
- assertion status: `UNVERIFIED_ADVISORY`;
- `needsHumanReview=true`;
- `providerInvocationStarted=false`;
- `providerSelectable=false`;
- `commandAvailable=false`;
- `resultAvailable=false`;
- `advisoryResult=null`.

The required limitation set is exactly:

- `PRODUCTION_PROVIDER_NOT_CONFIGURED`;
- `NO_ADVISORY_RESULT_AVAILABLE`;
- `HUMAN_REVIEW_REQUIRED`.

Direct construction of a P5 view fails closed unless every field matches this exact pre-P6 state.
A caller cannot construct a view that claims a result, Provider invocation, Provider selection or
command availability.

## 6. Server-owned evidence snapshot

The P5 response includes only bounded server-owned task version evidence needed to explain which
pending task is being viewed:

- task ID;
- instance ID;
- process definition key and version;
- Form key and version;
- compiler version;
- immutable content hash;
- task definition key;
- instance update time;
- task update time.

It does not include Provider-safe field values, raw form content, attachment content, Prompt,
Provider request/response, advisory text, P4 hash chain internals, Secret material or engine
identity.

The snapshot must match the exact authorized task and instance IDs. It cannot be paired with
another task or tenant.

## 7. GET-only and zero-egress boundary

P5 production source contains no:

- POST, PUT, PATCH or DELETE assistance mapping;
- Provider invocation;
- synchronous orchestrator call;
- P4 durable-evidence store call;
- Secret material access;
- HTTP client or `java.net` dependency;
- Flowable dependency;
- approval command;
- idempotency or reason header used to imply a command;
- background Worker, Queue, Scheduler, polling or retry.

The P5 GET endpoint is an availability/read endpoint. It performs no external network operation and
creates no durable assistance record.

P6 must not silently change this GET endpoint into an egress or generation endpoint. Any future
production invocation must use a distinct explicit server-owned boundary, preserve no-store read
semantics for this endpoint, and pass the complete P6 Provider/Secret/egress/cost/rate/circuit/kill-
switch gate.

## 8. Web presentation boundary

The Web task-detail drawer mounts `ApprovalAssistancePanel` only for an active approval task and not
for the initiator-revision form-editing task.

The panel:

- calls only the P5 GET client;
- exposes only the closed three use cases;
- renders `AI 辅助（未验证）`;
- renders `ADVISORY`;
- renders `UNVERIFIED_ADVISORY`;
- renders `必须人工复核`;
- states that AI has no approval authority;
- states that the production Provider is not configured;
- states that no AI content is generated or fabricated;
- shows the bounded server evidence snapshot;
- shows the server limitation codes and messages;
- states that the panel does not fill the approval opinion;
- states that it provides no approve, reject, transfer or other command.

The component does not import or call any approval action and does not bind to the existing approval
opinion or form-value models.

## 9. Mobile presentation boundary

The Mobile task-detail page mounts the Mobile `ApprovalAssistancePanel` under the same active-task
condition and not for the initiator-revision editing task.

The Mobile panel preserves the same:

- closed three use cases;
- GET-only transport;
- advisory/unverified/human-review labels;
- Provider-not-configured truth;
- no-fabricated-content statement;
- server evidence snapshot;
- limitation display;
- explicit command separation.

The Mobile component does not import or call approve, reject, transfer, resubmit, withdraw or
retrieve functions and does not bind to the approval opinion or editable form values.

## 10. Existing command paths remain unchanged

P5 does not change the existing approval command implementations, confirmation dialogs,
idempotency handling or action headers.

The Web workbench and Mobile task-detail files receive only:

- one component import;
- one isolated component mount.

The component remains outside the approval-opinion model and outside the command button group. P5
cannot proxy, prefill, recommend-click or automatically trigger an approval action.

## 11. Permanent P5 architecture gates

`m6-e-p5-read-only-presentation-boundary.test.mjs` adds two permanent gates through the existing
Repository hygiene job.

The server gate proves:

- exact GET route;
- trusted tenant/operator headers;
- pending-task participant ownership;
- no-store success and not-found paths;
- exact Provider-not-configured state;
- no Provider, P4 store, Secret, network, Flowable or command dependency;
- exact V49 and no V50+ migration.

The Web/Mobile gate proves:

- closed TypeScript use-case unions;
- `advisoryResult: null`;
- exact advisory/unverified/human-review booleans;
- GET-only request;
- no command headers or command imports;
- no Provider/model/route/Prompt/Secret selection;
- visible safety labels and no-fabrication statement;
- component separation from approval opinion/form models;
- exact mounts in both task-detail surfaces.

## 12. Retained failed and superseded Runs

All P5 natural Runs remain retained. None was rerun, hidden, deleted or used as final acceptance
evidence.

Run `30708668920` / #1040:

- Repository hygiene: failure;
- Mobile: success;
- Vben: cancelled only after a later append-only correction superseded the Head;
- Maven: cancelled only after the later correction superseded the Head;
- cause: the first P5 permanent test required the three use-case enum literals to be repeated in the
  presentation class even though production correctly bound the list to `UseCase.values()`;
- correction: validate the closed enum binding rather than duplicated literal text and make one
  limitation-text assertion case-insensitive.

Run `30708728292` / #1041:

- Repository hygiene: success;
- Vben: success;
- Mobile: success;
- Maven: cancelled after later Web/Mobile append-only work superseded the Head;
- not used as acceptance evidence.

Run `30709187338` / #1051:

- Repository hygiene: success;
- Vben, Mobile and Maven: cancelled after a proactive Java nested-record import correction
  superseded the Head;
- not used as acceptance evidence.

The nested-record import correction fixed only the controller unit-test imports. It did not modify
production behavior.

## 13. Successful P5 implementation verification

Exact implementation Head:

`d8a33814d305f829d510d955d376d19ef1c33756`

Permanent workflow:

- Run ID: `30709259080`;
- run number: `1052`;
- event: `pull_request`;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- attempt: `1`;
- conclusion: `success`.

Jobs:

| Job | Job ID | Result |
| --- | ---: | --- |
| Java 21 / Maven / PostgreSQL | `91393704748` | success |
| Vben TypeScript / production build | `91393704722` | success |
| UniApp TypeScript / H5 / WeChat | `91393704732` | success |
| Repository hygiene | `91393704730` | success |

Maven evidence recalculated from `maven-verify.log`:

- aggregate: `1477 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `156 / 156`;
- P1 focused Java: `22 / 22`;
- P2 focused Java: `20 / 20`;
- P3 focused Java: `14 / 14`;
- P4 Core: `8 / 8`;
- P4 PostgreSQL: `14 / 14`;
- P5 controller: `3 / 3`;
- approval-server module: `156 / 156`;
- Persistence JDBC: `295 / 295`;
- architecture module: `138 / 138`;
- ArchUnit `ModuleBoundariesTest`: `10 / 10`;
- `BUILD SUCCESS`: present;
- full reactor result: every module `SUCCESS`;
- total Maven time: `08:34 min`.

Permanent governance evidence:

- M6-E P0-P3 authority boundary: `9 / 9`;
- P4 durable-evidence boundary: `1 / 1`;
- P5 server boundary: `1 / 1`;
- P5 Web/Mobile presentation boundary: `1 / 1`;
- combined repository-hygiene entrypoint: `17 / 17`;
- existing M6-D foundation boundary: `10 / 10`;
- existing M6-D activation boundary: `6 / 6`;
- existing M6-D transport boundary: `7 / 7`.

Client validation:

- Web root tooling: success;
- Web TypeScript: success;
- Web production build: success;
- Mobile root tooling: success;
- Mobile TypeScript: success;
- Mobile H5 build: success;
- Mobile WeChat build: success.

## 14. P5 implementation artifact verification

Every ZIP was independently downloaded and locally SHA-256 hashed. Each local SHA-256 exactly
matches the GitHub artifact digest.

| Artifact | ID | Size | GitHub/local SHA-256 | Match |
| --- | ---: | ---: | --- | --- |
| Maven | `8821421621` | `27102` | `d036e483361babda132227fdcc3631e96dd5cde1bfe466660672863dade5e7de` | exact |
| Vben | `8821345988` | `18875` | `6084d56ab1ffc1ff670c61201c52772ffb2607111a7fb57b7a43af17abc5a462` | exact |
| Mobile | `8821336785` | `9803` | `cbc1020aec03473d329a9f3aae8e8be7bae0b7055512489eb7c68002a26b461e` | exact |
| Hygiene | `8821326620` | `9264` | `36f5a6eacce45b6776dafddf3cca159740dd91ad38e3e7dff3b1e8945f8ce776` | exact |

All four artifacts are unexpired and expire at `2026-10-30T16:57:01Z`.

## 15. Review and repository state before this record

Before this acceptance record was committed:

- current main: `fcf031da9e6e04b15a1255044021a7fdd6637421`;
- PR #83: Open + Draft + mergeable;
- branch compare: ahead `95`, behind `0`;
- changed files: `56`;
- submitted reviews: none;
- requested reviewers: none;
- unresolved review threads: none;
- PR reactions: none;
- existing PR comments contain only P0-P4 acceptance evidence and no actionable finding;
- Issues #80, #62, #13 and #14: open;
- auto-merge was not enabled.

The documented Head created by this record must receive a new natural permanent workflow and four
new independently matched artifacts. Run `30709259080` cannot substitute for documented-Head
validation.

## 16. Explicit absence of P6-P7 capability

P5 introduces no:

- real production Provider adapter;
- outbound HTTP/TLS/DNS operation;
- server-owned production Secret source;
- endpoint or model allowlist;
- SSRF control implementation;
- production Prompt content;
- customer knowledge or attachment extraction;
- paid/customer Provider call in CI;
- Provider cost or token charge;
- Provider rate limiter;
- production circuit/kill-switch activation;
- production advisory result;
- new durable evidence write;
- automation proposal or executable action;
- approve, reject, return, transfer, withdraw, terminate, migrate, publish or activate command;
- M6-F capability.

## 17. P5 formal decision

M6-E P5 is accepted as a tenant-scoped, participant-authorized, GET-only and explicitly unavailable
pre-P6 approval-assistance presentation, subject to the new documented-Head permanent validation.

P6 may begin only after:

1. the exact documented Head Run succeeds;
2. all four documented-Head artifacts are independently SHA-256 matched;
3. Maven, P5 controller, Web, Mobile and permanent-boundary evidence are recalculated;
4. Review, thread, comment and reaction checks contain no actionable finding;
5. PR #83 remains Open + Draft;
6. Issues #80, #62, #13 and #14 remain in their required state;
7. current main is unchanged or is merged into the branch through an ordinary Merge Commit and
   fully revalidated.

P6 must select exactly one production Provider adapter and establish server-owned Secret material,
endpoint/model allowlists, DNS/TLS/SSRF controls, request/response budgets, cost/rate limits,
circuit breaker, kill switch, timeout, one-attempt/no-fallback behavior and an incident runbook.
CI must remain deterministic and must not contact a paid or customer Provider.

P6 must preserve the P5 GET endpoint as zero-egress status/read infrastructure. Provider generation
must use a distinct explicit server-owned invocation boundary and cannot accept Provider, route,
model, Prompt, policy, output-Schema, Secret, tenant, operator or permission selection from the
client.

`M6_E_P5_ACCEPTED_PROVIDER_NOT_CONFIGURED`

`READ_ONLY_GET_NO_EGRESS`

`PC_MOBILE_ADVISORY_PRESENTATION`

`NO_FABRICATED_AI_CONTENT`

`NO_APPROVAL_COMMAND_PROXY`

`ADVISORY_NOT_AUTHORITY`

`AI_IS_NOT_AN_OPERATOR`
