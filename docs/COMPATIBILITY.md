# Compatibility Matrix

This matrix describes the combinations continuously validated by the repository and the explicit compatibility boundaries of the M2 product model.

## Build and runtime baseline

| Area | Supported / validated | Status |
| --- | --- | --- |
| Java | Java 21 | required and continuously validated |
| Spring | Spring Boot 4 | current server baseline |
| Workflow engine | Flowable 8 through the platform Engine SPI | current implementation |
| Database | PostgreSQL 16 | current reference production database and permanent CI baseline |
| Database target | MySQL 8.4 | original commitment restored in Issue #91; active blocking workstream, not yet supported |
| Node.js | Node.js 22 | CI baseline |
| Package manager | pnpm 10.33.4 | CI baseline |
| PC | Vue 3 + Vben `web-ele` + Element Plus | type-check and production build validated |
| Mobile | UniApp Vue 3 + Unibest + Wot UI | type-check, H5 and WeChat Mini Program builds validated |
| JVM architecture | x86-64 Linux CI; standard Java 21-compatible container/host | supported by JVM/container distribution |

The original PostgreSQL plus MySQL commitment is active again. MySQL 8.4 must not be used for production until Issue #91 completes the vendor-specific Flyway lineage, JDBC semantics, Flowable execution, concurrency/fault matrix, operations rehearsals and permanent dual-database workflow. Do not deploy the current branch against MySQL and assume PostgreSQL locks, JSON, timestamps, constraints or indexes are equivalent.

The governing workstream is [`database/MYSQL_8_4_PRODUCTION_COMPATIBILITY.md`](database/MYSQL_8_4_PRODUCTION_COMPATIBILITY.md).

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
- PostgreSQL remains the source of truth for the currently accepted platform projections, immutable artifacts, audit events, idempotency and Outbox state.
- MySQL 8.4 must gain an immutable vendor-selected migration history representing the same logical platform invariants without rewriting PostgreSQL checksums.
- Supported database identity is resolved from trusted JDBC metadata and startup configuration; clients cannot select a dialect.
- MySQL acceptance requires InnoDB, `utf8mb4`, strict SQL mode, UTC session semantics and proven microsecond timestamp behavior.
- Product outcomes, tenant isolation, idempotency, CAS, replay, audit hashes, lease fencing and recovery decisions must remain equivalent across vendors.
- Flowable tables are private to the engine adapter. Application code and operational reporting must not query or modify `ACT_*` tables directly.
- A database restore must keep platform tables and Flowable tables from the same consistent recovery point.
- Release, effective-release and instance-version hashes must be preserved byte-for-byte during migration or restore.

Until the dual-database gate is complete, PostgreSQL-specific constructs such as `jsonb`, `timestamptz`, `bytea`, `ON CONFLICT`, advisory locks, predicate indexes and PostgreSQL query plans remain explicit MySQL blockers rather than assumed portable behavior.

## Connector compatibility

The core product is independent from RuoYi, Sa-Token and third-party office suites. Connectors may map authentication, organization, files, messages and callbacks, but they may not change Approval DSL, Form Schema, version binding, permission or idempotency semantics.

RuoYi-Vue-Plus 5.X/6.X, generic REST, DingTalk and Feishu are integration targets. Only combinations covered by their connector-specific tests and deployment documentation should be treated as supported for production.

RuoYi MySQL menu examples validate only the host application's menu schema. They are not evidence that Approval Platform persistence supports MySQL.

## Upgrade policy

Before upgrading Java, Spring Boot, Flowable, PostgreSQL, MySQL, Vben, UniApp or a connector:

1. run the full Maven core reactor;
2. run every required PostgreSQL persistence shard;
3. after Issue #91 reaches the dual-database gate, run every required MySQL persistence shard;
4. run PC type-check and production build;
5. run UniApp type-check, H5 build and WeChat build;
6. run deterministic DSL/Form/UI/hash golden tests;
7. run deployment, effective-release, transfer and runtime exact-version tests;
8. verify existing immutable Release Packages still reproduce the expected artifacts;
9. review vendor-specific migration, rollback, backup and restore procedures in `docs/OPERATIONS.md` and the database compatibility record.
