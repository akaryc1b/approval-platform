# M6-A-P2 Server-Owned Credential Binding

Status: `CREDENTIAL_BINDING_FOUNDATION_IMPLEMENTED_NO_PRODUCTION_SECRET_BACKEND`

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

## Decision

M6-A-P2 implements only the server-owned credential binding and secret-lifecycle foundation. It is
provider-neutral at the resolution boundary, production-shaped, synchronous and fail closed.

This status does not mean `Production Secrets Enabled`, `Real Credential Store Enabled`,
`DingTalk Network Enabled` or `Automatic Execution Enabled`.

PR #67 remains Open + Draft. Issues #62, #63, #13 and #14 remain Open.

## Implemented slices

### P2-A — binding contracts and evidence

Closed credential material, binding state and resolution status enums bind one exact provider,
tenant, opaque credential reference, operation, key and version. Binding descriptors, resolution
requests and resolution evidence are deterministic and bounded. Evidence contains only hashes,
identifiers, timestamps, closed values and non-sensitive metadata. It is not authorization, audit
evidence or a production-enablement token.

### P2-B — scoped credential lifecycle

`ServerOwnedCredentialResolver` is owned by Platform Security and uses a
`CredentialMaterialSource` port. Production access is operation-bound and non-returning. Temporary
material copies are zeroized in `finally`; callback exceptions still close every scope; use after
close fails; repeated close is idempotent; and a closed resolver cannot resolve again.

The legacy generic `withCredential` and `withSecretBytes` methods remain only for source
compatibility and deterministic test fixtures. Production Resolver and scope implementations reject
those paths because their generic return values could carry bytes or copies out of the callback.

Java cannot prevent deliberately malicious trusted callback code from copying memory. The bounded
guarantee is that the production API provides no return path, the scope is available only to trusted
server components, and permanent architecture tests restrict Platform-owned production code.

### P2-C — version, validity and rotation

`DISABLED`, `REVOKED`, `NOT_YET_VALID`, `EXPIRED` and `ROTATION_PENDING` fail closed. Not-before
and expiration are evaluated against an injected deterministic clock. The source key and version
must match the exact descriptor, and a descriptor change during resolution fails closed.

Rotation completion requires the same binding, a pending previous version, a different active
version, an exact source-version snapshot and a currently valid active descriptor. Previous-version
fallback is never allowed. Rotation evidence remains secret-free and cannot authorize execution.

### P2-D — DingTalk captured conformance

A non-secret captured binding plan and Platform Security-owned DingTalk credential profile map only
`ORGANIZATION_READ` and `IDENTITY_RESOLVE` to the closed `ACCESS_TOKEN` material type.

DingTalk production adapter source remains unchanged. The Adapter does not own, save, resolve,
cache or rotate credentials. It still accepts only injected captured transport and has no credential
Resolver dependency.

The existing captured request continues to satisfy:

- `credentialMaterialPresent() == false`;
- `absoluteEndpointPresent() == false`;
- no sensitive header, provider host, AppKey, AppSecret or access-token value.

Provider mismatch, reference mismatch, revoked, expired and wrong-operation cases fail before the
DingTalk callback. The transport invocation count is zero for every resolution failure.

### P2-E — permanent governance boundary

Permanent tests prove that credential core has no Spring, Flowable, JDBC, HTTP, persistence,
secret-loader, cloud secret SDK, scheduler or worker dependency; DingTalk does not implement the
Resolver; browser and mobile code cannot access trusted credential types; evidence and plans carry
no raw secret type; production code retains no secret byte-array field; and the branch adds no
automatic PR/main workflow or Flyway migration.

## Explicitly absent and blocked

The following remain blocked after P2-E:

- no production secret backend, Vault, KMS, cloud secret manager or customer credential;
- no environment, system-property or file secret loader;
- no real provider transport, host, HTTP client or network call;
- no token acquisition, refresh, cache or Authorization header injection;
- no tenant routing;
- no persistence, credential store or Flyway migration;
- no scheduler, worker or background job;
- no automatic retry;
- no recovery, fallback, weighted routing, load balancing or health routing;
- no shared authorization, audit or production observability-export change;
- no browser or mobile credential control;
- no Flowable access;
- no approval process-state mutation or approve, reject, transfer, withdraw, terminate or migrate
  action;
- no M5 migration, intent, attempt, verification, reconciliation, lease, retry-lineage, UNKNOWN or
  runtime-binding semantic change;
- no new workflow;
- no PR readiness, auto-merge, merge or issue closure.

## Validation evidence

Each P2 slice received local Java 21 compilation, executable contract/lifecycle smoke validation,
source hygiene checks and a natural run of the repository's only permanent workflow. Exact final
run, artifact digest and test totals are recorded in PR #67 and Issue #63 after the final permanent
workflow completes.

No local Maven executable was available in the execution environment, so Maven reactor, Checkstyle
and architecture execution are taken only from the permanent GitHub Actions workflow rather than
claimed from local validation.

### Retained validation failure

Run `30155260636` / run #548 is retained. Credential Core, DingTalk, Checkstyle and all preceding
modules succeeded, while the new permanent boundary class failed two governance assertions. The
first assertion counted every manual or specialized workflow file instead of only automatic
`pull_request` / `push` workflows. The second required the exact phrase `no automatic retry` while
the document used an equivalent compound sentence.

The minimum correction is commit
`a1ec009e9c3fd56b4d7276abcb4c0f57bd036509`: it aligns the workflow filter with the repository's
established automatic-workflow test. This document now states the blocked retry capability with the
exact phrase required by the permanent gate. The failed run was not rerun or hidden; this new Head
triggers a natural new run.

## Stop condition

M6-A-P2 stops after permanent validation and governance evidence are recorded. M6-A-P3 production
DingTalk transport, a real secret store, token acquisition, token refresh, tenant routing,
persistence, worker, retry and recovery require new explicit authorization.
