# M6-A DingTalk Transport Conformance

Status: `DINGTALK_CAPTURED_TRANSPORT_CONFORMANCE_IMPLEMENTED`

Decision date: `2026-07-24`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #63
- pull request: PR #67
- branch: `agent/m6-a-connector-foundation`
- target branch: `main`
- verified main baseline: `d769722cf7dd5418739a91ad4c45ca1a1c147502`
- production-integration ownership-gate head: `59892374315afffd129ee537c88d4fc9727659db`
- selected capability: `PROVIDER_TRANSPORT`
- selected provider: `DINGTALK`

## Scope

This slice validates a DingTalk adapter boundary for two provider-neutral contracts:

- `DIRECTORY_READ` using the existing organization-read contract;
- `IDENTITY_RESOLUTION` using the existing identity-resolve contract.

The adapter is intentionally implemented with an injected captured transport. There is no real
DingTalk network call, no endpoint host, no production credentials, and no credential resolver.

PR #67 remains Open + Draft after this slice.

## Request conformance

The request encoder freezes two relative provider paths:

- `/v1.0/contact/users/search` for user-search request conformance;
- `/topapi/v2/user/get` for one-user detail and identity-resolution request conformance.

These path and request-body shapes follow current public examples in the official DingTalk Workspace
CLI source reviewed on `2026-07-24`. Only relative paths are committed. No DingTalk host, token header,
application key, application secret, customer domain or private address is present.

The captured request contains only:

- API family;
- HTTP method;
- relative path;
- bounded non-sensitive headers;
- bounded JSON body;
- bounded timeout.

It always reports that credential material and an absolute endpoint are absent, and that an external
server-owned credential binding would be required by any later real transport implementation.

## Executable surface

This slice permits deterministic captured execution only for:

- directory `USER_BY_ID`;
- identity namespace `dingtalk-userid`.

`USER_SEARCH` encoding and decoding are conformance fixtures only. The current public search response
provides user identifiers that would require separately coordinated enrichment calls. This slice does
not add multi-call execution coordination, tenant routing, persistence, retry, recovery or batching,
so the directory execution port rejects `USER_SEARCH` before invoking the captured transport.

Identity-resolution output remains provider evidence only and never establishes trusted platform
identity.

## Response and failure classification

The decoder maps bounded user-detail evidence into the existing provider-neutral directory and
identity result types. Transport outcomes retain the existing closed connector semantics:

- HTTP 2xx: decode the bounded provider response;
- HTTP 408 or captured timeout: `TIMEOUT` and reconcile before retry;
- HTTP 429: `RATE_LIMITED`;
- HTTP 5xx: `RETRYABLE_PROVIDER_FAILURE`;
- HTTP 4xx: `PERMANENT_PROVIDER_FAILURE`;
- unknown transport outcome, malformed success payload or identity mismatch: `UNKNOWN`.

The adapter never performs a retry. `TIMEOUT` and `UNKNOWN` remain subject to the existing explicit
reconciliation gate.

## Module boundary

The new `approval-connector-dingtalk` module depends only on:

- `approval-connector-spi`;
- Jackson Databind for bounded JSON encoding and decoding;
- JUnit in test scope.

It does not depend on `approval-integration-core`, `approval-integration-jdbc`, platform persistence,
Spring, Flowable, an HTTP client, a scheduler or a worker.

The existing `approval-integration-core`, `approval-integration-jdbc` and
`approval-connector-generic` ownership remains unchanged.

## Explicitly absent

- no real DingTalk network call;
- no production endpoint, public host, customer domain or private address;
- no production credentials, tokens, application keys or application secrets;
- no credential resolver, secret-store or key-management integration;
- no tenant routing;
- no connector-owned persistence;
- no `V33` or other Flyway migration;
- no connector worker, lease, scheduler or recovery process;
- no execution coordinator or multi-call enrichment pipeline;
- no automatic execution;
- no automatic retry;
- no health-based routing, implicit fallback, weighted routing or load balancing;
- no audit queue, telemetry exporter or production observability endpoint;
- no browser or mobile execution control;
- no direct Flowable access;
- no approval process-state mutation;
- no M5 source or semantic modification;
- no additional automatic PR/main workflow;
- no PR Ready, auto-merge or merge action.

## Validation scope

The permanent contract tests cover:

- provider descriptor and closed capability metadata;
- exact current relative path, method and JSON-body encoding;
- absence of absolute endpoints and credential material;
- rejection of sensitive captured headers;
- user-detail directory and identity mapping;
- no trusted identity elevation;
- wrong-provider and wrong-operation rejection before transport invocation;
- non-executable search and unsupported identity namespace rejection;
- timeout, unknown, rate-limit, retryable and permanent failure classification;
- provider-level error, malformed success payload and response-ID mismatch handling;
- bounded identifier-only search-response decoding.

Permanent architecture tests prove that the adapter contains no real network client, production
credential binding, JDBC access, scheduler, worker, Flowable dependency or client-exposed trusted
DingTalk context.

## Retained publication history

A temporary publish probe was mistakenly committed while testing the connector write path:

- `bf7156839a74698622b8e392565ec8e20a14a1ff` created `__probe_should_not_exist__`;
- `b0535bad05ce57febb5605d8e40788e799ff91c7` immediately removed it.

The two commits are net-zero in the final tree and remain in history because rebase, force-push and
history rewriting are prohibited.

A second publication mistake temporarily replaced `server-modules/pom.xml` with the word
`placeholder` while the correct Git object tree already existed:

- `6b0e2e16837f52e33d4398b99cb46de699ed36b7` created the intended DingTalk feature tree as an
  unreferenced commit;
- `a452a2f04b482c11d8de90cd7ec40c94dfb993b1` mistakenly changed the branch POM to `placeholder`;
- `df9a03542f70e26689dca75a78e46448cf9e910d` formed the first corrective merge object;
- `0b75554e30afa2d793c5a5afcf6819527b78e74e` restored the reactor POM on the live branch;
- `ce2cf293f7e4a502b55e5ca1c141ef63fa9002d6` reconciled both histories with the exact intended
  feature tree and advanced the branch using `force=false`;
- `64516f1c705c95ff7da13b3424deac698386d330` added the complete contract and architecture tests.

No history was hidden or rewritten. The final tree contains the complete DingTalk module, the
correct server reactor and no temporary probe or placeholder content.

## Next explicit gate

This captured transport conformance slice does not open real provider transport.

A later stage must separately authorize exactly one next capability, such as a production transport
implementation or server-owned credential binding. Opening either capability must not implicitly open
tenant routing, persistence, execution coordination, retry, recovery, authorization, audit,
observability or approval-state actions.
