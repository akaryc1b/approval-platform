# MySQL 8.4 R0 PostgreSQL Coupling Inventory

Status: `R0_COUNTS_AND_PATHS_CAPTURED / MIGRATION_AND_STORE_MANIFEST_PENDING`

Date: `2026-08-07`

Tracking:

- Issue `#91`;
- Draft PR `#92`;
- source `main`: `0cf6572770953a46fe5b16d15ecdff78cf607855`;
- exact successful inventory Head: `9b283ba50d4e6522dc910d50f333720d145b79a8`;
- natural PR Run: `31151113187` / `#1331`.

## 1. Purpose

This record captures generated evidence from the exact branch rather than treating historical
documentation or approximate repository descriptions as a compatibility inventory.

`DatabaseCompatibilityR0BoundaryTest` scans text sources under:

- `server-modules/approval-persistence-jdbc`;
- `server-modules/approval-integration-jdbc`;
- `apps/server`.

Generated `target` content and the scanner's own source are excluded. Counts include production
sources, migrations, configuration, focused tests and repository-owned documentation inside those
module boundaries. They are coupling indicators, not estimates of required code changes.

## 2. Successful R0 token and path counts

Run #1331 emitted every configured category and every matching repository path before completing
successfully.

| PostgreSQL coupling token | Exact occurrences | Exact files | Compatibility decision |
| --- | ---: | ---: | --- |
| `jsonb` | 344 | 91 | MySQL JSON storage, binding, canonical readback and index semantics required |
| `timestamptz` | 365 | 63 | MySQL `datetime(6)` plus explicit UTC and canonical instant policy required |
| `bytea` | 2 | 2 | MySQL BLOB mapping and byte-exact hash/attachment round trip required |
| `ON CONFLICT` | 60 | 40 | each admission/upsert/CAS site requires an operation-specific MySQL protocol |
| `pg_advisory*` | 25 | 22 | advisory-lock sites require bounded MySQL serialization equivalents |
| `SKIP LOCKED` | 5 | 5 | claim ordering, fairness, lease and stale-worker behavior require real MySQL proof |
| `RETURNING` | 11 | 7 | MySQL readback must preserve affected-row, identity and CAS semantics explicitly |
| `PostgreSQLContainer` | 183 | 61 | every PostgreSQL-only fixture needs a proven MySQL equivalent or an explicit portable-test refactor |
| `jdbc:postgresql` | 4 | 4 | configuration and historical-upgrade fixtures require a vendor-selected source |
| `EXPLAIN (FORMAT JSON)` | 3 | 3 | equivalent MySQL `EXPLAIN FORMAT=JSON` plan gates must be defined, not omitted |

The difference from the retained Run #1328 counts is intentional: the successful scanner excludes
its own token literals and generated files, so these are the accepted source-tree counts.

## 3. Highest-coupling paths

### 3.1 JSON

Representative high-density paths include:

- `JdbcApprovalMigrationTenantLineageTamperIntegrationTest.java`: `32` occurrences;
- `V40__create_migration_command_fence_and_claim.sql`: `18`;
- `V47__create_canary_bounded_orchestration.sql`: `18`;
- `JdbcApprovalMigrationEngineDispatchGuardIntegrationTest.java`: `13`;
- `JdbcApprovalCommentStore.java`: `12`;
- `V44__complete_exact_migration_runtime_binding.sql`: `8`.

Disposition: migration DDL and production stores require a reviewed MySQL JSON representation and
canonical string readback. Test-only replacements cannot satisfy production compatibility.

### 3.2 Time and precision

Representative high-density paths include:

- `JdbcApprovalSlaIndexPlanIntegrationTest.java`: `28` `timestamptz` occurrences;
- `m4-sla-execution-index-plan.sql`: `23`;
- `JdbcApprovalMigrationRuntimeBindingCasStoreIntegrationTest.java`: `18`;
- `JdbcApprovalParticipantQueryScaleIntegrationTest.java`: `17`;
- `JdbcApprovalSlaExecutionStoreIntegrationTest.java`: `17`;
- `V31__create_sla_execution_intents_attempts_and_replay.sql`: `13`;
- integration migration `V1__create_integration_inbox_outbox.sql`: `11`;
- `V49__create_ai_approval_assistance_durable_evidence.sql`: `10`.

Disposition: one versioned cross-database instant contract must define UTC, microsecond rounding,
JDBC binding and readback before timestamps participate in hashing, equality, expiry, lease or
replay decisions.

### 3.3 Conflict admission and CAS

`ON CONFLICT` appears across `40` files. The most concentrated production paths are:

- `JdbcApprovalMigrationOrchestrationStore.java`: `5`;
- `JdbcApprovalMigrationReconciliationExecutionStore.java`: `4`;
- `JdbcApprovalMigrationPlanAggregationStore.java`: `3`;
- `JdbcControlledAutomationLineageStore.java`: `3`.

The remaining occurrences cover command admission, exact verification, plan writing, migration
attempts, release evidence, audit state and other stores.

Disposition: a shared textual replacement with `ON DUPLICATE KEY UPDATE` is prohibited. Each site
must declare whether it means no-op admission, replay detection, immutable insert, exact CAS or a
true upsert, and tests must verify MySQL affected-row behavior.

### 3.4 Serialization and claims

PostgreSQL advisory-lock calls appear in `22` files. They protect definition/form/release updates,
instance command fencing, delegation/handover, migration orchestration, aggregation and runtime
binding. `SKIP LOCKED` appears in exactly five paths:

- `JdbcOutboxRepository.java`;
- `JdbcApprovalMigrationAttemptClaimStore.java`;
- `JdbcApprovalNotificationStore.java`;
- `JdbcApprovalSlaExecutionStore.java`;
- `JdbcApprovalMigrationAttemptClaimPlanIntegrationTest.java`.

Disposition: MySQL implementations must prove lock ownership, bounded claims, deterministic
ordering, stale-worker fencing and crash recovery under real InnoDB concurrency.

### 3.5 `RETURNING`

The seven matching paths are:

- `JdbcInboxRepository.java`;
- `JdbcOutboxRepository.java`;
- `JdbcApprovalMessageStore.java`;
- `JdbcApprovalNotificationStore.java`;
- `JdbcApprovalProjectionStore.java`;
- `JdbcApprovalSlaExecutionStore.java`;
- `JdbcApprovalMigrationTenantLineageTamperIntegrationTest.java`.

Disposition: generated identity, row-count and post-update readback semantics must be explicit.
MySQL compatibility cannot depend on connector-specific client flags or ambiguous affected-row
configuration.

### 3.6 PostgreSQL-only fixtures and plan tests

`PostgreSQLContainer` appears in `61` files. The current test surface therefore cannot be called
vendor neutral merely because production code uses Spring JDBC.

PostgreSQL JDBC URLs occur in:

- `apps/server/src/main/resources/application.yml`;
- `JdbcApprovalAssistanceGovernanceHistoryFaultTest.java`;
- `JdbcApprovalMigrationUpgradeIntegrationTest.java`;
- `JdbcApprovalMigrationUpgradeSupport.java`.

JSON plan inspection occurs in:

- `JdbcApprovalMigrationAttemptClaimPlanIntegrationTest.java`;
- `JdbcApprovalSlaExecutionIndexPlanIntegrationTest.java`;
- `JdbcApprovalSlaIndexPlanIntegrationTest.java`.

Disposition: PostgreSQL tests remain permanent. MySQL-specific fixtures and plan assertions must be
added; existing PostgreSQL evidence is not replaced or weakened.

## 4. Run #1331 permanent evidence

All nine physical Jobs completed `success` at exact Head
`9b283ba50d4e6522dc910d50f333720d145b79a8`:

- Java 21 / Maven Core;
- Persistence JDBC shards `0`, `1`, `2`, `3`;
- Maven/PostgreSQL aggregate;
- Vben TypeScript / production build;
- UniApp TypeScript / H5 / WeChat;
- Repository Hygiene.

Shard 3 executed `105 / 0 failures / 0 errors / 0 skipped`; the R0 boundary passed `3/3` and emitted
the exact category/path matrix. The real MySQL 8.4 baseline test in the permanent persistence suite
passed `2/2`.

Exact artifacts:

| Artifact | ID | SHA-256 |
| --- | ---: | --- |
| Maven | `8983375619` | `24786c7cbfdd79d56b115c7dfc089e08083b02ebbbd799e46868038143e38129` |
| Vben | `8983364549` | `107270f4964730d9b585762550210c0fb80c47f87d53273f9736040364bc623c` |
| Mobile | `8983348568` | `53a6bd1ee218568c422c7043cc3a336acf26b22719f081da2cb8ca435a3ca39d` |
| Hygiene | `8983328204` | `ba1d6bdef616fdc9680524f27f2537ff51afdb9e93b9b13f1338f032ddd2a97d` |

This Run proves the restored commitment, PostgreSQL non-regression, trusted vendor/version gate,
real MySQL 8.4 runtime baseline and R0 scanner. It does **not** prove MySQL migrations or product
persistence compatibility.

## 5. Test selection surface

The successful workflow selected `83` persistence classes exactly once across four shards:

- shard 0: `20` classes;
- shard 1: `23` classes;
- shard 2: `17` classes;
- shard 3: `23` classes.

The current suite remains mostly PostgreSQL-specific. P6 must evolve the selection verifier so
required PostgreSQL and MySQL suites are both complete and no test silently disappears behind a
vendor filter.

## 6. Path classification vocabulary

Every matching path must be assigned to an owner and one of these dispositions:

1. `PORTABLE_UNCHANGED` — proven valid and semantically equivalent on PostgreSQL and MySQL;
2. `DIALECT_STATEMENT_PAIR` — bounded PostgreSQL/MySQL statement implementation;
3. `VENDOR_STORE_IMPLEMENTATION` — separate infrastructure implementation behind one application
   port;
4. `MYSQL_TRANSACTION_PROTOCOL` — invariant implemented through locks/CAS/transactions because
   MySQL cannot express the PostgreSQL DDL or statement primitive directly;
5. `POSTGRESQL_TEST_RETAINED_WITH_MYSQL_EQUIVALENT` — vendor-specific PostgreSQL test retained plus
   a separate MySQL test proving the same product invariant;
6. `RELEASE_BLOCKER` — no safe equivalent has yet been proven.

## 7. Remaining R0 evidence

R0 remains incomplete until the branch records:

- every versioned SQL migration and Java migration through governed `V50`, with path, bytes and
  SHA-256;
- every production JDBC implementation path, with bytes and SHA-256;
- every persistence test source path and selected shard;
- Flowable database initialization and upgrade ownership;
- the accepted PostgreSQL checksum-preserving migration-location strategy;
- the accepted MySQL migration lineage strategy;
- an owner and target stage for every release blocker.

A new deterministic manifest test is the next R0 slice. It will emit exact immutable file identities
from the branch and bind them to a natural workflow artifact before migration translation begins.

`R0_COUNTS_ARE_NOT_MYSQL_SUPPORT`

`MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED`
