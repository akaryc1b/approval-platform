# M6-E P6-E Production Invocation Acceptance

Status: `M6_E_P6_E_PENDING_DOCUMENTED_HEAD_VALIDATION`

Date: `2026-08-04`

Tracking:

- parent milestone: Issue #62;
- roadmap rebaseline: Issue #78, closed / completed;
- workstream: Issue #80;
- Pull Request: #83;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- target: `main`;
- exact base/current main: `fcf031da9e6e04b15a1255044021a7fdd6637421`;
- accepted R0 documented Head: `bb3140e2a171d19e0ca49bc91f8476a213a22e81`;
- P6-E implementation Head: `9eb1215e6b31cde04f6488983f4ee269a034d7dd`;
- P6-E implementation Run: `30890792560` / #1155, success.

This record accepts for documented-Head validation only the P6-E explicit synchronous production
Provider invocation slice. It binds the accepted P1 projection, P2 advisory contract, P3 at-most-one
Provider orchestration principles, P4 durable hash-only evidence and P6-A through P6-D exact OpenAI
profile, Secret source, strict codec and secure sender into one default-disabled server-owned path.

This record does not authorize M6-F capability, automation proposals, executable actions, background
processing, a second Provider, a live paid/customer Provider call in CI, PR Ready, merge or Issue #80
closure.

## 1. Entry gate and exact repository state

P6-E began only after R0 was formally accepted by the append-only PR evidence record at exact Head:

`bb3140e2a171d19e0ca49bc91f8476a213a22e81`

R0 permanent Run:

`30885050995` / #1123 — all four jobs success, all four artifacts independently SHA-256 exact.

The R0 source file intentionally retains its creation-time pending status. Its formal acceptance is
frozen by PR comment `issuecomment-5175663966`; this P6-E record does not rewrite that append-only
history.

Immediately before this document was created, GitHub was queried again and reported:

- current `main`: `fcf031da9e6e04b15a1255044021a7fdd6637421`;
- implementation Head: `9eb1215e6b31cde04f6488983f4ee269a034d7dd`;
- compare: ahead `198`, behind `0`;
- PR #83: Open, Draft, mergeable, not merged;
- commits: `198`;
- changed files: `114`;
- requested reviewers: none;
- unresolved inline review threads: zero;
- PR reactions: zero;
- submitted Reviews: two P6-A evidence-only `COMMENTED` records, explicitly non-actionable;
- no `REQUEST_CHANGES` review;
- auto-merge: not enabled;
- Ready transition: not performed.

Issue state remains:

- Issue #80: Open;
- Issue #62: Open;
- Issue #13: Open;
- Issue #14: Open;
- Issue #78: Closed / Completed.

No newer `main` Merge Commit is required because the branch remains behind zero.

## 2. Accepted P6-E production scope

P6-E adds only the following production invocation path:

1. `OpenAiResponsesAdvisoryProvider` as the exact framework-free OpenAI Responses adapter;
2. `OpenAiResponsesProductionRuntimeFactory` for bounded tenant runtime bindings;
3. `ApprovalAssistanceProductionOrchestrator` as a separate production orchestrator;
4. `ApprovalAssistanceProductionDurableEvidenceFactory` for the accepted P4 record;
5. `ApprovalAssistanceGenerationService` as the server-owned application boundary;
6. `ApprovalAssistanceGenerationContracts` as the closed public response contract;
7. `ApprovalAssistanceGenerationController` with one distinct explicit POST;
8. `ApprovalAssistanceProductionConfiguration` as the default-disabled composition root;
9. explicit PC and Mobile generation clients and user-click handlers;
10. deterministic Java and permanent repository-boundary tests.

The accepted P3 deterministic-test-only orchestrator remains unchanged and retains its
`DETERMINISTIC_TEST_ONLY` boundary. Production support is additive rather than a relaxation of P3.

No second automatic workflow was added. No V50-or-higher migration was added. P6-E reuses the
accepted V49 P4 durable-evidence schema.

## 3. Closed explicit API contract

P6-E exposes exactly one generation command surface:

`POST /api/approval/tasks/{taskId}/assistance/generations`

The request body must be one JSON object containing exactly one field:

`useCase`

The value must map to one accepted approval-assistance use case. Unknown, duplicate, client-selected
Provider, model, Prompt, endpoint, policy, Secret or routing fields fail before service invocation.

The response is `Cache-Control: no-store` and exposes only:

- stable generation status and bounded code;
- advisory content after strict P2 revalidation;
- `ADVISORY` authority;
- `UNVERIFIED_ADVISORY` assertion status;
- `needsHumanReview=true`;
- `commandAvailable=false`;
- `providerSelectable=false`;
- `retryAttempted=false`;
- `fallbackAttempted=false`;
- durable P4 evidence ID only after successful storage.

The public response does not expose Provider/model/Prompt/Secret versions, raw Provider request IDs,
request or response bodies, Authorization material, endpoint resolution, TLS evidence, pricing
material or internal failure bodies.

The accepted P5 GET remains distinct and zero-egress. Loading the page, changing the selected use
case or reading availability cannot start a Provider invocation.

## 4. Trusted identity and task revalidation

The generation service accepts tenant, operator, request and trace identity only from the existing
trusted server request boundary. The client cannot nominate another tenant, operator, participant,
Provider or evidence owner.

Before runtime binding or Provider invocation, the service:

1. checks that the production runtime exists;
2. constructs the exact trusted pending-task identity;
3. queries the pending task through `ApprovalTaskQuery`;
4. creates only the bounded Provider-safe projection;
5. queries the same pending task a second time;
6. requires exact snapshot equality.

A missing, cross-tenant, non-participant or changed task fails before Provider binding. Snapshot drift
returns a stable stale-task result and cannot continue to network egress.

The projection contains only the accepted visible fields and versions. It does not load attachment
content, hidden fields, customer knowledge, arbitrary process variables or Flowable internal tables.

## 5. Exact Provider and version binding

The production adapter retains the P6-A exact profile:

- Provider ID: `openai-responses`;
- Provider type: `REMOTE`;
- protocol: OpenAI Responses V1;
- exact endpoint: `https://api.openai.com:443/v1/responses`;
- exact model snapshot: `gpt-5-mini-2025-08-07`;
- exact strict output Schema;
- `store=false`;
- `background=false`;
- `stream=false`;
- tools empty;
- `tool_choice=none`;
- no previous-response or conversation state.

P6-E adds one server-owned Prompt template per accepted use case and binds exact Provider, model,
Prompt, policy, knowledge-source-none and output-Schema versions to each request.

The adapter delegates only to the accepted P6-C encoder, P6-D transport port and P6-C strict decoder.
It contains no endpoint override, HTTP client, Secret access, Spring component, JDBC dependency,
Flowable dependency, retry loop, fallback Provider or command behavior.

Malformed, refused, unsupported, stale, policy-blocked, timeout, transport-unavailable or invalid
output is compressed into stable low-information classifications. Provider bodies and exception text
are never returned to the public API.

## 6. Default-disabled composition root

Production invocation is disabled unless:

`APPROVAL_AI_OPENAI_ENABLED=true`

Any value other than exact `true` or `false` fails closed. The default is `false`.

When enabled, the composition root requires all exact server-owned settings:

- `OPENAI_API_KEY_VERSION`;
- `APPROVAL_AI_OPENAI_SECRET_EFFECTIVE_FROM`;
- `APPROVAL_AI_OPENAI_SECRET_EXPIRES_AT`;
- `APPROVAL_AI_OPENAI_SECRET_POLICY_REVISION`;
- `APPROVAL_AI_OPENAI_KILL_SWITCH_GENERATION`;
- `APPROVAL_AI_OPENAI_KILL_SWITCH_POLICY_REVISION`;
- `APPROVAL_AI_OPENAI_COST_POLICY_VERSION`;
- `APPROVAL_AI_OPENAI_COST_POLICY_EFFECTIVE_FROM`;
- `APPROVAL_AI_OPENAI_COST_POLICY_EXPIRES_AT`;
- `APPROVAL_AI_OPENAI_INPUT_MICROS_PER_TOKEN`;
- `APPROVAL_AI_OPENAI_OUTPUT_MICROS_PER_TOKEN`;
- `APPROVAL_AI_OPENAI_MAX_REQUEST_MICROS`;
- `APPROVAL_AI_OPENAI_TENANT_RATE_LIMIT`;
- `APPROVAL_AI_OPENAI_GLOBAL_RATE_LIMIT`;
- `APPROVAL_AI_OPENAI_RATE_WINDOW_SECONDS`;
- `APPROVAL_AI_OPENAI_CIRCUIT_FAILURE_THRESHOLD`;
- `APPROVAL_AI_OPENAI_CIRCUIT_OPEN_SECONDS`.

The exact Secret and cost-policy validity windows must contain the server clock instant. Missing,
blank, padded, malformed, non-positive, expired or not-yet-effective configuration fails during
runtime creation.

Configuration does not read `OPENAI_API_KEY`. Raw key material is still acquired only inside the
accepted P6-D callback after admission, DNS, TCP and verified TLS.

## 7. Admission, network and Secret reuse

P6-E does not replace or bypass P6-D. Every actual send still requires:

1. canonical P6-C request validation;
2. exact kill-switch generation and evidence;
3. enabled kill switch;
4. circuit permit;
5. tenant and global rate permits;
6. current cost policy and request-cost permit;
7. deadline-bounded DNS;
8. public-address validation;
9. connection to one admitted resolved address;
10. trusted TLS chain and hostname verification;
11. connected-address evidence binding;
12. second pre-dispatch control validation;
13. callback-scoped Secret lease;
14. exactly one HTTP exchange.

The runtime factory creates bounded tenant bindings and shares bounded circuit, rate and cost
controls. It stores only hashes and version evidence, never API-key material.

A dispatch-ambiguous failure remains `UNKNOWN` and does not authorize a second send. There is no
retry, redirect follow, alternate-address retry, second Provider or post-invocation fallback.

## 8. Production orchestration boundary

`ApprovalAssistanceProductionOrchestrator` requires:

- exact remote Provider descriptor;
- exact single model;
- exact enabled route;
- exact Provider/model version equality;
- exactly one allowed Provider and model authorization key;
- exact supported capability;
- maximum timeout no greater than fifteen seconds;
- `maximumProviderAttempts=1`;
- affirmative kill-switch, circuit, tenant-rate, global-rate and cost controls;
- projection and route budget conformance.

It starts at most one Provider invocation. A decoded result is reconstructed through the accepted P2
`Result` contract before it can be returned or persisted.

Usage evidence is platform-observed and low-cardinality. Raw Provider token details, request IDs and
bodies are not exposed through the API or P4 evidence.

The outcome requires one started Provider invocation, zero retry and zero fallback. No result can
acquire approval, Flowable, migration, publication, permission or Secret authority.

## 9. Durable P4 evidence and failure semantics

P6-E reuses `ApprovalAssistanceDurableEvidence` and
`JdbcApprovalAssistanceDurableEvidenceStore`.

The production evidence factory persists only:

- tenant-scoped evidence ID;
- request, subject, resource, projection, execution, route, version and outcome hashes;
- use case and closed result classification;
- one-attempt, invocation-started, no-retry, no-fallback and kill-switch-generation evidence;
- bounded advisory counts and confidence classification;
- request, record and retention timestamps;
- canonical evidence hash.

Provider-safe values are reduced to canonical hashes before persistence. Raw input fields, Prompt
text, advisory text, request/response JSON, API key, raw Provider request ID, endpoint, DNS/TLS
material and failure body are not stored.

The service performs exactly one store call after the one Provider outcome. It accepts only the
existing P4 `STORED` or exact `REPLAYED` result. `CONFLICT` returns a stable conflict response.
Store unavailability returns a stable unavailable response.

A persistence conflict or unavailable store never causes a second Provider invocation. A successful
public response is impossible until durable evidence exists.

## 10. PC and Mobile explicit user action

PC and Mobile each add one explicit generation client and one click handler.

The clients:

- call only the distinct generation POST;
- send only `useCase`;
- retain the existing server identity and transport boundaries;
- do not select Provider, model, endpoint, Prompt, policy or Secret;
- do not poll, stream or retry automatically.

The panels:

- require an explicit user click;
- block duplicate clicks while a request is in flight;
- never generate from page load, component watch or use-case selection;
- display unverified advisory content and limitations;
- do not prefill, submit or alter approval opinions or commands;
- retain `needsHumanReview=true` and advisory-only language.

There is no background refresh, Queue, Worker, Scheduler, listener or autonomous continuation.

## 11. Architecture, migration and workflow boundaries

The accepted architecture remains:

- `java.net` authority only in `io.github.akaryc1b.approval.ai.openai..`;
- AI SPI and AI Core remain network-free;
- AI modules remain independent from Spring, Flowable, SQL/JDBC and PostgreSQL;
- the server composition root may depend on the OpenAI module;
- the generation service may call the P4 store port;
- only the composition root constructs the JDBC store;
- the Controller never persists directly and never owns the sender;
- no production code accesses Flowable `ACT_*` tables;
- no command handler depends on AI output.

The highest Flyway migration remains the unique governed M6-E V49. P6-E adds no V50+ migration.

The only automatic PR/main workflow remains:

`.github/workflows/approval-platform-validation.yml`

## 12. Implementation permanent verification

Implementation Head:

`9eb1215e6b31cde04f6488983f4ee269a034d7dd`

Permanent Run:

`30890792560` / #1155 — all four jobs success.

| Job | Job ID | Result |
| --- | ---: | --- |
| Java 21 / Maven / PostgreSQL | `91932156908` | success |
| Vben TypeScript / production build | `91932156956` | success |
| UniApp TypeScript / H5 / WeChat | `91932156952` | success |
| Repository hygiene | `91932156915` | success |

Recalculated implementation evidence:

- Maven aggregate: `1543 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `156 / 156`;
- OpenAI Provider module: `54 / 54`;
- approval-application: `233 / 233`;
- Persistence JDBC: `295 / 295`;
- architecture module: `139 / 139`;
- approval-server: `167 / 167`;
- P6-E new Java tests: `15 / 15`;
- all 26 Maven reactor projects: success;
- `BUILD SUCCESS`: present;
- repository hygiene: `29 / 29`;
- M6 AI foundation: `10 / 10`;
- M6 AI activation review: `6 / 6`;
- M6 AI transport review including P6-E: `16 / 16`;
- Vben type-check and production build: success;
- UniApp type-check, H5 and WeChat builds: success.

Implementation artifacts were independently downloaded and SHA-256 matched:

| Artifact | ID | Size | SHA-256 | Match |
| --- | ---: | ---: | --- | --- |
| Maven | `8885193288` | `27485` | `14ef98faa689ebb1a72289698de7e5fa10fe269f2e64c63e735362d2d27f466f` | exact |
| Vben | `8884993974` | `18907` | `20b97e4d91eb6578e3afb0d63c318dedba721b33c18d4b961844cdcb545162fb` | exact |
| Mobile | `8884969684` | `9798` | `23b85b76625e57f7a2839694e3bdff7b3b8d6b53c146143c0dc3449dde7a6d2b` | exact |
| Hygiene | `8884947620` | `9663` | `8938047f24387b961f80d87a6030bca094da832f03daae5c760ad627fd248ad8` | exact |

All four are unexpired and expire `2026-11-02T08:09:50Z`.

## 13. Retained append-only failure evidence

No failed Run was rerun, hidden or used as acceptance evidence.

| Head | Run | Number | Retained outcome |
| --- | ---: | ---: | --- |
| `102c19e31080ff7e918da821abd789783db0fc8b` | `30889061392` | #1149 | Web succeeded; Mobile type-check exposed use of unsupported `body`; Hygiene exposed a comment-text false positive; Maven was superseded and cancelled |
| `d7865dd7546f63f1f7fed5f1a10a7298263f5602` | `30889685739` | #1151 | Hygiene, Web and Mobile succeeded; Maven exposed exactly three unused imports through Checkstyle |
| `16ab9efb6beac726719fbefd851b150100ca92e1` | `30890613030` | #1154 | Hygiene exposed stale static evidence for the new closed `REQUEST_FIELDS` set; later jobs were superseded by the correction |
| `9eb1215e6b31cde04f6488983f4ee269a034d7dd` | `30890792560` | #1155 | final implementation Head; all four jobs success |

Corrections were minimal and append-only:

- Mobile uses the accepted transport `data` option;
- the governance rule detects executable conversation state rather than explanatory prose;
- unused imports were removed;
- the request contract now uses its closed `REQUEST_FIELDS` set;
- permanent evidence checks require both exact field-count and field-membership validation.

## 14. Permanent authority and explicit exclusions

P6-E permanently retains:

- AI is never an operator;
- every returned result is advisory, unverified and human-reviewed;
- no Provider-to-command path;
- no approve, reject, return, transfer, withdraw, terminate, migrate, publish or activate command;
- no permission, tenant, operator, audit, worker, lease or engine identity manufacture;
- no arbitrary HTTP, SQL or script execution;
- no client-selected Provider, model, Prompt, endpoint, policy or Secret;
- no second Provider, retry, fallback, redirect following or stream;
- no previous-response, conversation, tools, function calling, RAG, embedding or vector store;
- no attachment-content extraction;
- no Queue, Worker, Scheduler, listener, polling or background continuation;
- no automation proposal or executable action;
- no M6-F capability;
- no live paid/customer OpenAI call in CI.

P6-E does not claim general production deployment readiness. Runtime enablement still requires exact
server-owned environment settings, a valid Secret version, a current cost policy, an affirmative kill
switch and the accepted P6-D network trust path.

## 15. Documented-Head gate

This file creates a new exact documented Head. P6-E is not formally accepted until:

1. a new natural pull-request workflow for that exact Head completes successfully;
2. all four jobs are successful;
3. four new artifacts are present, unexpired and tied to that exact Run and Head;
4. every artifact is independently downloaded and SHA-256 matched;
5. Maven, Web, Mobile and hygiene evidence are recalculated;
6. current `main` remains exact or is merged normally and fully revalidated;
7. PR #83 remains Open + Draft + mergeable and behind zero;
8. Reviews, threads, comments and reactions contain no actionable finding;
9. Issues #80, #62, #13 and #14 remain Open and Issue #78 remains Completed;
10. an append-only PR evidence record freezes the successful documented-Head results.

Only after those conditions may P6-E be marked formally accepted and P6-F be separately authorized.

This record is not Ready, merge authorization or Issue #80 closure.

`M6_E_P6_E_NOT_YET_FORMALLY_ACCEPTED`

`P6_F_REMAINS_GATED`

`PR_83_REMAINS_DRAFT`

`AI_IS_NOT_AN_OPERATOR`
