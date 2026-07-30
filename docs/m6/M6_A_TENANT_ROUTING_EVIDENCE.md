# M6-A-P4 Tenant Routing Permanent Evidence

Status: `TENANT_ROUTING_IMPLEMENTED_DEFAULT_DISABLED`

Evidence date: `2026-07-26`

Tracking:

- repository: `akaryc1b/approval-platform`
- parent milestone: Issue #62
- workstream: Issue #63
- pull request: PR #67
- branch: `agent/m6-a-connector-foundation`
- target: `main`
- selected production capability: `TENANT_ROUTING`
- capability owner: `PLATFORM_APPLICATION`

## Acceptance chain

The M6-A acceptance chain remains:

1. Connector Foundation — `FORMALLY_ACCEPTED_CONTRACT_FOUNDATION`;
2. Production Integration Ownership Gate — `READY_FOR_SCOPED_IMPLEMENTATION_REVIEW`;
3. DingTalk Captured Transport Conformance —
   `DINGTALK_CAPTURED_TRANSPORT_CONFORMANCE_IMPLEMENTED`;
4. Server-Owned Credential Binding —
   `CREDENTIAL_BINDING_FOUNDATION_IMPLEMENTED_NO_PRODUCTION_SECRET_BACKEND`;
5. M6-A-P3 — `ACCEPTED / PERMANENTLY_VALIDATED`;
6. M6-A-P4 — `TENANT_ROUTING_IMPLEMENTED_DEFAULT_DISABLED`.

P4 does not authorize PR readiness, auto-merge, merge, issue closure, production route execution,
production tenant onboarding, Token access, or any capability after `TENANT_ROUTING`.

## Reverified baseline

Before P4 modification:

- `main`: `d769722cf7dd5418739a91ad4c45ca1a1c147502`;
- PR #67: Open + Draft + mergeable;
- P3 head: `17a4d45c8c4ccd332261c377f5a18034c8a664e5`;
- relation to `main`: ahead `64`, behind `0`;
- PR commits: `64`;
- changed files: `148`;
- additions/deletions: `18,002 / 1`;
- Issues #62, #63, #13 and #14: Open;
- M5 PR #58 actual head: `462cdac8b2cc3fbd260f001da269b2794ab79bd3`;
- M6-B PR #68 actual head: `330dbdd035e436459ffdedf0d2b0c8e07dac7e6c`;
- M6-C PR #69 actual head: `72acb3ba18602c09c28bfe08b58f8b91e6efe6e4`;
- M6-D PR #70 actual head: `9d588215e869c8f1332c0bc1a2809fbd235c2efa`;
- `main` highest Flyway migration: `V32`;
- M5 branch migration ownership: `V33` through `V43`;
- M6-A, M6-B, M6-C and M6-D: no Flyway migration;
- only automatic pull-request or push workflow:
  `.github/workflows/approval-platform-validation.yml`;
- PR #67 unresolved review threads: none;
- P3 permanent run and artifact: intact.

The prior local checkout was not inherited by this execution environment. GitHub refs were treated as
the source of truth. Every branch update was a non-force fast-forward. No rebase, force push, amend,
squash or reset was used.

## P3 formal acceptance evidence

P3 remains default disabled and permanently validated:

- head: `17a4d45c8c4ccd332261c377f5a18034c8a664e5`;
- workflow: `Approval Platform Validation`;
- run ID: `30187813665`;
- run number: `#565`;
- result: `success`;
- Repository hygiene: `success`;
- Java 21 / Maven / PostgreSQL: `success`;
- Vben TypeScript / production build: `success`;
- UniApp TypeScript / H5 / WeChat: `success`;
- Maven aggregate: `674 / 0 / 0 / 0`;
- P3 focused tests: `29`;
- accumulated M6-A focused tests at P3: `197`;
- Maven artifact ID: `8627607950`;
- GitHub digest and downloaded ZIP SHA-256:
  `90a8b5e593dfe6821144913512bbffcb7ed3f786566f9c5f7043c8b410db66e2`.

P3 acceptance does not enable the production DingTalk transport, provide application wiring, configure
a tenant, obtain a Token, create a production Secret Backend, or permit background execution.

## P4 implementation commits

P4 was published append-only:

1. `2d8402156a590ca29f56a66fe008ab5e360c5b5c` —
   `docs(connector): define tenant routing gate`;
2. `9e2b0fecbad7905c7228ea6192998a5bb99facf9` —
   `feat(connector): add tenant route contracts`;
3. `82b0b62c9e57a38bcacf3646dace55351e3a8c58` —
   `feat(connector): add trusted tenant route service`;
4. `281b76f0d0042fc32dc7aaaee09fddb197c1d586` —
   `feat(connector): wire default-disabled tenant routing`;
5. `330bae177aca12288cf3899db58403a9f4ec2a06` —
   `test(connector): validate tenant route configuration`;
6. `2e2f134818da47b8c82d1423c2c629270166cc8d` —
   `test(connector): validate tenant isolation and zero-secret routing`;
7. `9d0311651a07350507d1f818824e5064e376f3a2` —
   `test(connector): validate route revalidation and Spring fail-closed wiring`;
8. `8d4fbfd039ee1a91401fffca3acd8239ce64d60a` —
   `test(connector): enforce tenant routing permanent boundaries`;
9. `11dfa48a36f3d698640adb853354a0914a6ad3d0` —
   `fix(connector): canonicalize absent route request evidence`;
10. `343aa053be7f028fa8fe082609c6e3fce1b6bd8e` —
    `fix(connector): reject globally invalid tenant route snapshots`;
11. `624df1cd256228ba4531ab993ba9f22b0df6e701` —
    `fix(connector): preserve exact ambiguous route evidence`.

The implementation head validated below is:

`624df1cd256228ba4531ab993ba9f22b0df6e701`

## Modules and files

New module:

`server-modules/approval-connector-routing-core`

Its compile dependencies are limited to:

- `approval-connector-spi`;
- `approval-connector-credential-core`.

It has no Spring, JDBC, persistence, integration-core, Flowable, DingTalk HTTP transport, worker,
scheduler, retry or production Secret Backend dependency.

Platform Application entry point:

- `server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/TenantConnectorRouteResolutionService.java`.

The service obtains the tenant only from the existing trusted `RequestContext`. The route request cannot
carry tenant, Provider, API family, credential reference, endpoint, permission or operator authority.

Default-disabled Spring adapter:

- `apps/server/src/main/java/io/github/akaryc1b/approval/config/ApprovalTenantConnectorRoutingConfiguration.java`;
- `apps/server/src/main/java/io/github/akaryc1b/approval/config/ApprovalTenantConnectorRoutingProperties.java`;
- `apps/server/src/main/resources/application.yml`.

Default:

```yaml
approval:
  connector:
    tenant-routing:
      enabled: false
```

When disabled, no route snapshot, resolver, revalidator or route-resolution service bean is created.
When enabled, a valid immutable startup snapshot and an existing server-owned
`CredentialBindingCatalog` are mandatory. Missing, malformed, unknown, duplicate or incomplete
configuration fails closed. No Controller, public REST mapping, management endpoint, listener,
scheduler or transport execution chain is created.

Governance records:

- `docs/m6/M6_A_TENANT_ROUTING.md`;
- `docs/m6/M6_A_TENANT_ROUTING_EVIDENCE.md`.

## Closed route matrix

P4 permits only:

| Provider | Capability | Connector operation | Provider operation | API family |
| --- | --- | --- | --- | --- |
| `dingtalk` | `ORGANIZATION` | `ORGANIZATION_READ` | `USER_BY_ID` | `OPEN_API_V1` |
| `dingtalk` | `ORGANIZATION` | `ORGANIZATION_READ` | `USER_BY_ID` | `LEGACY_OAPI` |
| `dingtalk` | `AUTHENTICATION` | `IDENTITY_RESOLVE` | `dingtalk-userid` | `LEGACY_OAPI` |

Transport profile is fixed to `DINGTALK_JAVA21_FIXED_HTTPS_V1`. The route contains no host or path.
The P3 transport remains the sole owner of official host, path, method and credential placement.

Executable `USER_SEARCH`, arbitrary DingTalk APIs, Feishu, message sending, third-party task creation,
callbacks, organization synchronization, department crawling, multi-call enrichment, fallback,
weighted routing, load balancing and health routing are rejected.

## Deterministic configuration and plan evidence

The immutable snapshot:

- canonical-sorts definitions;
- uses deterministic SHA-256;
- uses an exact tenant + capability + closed route-intent index;
- has no wildcard, catch-all, global default or fuzzy fallback;
- caps total routes at `5,000`;
- caps routes per tenant at `16`;
- caps canonical snapshot evidence at `256 KiB`;
- rejects invalid definition or snapshot hashes;
- rejects duplicate definitions and duplicate exact keys;
- rejects unsupported Provider, operation, API family, profile or material type.

The immutable route plan contains only bounded values and hashes:

- trusted tenant evidence hash;
- Provider, capability and closed operation;
- API family and transport profile;
- credential-reference hash and material type;
- route, route-policy and credential-policy versions;
- snapshot, definition and credential-descriptor hashes;
- request, business and correlation evidence;
- caller-controlled logical time;
- deterministic plan hash.

It contains no raw tenant ID, raw credential reference, secret bytes, access Token, refresh Token,
AppKey, AppSecret, authorization header, query credential, host, path, request body, response body,
callback or approval-state command.

Canonical serialization is fixed, length-prefixed UTF-8 followed by SHA-256. Missing optional evidence
uses a stable canonical absence marker. Random UUIDs, system time and unstable collection ordering do
not enter business hashes.

## Fail-closed resolution and revalidation

Resolution returns no executable plan for:

- source absence or exception;
- malformed, hash-mismatched or globally invalid snapshot;
- exact route missing;
- exact route ambiguous;
- disabled route;
- route not yet valid or expired;
- unsupported route matrix;
- credential descriptor absence or mismatch;
- tenant, Provider, operation, material, policy or descriptor-fingerprint mismatch;
- evidence generation failure.

An exact multi-match produces `AMBIGUOUS`. Invalid duplicate configuration elsewhere in the snapshot
still blocks otherwise exact resolution as `INVALID_CONFIGURATION`.

Revalidation rejects:

- trusted tenant change;
- plan hash change;
- snapshot change;
- Provider, capability, operation, API family or transport-profile change;
- credential-reference hash, material or descriptor change;
- route or policy version change;
- disabled, expired, not-yet-valid or globally invalid route configuration.

It never automatically selects another route, changes Provider or credential, repairs a plan, opens
secret material, executes transport, retries or falls back.

## Tenant-isolation evidence

Tests prove:

- tenant A cannot resolve tenant B's route;
- tenant A never falls back to tenant B or a global default;
- trusted tenant is read only from `RequestContext`;
- caller-supplied tenant authority does not exist in `RouteRequest`;
- Provider, API family and credential overrides do not exist in `RouteRequest`;
- cross-tenant plan revalidation fails;
- missing and cross-tenant errors do not reveal another tenant's route or credential reference;
- one thousand tenants with two routes each use deterministic exact-key lookup;
- duplicate detection remains deterministic.

## Zero-secret evidence

Tests use a boundary object implementing both `CredentialBindingCatalog` and
`CredentialMaterialSource`. Route resolution and revalidation call only catalog metadata lookup.
Material opening is an assertion failure and its invocation count remains zero.

Verified counters:

- credential material open: `0`;
- secret callback: `0`;
- Token acquisition: `0`;
- Token refresh: `0`.

Architecture tests also reject `CredentialMaterialSource`, `ServerOwnedCredentialResolver`,
`openMaterial`, Token acquisition/refresh identifiers, AppSecret and authorization construction from
routing production source.

## Zero-network evidence

Verified HTTP sender invocation count: `0`.

The routing core has no HTTP client or P3 production-transport dependency. Spring wiring creates no
DingTalk transport or Connector execution coordinator. Application startup and route resolution do
not perform DNS, HTTP, DingTalk or any other network call.

## Test evidence

P4 focused tests at the implementation head:

- route configuration: `12`;
- route resolver, tenant isolation, plan and credential boundary: `11`;
- route revalidation: `6`;
- globally invalid snapshot behavior: `2`;
- trusted RequestContext application service: `1`;
- Spring default-disabled fail-closed wiring: `5`;
- permanent architecture boundaries: `8`.

Total P4 focused:

`45 / 0 / 0 / 0`

Maven aggregate:

`719 / 0 / 0 / 0`

Accumulated M6-A focused tests:

`242`

The test suite covers deterministic hashes, canonical ordering, exact route uniqueness, malformed and
oversized configuration, 1,000 tenants, validity windows, provider/API/operation compatibility,
tenant isolation, zero secret access, zero Token activity, zero network dispatch, immutable plans,
stale revalidation, Spring startup boundaries, no Flyway, no Flowable dependency, no persistence,
worker, scheduler, retry, fallback, load balancing, health routing or approval-state mutation.

## Implementation-head permanent validation

Workflow:

`Approval Platform Validation`

Run:

- run ID: `30209026970`;
- run number: `#644`;
- head: `624df1cd256228ba4531ab993ba9f22b0df6e701`;
- result: `success`;
- Repository hygiene: `success`;
- Java 21 / Maven / PostgreSQL: `success`;
- Vben TypeScript / production build: `success`;
- UniApp TypeScript / H5 / WeChat: `success`;
- Maven aggregate: `719 / 0 / 0 / 0`;
- P4 focused: `45 / 0 / 0 / 0`;
- accumulated M6-A focused: `242`.

Artifacts:

| Artifact | ID | GitHub SHA-256 digest |
| --- | ---: | --- |
| `approval-maven-30209026970` | `8633957756` | `6053ac63e56889c0656c66a430dfd604acab0b3dcfdb1e572e23e4edb6cab82c` |
| `approval-vben-30209026970` | `8633925123` | `9ec93d8c308d9fc301641066c0af0c5d764529db3e6146ff2399fe9bc83596f1` |
| `approval-mobile-30209026970` | `8633916861` | `16bf8e214ad44439e9136ed6f0544d4e1d25cbe78b4be9ce956cc1fee56fb008` |
| `approval-hygiene-30209026970` | `8633908958` | `08c156271beee0328080bb79d4c552d67205b9cc2203fb88d59a3c51a181d556` |

Downloaded Maven ZIP local SHA-256:

`6053ac63e56889c0656c66a430dfd604acab0b3dcfdb1e572e23e4edb6cab82c`

It exactly matches the GitHub digest. The downloaded Maven log records all 20 reactor projects as
`SUCCESS`, `BUILD SUCCESS`, and total Maven time `04:38 min`.

## Retained failure lineage

No failed run was deleted, cancelled, rerun to hide evidence or removed from the history.

### Run #631 — absent optional evidence

- run ID: `30208238782`;
- head: `8d4fbfd039ee1a91401fffca3acd8239ce64d60a`;
- Repository hygiene, Vben and UniApp: `success`;
- Maven: `failure`;
- root cause: absent optional business reference was passed as `null` into canonical serialization;
- fix: commit `11dfa48a36f3d698640adb853354a0914a6ad3d0` introduced a deterministic canonical absence marker;
- Maven artifact ID: `8633697642`;
- GitHub digest and downloaded ZIP SHA-256:
  `a7643d2c1b00a57267a89f9c725d08174971aafe555ea0ff812bca56dc8b3a27`.

### Run #643 — ambiguous evidence ordering

- run ID: `30208864213`;
- head: `343aa053be7f028fa8fe082609c6e3fce1b6bd8e`;
- Repository hygiene, Vben and UniApp: `success`;
- Maven: `failure`;
- root cause: global invalid-configuration validation made the closed `AMBIGUOUS` result unreachable for
  an exact multi-match;
- fix: commit `624df1cd256228ba4531ab993ba9f22b0df6e701` preserves exact `AMBIGUOUS`
  evidence while still rejecting invalid duplicate configuration elsewhere;
- Maven artifact ID: `8633869597`;
- GitHub digest and downloaded ZIP SHA-256:
  `b199f7fcc9bbf5936782fbd032c48ab86754927e30e160ca62be7c7349001e76`.

### Intermediate successful lineage

Run #638 permanently validated the first correction:

- run ID: `30208514621`;
- head: `11dfa48a36f3d698640adb853354a0914a6ad3d0`;
- result: `success`;
- Maven aggregate: `717 / 0 / 0 / 0`;
- P4 focused at that head: `43 / 0 / 0 / 0`;
- Maven artifact ID: `8633822237`;
- GitHub digest and downloaded ZIP SHA-256:
  `aab709d1fb1ce357a8b4fe9aed0c80f528daf56f20fa3ccd64f41d69d5fa6a2c`.

## Permanent architecture boundaries

P4 leaves all of the following unimplemented and blocked:

- Token Acquisition;
- Token Refresh and Token cache;
- AppKey/AppSecret lifecycle;
- production Secret Backend;
- credential material opening during routing;
- production route persistence;
- database route source;
- Flyway migration or migration placeholder;
- Connector execution coordination;
- transport dispatch;
- Connector Worker;
- scheduler, listener or background execution;
- automatic retry;
- recovery or reconciliation worker;
- Provider fallback;
- weighted routing, load balancing or health routing;
- route hot reload, file watch or remote configuration center;
- HTTP, Web, Mobile or management route controls;
- shared audit-platform or observability-exporter semantic change;
- Flowable dependency or M5 source/migration change;
- Approval-State Mutation;
- approve, reject, transfer, withdraw, terminate or migrate action;
- second automatic workflow;
- PR Ready, auto-merge, merge or issue closure.

P4 added no Flyway migration and did not modify M5 PR #58 or M6-B/C/D.

## Document-head validation

This evidence file is the final P4 documentation-only append. Its naturally triggered workflow run is
recorded in PR #67 and Issue #63 after completion so that recording the final run does not create an
unbounded evidence-commit loop.

## Stop condition and next gate

P4 is complete after the evidence-document head receives its natural permanent validation and the PR
and Issue records are updated. Work must stop at that point.

The next explicit gate is M6-A-P5 `PRODUCTION_SECRET_MATERIAL_SOURCE`. It requires a separately chosen
production Secret Backend and new user authorization. It is not implemented by P4.
