# MySQL 8.4 P2 Staging Evidence

Status: `P2_CLEAN_INSTALL_PROVEN / HISTORICAL_UPGRADE_AND_PRODUCT_PARITY_PENDING`

Date: `2026-08-07`

Tracking:

- Issue: `#91`;
- Draft PR: `#92`;
- source `main`: `0cf6572770953a46fe5b16d15ecdff78cf607855`;
- exact proven branch Head: `e416dce7664fd45665e13194d5563766368aee12`;
- natural PR workflow Run: `31163929901` / `#1351`;
- workflow conclusion: `success`;
- PR remains Draft and no merge is authorized.

## 1. Scope proven by Run #1351

Run #1351 proves a clean installation of the current governed platform schema on a real MySQL
8.4 Testcontainers instance. It does not prove historical MySQL upgrades or full product runtime
compatibility.

The MySQL history uses an explicit Java migration at logical version `50` and a governed compressed
schema snapshot split across nine repository resources. PostgreSQL migrations `V1` through `V50`
remain unchanged and continue to be selected through their existing PostgreSQL history.

The clean baseline contains `285` source statements. The reviewed execution plan executes `281`
statements and skips exactly four clean-baseline-only historical operations:

- three empty V32 release-event backfill operations;
- one exact repeated historical declaration of
  `uk_approval_task_tenant_task` with the same active definition.

Any unlisted duplicate index, changed index definition, missing governed translation or unexpected
SQL error remains fail-closed.

## 2. Real MySQL 8.4 migration evidence

`MySqlFlywayCleanMigrationIntegrationTest` completed:

```text
Tests run: 6
Failures: 0
Errors: 0
Skipped: 0
Time: 33.087 s
```

The six permanent assertions prove:

1. Flyway reaches exact governed version `50` with one applied MySQL baseline migration;
2. every platform `ap_*` table uses InnoDB and `utf8mb4_0900_as_cs`;
3. eight governed relational foreign keys exist in `information_schema`;
4. comment, audit and process-migration evidence columns that must be required are `NOT NULL`;
5. governed comment/audit uniqueness and notification deduplication constraints exist;
6. `flyway_schema_history` contains no failed entry.

The separate real-server baseline test
`ApprovalDatabaseVendorMySqlIntegrationTest` completed `2 / 0 / 0 / 0`, proving exact MySQL 8.4
identity together with the required character, collation, UTC, isolation and strict-mode settings.

## 3. Preserved schema invariants

The MySQL V50 normalization layer retains or reconstructs, rather than silently drops:

- actual named foreign keys for task, message, comment, attachment and form-submission ownership;
- comment lifecycle checks, tenant/comment uniqueness and deletion metadata consistency;
- audit schema/version/sequence/hash non-nullability, checks and tenant-scoped uniqueness;
- required migration-attempt outcome/failure classifications;
- both SLA timestamp checks with schema-unique MySQL constraint names;
- full notification deduplication tuple semantics through a deterministic generated binary SHA-256
  key; a digest collision fails closed as a uniqueness conflict;
- case-sensitive identifiers and evidence material under `utf8mb4_0900_as_cs`.

No migration disables `FOREIGN_KEY_CHECKS`, weakens PostgreSQL migration files or claims a prefix
index is equivalent to a full unique business key.

## 4. Complete workflow evidence

All nine physical Jobs completed `success`:

| Job | Job ID | Result |
| --- | ---: | --- |
| Java 21 / Maven core | `92820325077` | success |
| Persistence JDBC / shard 0 | `92820325114` | success |
| Persistence JDBC / shard 1 | `92820325102` | success |
| Persistence JDBC / shard 2 | `92820325175` | success |
| Persistence JDBC / shard 3 | `92820325151` | success |
| Java 21 / Maven / PostgreSQL aggregate | `92820888865` | success |
| Vben TypeScript / production build | `92820325236` | success |
| UniApp TypeScript / H5 / WeChat | `92820325136` | success |
| Repository hygiene | `92820325089` | success |

The Maven aggregate independently reported:

```text
selected test classes: 89
Surefire report classes: 88
selected abstract classes without reports: 1
tests: 366
failures: 0
errors: 0
skipped: 0
selection coverage: exact
duplicate selection count: 0
non-abstract selected classes without reports: 0
```

## 5. Independently verified artifacts

Every permanent artifact was downloaded independently after workflow completion. The locally
calculated SHA-256 exactly matches the GitHub artifact digest.

| Artifact | ID | GitHub and independently verified SHA-256 |
| --- | ---: | --- |
| `approval-maven-31163929901` | `8988261820` | `3284ad21a601577f55adb1a6dbcad0728d438aaf2475a2a65b69cc45ddf4e101` |
| `approval-vben-31163929901` | `8988240842` | `582a3829a4d136c867e32fedc7713f1300aa242c7ccd0d8db68ae85bf7499be1` |
| `approval-mobile-31163929901` | `8988217493` | `d4169d753afd6a3118bbad6b212bac7b7b1d4adc376f1ff83e8332a3df5fc936` |
| `approval-hygiene-31163929901` | `8988192690` | `a5ca11ec595626aafe7713f4e7f5432716cebc5aefb7d3bad5c4dc01533c3410` |

## 6. Explicit remaining blockers

This evidence closes only the clean-current-schema part of P2. The following remain mandatory:

- a reviewed historical MySQL upgrade lineage and fixtures through governed checkpoints;
- repeated Flyway validation and checksum stability across those checkpoints;
- full MySQL JDBC implementations for PostgreSQL-specific SQL, JSON, timestamps, UUIDs, conflict
  admission, CAS, locks, bounded claims, `RETURNING` and query-plan gates;
- semantic and concurrency equivalence for tenant isolation, idempotency, audit, Inbox/Outbox,
  leases, migration evidence, AI evidence and controlled automation;
- real executable-server and Flowable initialization/execution on MySQL 8.4;
- complete permanent PostgreSQL and MySQL test selections in the single workflow;
- backup, restore, rollback, fault, security and performance rehearsals;
- formal acceptance and natural post-main dual-database verification.

Therefore the current product status remains:

```text
POSTGRESQL_16_SUPPORTED
MYSQL_8_4_CLEAN_INSTALL_PROVEN
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```

`STAGED_SCHEMA_IS_NOT_PRODUCTION_SUPPORT`

`MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED`
