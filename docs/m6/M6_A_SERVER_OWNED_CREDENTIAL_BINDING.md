# M6-A-P2 Server-Owned Credential Binding

Status: `SCOPED_CREDENTIAL_LIFECYCLE_IMPLEMENTED`

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

P2-B adds a Platform Security-owned resolver, a material-source port and non-returning scoped-use
APIs. The resolver binds one exact provider, tenant, credential reference, operation, key and
version before opening material. It never falls back to another reference or source version.

The Platform Security module is:

`server-modules/approval-connector-credential-core`

It depends on `approval-connector-spi` and JUnit in test scope. It does not depend on Spring,
Flowable, JDBC, an HTTP client, persistence, a scheduler, a worker, Vault, KMS or a cloud secret
SDK.

## Scoped lifecycle

Production use is limited to synchronous, non-returning callbacks. Every temporary material copy
is zeroized in a `finally` block. Callback failure still closes both the resolved credential scope
and the material-source scope. Use after close is rejected and repeated close is idempotent. A
closed resolver cannot resolve again.

The older generic `withCredential` and `withSecretBytes` APIs remain only for source compatibility
and deterministic test fixtures. They are explicitly documented as unsafe for production because
a generic return can carry an array or a copy outside the callback. `ServerOwnedCredentialResolver`
rejects both generic paths and requires an operation-bound `CredentialResolutionRequest`.

Java cannot prevent deliberately malicious trusted callback code from copying memory. The
foundation guarantee is therefore bounded: the production API does not offer a return path,
Platform-owned production code is subject to permanent architecture tests, and the scope is only
available to trusted server components.

## Evidence safety

Credential descriptors and resolution evidence contain only bounded identifiers, closed enum
values, timestamps, operation policy, deterministic SHA-256 values and bounded non-sensitive
metadata. They contain no raw secret bytes, token, password, AppSecret, private key,
Authorization header or absolute endpoint.

Resolution evidence is not a production-enablement token. It does not establish authorization,
audit completion or permission to execute a provider operation.

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
