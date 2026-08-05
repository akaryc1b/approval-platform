# M6-E P6-D OpenAI Secure Sender Acceptance

Status: `M6_E_P6_D_FORMALLY_ACCEPTED`

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
- P6-D implementation Run: `30874932152` / #1120, success;
- accepted documented Head before this status-only correction: `00d3a8b93da2023350b9af112841adc5f4d9242a`;
- accepted documented-Head Run: `30875527717` / #1121, success.

This record formally accepts only the P6-D exact OpenAI Responses endpoint policy, canonical request
binding, DNS/TCP/TLS-bound secure sender, strict HTTP framing and pre-dispatch kill-switch, circuit,
rate, cost and Secret admission.

The sender remains isolated in `approval-ai-openai` and is not wired into `apps/server`.

This record does not authorize P6-E, a generation API, Provider orchestration binding, P4 evidence
writes, runtime enablement, a real OpenAI call in CI, a database migration, an approval command, an
automation proposal, PR Ready or merge.

## 1. Entry gate and accepted basis

P6-D began only after P6-C completed independent documented-Head acceptance and the user separately
authorized P6-D.

Accepted P6-C basis:

- documented Head: `86019d05cfbfa78d14ed275b280feeaa269fe2fe`;
- permanent Run: `30868641009` / #1088;
- all four jobs: success;
- all four artifacts independently downloaded and SHA-256 matched;
- Maven aggregate: `1500 / 0 / 0 / 0`;
- OpenAI Provider module: `23 / 23`;
- P6-C codec-focused tests: `16 / 16`;
- architecture module: `138 / 138`;
- repository hygiene: `25 / 25`.

P6-D preserves the accepted P6-C request encoder, strict response decoder and transport-port
contracts. It does not redefine the Provider-safe payload, output Schema or advisory-result contract.

## 2. Accepted production scope

P6-D adds or advances only isolated Provider transport infrastructure:

1. `OpenAiResponsesEndpointPolicy`;
2. `OpenAiResponsesHttpCodec`;
3. `OpenAiResponsesJdkSecureNetwork`;
4. `OpenAiResponsesNetworkSupport`;
5. `OpenAiResponsesRequestProfileValidator`;
6. `OpenAiResponsesSecureHttpSender`;
7. `OpenAiResponsesTransportAdmission`;
8. `OpenAiResponsesTransportControls`;
9. `OpenAiResponsesTransportException`;
10. P6-D extensions to `OpenAiResponsesTransportPort`.

P6-D adds deterministic tests:

1. `OpenAiResponsesTransportAdmissionTest`;
2. `OpenAiResponsesSecureHttpSenderTest`;
3. `OpenAiResponsesSecureHttpSenderSecurityTest`;
4. `OpenAiResponsesHttpFramingTest`;
5. shared deterministic sender test support.

Permanent repository evidence is provided by:

- `scripts/tests/m6-e-p6-openai-sender-boundary.test.mjs`;
- the existing P6-A Provider audit boundary;
- the existing P6-C codec boundaries;
- permanent ArchUnit module boundaries.

No second automatic workflow was added.

## 3. Exact endpoint and canonical request binding

The sender accepts exactly:

`POST https://api.openai.com:443/v1/responses`

The policy freezes HTTPS, `api.openai.com`, port `443`, `/v1/responses`, no userinfo, no query, no
fragment, no redirect and no alternate compatible, regional, local or fallback endpoint.

`OpenAiResponsesRequestProfileValidator` parses the exact request bytes with duplicate-property
detection, requires the exact P6-C stateless profile, strictly parses the embedded Provider-safe
payload, reconstructs the accepted capability and version references, derives the accepted output
limits and re-encodes through `OpenAiResponsesRequestEncoder`.

Before any admission, DNS or Secret access, it requires exact equality of:

- encoded bytes;
- request-body SHA-256;
- connect timeout;
- total timeout;
- output-token ceiling, with `maximumOutputTokens <= 16384`.

Arbitrary, weakened, empty or drifted output Schema, unknown embedded payload fields, version drift or
noncanonical bytes fail closed before egress.

## 4. Fail-closed admission order

The accepted sequence is:

1. canonical request validation;
2. cancellation and deadline validation;
3. exact kill-switch generation/evidence validation;
4. kill-switch enabled check;
5. circuit-breaker permit;
6. tenant/global rate-limit permit;
7. current server-owned pricing-policy validation;
8. maximum estimated request-cost validation;
9. DNS resolution;
10. public-address and resolution-age validation;
11. direct TCP connection to one admitted address;
12. TLS handshake and hostname verification;
13. connected-address evidence binding;
14. second kill-switch, cost and cancellation validation;
15. callback-scoped Secret lease acquisition;
16. API-key byte validation;
17. final pre-dispatch revalidation;
18. exactly one HTTP exchange;
19. bounded response and circuit outcome recording.

Kill-switch, circuit, rate and cost controls execute before DNS and Secret acquisition. DNS and TLS
complete before Secret acquisition. A permit is request-hash-bound and single-use.

## 5. DNS, SSRF, TCP and TLS boundary

DNS runs only for the exact allowlisted host inside a deadline-bounded virtual-thread task.

The implementation rejects empty, oversized, stale, future, local, private, link-local, multicast,
unspecified, documentation, benchmarking, carrier-grade NAT, transition and other special-purpose
address sets.

It sorts and deduplicates addresses, records hash-only resolution evidence and connects directly to
one admitted `InetAddress`. It does not reconnect by hostname after admission.

DNS, TCP and TLS share a connect-stage deadline no greater than two seconds. The complete exchange
remains under the total request deadline no greater than fifteen seconds.

TLS uses the JDK default trust store with:

- SNI for `api.openai.com`;
- HTTPS endpoint identification;
- hostname verification;
- trusted certificate-chain validation;
- TLS 1.2 or TLS 1.3 only;
- no trust-all manager;
- no hostname bypass;
- no plaintext fallback.

The TLS socket wraps the same TCP socket connected to the admitted address. Connected-address and TLS
peer evidence is hash-only.

## 6. Secret material boundary

P6-D reuses the accepted P6-B `CredentialMaterialLease`.

The API key:

- is acquired only after verified DNS/TCP/TLS evidence;
- is available only inside the bounded send callback;
- must be non-empty, bounded printable ASCII;
- cannot contain CR, LF, NUL or control bytes;
- is written only to `Authorization: Bearer`;
- is zeroized by callback and close paths;
- is absent from evidence, exceptions, metrics, `toString` and durable storage.

Malformed or stale Secret material fails before dispatch. The sender never owns long-lived raw Secret
state.

## 7. Strict one-attempt HTTP framing

The request uses exact HTTP/1.1 framing with Host, Authorization, JSON content type, JSON accept,
`Accept-Encoding: identity`, bounded client-request ID, exact Content-Length and `Connection: close`.

The response parser:

- accepts HTTP/1.0 or HTTP/1.1 status framing only;
- rejects redirects;
- rejects header folding, duplicate headers and whitespace before the colon;
- accepts token syntax only for header names;
- rejects control, NUL, DEL and non-ASCII header values;
- bounds header line, total-header and header-count sizes;
- rejects Content-Length plus Transfer-Encoding ambiguity;
- accepts identity content encoding only;
- accepts bounded Content-Length or strict bounded chunked framing;
- rejects unsupported transfer encoding and oversized bodies;
- checks cancellation and deadline while reading;
- never includes response bodies in failures.

The sender performs exactly one exchange after `markDispatched`. It has no redirect follow, automatic
retry, alternate address retry, Provider fallback, polling, Queue, Worker or Scheduler.

A failure after dispatch is recorded as ambiguous `UNKNOWN` circuit evidence and never authorizes a
second send.

## 8. Architecture and runtime isolation

Production network authority is isolated to:

`io.github.akaryc1b.approval.ai.openai..`

Permanent ArchUnit rules require:

- all AI packages remain independent from Spring, Flowable, SQL/JDBC, PostgreSQL and host frameworks;
- every AI package outside `ai.openai` remains prohibited from depending on `java.net`.

`apps/server/pom.xml` has no dependency on `approval-ai-openai`. P6-D adds no Spring component,
`@Bean`, Controller, public endpoint, P3 production Provider registration, P4 store dependency, JDBC
dependency or Flowable dependency.

The accepted P5 GET endpoint remains zero-egress and cannot invoke the sender. P6-D production
network code has no runtime caller through this slice.

## 9. Deterministic implementation verification

Implementation Head:

`fea6f5db39ea36faf3b721dff5637b2b20e23455`

Permanent Run:

`30874932152` / #1120 — all four jobs success.

| Job | Job ID | Result |
| --- | ---: | --- |
| Java 21 / Maven / PostgreSQL | `91884403822` | success |
| Vben TypeScript / production build | `91884403828` | success |
| UniApp TypeScript / H5 / WeChat | `91884403801` | success |
| Repository hygiene | `91884403855` | success |

Implementation evidence:

- Maven aggregate: `1528 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `156 / 156`;
- OpenAI Provider module: `50 / 50`;
- P6-B Secret source: `7 / 7`;
- P6-C codec-focused: `16 / 16`;
- P6-D sender/admission/HTTP-focused: `27 / 27`;
- approval-application: `233 / 233`;
- Persistence JDBC: `295 / 295`;
- architecture module: `139 / 139`;
- `ModuleBoundariesTest`: `11 / 11`;
- approval-server: `156 / 156`;
- all 26 Maven reactor projects: success;
- `BUILD SUCCESS` present.

Implementation artifacts were independently downloaded and SHA-256 matched:

| Artifact | ID | Size | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `8879320115` | `27388` | `a713c1542f182854f70098ca9ce97e0163ca1111ee1b5cbb2d1557a9e738bd05` |
| Vben | `8879209921` | `18897` | `9f573241a2396054a4f814c1230143069db0a61d50ddf9226dd5f79b544977d3` |
| Mobile | `8879195908` | `9755` | `0bd438b8c6aaabe2f1038f5bca968d04eb83dcd252aeca860545126e3fd5378a` |
| Hygiene | `8879186277` | `9248` | `c11c41b655c7d08aa59ea546e1b5198bbb0801905fb6d96f7aaf20b1ab431804` |

## 10. Accepted documented-Head verification

Accepted documented Head before this status-only correction:

`00d3a8b93da2023350b9af112841adc5f4d9242a`

Permanent Run:

`30875527717` / #1121 — all four jobs success.

| Job | Job ID | Result |
| --- | ---: | --- |
| Java 21 / Maven / PostgreSQL | `91886101774` | success |
| Vben TypeScript / production build | `91886101758` | success |
| UniApp TypeScript / H5 / WeChat | `91886101772` | success |
| Repository hygiene | `91886101777` | success |

Recalculated documented-Head evidence:

- Maven aggregate: `1528 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `156 / 156`;
- OpenAI Provider module: `50 / 50`;
- P6-B Secret source: `7 / 7`;
- P6-C codec-focused: `16 / 16`;
- P6-D sender/admission/HTTP-focused: `27 / 27`;
- transport admission: `8 / 8`;
- secure sender: `6 / 6`;
- secure sender security: `7 / 7`;
- HTTP framing: `6 / 6`;
- approval-application: `233 / 233`;
- Persistence JDBC: `295 / 295`;
- architecture module: `139 / 139`;
- `ModuleBoundariesTest`: `11 / 11`;
- approval-server: `156 / 156`;
- all 26 Maven reactor projects: success;
- `BUILD SUCCESS` present;
- Maven time: `06:18 min`;
- repository hygiene: `29 / 29`;
- P6-D permanent sender boundaries: `4 / 4`;
- M6 AI foundation: `10 / 10`;
- M6 AI activation review: `6 / 6`;
- M6 AI transport review: `7 / 7`;
- Vben type-check and production build: success;
- UniApp type-check, H5 and WeChat builds: success.

Final documented-Head artifacts were independently downloaded and SHA-256 matched:

| Artifact | ID | Size | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `8879486950` | `27234` | `2fb206b1f5970d94079c1d4113cc2b6737680794a35bed081852cc2d4e4dec4a` |
| Vben | `8879410964` | `18930` | `22940bb9f048eb3d29b90bdea08a0ff51d91b61d0b41c513fc0b2c2f1c6b9fd6` |
| Mobile | `8879396267` | `9795` | `33e87502a3fafa4095fcf7568672be0e487b7cce4211b5003cc0716dc57564b3` |
| Hygiene | `8879384579` | `9225` | `157e1516fd0bf9c5918553c5964b9844683d516fb1892206f5b6e3f0d647eafb` |

All four are unexpired and expire `2026-11-02T03:42:39Z`.

## 11. Retained append-only failure evidence

No failed Run was rerun, hidden or used as acceptance evidence.

| Head | Run | Number | Retained outcome |
| --- | ---: | ---: | --- |
| `abbb30b987109f0ff80260629e06d267aea9d40c` | `30872883978` | #1108 | Hygiene exposed `Content-Encoding` static-evidence drift |
| `6ee332673cf5e5bb8438956756c3deb169e5d0c8` | `30873765866` | #1116 | Hygiene passed `28 / 29`; explicit token-ceiling evidence missing |
| `019aa6d92ed679b112f8c5bc5fda665c73450d2a` | `30873866527` | #1117 | Hygiene passed `28 / 29`; normalized content-encoding evidence missing |
| `27613a504778bb49ce6e1bf02bc5d47755b5b682` | `30874011403` | #1118 | three jobs succeeded; Maven exposed stale global AI network prohibition |
| `10bba67ee47fe3b5f5f575a00e662a59833277cc` | `30874508586` | #1119 | three jobs succeeded; Maven exposed unsupported ArchUnit composition |
| `fea6f5db39ea36faf3b721dff5637b2b20e23455` | `30874932152` | #1120 | final implementation Head, all four jobs success |
| `00d3a8b93da2023350b9af112841adc5f4d9242a` | `30875527717` | #1121 | accepted documented Head, all four jobs success |

## 12. Status-only correction

The file created at `00d3a8b93da2023350b9af112841adc5f4d9242a` retained the stale heading
`M6_E_P6_D_FORMALLY_ACCEPTED_PENDING_DOCUMENTED_HEAD_VALIDATION` after #1121 had completed
successfully and PR/Issue metadata had recorded formal acceptance.

This append-only correction changes documentation only:

- status is now `M6_E_P6_D_FORMALLY_ACCEPTED`;
- exact #1121 jobs, evidence and artifacts are frozen in this record;
- no production, test, workflow, migration, dependency or runtime behavior changes;
- no P6-E authority is introduced.

The exact correction Head receives its own natural four-job permanent validation before PR/Issue
metadata is finalized.

## 13. Explicitly absent through P6-D

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
- approval, rejection, return, transfer, withdrawal, termination, migration, publication or activation command;
- P6-E invocation service;
- M6-F capability.

## 14. Formal decision and next gate

P6-D is formally accepted as one isolated, exact-endpoint, fail-closed production sender and admission
foundation. Its code may open a network connection only when deliberately constructed by a future
server-owned caller, but no caller or runtime route exists through P6-D.

PR #83 must remain Open + Draft. Issue #80 must remain Open. No Ready, auto-merge or merge action is
authorized.

P6-E remains gated. It requires separate explicit user authorization and a new append-only safe slice
for a server-owned invocation service, distinct API and P4 evidence integration. This P6-D acceptance
does not start P6-E.
