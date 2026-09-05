# MySQL 8.4 P2 Migration Baseline Strategy

Status: `P2_CURRENT_SCHEMA_BASELINE_IMPLEMENTED / SEMANTIC_EQUIVALENCE_PENDING`

Date: `2026-08-07`

Tracking:

- Issue `#91`;
- Draft PR `#92`;
- PostgreSQL source Head: `0cf6572770953a46fe5b16d15ecdff78cf607855`;
- accepted PostgreSQL migration history: immutable `V1` through `V50`;
- MySQL first supported release target: governed schema version `V50`.

## Decision

MySQL has never been a released or accepted Approval Platform production database. There is no
legitimate MySQL installation carrying historical Flyway versions `V1` through `V49`.

The MySQL lineage therefore starts with one immutable current-schema baseline:

```text
V50__Baseline_approval_platform
```

This is intentionally different from copying PostgreSQL's historical evolution into fifty
synthetic MySQL migrations. A fake step-by-step history would imply support for intermediate MySQL
schemas that were never shipped, tested or operable.

PostgreSQL migrations remain byte-for-byte unchanged. Future logical schema changes after this
baseline must provide both vendor implementations at the same governed version, beginning with
`V51`.

## Baseline construction

The V50 Java migration embeds a compressed, checksummed MySQL 8.4 DDL baseline generated from the
exact PostgreSQL V1-V50 schema inventory. It:

- executes only against the explicitly selected MySQL Flyway location;
- runs outside a claimed transactional DDL boundary because MySQL DDL auto-commits;
- creates InnoDB tables using `utf8mb4_0900_as_cs`;
- maps PostgreSQL UUID storage to case-sensitive `varchar(36)`;
- maps `jsonb` to native MySQL `json`;
- maps `timestamptz` to UTC-governed `datetime(6)`;
- maps `bytea` to `longblob`;
- translates PostgreSQL checks to MySQL 8.4 checks where an exact expression exists;
- records an explicit checksum over the uncompressed baseline SQL.

The migration does not alter or relocate any PostgreSQL migration.

## Current limitation

A schema that migrates cleanly is not yet production compatibility. The first baseline intentionally
marks PostgreSQL procedural triggers, partial unique indexes, functional/GIN indexes and operation-
specific upsert/lock behavior as unresolved compatibility work.

Those gaps remain release blockers until P2/P3/P4 provide and test MySQL equivalents for:

- append-only and immutable-evidence enforcement;
- nullable generated-key replacements for PostgreSQL partial uniqueness;
- functional/generated-column indexes;
- application/JDBC statement dialects;
- advisory-lock and row-claim protocols;
- JSON canonical readback;
- exact timestamp rounding before hashing and replay comparison;
- affected-row and duplicate-key semantics;
- Flowable and executable-server behavior.

The V50 baseline may be rewritten on this unmerged Draft branch while these blockers are being
resolved. It becomes immutable only after formal acceptance and merge.

## Upgrade contract

Before MySQL production support is declared:

1. a clean MySQL 8.4 database must migrate directly to V50;
2. the resulting schema must pass structural and semantic comparison against the accepted logical
   PostgreSQL schema;
3. backup/restore of a V50 MySQL database must preserve platform and Flowable evidence together;
4. future V51+ upgrades must be tested from accepted MySQL V50 fixtures;
5. Flyway must reject mixed PostgreSQL/MySQL migration locations and wrong-vendor startup.

There is no supported MySQL downgrade below V50 and no claim that a PostgreSQL database can be
converted in place to MySQL. Cross-vendor data migration, if ever required, is a separate governed
export/import project.

`MYSQL_LINEAGE_STARTS_AT_GOVERNED_V50`

`CLEAN_SCHEMA_IS_NOT_SEMANTIC_EQUIVALENCE`

`MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED`
