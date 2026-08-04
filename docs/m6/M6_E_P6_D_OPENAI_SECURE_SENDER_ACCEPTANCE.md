# M6-E P6-D OpenAI Secure Sender Acceptance

Status: `M6_E_P6_D_FORMALLY_ACCEPTED_PENDING_DOCUMENTED_HEAD_VALIDATION`

Date: `2026-08-04`

Tracking:

- parent milestone: Issue #62;
- roadmap rebaseline: Issue #78, closed / completed;
- workstream: Issue #80;
- Pull Request: #83;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- target: `main`;
- exact base/current main: `fcf031da9e6e04b15a1255044021a7fdd6637421`;
- P6-D implementation Head: `fea6f5db39ea36faf3b721dff5637b2b20e23455`;
- P6-D implementation Run: `30874932152` / run number `1120`, success;
- implementation compare: ahead `163`, behind `0`.

This record accepts only the P6-D exact OpenAI Responses endpoint policy, DNS/TCP/TLS-bound secure
sender, strict HTTP framing and pre-dispatch kill-switch, circuit, rate, cost and Secret admission.
The sender remains isolated in `approval-ai-openai` and is not wired into `apps/server`.

This record does not authorize P6-E, a public invocation endpoint, Provider orchestration binding,
P4 evidence writes, runtime enablement, an actual OpenAI call, a database migration, an approval
command, an automation proposal, PR Ready or merge.

## 1. P6-C entry gate

P6-D began only after P6-C completed its independent documented-Head acceptance and the user
separately authorized P6-D.

Accepted P6-C evidence:

- exact documented Head: `86019d05cfbfa78d14ed275b280feeaa269fe2fe`;
- permanent Run: `30868641009` / #1088;
- all four permanent jobs: success;
- all four artifacts independently downloaded and SHA-256 matched;
- Maven aggregate: `1500 / 0 / 0 / 0`;
- OpenAI Provider module: `23 / 23`;
- P6-C codec-focused tests: `16 / 16`;
- architecture module: `138 / 138`;
- repository hygiene: `25 / 25`;
- PR #83 remained Open + Draft;
- Issue #80 remained open.

The P6-C request encoder, strict response decoder and transport-port contracts remain the exact
protocol basis for P6-D. P6-D does not redefine the Provider-safe payload, output Schema or response
contract.

## 2. Accepted P6-D production scope

P6-D adds or advances only isolated Provider-transport infrastructure:

1. `OpenAiResponsesEndpointPolicy`;
2. `OpenAiResponsesHttpCodec`;
3. `OpenAiResponsesJdkSecureNetwork`;
4. `OpenAiResponsesNetworkSupport`;
5. `OpenAiResponsesRequestProfileValidator`;
6. `OpenAiResponsesSecureHttpSender`;
7. `OpenAiResponsesTransportAdmission`;
8. `OpenAiResponsesTransportControls`;
9. `OpenAiResponsesTransportException`;
10. the P6-D extensions to `OpenAiResponsesTransportPort`.

P6-D adds deterministic tests:

1. `OpenAiResponsesTransportAdmissionTest`;
2. `OpenAiResponsesSecureHttpSenderTest`;
3. `OpenAiResponsesSecureHttpSenderSecurityTest`;
4. `OpenAiResponsesHttpFramingTest`;
5. shared deterministic sender test support.

P6-D adds one permanent repository boundary:

- `scripts/tests/m6-e-p6-openai-sender-boundary.test.mjs`.

The existing Provider audit and P6-C codec boundary were advanced only to recognize this exact
isolated sender path. No second automatic workflow was added.

## 3. Exact endpoint policy

The sender accepts exactly one endpoint:

`https://api.openai.com:443/v1/responses`

The policy freezes:

- scheme: HTTPS only;
- host: `api.openai.com` only;
- port: `443` only;
- path: `/v1/responses` only;
- method: POST only;
- userinfo: absent;
- query: absent;
- fragment: absent;
- redirects: prohibited;
- endpoint configuration from clients: prohibited;
- alternate OpenAI-compatible endpoint: prohibited;
- regional, Azure-compatible, local or fallback Provider endpoint: prohibited.

The sender constructor requires the exact singleton endpoint policy. An alternate policy object or
endpoint identity is rejected before DNS, Secret acquisition or dispatch.

## 4. Canonical P6-C request binding

P6-D does not trust a transport request merely because its root fields look correct.

`OpenAiResponsesRequestProfileValidator`:

1. parses the exact request bytes with duplicate-property detection;
2. requires the exact P6-C root property set;
3. requires the exact stateless profile:
   - model `gpt-5-mini-2025-08-07`;
   - `store=false`;
   - `background=false`;
   - `stream=false`;
   - `tools=[]`;
   - `tool_choice=none`;
   - `truncation=disabled`;
4. requires one user message and one `input_text` item;
5. strictly parses the embedded Provider-safe payload;
6. rejects unknown or duplicate embedded fields;
7. reconstructs exact capability, Prompt, policy, output-Schema and Provider-safe input fields;
8. reconstructs the exact accepted version references;
9. derives the accepted P2 output limits from the supplied Schema;
10. rebuilds the request through the accepted P6-C `OpenAiResponsesRequestEncoder`;
11. requires exact equality of:
    - encoded bytes;
    - request-body SHA-256;
    - connect timeout;
    - total timeout;
12. requires `maximumOutputTokens <= 16384`.

Any arbitrary, weakened, empty or drifted JSON Schema fails before admission, DNS or Secret access.
Any unknown embedded payload field or version drift fails before admission, DNS or Secret access.

This re-encode comparison keeps P6-D bound to the already accepted P6-C canonicalization and avoids a
second independent Schema implementation.

## 5. Pre-dispatch admission sequence

The accepted sequence is fail-closed and ordered:

1. exact canonical request validation;
2. cancellation and deadline validation;
3. exact kill-switch generation/evidence validation;
4. authoritative kill-switch enabled check;
5. circuit-breaker permit;
6. tenant/global rate-limit permit;
7. current server-owned pricing-policy validation;
8. estimated maximum request-cost validation;
9. DNS resolution;
10. public-address and resolution-age validation;
11. direct TCP connection to one admitted address;
12. TLS handshake and hostname verification;
13. connected-address evidence binding;
14. second kill-switch/cost/cancellation revalidation;
15. callback-scoped Secret lease acquisition;
16. API-key byte validation;
17. final pre-dispatch revalidation;
18. exactly one HTTP write/read attempt;
19. bounded response evidence and circuit outcome recording.

Kill-switch, circuit, rate and cost checks occur before DNS and Secret acquisition. DNS and TLS are
completed before Secret acquisition. Secret material is unavailable to endpoint admission and
connection establishment.

## 6. Kill switch, circuit, rate and cost controls

### Kill switch

The sender requires exact Provider ID, Provider version, generation and evidence hash. It revalidates
the snapshot before Secret access and immediately before dispatch. Drift or disablement fails closed.

### Circuit breaker

The circuit breaker has closed, open and half-open behavior with a bounded failure threshold and open
interval. A permit is request-bound and single-use. An exception after dispatch records `UNKNOWN` and
cannot authorize a second attempt.

### Rate limiter

The rate limiter enforces both tenant and global windows using server-owned tenant hashes. A permit
cannot be replayed for another request hash.

### Cost policy

The server-owned cost policy freezes:

- pricing-policy ID/version evidence;
- exact model snapshot;
- input and output token prices;
- maximum estimated request cost;
- effective and expiry timestamps.

Unknown, stale, future, expired or model-drift pricing fails closed. Cost is re-estimated immediately
before dispatch.

No client controls kill-switch, circuit, rate, pricing, endpoint, model or token ceiling values.

## 7. DNS and SSRF boundary

DNS is performed only for the exact allowlisted host and within the connect-stage deadline.

The implementation:

- runs blocking resolution inside a deadline-bounded virtual-thread task;
- rejects empty results;
- sorts and deduplicates addresses;
- bounds the resolution set;
- rejects stale or future resolution evidence;
- rejects loopback, link-local, site-local/private, multicast, unspecified, documentation,
  benchmarking, carrier-grade NAT, reserved transition and other special-purpose ranges;
- calculates hash-only address-set and resolution evidence;
- connects directly to one admitted `InetAddress` rather than reconnecting by hostname;
- requires the connected address hash to be a member of the admitted set.

The connection path therefore does not perform a second host lookup after admission. String
allowlisting alone is not treated as SSRF evidence.

## 8. TCP and TLS binding

DNS, TCP connection and TLS handshake share one connect-stage deadline no greater than two seconds.
The HTTP exchange remains under the total request deadline no greater than fifteen seconds.

TLS uses the JDK default trusted certificate validation with:

- SNI for `api.openai.com`;
- HTTPS endpoint identification;
- hostname verification;
- trusted certificate-chain validation;
- TLS 1.2 or TLS 1.3 only;
- no trust-all manager;
- no hostname-verifier bypass;
- no plaintext fallback.

The TLS socket wraps the same TCP socket connected to the admitted IP address. The secure channel
records only connected-address and certificate-chain hashes, never certificate bodies or endpoint
input from a client.

If the connected address, TLS verification or endpoint evidence cannot be proven, the sender fails
closed before Secret material or HTTP dispatch.

## 9. Secret material boundary

P6-D reuses the accepted P6-B `CredentialMaterialLease` and exact production credential request.

The API key:

- is acquired only after DNS and TLS verification;
- is available only inside the bounded send callback;
- is validated as non-empty bounded printable ASCII;
- cannot contain CR, LF, NUL or control bytes;
- is written only to the `Authorization: Bearer` header;
- is zeroized by the lease callback and close path;
- is absent from exceptions, evidence, `toString`, metrics and durable storage.

A malformed API key fails before the HTTP header is written. Concurrent or stale lease access remains
closed under the accepted P6-B behavior.

## 10. Strict HTTP framing

The sender writes one HTTP/1.1 POST request with:

- exact Host header;
- `Authorization: Bearer <callback-scoped-secret>`;
- `Content-Type: application/json`;
- `Accept: application/json`;
- `Accept-Encoding: identity`;
- bounded client-request ID;
- exact Content-Length;
- `Connection: close`.

The response parser:

- accepts HTTP/1.0 or HTTP/1.1 status framing only;
- rejects redirects before body processing;
- rejects header folding;
- rejects duplicate headers;
- rejects whitespace before the header colon;
- accepts token syntax only for header names;
- rejects control, NUL, DEL and non-ASCII header values;
- bounds header line, total-header and header-count sizes;
- rejects simultaneous Content-Length and Transfer-Encoding;
- accepts only identity content encoding;
- accepts bounded Content-Length or strict bounded chunked framing;
- rejects unsupported transfer encodings;
- rejects oversized bodies before allocation;
- checks cancellation and deadline while reading;
- never includes response bodies in failures.

## 11. One-attempt and unknown-outcome semantics

The sender performs exactly one exchange call after `markDispatched`.

There is no:

- automatic retry;
- alternate address retry;
- alternate Provider;
- pre- or post-invocation fallback;
- redirect follow;
- polling;
- background continuation;
- Queue, Worker or Scheduler.

A failure before dispatch records no Provider attempt. A failure after dispatch is treated as
ambiguous/`UNKNOWN` circuit evidence and never causes a second send. A non-200 response is returned as
bounded transport evidence for the later strict decoder/invocation layer; P6-D itself does not create
an advisory success.

## 12. Redaction-safe transport evidence

Successful transport evidence contains only bounded hashes for:

- endpoint policy;
- admission decision;
- DNS resolution;
- connected address;
- TLS peer chain;
- client request ID;
- response body.

The transport response retains the raw Provider request ID only in the in-memory response contract
needed by the strict P6-C decoder. `toString` renders only its hash. No raw request body, response body,
API key, tenant, operator, task or instance identity is persisted.

## 13. Architecture and runtime isolation

P6-D production network authority is isolated to:

`io.github.akaryc1b.approval.ai.openai..`

Permanent ArchUnit rules require:

- all AI packages remain independent from Spring, Flowable, SQL/JDBC, PostgreSQL and host
  frameworks;
- every AI package outside `ai.openai` remains prohibited from depending on `java.net`.

The OpenAI adapter module remains absent from `apps/server/pom.xml`. P6-D adds no Spring component,
`@Bean`, Controller, service wiring, public endpoint, P3 orchestrator dependency, P4 store dependency,
JDBC dependency or Flowable dependency.

The accepted P5 GET status endpoint remains zero-egress and cannot invoke the sender.

## 14. Deterministic test evidence

The final OpenAI Provider module passes `50 / 50` tests.

Existing accepted tests remain green:

- P6-B Secret source: `7 / 7`;
- P6-C codec and transport port: `16 / 16`.

P6-D-focused tests pass `27 / 27`:

| Test class | Result |
| --- | ---: |
| `OpenAiResponsesTransportAdmissionTest` | `8 / 8` |
| `OpenAiResponsesSecureHttpSenderTest` | `6 / 6` |
| `OpenAiResponsesSecureHttpSenderSecurityTest` | `7 / 7` |
| `OpenAiResponsesHttpFramingTest` | `6 / 6` |

Deterministic tests cover:

- kill-switch, circuit, rate and cost admission;
- permit replay and request-hash drift;
- canonical P6-C request re-encoding;
- arbitrary Schema and embedded-payload drift rejection before egress;
- DNS special-purpose address rejection;
- stale/future DNS evidence;
- connected-address and TLS evidence drift;
- Secret acquisition only after TLS;
- Secret zeroization;
- malformed API-key header-injection rejection;
- one exchange only and no retry/fallback;
- 200, 401/403, 429, 5xx and ambiguous failures;
- redirect rejection;
- Content-Length and chunked framing;
- duplicate and ambiguous headers;
- compressed and oversized response rejection;
- cancellation and timeout behavior;
- redaction-safe errors and evidence.

Tests inject deterministic DNS/TLS/network/Secret boundaries. They do not read a real
`OPENAI_API_KEY`, resolve `api.openai.com`, open an external socket, use customer data or require a
paid Provider account.

## 15. Permanent repository boundaries

The combined repository-hygiene entrypoint passes `29 / 29`.

P6-D-specific permanent evidence passes `4 / 4`:

1. exact endpoint, admission order and one-attempt sender;
2. network and Secret authority isolated to one Provider path;
3. deterministic zero-egress tests and fail-closed ordering;
4. no server wiring, migration or P6-E capability.

Retained M6 boundaries remain green:

- M6 AI foundation: `10 / 10`;
- M6 AI activation review: `6 / 6`;
- M6 AI transport review: `7 / 7`.

The architecture module passes `139 / 139`. `ModuleBoundariesTest` passes `11 / 11`, including the
new rule that isolates `java.net` authority to the OpenAI adapter.

## 16. Retained append-only failure and correction evidence

No failed Run was rerun, hidden or used as acceptance evidence.

| Head | Run | Number | Retained outcome |
| --- | ---: | ---: | --- |
| `abbb30b987109f0ff80260629e06d267aea9d40c` | `30872883978` | #1108 | Hygiene exposed a `Content-Encoding` static-evidence mismatch; Vben and Mobile succeeded |
| `6ee332673cf5e5bb8438956756c3deb169e5d0c8` | `30873765866` | #1116 | Hygiene passed `28 / 29`; explicit output-token-ceiling evidence was missing |
| `019aa6d92ed679b112f8c5bc5fda665c73450d2a` | `30873866527` | #1117 | Hygiene passed `28 / 29`; normalized `content-encoding` evidence was missing |
| `27613a504778bb49ce6e1bf02bc5d47755b5b682` | `30874011403` | #1118 | Hygiene, Vben and Mobile succeeded; Maven exposed the old global AI network prohibition |
| `10bba67ee47fe3b5f5f575a00e662a59833277cc` | `30874508586` | #1119 | Hygiene, Vben and Mobile succeeded; Maven exposed an unsupported ArchUnit rule-composition API |

The final corrections:

- bound every transmitted request byte to canonical P6-C re-encoding;
- replaced fake `{}` test payloads with real encoder output;
- made DNS, TCP and TLS share the two-second connect budget;
- hardened HTTP header and body framing;
- retained explicit static evidence for token and content-encoding controls;
- split architecture isolation into two supported permanent ArchUnit rules.

## 17. Implementation Run and artifacts

Implementation Head:

`fea6f5db39ea36faf3b721dff5637b2b20e23455`

Permanent Run:

`30874932152` / #1120 — `pull_request`, attempt 1, `success`.

| Job | Job ID | Result |
| --- | ---: | --- |
| Java 21 / Maven / PostgreSQL | `91884403822` | success |
| Vben TypeScript / production build | `91884403828` | success |
| UniApp TypeScript / H5 / WeChat | `91884403801` | success |
| Repository hygiene | `91884403855` | success |

Recalculated Maven evidence:

- aggregate: `1528 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `156 / 156`;
- OpenAI Provider module: `50 / 50`;
- P6-D focused: `27 / 27`;
- approval-application: `233 / 233`;
- Persistence JDBC: `295 / 295`;
- architecture module: `139 / 139`;
- `ModuleBoundariesTest`: `11 / 11`;
- approval-server: `156 / 156`;
- all 26 Maven reactor projects: success;
- `BUILD SUCCESS`: present;
- Maven time: `08:19 min`.

Every implementation artifact ZIP was independently downloaded and SHA-256 hashed. Every local value
exactly matches GitHub:

| Artifact | ID | Size | SHA-256 | Match |
| --- | ---: | ---: | --- | --- |
| Maven | `8879320115` | `27388` | `a713c1542f182854f70098ca9ce97e0163ca1111ee1b5cbb2d1557a9e738bd05` | exact |
| Vben | `8879209921` | `18897` | `9f573241a2396054a4f814c1230143069db0a61d50ddf9226dd5f79b544977d3` | exact |
| Mobile | `8879195908` | `9755` | `0bd438b8c6aaabe2f1038f5bca968d04eb83dcd252aeca860545126e3fd5378a` | exact |
| Hygiene | `8879186277` | `9248` | `c11c41b655c7d08aa59ea546e1b5198bbb0801905fb6d96f7aaf20b1ab431804` | exact |

All four are unexpired and expire `2026-11-02T03:30:11Z`.

## 18. Explicitly absent through P6-D

P6-D contains no:

- `apps/server` dependency on `approval-ai-openai`;
- Spring Provider bean;
- public or internal generation Controller;
- P3 production Provider registration;
- P4 evidence-store write;
- actual OpenAI call in CI;
- paid/customer Provider dependency;
- V50 or later migration;
- retry, fallback, redirect follow, polling, Queue, Worker or Scheduler;
- conversation, tool, file, vector, MCP or hosted Prompt state;
- browser/mobile Provider or Secret selection;
- approval, rejection, return, transfer, withdrawal, termination, migration, publication or activation
  command;
- P6-E invocation service;
- M6-F capability.

## 19. Formal decision and next gate

P6-D is accepted as one isolated, exact-endpoint, fail-closed production sender and admission
foundation. Its code may open a network connection only when deliberately constructed by a future
server-owned caller, but no such caller or runtime route exists through P6-D.

PR #83 must remain Open + Draft. Issue #80 must remain open. No Ready or merge action is authorized.

P6-E remains gated. It requires separate explicit user authorization and a new append-only safe slice
for the server-owned invocation service, distinct API and P4 evidence integration. This P6-D
acceptance does not start P6-E.
