# M6-A-P3 Production DingTalk Transport

Status: `DINGTALK_PRODUCTION_TRANSPORT_IMPLEMENTED_DEFAULT_DISABLED`

Decision date: `2026-07-26`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #63
- pull request: PR #67
- branch: `agent/m6-a-connector-foundation`
- target branch: `main`
- selected capability: `PROVIDER_TRANSPORT`
- owner: `CONNECTOR_ADAPTER`
- credential owner: `PLATFORM_SECURITY`
- prerequisite: M6-A-P2 server-owned credential binding

## Decision

M6-A-P3 implements a production-shaped DingTalk HTTPS transport behind the existing provider-neutral
DingTalk transport boundary. The implementation is present in an isolated server module but remains
default disabled: no application configuration, Spring bean, worker, scheduler, endpoint or tenant
route constructs it automatically.

This stage authorizes only the transport of an already-authorized, operation-bound request using one
exact P2 credential binding. It does not authorize credential issuance, lifecycle automation,
persistence, execution coordination or approval actions.

PR #67 remains Open + Draft. Issues #62, #63, #13 and #14 remain Open.

## Module boundary

New module:

`server-modules/approval-connector-dingtalk-http`

Compile dependencies are limited to:

- `approval-connector-dingtalk`;
- `approval-connector-credential-core`.

JUnit is test scope only. The module has no Spring, Flowable, JDBC, platform application,
integration persistence, cloud secret SDK or third-party HTTP-client dependency. It uses the Java 21
HTTP client only.

The existing `approval-connector-dingtalk` module remains credential-free. It receives an injected
`DingTalkTransport`; it does not import or hold `ServerOwnedCredentialResolver`, secret material or
credential-store state. Directory and identity adapters now forward the trusted execution context and
closed operation to the transport boundary. Captured test transports remain source-compatible through
the context-free functional method. Production transport rejects that context-free path.

## Fixed endpoint and operation matrix

The transport has no configurable base URL, customer endpoint, tenant endpoint or endpoint override.
Only HTTPS and the following exact bindings are permitted:

| Operation | API family | Fixed host | Exact path | Credential placement |
| --- | --- | --- | --- | --- |
| `ORGANIZATION_READ` | `OPEN_API_V1` | `api.dingtalk.com` | `/v1.0/contact/users/search` | `x-acs-dingtalk-access-token` header |
| `ORGANIZATION_READ` | `LEGACY_OAPI` | `oapi.dingtalk.com` | `/topapi/v2/user/get` | percent-encoded `access_token` query |
| `IDENTITY_RESOLVE` | `LEGACY_OAPI` | `oapi.dingtalk.com` | `/topapi/v2/user/get` | percent-encoded `access_token` query |

`IDENTITY_RESOLVE` cannot use the organization-search route. Unsupported operation, API family,
method, path or captured header is rejected before credential resolution and before network access.
The existing directory execution port continues to execute only `USER_BY_ID`; P3 does not open the
higher-level `USER_SEARCH` orchestration or enrichment path.

## Server-owned credential use

The transport obtains a secret-free `CapturedCredentialBindingPlan` from a trusted server-owned plan
source. The plan must exactly match:

- provider `dingtalk`;
- trusted credential-reference hash;
- requested operation;
- P2-required material type;
- key ID and version ID;
- descriptor fingerprint and policy version.

`ServerOwnedCredentialResolver` then revalidates provider, tenant, reference, operation, material
type, key, version, descriptor fingerprint, validity and source evidence. A mismatch or credential
resolution failure results in zero HTTP sender invocations.

Secret bytes are available only inside the P2 non-returning callback. Temporary byte-array copies are
zeroized by P2 on normal completion and failure. The HTTP protocol requires an access-token header or
query value; Java's standard HTTP API necessarily creates short-lived immutable header/URI string
representations. P3 stores none of those values in fields, evidence, exceptions, metadata or logs, and
all transport `toString()` output is redacted. Java cannot guarantee immediate erasure of immutable
runtime strings; this is an explicit implementation limitation rather than a claim of perfect memory
erasure.

## Network safety boundary

Before opening credential material, the transport:

- selects one fixed official host and exact path from the closed matrix;
- resolves the host and rejects empty results;
- rejects every resolution containing loopback, link-local, site-local, multicast, private,
  carrier-grade NAT, reserved, benchmark or documentation addresses;
- rejects IPv4-mapped private IPv6 values;
- validates request-body bytes and captured header allowlists.

The Java 21 HTTP client:

- uses HTTPS hostname identification;
- allows TLS 1.3 and TLS 1.2;
- never follows redirects;
- uses an explicit no-proxy selector;
- rejects ambient authenticator and cookie-handler state;
- uses a bounded connect timeout and the request's bounded timeout;
- sends exactly one attempt;
- reads at most 65,536 response-body bytes plus one overflow byte;
- decodes response bodies with strict UTF-8;
- retains only bounded visible provider request IDs.

The preflight DNS check and the JDK connection resolution are separate runtime steps. P3 therefore
does not claim cryptographic DNS pinning. Risk is reduced by immutable official hosts, no proxy, no
redirect, HTTPS hostname verification and rejection of non-public preflight results. Stronger DNS
pinning or a separately governed outbound gateway requires a later explicit security gate.

## Failure semantics

The transport never retries.

- connect/read timeout: `TIMEOUT`;
- DNS failure, interruption, I/O failure, invalid response size or invalid UTF-8: `UNKNOWN`;
- HTTP response, including an empty body: preserves the status code for the existing deterministic
  408, 429, 4xx and 5xx classification;
- malformed successful provider payload: existing adapter mapping returns `UNKNOWN`;
- provider application rejection: existing adapter mapping returns the closed provider failure;
- uncertain outcomes continue to require explicit reconciliation before any retry decision.

No response body, token, credential reference value or endpoint override becomes error details,
provider metadata or evidence.

## Tests and permanent boundaries

P3 tests use injected DNS resolvers, HTTP senders and Java `HttpClient` fixtures. They do not connect
to DingTalk or start a local network server. Contract tests cover:

- both exact official endpoint/authentication forms;
- operation/path separation;
- credential, plan and descriptor mismatch before network;
- public-address enforcement before secret access;
- timeout and unknown single-attempt behavior;
- token byte validation, cleanup and redacted rendering;
- request, response and provider-request-ID bounds;
- redirect, proxy, ambient authentication and cookie restrictions;
- empty HTTP bodies preserving error status classification.

Permanent architecture tests freeze module dependencies, fixed endpoints, no endpoint override,
trusted context-bound adapter invocation, default-disabled application wiring, no raw secret field,
no new automatic workflow, no M6 Flyway migration and no M5 semantic overlap.

## Explicitly absent and blocked

The following remain blocked after P3:

- no Token Acquisition;
- no Token Refresh;
- no access-token cache or refresh-token storage;
- no AppKey, AppSecret, customer credential or real production secret backend;
- no Tenant Routing;
- no Persistence;
- no M6 Flyway migration;
- no Worker;
- no scheduler or background execution loop;
- no Automatic Retry;
- no Recovery;
- no reconciliation worker, fallback, weighted routing, load balancing or health routing;
- no execution coordinator or multi-call enrichment;
- no shared authorization, audit or production observability-export change;
- no browser or mobile provider control;
- no direct Flowable access;
- no Approval-State Mutation;
- no approve, reject, transfer, withdraw, terminate or migrate action;
- no M5 source or migration semantic change;
- no new automatic workflow;
- no PR Ready, auto-merge, merge or issue closure.

## Validation evidence

Local Java 21 validation includes strict `javac -Xlint:all -Werror` compilation, production transport
smoke validation, JDK HTTP sender behavior tests and source-boundary checks. No local Maven executable
is available, so Maven Reactor, Checkstyle and permanent architecture execution are claimed only from
the repository's permanent GitHub Actions workflow.

The exact final workflow run, Maven artifact digest and test totals are recorded in PR #67 and Issue
#63 after the final natural workflow completes. Failed or cancelled runs are retained and are not
rerun or hidden.

## Stop condition

M6-A-P3 stops after permanent validation and governance evidence are recorded. A real secret backend,
token acquisition or refresh, tenant routing, persistence, execution coordination, worker, retry,
recovery or approval action requires a new explicit authorization.
