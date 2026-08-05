# M6-E P6 OpenAI Provider Activation Audit

Status: `P6_PROVIDER_SELECTED_IMPLEMENTATION_NOT_AUTHORIZED`

Date: `2026-08-01`

Tracking:

- parent milestone: Issue #62;
- roadmap rebaseline: Issue #78, closed / completed;
- workstream: Issue #80;
- Pull Request: #83;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- target: `main`;
- exact base/current main: `fcf031da9e6e04b15a1255044021a7fdd6637421`;
- P5 documented Head: `895fb673004ca288a621bddc6720bad05ee24c32`;
- P5 final Run: `30709845305` / #1053, success.

This is the mandatory P6 audit and selection slice. It selects one production Provider and freezes
the required implementation boundaries. It adds no Provider adapter, network client, runtime Secret
loader, invocation endpoint or production Prompt.

## 1. Selected Provider

The only selected production Provider for M6-E is:

- vendor: `OPENAI`;
- provider ID: `openai-responses`;
- provider type: `REMOTE`;
- protocol profile: `OPENAI_RESPONSES_V1`;
- API operation: `POST /v1/responses`;
- endpoint host: `api.openai.com`;
- scheme: `HTTPS`;
- port: `443`;
- redirects: prohibited;
- private/local addresses: prohibited;
- model snapshot: `gpt-5-mini-2025-08-07`;
- floating model aliases: prohibited.

No second Provider, regional endpoint, Azure-compatible endpoint, OpenAI-compatible third-party
endpoint, local model or fallback Provider is authorized by this audit.

Official protocol evidence reviewed for this decision:

- OpenAI Responses API and text/structured output reference;
- OpenAI API authentication guidance requiring server-side Bearer API keys;
- OpenAI data-control guidance for `store=false`;
- OpenAI model/data-residency inventory confirming the selected model snapshot is supported by
  `/v1/responses`.

External documentation is evidence for protocol selection only. It does not authorize deployment or
runtime execution.

## 2. Exact request profile

The production request profile must be closed and server-owned:

- HTTP method: `POST`;
- path: exactly `/v1/responses`;
- `Authorization: Bearer <server-owned-secret>`;
- `Content-Type: application/json`;
- request body encoded as UTF-8 JSON;
- `model`: exactly `gpt-5-mini-2025-08-07`;
- `store`: exactly `false`;
- `background`: exactly `false`;
- `stream`: exactly `false`;
- `tools`: empty;
- `tool_choice`: exactly `none`;
- `previous_response_id`: absent;
- `conversation`: absent;
- `prompt_cache_retention`: absent;
- image, file, audio and tool input: prohibited;
- input modality: text only;
- output modality: text only;
- `text.format.type`: exactly `json_schema`;
- `text.format.name`: exactly `approval_assistance_v1`;
- `text.format.strict`: exactly `true`;
- output Schema: the exact accepted P2 `approval-assistance` Schema version;
- `max_output_tokens`: bounded by server policy;
- request metadata: no raw tenant, operator, task, instance or business identity.

The request must use the exact P1 Provider-safe projection and P2 server-owned Prompt/version
contracts. Clients cannot supply instructions, Prompt text, model, endpoint, route, Schema or
sampling parameters.

## 3. State and data-retention boundary

Responses API application state must not be used for approval assistance.

The adapter must enforce:

- `store=false`;
- no background mode;
- no streaming;
- no conversation object;
- no previous-response chaining;
- no Provider-hosted file, vector, web-search, code-interpreter, MCP or function tool;
- no response ID persisted as a conversation continuation token;
- no raw request/response body persisted by the platform;
- P4 retains only the accepted hash-only durable evidence.

Any Provider account or organization that cannot meet the required data-control policy remains
blocked. P6 implementation cannot silently relax `store=false` or introduce a stateful API mode.

## 4. Secret backend selection

P6 selects one concrete runtime Secret source:

- process environment variable: `OPENAI_API_KEY`;
- non-secret version evidence variable: `OPENAI_API_KEY_VERSION`;
- injection owner: platform deployment Secret backend;
- material consumer: server-only OpenAI transport callback;
- browser/mobile exposure: prohibited;
- YAML/property/file/database literal value: prohibited;
- logging, metrics, exception or audit rendering: prohibited;
- caching beyond one callback-scoped lease: prohibited.

The platform deployment Secret backend must inject the environment variable. The application must
not retrieve the key through arbitrary HTTP, accept it from a request, store it in Git, persist it in
the database or expose it through an actuator/config endpoint.

The runtime implementation must reuse the accepted M6-A callback-scoped material lease semantics:
copy only for the bounded send callback, zeroize platform-owned copies in `finally`, reject concurrent
use, and never return the Secret value from a public API.

The existing M6-D `AiExternalSecretResolver.inspectReference` remains metadata-only and cannot be
modified to return Secret material. P6 must introduce a separate production material adapter in the
Provider transport module.

## 5. Endpoint, DNS, TLS and SSRF controls

The endpoint allowlist contains exactly:

`https://api.openai.com:443/v1/responses`

The production transport must:

- reject any scheme other than HTTPS;
- reject any port other than 443;
- reject userinfo, fragments, queries in the configured endpoint and encoded path traversal;
- reject redirects at the HTTP client and response level;
- resolve only the exact allowlisted host;
- reject empty, unknown, loopback, link-local, private, multicast, documentation and reserved
  address classes;
- reject a resolution set that changes between admission and connection evidence;
- require TLS hostname verification for `api.openai.com`;
- require a valid, non-expired trusted certificate chain;
- prohibit trust-all managers, hostname-verifier bypasses and plaintext fallback;
- record only hash/redaction-safe DNS/TLS evidence;
- fail closed if the runtime cannot prove the connection used the admitted endpoint evidence.

P6 must not claim SSRF safety from string allowlisting alone. A transport that cannot bind admission
evidence to the actual connection remains disabled.

## 6. Invocation and budget controls

The Provider path remains synchronous and at-most-once:

- exactly one candidate route;
- exactly one HTTP attempt;
- zero automatic retry;
- zero pre-invocation alternate Provider;
- zero post-invocation fallback;
- zero redirect follow;
- zero polling/background continuation;
- cancellation checked before dispatch and while awaiting the response;
- connect timeout: maximum `2 seconds`;
- total Provider timeout: maximum `15 seconds`;
- response body: bounded before full parse;
- request body: bounded before dispatch;
- input fields/text/collection/depth: inherited from accepted P1 evidence;
- advisory item counts and confidence/evidence rules: inherited from accepted P2;
- output token ceiling: server-owned and finite;
- per-request estimated cost ceiling: server-owned and finite;
- per-tenant and global rate limits: required before production enablement;
- circuit breaker and authoritative kill switch: required before Secret lease or dispatch.

Timeout, cancellation, rate limit, cost limit, open circuit, disabled kill switch, Secret failure,
DNS/TLS failure, HTTP failure, malformed JSON, refusal, incomplete response, Schema mismatch,
unknown fields, version mismatch and P2 validation failure must produce a non-retryable bounded
failure or `UNKNOWN` outcome. None may trigger a second Provider call.

## 7. Response parsing

The adapter must accept only a completed non-streaming Responses API result that contains exactly one
structured assistant output matching the strict `approval_assistance_v1` JSON Schema.

The parser must fail closed on:

- HTTP status outside the accepted success status;
- missing or mismatched Provider request identifier evidence;
- response status other than completed;
- incomplete, cancelled, queued or in-progress response;
- Provider error object;
- refusal or safety-blocked output;
- missing output text;
- multiple advisory payloads;
- invalid UTF-8;
- malformed JSON;
- unknown JSON properties;
- duplicate JSON properties;
- excessive nesting, string length, collection length or total bytes;
- output Schema/version mismatch;
- model mismatch;
- non-finite usage or token evidence;
- any result that fails the accepted P2 contract revalidation.

No Provider response text or error body may be placed in an exception, log, metric, audit record or
P4 durable record.

## 8. Usage, cost and observability

Usage evidence may contain only bounded numeric token counts, selected model snapshot, closed outcome
classification, latency bucket, stable error code and request-ID hash.

Forbidden observability dimensions include:

- tenant ID;
- operator ID;
- task/instance/business ID;
- Prompt or input field value;
- Provider output text;
- raw Provider request ID;
- API key or Secret reference;
- endpoint supplied outside the exact allowlist;
- arbitrary exception message.

Metrics must use low-cardinality closed tags. Cost estimation must use a server-owned pricing-policy
version and fail closed when the selected model price is unknown or stale.

## 9. Production invocation API boundary

The accepted P5 GET endpoint remains zero-egress status/read infrastructure and cannot invoke a
Provider.

A later P6 implementation may add one distinct explicit invocation endpoint only after server-owned
participant authorization and exact P1-P5 evidence construction. The client request may contain only:

- task ID in the path;
- one closed P2 use case;
- ordinary authenticated request context.

The client cannot supply Provider, endpoint, model, Prompt, policy, Schema, Secret, routing,
retention, evidence identity, command or approval outcome.

The invocation endpoint must not approve, reject, transfer, return, withdraw, terminate, migrate,
publish, activate or populate an approval comment. It returns only an advisory result and evidence
metadata.

## 10. Durable evidence ordering

P4 evidence is written only after:

1. exact server authorization;
2. P1 projection construction;
3. P2 request construction;
4. route/kill-switch/circuit/rate/cost/Secret/DNS/TLS admission;
5. one Provider attempt;
6. strict response parse;
7. P2 contract revalidation;
8. P3 outcome and execution-evidence match.

Provider failure must not manufacture a success advisory. If durable failure evidence is written, it
must remain hash-only and comply with the accepted P4 contract.

## 11. CI and test strategy

CI must not read `OPENAI_API_KEY` and must not call `api.openai.com`.

P6 tests must use:

- deterministic in-memory Secret bytes owned by the test;
- deterministic DNS/TLS admission evidence;
- a loopback fake HTTP sender behind the transport port, not a fake production Provider bean;
- exact request-body golden hashes;
- exact response fixtures covering success, refusal, malformed JSON, unknown fields, oversized body,
  timeout, cancellation, 401/403/429/5xx and connection ambiguity;
- assertions for exactly one send and zero retry/fallback;
- assertions that logs/exceptions/audit contain no Secret, request body or response body;
- permanent architecture tests prohibiting network access from CI fixtures and clients.

No test may require a paid account, customer data or external network availability.

## 12. Required implementation sequence

P6 must proceed in independent append-only slices:

1. `P6-A`: this Provider/protocol/Secret/egress audit and permanent boundary;
2. `P6-B`: production Secret environment source and callback-scoped lease conformance;
3. `P6-C`: OpenAI request encoder, strict response decoder and deterministic transport port tests;
4. `P6-D`: DNS/TLS/SSRF-enforced sender, rate/cost/circuit/kill-switch admission;
5. `P6-E`: server-owned invocation service and distinct API, P4 evidence integration;
6. `P6-F`: fault/security/incident runbook and final P6 acceptance.

Each slice requires its own natural four-job permanent validation and artifact verification. A later
slice cannot use a prior slice's Run as final evidence.

## 13. Explicitly blocked by this audit

This audit authorizes no:

- production OpenAI call;
- API key loading;
- Provider bean;
- HTTP sender;
- DNS lookup or TLS connection;
- runtime route enablement;
- invocation endpoint;
- Prompt content;
- P4 write from Provider output;
- Provider retry or fallback;
- tool call;
- conversation state;
- browser/mobile Secret or Provider selection;
- approval or automation command;
- PR Ready, merge or Issue closure.

`OPENAI_RESPONSES_V1_SELECTED`

`MODEL_SNAPSHOT_GPT_5_MINI_2025_08_07`

`SERVER_SECRET_OPENAI_API_KEY`

`STORE_FALSE_TOOLS_NONE_ONE_ATTEMPT`

`IMPLEMENTATION_NOT_AUTHORIZED_UNTIL_P6_A_ACCEPTANCE`
