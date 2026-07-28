# M6-A-P5 Backend-Neutral Production Secret Material Source

Status: `BACKEND_NEUTRAL_MATERIAL_SOURCE_DEFAULT_DISABLED`

Concrete backend selection: `BLOCKED_PENDING_BACKEND_SELECTION`

Production connector execution: `PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED`

Decision date: `2026-07-28`

Tracking:

- parent milestone: Issue #62;
- workstream: Issue #63;
- pull request: PR #67;
- branch: `agent/m6-a-connector-foundation`;
- target branch: `main`;
- capability owner: `PLATFORM_SECURITY`;
- selected capability: `PRODUCTION_SECRET_MATERIAL_SOURCE`;
- implementation boundary: backend-neutral contracts and deterministic fake only.

## Decision gate

The repository and accepted operations documentation require production secrets to be managed by a
Secret manager, but they do not select a vendor, product, deployment topology, authentication
mechanism, tenancy model, audit owner or availability policy. M6-A therefore cannot infer or invent a
concrete production Secret Backend.

P5 records the concrete-backend decision as `BLOCKED_PENDING_BACKEND_SELECTION`. It implements no
Vault, Kubernetes Secret, cloud Secret Manager, KMS, environment-variable, system-property, file,
database or remote HTTP adapter.

PR #67 remains Open + Draft. P5 does not mark the PR Ready, enable auto-merge or merge it.

## Implemented backend-neutral boundary

P5 adds an internal server-owned request that binds one exact:

- tenant hash and Provider;
- opaque credential-reference hash;
- route-plan hash;
- credential-binding fingerprint;
- immutable material version and version-evidence hash;
- material type and connector operation;
- protocol profile and closed capability;
- environment classification;
- policy revision.

Request evidence is deterministic and hash-only. It is not a Controller DTO, browser contract,
credential, execution token or authorization grant.

The source interface remains compatible with the accepted P2 material scope. Its P5 lease method
fails closed with `BACKEND_NOT_SELECTED` unless a separately reviewed implementation overrides it.
No production implementation is present in this slice.

## Material lease lifecycle

The backend-neutral lease takes ownership of one bounded material byte array, copies it into a
direct buffer and immediately zeroizes the transferred array. Trusted server code receives only a
temporary callback-scoped copy. The copy is zeroized in `finally` on success, callback failure,
timeout classification and cancellation classification.

The lease provides these bounded guarantees:

- one active use at a time;
- concurrent use is rejected;
- explicit close is idempotent;
- close during use prevents another use and defers release until the callback exits;
- use after close is rejected;
- owned direct-buffer bytes are overwritten before backend release;
- release is attempted at most once;
- backend release messages are replaced by a stable redacted failure code;
- acquisition and release evidence contains hashes, booleans, ordinals and closed classifications
  only.

Java cannot guarantee forensic erasure of every JVM, operating-system or hardware copy, nor can it
prevent deliberately malicious trusted callback code from copying bytes. The enforceable boundary is
that the production API returns no Secret value, provides no cache or persistence path, bounds the
lease lifetime, zeroizes every platform-owned temporary copy and restricts access to trusted server
components.

## Exact admission and rotation

Before material is exposed, exact admission rejects:

- Provider, credential reference, tenant, route, binding, version, material type, operation,
  protocol, capability, environment or policy drift;
- disabled, revoked, expired or not-yet-valid bindings;
- `ROTATION_PENDING` and ambiguous active-version evidence;
- malformed descriptors and mismatched source evidence.

Version rotation is explicit. A request for a new version produces different deterministic evidence.
There is no fallback to a previous version, wildcard version, latest-version lookup or silent repair.

## Failure and audit model

Failures use a closed low-cardinality enumeration and stable snake-case codes. Exceptions do not
accept arbitrary backend text. Public descriptor and audit rendering excludes:

- raw material;
- tenant identity;
- credential-reference identity;
- endpoint or host;
- request or response body;
- Authorization header;
- AppKey, AppSecret or access-token value.

There is no Secret cache key because P5 introduces no cache. There is no Secret log event because P5
introduces no logger or exporter. Hash-only evidence is not permission to execute a connector.

## Default-disabled Spring gate

Server configuration is literal and default disabled:

```yaml
approval:
  connector:
    secret-material:
      enabled: false
      backend-selection: BLOCKED_PENDING_BACKEND_SELECTION
```

Default startup creates only a secret-free status record and no `CredentialMaterialSource` bean.
Setting `enabled=true`, naming a concrete backend or supplying an unknown property fails startup.
There is no Controller, management endpoint, Web/Mobile control, worker, scheduler or event listener.

## Deterministic testing

The deterministic fake backend is test-only. It uses fixed caller-owned time and ordinal evidence,
opens no network connection and stores no production Secret. Tests cover:

- deterministic redacted request and descriptor evidence;
- default backend-not-selected rejection;
- exact drift and binding-state rejection;
- explicit version rotation with no fallback;
- normal close and callback failure;
- timeout and cancellation classification;
- duplicate close and use after close;
- concurrent use and close-during-use;
- release failure redaction;
- material-free exception, descriptor, lease and audit rendering;
- default-disabled Spring startup and fail-closed configuration.

## Explicitly absent and blocked

- no concrete production Secret Backend;
- no environment, system-property or file Secret loader;
- no Vault, KMS, Kubernetes Secret or cloud Secret Manager adapter;
- no Secret persistence, cache, database table or Flyway migration;
- no AppKey/AppSecret administration UI;
- no Token Acquisition;
- no Token Refresh;
- no Token cache;
- no Provider invocation or HTTP dispatch;
- no execution coordinator;
- no worker;
- no scheduler, scanner, polling loop or event listener;
- no automatic retry;
- no reconciliation or recovery worker;
- no Provider fallback, weighted routing, load balancing or health routing;
- no browser, Web, H5, WeChat or Mobile Secret control;
- no Flowable access;
- no Approval-State Mutation;
- no approve, reject, return, transfer, withdraw, terminate or migrate command;
- no M5 migration semantic change;
- no `V49` or higher migration;
- no second automatic PR/main workflow;
- no PR Ready, auto-merge, merge or issue closure.

## Next gate

A later backend-specific gate must explicitly select exactly one production Secret Backend and define
its deployment, authentication, authorization, tenancy, audit, availability, rotation and incident
ownership. That gate must add adapter-specific conformance tests and permanent evidence.

P5 does not authorize that future adapter, Token lifecycle, connector dispatch or production
execution.
