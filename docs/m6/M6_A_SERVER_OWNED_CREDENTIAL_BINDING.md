# M6-A-P2 Server-Owned Credential Binding

Status: `DINGTALK_CREDENTIAL_BINDING_CONFORMANCE_IMPLEMENTED`

Decision date: `2026-07-25`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #63
- pull request: PR #67
- branch: `agent/m6-a-connector-foundation`
- target branch: `main`
- selected capability: `CREDENTIAL_RESOLUTION`
- owner: `PLATFORM_SECURITY`
- decision: `SHARED_COORDINATION_REQUIRED`

## Scope

M6-A-P2 establishes a provider-neutral, server-owned, production-shaped and fail-closed
credential-binding foundation. This document is intentionally limited to credential resolution and
does not open any other production capability.

P2-A introduced closed credential material, binding-state and resolution-status sets together with
deterministic, secret-free binding descriptors, resolution requests and resolution evidence.

P2-B added a Platform Security-owned resolver, a material-source port and non-returning scoped-use
APIs. P2-C added deterministic version, validity and rotation semantics without persistence or
background execution.

P2-D adds a non-secret captured binding plan and a Platform Security-owned DingTalk credential
profile. The profile maps only `ORGANIZATION_READ` and `IDENTITY_RESOLVE` to the closed
`ACCESS_TOKEN` material type. It does not acquire, refresh, cache or transmit a token.

DingTalk production adapter source remains unchanged. It does not own, save, resolve, cache or
rotate credentials. The credential-core module references the captured DingTalk module only in
test scope.

## DingTalk captured conformance

The test consumer resolves one exact server-owned binding, exercises the bounded secret scope and
then invokes the existing injected `DingTalkTransport` with its credential-free captured request.
The request continues to guarantee:

- `credentialMaterialPresent() == false`;
- `absoluteEndpointPresent() == false`;
- no sensitive header;
- no provider host;
- no AppKey, AppSecret or access-token value.

Provider mismatch, reference mismatch, revoked, expired and wrong-operation cases fail before the
callback. In every such case the DingTalk transport invocation count is exactly zero.

## Scoped lifecycle and validity

Every temporary material copy is zeroized in a `finally` block. Callback failure closes the
resolved scope and material-source scope. Use after close is rejected, repeated close is idempotent,
and a closed resolver cannot resolve again.

`DISABLED`, `REVOKED`, `NOT_YET_VALID`, `EXPIRED` and `ROTATION_PENDING` fail closed. Material
source version must match the exact active descriptor version. A descriptor change during
resolution fails closed, and previous-version fallback is forbidden.

Java cannot prevent deliberately malicious trusted callback code from copying memory. The
foundation guarantee is bounded: the production API has no return path, Platform-owned production
code is subject to permanent architecture tests, and the scope is available only to trusted server
components.

## Explicitly absent

There is no production secret backend, token-acquisition endpoint, OAuth refresh, Authorization
header injection, `x-acs-dingtalk-access-token`, real DingTalk host, HTTP client, network call,
persistence, migration, scheduler, worker, automatic retry or fallback.

## Explicitly blocked

The following remain blocked:

- production secret material or a production secret backend;
- real DingTalk or other provider networking;
- token acquisition or refresh;
- tenant routing or connector persistence;
- Flyway migration, including M6-owned `V33`, `V36` or any other version;
- worker, scheduler, background execution, retry, recovery or fallback;
- shared authorization, audit or observability-export changes;
- approval-state actions or process-state mutation;
- M5 migration changes;
- another workflow;
- marking PR #67 Ready, enabling auto-merge or merging it.

PR #67 remains Open + Draft. Issues #62, #63, #13 and #14 remain Open.
