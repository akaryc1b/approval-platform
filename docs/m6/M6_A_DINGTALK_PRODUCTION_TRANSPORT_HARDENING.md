# M6-A-P3 Production DingTalk Transport Hardening

Status: `DINGTALK_PRODUCTION_TRANSPORT_IMPLEMENTED_DEFAULT_DISABLED`

Decision date: `2026-07-26`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #63
- pull request: PR #67
- branch: `agent/m6-a-connector-foundation`
- target branch: `main`
- selected capability: `PROVIDER_TRANSPORT`
- transport owner: `CONNECTOR_ADAPTER`
- credential owner: `PLATFORM_SECURITY`

## Purpose

This addendum records the final security review of the default-disabled M6-A-P3 DingTalk HTTPS
transport. The review found two transport-boundary gaps that were inside the already authorized P3
scope:

1. the original IPv6 classifier accepted several special-purpose IPv6 prefixes inside global unicast;
2. a provider-controlled request ID could repeat the access token and then enter bounded provider
   metadata.

The corrections do not open another capability. PR #67 remains Open + Draft. Issues #62, #63, #13
and #14 remain Open.

## Special-purpose IPv6 rejection

The endpoint policy continues to permit only the fixed official DingTalk hosts. Before credential
resolution, every resolved address must be public. In addition to loopback, link-local, site-local,
multicast, private IPv4, carrier-grade NAT, benchmark IPv4, documentation IPv4 and IPv4-mapped private
IPv6 values, the policy now rejects these special-purpose IPv6 families:

- `2001::/23` protocol-assignment space, including benchmarking and other non-general-purpose ranges;
- `2001:db8::/32` documentation space;
- `2002::/16` 6to4 space, preventing an encoded private IPv4 address from bypassing the public-address
  preflight;
- `3ffe::/16` former 6bone space;
- `3fff::/20` documentation space.

A normal public `2000::/3` address outside the rejected prefixes remains accepted. Tests prove both
rejection and the retained public-address path without making a network call.

## Credential echo removal

The Provider may control request-ID response headers. The JDK sender already limits request IDs to
visible ASCII and 128 characters, but bounds alone do not prevent a malicious or broken Provider from
repeating a credential.

The endpoint policy now checks the returned provider request ID while the P2 credential callback is
still active. If the request ID contains either:

- the raw access-token byte sequence; or
- the rendered header/query representation, including case-insensitive percent-encoding,

then the request ID is discarded before a `DingTalkTransportResponse` reaches Adapter result mapping.
The status code, response body, completion time and deterministic outcome classification remain
unchanged. Temporary request-ID bytes used for comparison are zeroized in `finally`.

No token or provider request ID is written to a field, exception message, evidence object, log or
persistent store. The unavoidable short-lived immutable HTTP header or URI string limitation remains
explicitly documented in the primary P3 decision record.

## Validation scope

The added contract tests prove:

- `2001:2::/48`, `2002::/16`, `3ffe::/16` and `3fff::/20` examples fail before credential opening and
  before HTTP sender invocation;
- a normal public IPv6 address remains accepted;
- raw header-token echoes are removed from provider request metadata;
- lower-case percent-encoded query-token echoes are removed case-insensitively;
- credential material is still closed and zeroized after each path.

Permanent architecture tests freeze the special-purpose IPv6 checks, provider-request-ID credential
echo removal and this governance addendum.

## Explicitly unchanged and blocked

The hardening keeps all P3 stop conditions unchanged:

- no Token Acquisition;
- no Token Refresh;
- no AppKey, AppSecret, refresh token, token cache or production secret backend;
- no Tenant Routing;
- no Persistence;
- no M6 Flyway migration;
- no Worker;
- no scheduler or background execution;
- no Automatic Retry;
- no Recovery;
- no execution coordinator, fallback, weighted routing, load balancing or health routing;
- no shared authorization, audit or production observability-export change;
- no browser or mobile provider control;
- no Flowable access;
- no Approval-State Mutation;
- no approve, reject, transfer, withdraw, terminate or migrate action;
- no M5 source or migration semantic change;
- no new automatic workflow;
- no PR Ready, auto-merge, merge or issue closure.

## Stop condition

M6-A-P3 stops after the hardening receives a natural permanent workflow run and the final PR/Issue
validation evidence is recorded. Any capability outside `PROVIDER_TRANSPORT` requires a new explicit
authorization.
