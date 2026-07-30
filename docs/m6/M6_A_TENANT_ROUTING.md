# M6-A-P4 Server-Owned Tenant Connector Routing

Status: `TENANT_ROUTING_IMPLEMENTED_DEFAULT_DISABLED`

Decision date: `2026-07-26`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #63
- pull request: PR #67
- branch: `agent/m6-a-connector-foundation`
- target branch: `main`
- selected capability: `TENANT_ROUTING`
- owner: `PLATFORM_APPLICATION`
- M6-A-P3: `ACCEPTED / PERMANENTLY_VALIDATED`

## Decision boundary

P4 opens only server-owned tenant route resolution. It does not open Connector execution.

The platform may resolve one exact, enabled and currently valid tenant-local route from a trusted
server `RequestContext`. A successful resolution returns an immutable, secret-free route plan and
deterministic evidence. It does not call DingTalk, open credential material, acquire a token, schedule
work, retry an operation, persist a route, or mutate approval state.

PR #67 remains Open + Draft. Issues #62, #63, #13 and #14 remain Open.

## Accepted P3 prerequisite

The P3 head `17a4d45c8c4ccd332261c377f5a18034c8a664e5` remains default disabled and permanently
validated by Approval Platform Validation Run `30187813665` / run #565. The Maven artifact ID is
`8627607950` and its GitHub SHA-256 digest is
`90a8b5e593dfe6821144913512bbffcb7ed3f786566f9c5f7043c8b410db66e2`.

This P3 acceptance does not enable a production Connector, create a real tenant configuration, grant
network execution, or authorize PR readiness or merge.

## Module ownership

P4 adds `approval-connector-routing-core`.

The module depends only on:

- `approval-connector-spi` for the existing closed capability and operation vocabulary;
- `approval-connector-credential-core` for the existing secret-free credential reference and binding
  descriptor.

It has no Spring, JDBC, persistence, Flowable, HTTP transport, integration-core, worker, scheduler or
retry dependency.

`TenantConnectorRouteResolutionService` lives in `approval-application` and is the platform-owned
entry point. It obtains the tenant only from the trusted `RequestContext` and delegates to the exact
resolver or revalidator.

## Closed request authority

`RouteRequest` contains only:

- Connector capability;
- one closed route intent;
- optional business-reference evidence;
- request or trace correlation evidence.

It cannot contain or override tenant, Provider, host, endpoint, path, HTTP method, API family,
transport profile, credential reference, key/version, secret, Token, AppKey, AppSecret, operator,
permission, route state, priority or fallback Provider.

## Closed DingTalk matrix

P4 supports only the P3-captured transport matrix:

| Capability | Connector operation | Closed Provider operation | API family |
| --- | --- | --- | --- |
| `ORGANIZATION` | `ORGANIZATION_READ` | `USER_BY_ID` | `OPEN_API_V1` or `LEGACY_OAPI` |
| `AUTHENTICATION` | `IDENTITY_RESOLVE` | `dingtalk-userid` | `LEGACY_OAPI` |

Every route uses Provider `dingtalk`, transport profile
`DINGTALK_JAVA21_FIXED_HTTPS_V1`, and credential material type `ACCESS_TOKEN`.

The route contains no host or path. P3 transport remains the only owner of fixed official DingTalk
HTTPS hosts and captured paths.

Executable `USER_SEARCH`, arbitrary DingTalk APIs, organization synchronization, department crawling,
messages, external to-do, callbacks, Feishu, custom endpoints, fallback, weighted routing, load
balancing and health routing remain blocked.

## Immutable configuration snapshot

The server loads one read-only startup snapshot. The default property is:

```yaml
approval:
  connector:
    tenant-routing:
      enabled: false
```

When disabled, no snapshot, resolver, revalidator or route-resolution service bean is created.

When enabled, configuration version, snapshot hash, definitions and definition hashes are mandatory.
An existing server-owned `CredentialBindingCatalog` bean is mandatory. Missing, malformed, unknown,
duplicate, unsupported or incomplete configuration fails startup. Unknown properties are rejected.
There is no dynamic reload, file watch, remote config center, database source, Provider lookup or HTTP
management surface.

Snapshot limits are:

- maximum routes: `5,000`;
- maximum routes per tenant: `16`;
- canonical snapshot evidence: `256 KiB`;
- exact identifier: `128` characters;
- version or policy version: `64` characters;
- correlation evidence: `256` characters;
- business-reference evidence: `256` characters.

The exact index key is tenant + capability + closed route intent. Resolution does not perform a linear
wildcard scan or fuzzy fallback. Tests cover 1,000 tenants with two routes per tenant.

## Route definition and plan evidence

Each definition binds exact tenant, Provider, capability, Connector operation, Provider operation, API
family, transport profile, secret-free credential reference, material type, route/policy versions,
credential descriptor fingerprint, enabled state, validity window and deterministic SHA-256.

A successful plan contains only bounded values and hashes: trusted tenant evidence, Provider,
capability and operation evidence, API family, transport profile, credential-reference hash, material
type, route/policy versions, snapshot/definition/descriptor hashes, business/correlation evidence,
caller-controlled logical time and deterministic plan hash.

The plan contains no raw tenant ID, raw credential reference, credential bytes, Token, AppKey,
AppSecret, authorization header, query credential, host, path, request body, response body, executable
callback or approval-state command.

Canonical serialization uses fixed, length-prefixed UTF-8 fields and deterministic SHA-256. No random
UUID, system clock or unstable collection ordering enters a content hash.

## Resolution and tenant isolation

Resolution is fail closed for source failure, hash mismatch, missing or ambiguous route, disabled,
not-yet-valid or expired route, unsupported Provider/operation/API family/profile/material, incompatible
credential descriptor, tenant/provider/operation/policy/fingerprint mismatch, and evidence generation
failure.

A tenant never falls back to another tenant or a global default. Missing and cross-tenant results do
not reveal whether another tenant has a route.

## Credential boundary

The resolver and revalidator use only `CredentialBindingCatalog.find(...)` and descriptor metadata.
They verify reference identity, tenant, Provider, operation, material type, credential policy,
descriptor fingerprint, state and validity.

They have no `CredentialMaterialSource`, no `ServerOwnedCredentialResolver`, no secret callback, no
Token acquisition or refresh API, and no HTTP sender. Tests use a catalog that also implements the
material-source interface and prove material-open count remains zero.

## Revalidation

Revalidation verifies the existing plan hash, trusted tenant, exact original snapshot, exact route,
Provider, capability, operation, API family, transport profile, credential-reference hash, material,
descriptor fingerprint, versions, enabled state and current validity.

Any drift rejects future dispatch. Revalidation never selects a replacement, changes Provider or
credential, repairs an old plan, opens secret material, invokes transport or retries.

## Explicitly blocked

P4 retains:

- no Token Acquisition;
- no Token Refresh;
- no AppKey or AppSecret lifecycle;
- no production Secret Backend;
- no route persistence or Flyway migration;
- no execution coordination;
- no Connector Worker;
- no Scheduler or background execution;
- no Automatic Retry;
- no recovery or reconciliation worker;
- no Provider fallback, weighted routing, load balancing or health routing;
- no Controller, REST mapping, management endpoint, Web action or Mobile action;
- no shared audit or observability exporter change;
- no Flowable dependency or M5 semantic change;
- no Approval-State Mutation;
- no approve, reject, transfer, withdraw, terminate or migrate action;
- no second automatic workflow;
- no Ready, auto-merge, merge or issue closure.

## Stop condition and next gate

P4 stops after the committed head receives one natural permanent validation run and its artifacts and
digests are verified and recorded in `M6_A_TENANT_ROUTING_EVIDENCE.md`.

The next explicit gate is M6-A-P5 `PRODUCTION_SECRET_MATERIAL_SOURCE`. It requires a separately chosen
production Secret Backend and must not be implemented by P4.
