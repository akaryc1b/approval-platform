# M6-A-P6 DingTalk Token Acquisition, Refresh and Rotation Lifecycle

Status: `DINGTALK_TOKEN_LIFECYCLE_IMPLEMENTED_DEFAULT_DISABLED`

Production connector execution: `PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED`

Tracking:

- parent milestone: Issue #62;
- workstream: Issue #63;
- pull request: PR #67;
- branch: `agent/m6-a-connector-foundation`;
- target branch: `main`;
- selected capability: `TOKEN_LIFECYCLE`;
- owner: `PLATFORM_SECURITY`.

## Decision

P6 adds a bounded, synchronous and on-demand DingTalk Token lifecycle behind
server-owned credential, tenant-route and Kill Switch gates. The lifecycle is
implemented in the independent `approval-connector-dingtalk-token` module so the
accepted P3 HTTP transport remains unchanged and continues to contain no Token
acquisition or refresh behavior.

This gate does not authorize production execution. The application configuration
is literal default disabled. Enabling the gate requires every server-owned port,
including a credential material source, route revalidator, Kill Switch and Token
endpoint port. The repository intentionally supplies no real DingTalk Token
endpoint and no concrete production Secret Backend, so production startup cannot
construct a working Token lifecycle from repository defaults.

PR #67 remains Open + Draft.

## Closed lifecycle

The lifecycle accepts one exact server-owned request binding:

- trusted tenant identity;
- immutable P4 route plan and route definition hash;
- provider, capability, operation, API family and transport profile;
- application-credential reference, binding fingerprint and exact version;
- credential policy and Token policy versions;
- Kill Switch revision;
- environment classification.

The request is internal and cannot be submitted by Web or Mobile clients. It
contains no AppKey, AppSecret or Token material. Tenant and credential references
are represented in public evidence only by deterministic hashes.

Before every cache hit, acquisition, refresh and single-flight join, the
coordinator revalidates:

1. coordinator state and exact Token policy version;
2. Kill Switch decision and revision;
3. the immutable route through `TenantConnectorRouteRevalidator`;
4. exact credential catalog descriptor and lifecycle state;
5. provider, tenant, material type, binding, version and policy identity;
6. P5 material admission and bounded validity.

Any failure invalidates the affected credential/route family and fails closed.
There is no previous-version fallback.

## On-demand acquisition and refresh

The coordinator does not start a thread or poll. A caller request performs at
most one synchronous endpoint attempt when no usable cached Token exists.

- a Token before its refresh threshold yields `CACHE_HIT`;
- a Token at or after its refresh threshold is removed and refreshed on demand;
- concurrent requests for one exact cache key share one single-flight future;
- a bounded wait timeout fails closed and does not start another endpoint call;
- exact credential-version, binding, route, policy or Kill Switch changes create
  a different cache identity and zeroize the superseded family entry;
- revoked, disabled, expired, not-yet-valid and rotation-pending credentials
  invalidate the family and cannot use cached material;
- endpoint failures, malformed responses and invalid lifetimes do not install a
  cache entry;
- no automatic retry occurs after an endpoint attempt.

The cache is process-local, bounded and non-persistent. It is not a distributed
Token store, recovery mechanism or execution authority.

## Material lifecycle and redaction

P6 uses the P5 `CredentialMaterialLease` to obtain one exact test/non-production
application credential. The binary fixture is decoded only inside the lease
callback. AppKey and AppSecret temporary arrays are zeroized after the endpoint
port returns or fails.

The endpoint port returns Token bytes through a callback. The coordinator takes
ownership into a direct buffer and immediately zeroizes the supplied array.
Every issued `DingTalkAccessTokenLease` owns another direct buffer, creates only a
scoped callback copy, zeroizes that copy, rejects concurrent use, safely handles
repeated close, defers close during an active callback, and rejects use after
close. Cache invalidation and coordinator shutdown zeroize retained direct
buffers.

Exceptions and evidence use closed failure codes. They do not include material,
backend text, endpoint response text, tenant identifiers, credential references
or arbitrary error strings.

## Default-disabled Spring gate

```yaml
approval:
  connector:
    dingtalk-token:
      enabled: false
      policy-version: dingtalk-token-policy-v1
      refresh-before-expiry: 5m
      minimum-validity: 30s
      maximum-lifetime: 2h
      single-flight-wait: 5s
      maximum-entries: 256
```

When disabled, no Token policy, route gate or coordinator bean is created. When
enabled, missing server-owned dependencies fail startup. The configuration does
not create a concrete material source, Kill Switch or Token endpoint adapter.

## Explicitly absent

- no real DingTalk Token endpoint;
- no concrete production Secret Backend;
- no production AppKey, AppSecret or access Token;
- no customer credential or endpoint;
- no Persistence;
- no database table or Flyway migration;
- no Worker;
- no Scheduler;
- no background refresh, polling loop or scanner;
- no Automatic Retry;
- no fallback to a previous Token or credential version;
- no distributed cache, cluster coordination or recovery process;
- no P7 connector invocation coordinator or read-only business dispatch;
- no public management, Web or Mobile Token control;
- no Flowable access;
- no Approval-State Mutation;
- no second automatic workflow;
- no PR Ready, auto-merge, merge or issue closure.

## Validation boundary

Deterministic tests use only a fake credential material source, injected clock,
scripted Kill Switch, scripted route gate and local in-memory endpoint stub. They
do not open a socket or call DingTalk.

The focused tests cover request/evidence redaction, acquisition, cache hit,
on-demand refresh, single-flight, tenant isolation, exact version rotation,
revocation, Kill Switch changes, route drift, timeout, malformed lifetime,
material release, scoped zeroization, concurrency, close-during-use and
coordinator shutdown.

Permanent architecture tests prove the independent module has no HTTP client,
Spring, persistence, Flowable, worker, scheduler or real endpoint implementation;
adds no V49 migration or second automatic workflow; and cannot express an
approval command.

## Stop condition

P6 stops after implementation and documented-head permanent validation. P7 remains blocked until a separate safe slice is authorized and must preserve
pre-dispatch Kill Switch, route, credential and Token revalidation.

`PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED`
