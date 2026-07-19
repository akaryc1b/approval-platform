# Compatibility Matrix

This matrix describes the combinations continuously validated by the repository and the explicit compatibility boundaries of the M2 product model.

## Build and runtime baseline

| Area | Supported / validated | Status |
| --- | --- | --- |
| Java | Java 21 | required and continuously validated |
| Spring | Spring Boot 4 | current server baseline |
| Workflow engine | Flowable 8 through the platform Engine SPI | current implementation |
| Database | PostgreSQL; CI integration tests use PostgreSQL 16 | reference production database |
| Node.js | Node.js 22 | CI baseline |
| Package manager | pnpm 10.33.4 | CI baseline |
| PC | Vue 3 + Vben `web-ele` + Element Plus | type-check and production build validated |
| Mobile | UniApp Vue 3 + Unibest + Wot UI | type-check, H5 and WeChat Mini Program builds validated |
| JVM architecture | x86-64 Linux CI; standard Java 21-compatible container/host | supported by JVM/container distribution |

MySQL 8 is a future compatibility target and is not part of the current release gate. Do not deploy M2 against MySQL and assume PostgreSQL semantics, locks, JSON behavior or indexes are equivalent.

## Product protocol versions

| Protocol | Current version | Compatibility rule |
| --- | --- | --- |
| Approval DSL | `1.0` | immutable published definitions retain exact DSL and compiler version |
| Form Schema | `1.0` | immutable published Form versions retain exact field semantics |
| UI Schema | `1.0` with additive composite-section fields | legacy flat sections remain readable through compatibility constructors/defaults |
| Form component descriptor | component version `1` | unknown type/version/property is rejected; unsupported clients use safe read-only fallback only for previously accepted content |
| Form Package | integer product version | exact Form/UI versions and hashes are bound atomically |
| Release Package | integer product version | exact DSL, Form Package, compiler, BPMN/DMN and metadata hashes are bound atomically |
| Artifact transfer | `APPROVAL_DSL_EXPORT_V1`, `APPROVAL_RELEASE_PACKAGE_EXPORT_V1` | closed request/envelope/payload fields; unknown versions are rejected |
| Preflight | `approval-release-preflight-v1` identity | stale or different preflight hashes cannot publish/deploy |
| Batch simulation report | versioned deterministic report identity | report hash binds DSL, Form/UI and scenario results |

Additive Java/TypeScript fields are not automatically wire-compatible. New fields must define defaults, hashing behavior, persistence round-trip tests and cross-client rendering behavior.

## UI Schema and renderer matrix

| Capability | PC | H5 | WeChat Mini Program | Server authority |
| --- | --- | --- | --- | --- |
| Flat sections | yes | yes | yes | validated |
| Recursive sections up to depth 4 | yes | yes | yes | validated |
| Stable sibling order | yes | yes | yes | hashed/validated |
| Controlled section visibility | yes | yes | yes | schema validated |
| `EDITABLE` / `READONLY` / `HIDDEN` | yes | yes | yes | submission/revision enforced |
| `readonlySummary` | yes | yes | yes | permission-reducing and enforced |
| Base field components | yes | yes | yes | business value type validated |
| Business/user/department selectors | host-adapted text-compatible renderer | platform-adapted or read-only fallback | platform-adapted or read-only fallback | closed registry/type compatibility |
| Unknown accepted historical component | read-only fallback | read-only fallback | read-only fallback | cannot be newly published |

A client may use a platform-specific control, but it may not change the field's server-side business type or permission semantics.

## Management authentication matrix

| Source | Status | Required deployment boundary |
| --- | --- | --- |
| Authenticated Servlet Principal + container roles | production default | host authentication maps canonical Approval authorities |
| Trusted permission header | explicit opt-in | gateway strips inbound header, authenticates caller, injects header and blocks direct service access |
| Disabled permission interceptor | local development only | never use in production |

Canonical management authorities are:

- `approval.management.read`;
- `approval.management.design`;
- `approval.management.publish`;
- `approval.management.deploy`;
- `approval.management.activate`;
- `approval.management.transfer`;
- `approval.management.admin`.

## Database and migration compatibility

- Flyway migrations are append-only. Never edit a migration that has been applied to an environment.
- PostgreSQL is the source of truth for platform projections, immutable artifacts, audit events, idempotency and Outbox state.
- Flowable tables are private to the engine adapter. Application code and operational reporting must not query or modify `ACT_*` tables directly.
- A database restore must keep platform tables and Flowable tables from the same consistent recovery point.
- Release, effective-release and instance-version hashes must be preserved byte-for-byte during migration or restore.

## Connector compatibility

The core product is independent from RuoYi, Sa-Token and third-party office suites. Connectors may map authentication, organization, files, messages and callbacks, but they may not change Approval DSL, Form Schema, version binding, permission or idempotency semantics.

RuoYi-Vue-Plus 5.X/6.X, generic REST, DingTalk and Feishu are integration targets. Only combinations covered by their connector-specific tests and deployment documentation should be treated as supported for production.

## Upgrade policy

Before upgrading Java, Spring Boot, Flowable, PostgreSQL, Vben, UniApp or a connector:

1. run the full Maven reactor including PostgreSQL/Testcontainers;
2. run PC type-check and production build;
3. run UniApp type-check, H5 build and WeChat build;
4. run deterministic DSL/Form/UI/hash golden tests;
5. run deployment, effective-release, transfer and runtime exact-version tests;
6. verify existing immutable Release Packages still reproduce the expected artifacts;
7. review migration, rollback and backup procedures in `docs/OPERATIONS.md`.
