# M6-E P6-A OpenAI Provider Audit Acceptance

Status: `M6_E_P6_A_FORMALLY_ACCEPTED_PENDING_DOCUMENTED_HEAD_VALIDATION`

Date: `2026-08-03`

Tracking:

- parent milestone: Issue #62;
- roadmap rebaseline: Issue #78, closed / completed;
- workstream: Issue #80;
- Pull Request: #83;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- target: `main`;
- exact base/current main: `fcf031da9e6e04b15a1255044021a7fdd6637421`.

This record accepts only the P6-A Provider/protocol/Secret/egress audit and permanent non-
implementation boundary. It does not authorize API-key loading, a production Provider bean, an HTTP
sender, DNS lookup, TLS connection, runtime route enablement, an invocation endpoint, production
Prompt content, a Provider call, a P4 write from Provider output or any approval/automation command.

## 1. P5 entry gate

P6-A began only after P5 completed its independent documented-Head acceptance.

Accepted P5 evidence:

- exact documented Head: `895fb673004ca288a621bddc6720bad05ee24c32`;
- permanent Run: `30709845305` / run number `1053`;
- conclusion: `success`;
- all four permanent jobs: `success`;
- all four artifacts: independently downloaded and SHA-256 matched;
- Maven aggregate: `1477 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `156 / 156`;
- P5 controller: `3 / 3`;
- P4 PostgreSQL: `14 / 14`;
- Persistence JDBC: `295 / 295`;
- architecture module: `138 / 138`;
- ArchUnit `ModuleBoundariesTest`: `10 / 10`;
- P5 server boundary: `1 / 1`;
- P5 Web/Mobile boundary: `1 / 1`;
- combined repository hygiene: `17 / 17`;
- Web TypeScript / production build: success;
- Mobile TypeScript / H5 / WeChat builds: success;
- no actionable Review finding;
- PR #83 remained Open + Draft.

## 2. Selected production Provider profile

P6-A selects exactly one Provider profile:

- vendor: `OPENAI`;
- provider ID: `openai-responses`;
- provider type: `REMOTE`;
- protocol profile: `OPENAI_RESPONSES_V1`;
- operation: `POST /v1/responses`;
- exact endpoint: `https://api.openai.com:443/v1/responses`;
- model snapshot: `gpt-5-mini-2025-08-07`;
- redirects: prohibited;
- floating model aliases: prohibited;
- alternate, regional, Azure-compatible, third-party-compatible, local and fallback Providers:
  prohibited.

Official OpenAI API documentation was rechecked before this acceptance. The selected model snapshot
is listed for the Responses endpoint, the Responses API supports strict JSON-Schema structured
output and `tool_choice=none`, and API keys are required to remain server-side Bearer credentials.
External documentation supports protocol selection only and grants no deployment or execution
authority.

## 3. Exact request profile

A later implementation must be server-owned and must enforce:

- method `POST`;
- path `/v1/responses` only;
- UTF-8 JSON request body;
- `Authorization: Bearer <server-owned-secret>`;
- model exactly `gpt-5-mini-2025-08-07`;
- `store=false`;
- `background=false`;
- `stream=false`;
- empty tools;
- `tool_choice=none`;
- no `previous_response_id`;
- no conversation state;
- no prompt-cache-retention override;
- text input and text output only;
- strict `json_schema` output named `approval_assistance_v1`;
- the exact accepted P2 output-Schema version;
- finite server-owned output-token and cost ceilings;
- no raw tenant, operator, task, instance or business identity in Provider metadata.

Clients cannot supply Provider, endpoint, model, Prompt, policy, output Schema, Secret, routing,
retention, evidence identity or sampling parameters.

## 4. Secret-source decision

P6-A selects:

- material environment variable: `OPENAI_API_KEY`;
- non-secret version-evidence variable: `OPENAI_API_KEY_VERSION`;
- injection owner: the platform deployment Secret backend;
- consumer: a server-only bounded send callback.

The future Secret implementation must:

- reject missing, blank or malformed material/version values;
- never read the key in CI;
- never expose material to Web/Mobile, configuration APIs, actuator, logs, metrics, exceptions or
  audit evidence;
- never persist material in YAML, properties, files, Git or the database;
- reuse the accepted M6-A callback-scoped material lease semantics;
- copy bytes only for the bounded callback;
- zeroize platform-owned copies in `finally`;
- reject concurrent lease use;
- avoid caching beyond the callback-scoped lease.

The metadata-only M6-D `AiExternalSecretResolver.inspectReference` remains unchanged and cannot
return Secret material.

## 5. Endpoint, DNS, TLS and SSRF decision

The only allowlisted endpoint is:

`https://api.openai.com:443/v1/responses`

A later sender must reject:

- non-HTTPS schemes;
- ports other than 443;
- userinfo, fragments, endpoint queries and encoded path traversal;
- redirects;
- unknown or alternate hosts;
- empty resolution sets;
- loopback, link-local, private, multicast, documentation and reserved addresses;
- resolution drift between admission and connection evidence;
- invalid, expired or untrusted TLS chains;
- hostname-verification bypass;
- trust-all managers;
- plaintext fallback.

String allowlisting alone is not sufficient. Production activation remains blocked unless actual
connection evidence can be bound to the admitted endpoint evidence.

## 6. Invocation and budget decision

The future production path remains synchronous and at most once:

- exactly one candidate route;
- exactly one HTTP attempt;
- no automatic retry;
- no alternate Provider;
- no post-invocation fallback;
- no redirect follow;
- no background continuation or polling;
- connect timeout no greater than 2 seconds;
- total Provider timeout no greater than 15 seconds;
- request body bounded before send;
- response body bounded before full parse;
- finite input, output-token, cost, per-tenant-rate and global-rate limits;
- circuit breaker and authoritative kill switch before Secret lease or dispatch.

Every timeout, cancellation, admission failure, Secret failure, DNS/TLS failure, HTTP failure,
malformed/oversized response, refusal, incomplete result, model/Schema mismatch or P2 validation
failure is non-retryable and cannot cause a second Provider call.

## 7. Strict response decision

A later decoder may accept only one completed, non-streaming Responses result containing exactly one
strict `approval_assistance_v1` structured payload.

It must fail closed on:

- non-success HTTP status;
- missing or mismatched request-identifier evidence;
- non-completed response status;
- Provider error, refusal or safety-blocked output;
- missing or multiple advisory payloads;
- invalid UTF-8 or malformed JSON;
- duplicate or unknown JSON properties;
- excessive bytes, nesting, strings or collections;
- model, output-Schema or version mismatch;
- invalid usage evidence;
- any P2 contract-revalidation failure.

No Provider response text or error body may enter exceptions, logs, metrics, audit or P4 evidence.

## 8. P5 endpoint remains zero-egress

The accepted P5 endpoint remains:

`GET /api/approval/tasks/{taskId}/assistance`

It must remain a no-store, zero-egress availability/read endpoint. P6 cannot silently convert it into
a generation endpoint.

A future production invocation, if accepted in P6-E, must use a distinct explicit server-owned
boundary. The client may provide only a task ID in the path, one closed P2 use case and ordinary
authenticated request context. It cannot nominate any Provider, route, version, Secret, identity,
evidence or command field.

## 9. Durable-evidence ordering

A future P4 evidence write may happen only after:

1. server-owned participant authorization;
2. exact P1 projection construction;
3. exact P2 request construction;
4. route, kill-switch, circuit, rate, cost, Secret, DNS and TLS admission;
5. one Provider attempt;
6. strict response parsing;
7. P2 result revalidation;
8. exact P3 outcome/execution-evidence matching.

Provider failure cannot manufacture success advisory evidence. Any retained failure evidence remains
hash-only and subject to P4 invariants.

## 10. Deterministic CI decision

CI must not:

- read `OPENAI_API_KEY`;
- read customer Secret material;
- call `api.openai.com`;
- require a paid account;
- require customer data;
- depend on external DNS, TLS or network availability.

Later deterministic tests must use in-memory test-owned Secret bytes, deterministic admission
evidence, a fake sender behind a transport port, exact request golden hashes, bounded response
fixtures, exactly-one-send assertions, zero-retry/fallback assertions and redaction checks.

## 11. Required P6 sequence

P6 remains split into independent append-only slices:

1. `P6-A`: Provider/protocol/Secret/egress audit and permanent boundary;
2. `P6-B`: production Secret environment source and callback-scoped lease conformance;
3. `P6-C`: request encoder, strict decoder and deterministic transport-port tests;
4. `P6-D`: DNS/TLS/SSRF sender and rate/cost/circuit/kill-switch admission;
5. `P6-E`: server-owned invocation service, distinct API and P4 evidence integration;
6. `P6-F`: fault/security acceptance, incident runbook and final P6 acceptance.

A later slice cannot use an earlier slice's Run as final evidence.

## 12. P6-A implementation absence

At exact P6-A Head, production source contains no:

- OpenAI-named production class;
- `api.openai.com` literal;
- `OPENAI_API_KEY` or `OPENAI_API_KEY_VERSION` read;
- HTTP client;
- Bearer-header construction;
- production Provider bean;
- DNS lookup;
- TLS connection;
- invocation controller;
- new migration;
- Queue, Worker, Scheduler, polling or retry;
- approval or automation command.

The unique governed M6-E V49 remains the repository maximum; no V50+ migration exists.

## 13. Successful P6-A verification

Exact implementation Head:

`b4714363f175dab5862c9d1e9438bec4da90ddc0`

Permanent workflow:

- Run ID: `30710781517`;
- run number: `1056`;
- event: `pull_request`;
- attempt: `1`;
- conclusion: `success`.

Jobs:

| Job | Job ID | Result |
| --- | ---: | --- |
| Java 21 / Maven / PostgreSQL | `91397762252` | success |
| Vben TypeScript / production build | `91397762232` | success |
| UniApp TypeScript / H5 / WeChat | `91397762263` | success |
| Repository hygiene | `91397762190` | success |

Maven evidence recalculated from `maven-verify.log`:

- aggregate: `1477 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `156 / 156`;
- P5 controller: `3 / 3`;
- P4 Core: `8 / 8`;
- P4 PostgreSQL: `14 / 14`;
- approval-server: `156 / 156`;
- Persistence JDBC: `295 / 295`;
- architecture module: `138 / 138`;
- ArchUnit `ModuleBoundariesTest`: `10 / 10`;
- `BUILD SUCCESS`: present;
- every reactor module: `SUCCESS`;
- total Maven time: `08:25 min`.

Permanent governance evidence:

- M6-E P0-P3 boundary: `9 / 9`;
- P4 durable-evidence boundary: `1 / 1`;
- P5 server boundary: `1 / 1`;
- P5 Web/Mobile boundary: `1 / 1`;
- P6-A exact profile selection boundary: `1 / 1`;
- P6-A no-implementation boundary: `1 / 1`;
- combined repository-hygiene entrypoint: `19 / 19`;
- existing M6-D boundaries: `10 / 10 + 6 / 6 + 7 / 7`.

## 14. P6-A artifact verification

Every ZIP was independently downloaded and locally SHA-256 hashed. Each local hash exactly matches
GitHub.

| Artifact | ID | Size | GitHub/local SHA-256 | Match |
| --- | ---: | ---: | --- | --- |
| Maven | `8821890929` | `27046` | `9bea582ca7179a380faf820e0164e25b5c6e012dccd32c0b510cd7cabe89f150` | exact |
| Vben | `8821817081` | `18928` | `2073249f4dbfe5444cef0510bc2d7ae58087d9c72633748b7190f85dc20afea6` | exact |
| Mobile | `8821806599` | `9773` | `445cff4fd4cf29b70be886038f0a6c436d05eb8684ee0e6b6326467e954cc1cb` | exact |
| Hygiene | `8821799522` | `9250` | `28c87b5153772c67ff3e71dbcc277c3f5fe26e94fd70811ec81f3f905488e98b` | exact |

All four artifacts are unexpired and expire at `2026-10-30T17:38:52Z`.

## 15. Review and repository state before this record

Before this acceptance record was committed:

- current main: `fcf031da9e6e04b15a1255044021a7fdd6637421`;
- PR #83: Open + Draft + mergeable;
- branch compare: ahead `99`, behind `0`;
- changed files: `59`;
- submitted reviews: none;
- requested reviewers: none;
- unresolved review threads: none;
- PR reactions: none;
- Issues #80, #62, #13 and #14: open;
- auto-merge was not enabled;
- existing PR comments contain P0-P5 acceptance evidence and no actionable finding.

The documented Head created by this record must receive a new natural permanent workflow and four
new independently matched artifacts. Run `30710781517` cannot substitute for documented-Head
validation.

## 16. P6-A formal decision

P6-A is accepted as the exact OpenAI Responses profile-selection and non-implementation audit,
subject to the new documented-Head permanent validation.

P6-B may begin only after:

1. the exact documented-Head Run succeeds;
2. all four documented-Head artifacts are independently SHA-256 matched;
3. Maven and permanent-governance evidence are recalculated;
4. Review, thread, comment and reaction checks contain no actionable finding;
5. PR #83 remains Open + Draft;
6. Issues #80, #62, #13 and #14 remain open;
7. current main is unchanged or is merged into the branch using an ordinary Merge Commit and fully
   revalidated.

P6-B may add only the server-owned environment Secret source and callback-scoped lease conformance.
It must add no HTTP sender, DNS lookup, TLS connection, Provider invocation, invocation endpoint,
Prompt content, P4 write, migration or command.

`OPENAI_RESPONSES_V1_PROFILE_ACCEPTED`

`MODEL_SNAPSHOT_GPT_5_MINI_2025_08_07`

`P6_B_SECRET_SOURCE_ONLY_AFTER_DOCUMENTED_HEAD`

`NO_PRODUCTION_PROVIDER_CALL`

`NO_APPROVAL_COMMAND`

`AI_IS_NOT_AN_OPERATOR`
