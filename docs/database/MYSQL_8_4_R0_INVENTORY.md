# MySQL 8.4 R0 PostgreSQL Coupling Inventory

Status: `R0_IN_PROGRESS / GENERATED_COUNTS_CAPTURED / EXACT_PATH_MATRIX_PENDING`

Date: `2026-08-07`

Tracking:

- Issue `#91`;
- Draft PR `#92`;
- source `main`: `0cf6572770953a46fe5b16d15ecdff78cf607855`;
- inventory Head: `933b90034b2a16a7243c235324556d16827e66ef`;
- natural PR Run: `31150857717` / `#1328`.

## Purpose

This record captures generated evidence from the exact branch rather than treating historical
documentation or approximate repository descriptions as a compatibility inventory.

The counts below were emitted by `DatabaseCompatibilityR0BoundaryTest` while scanning text sources
under:

- `server-modules/approval-persistence-jdbc`;
- `server-modules/approval-integration-jdbc`;
- `apps/server`.

Generated `target` content is excluded. Counts include production sources, migrations,
configuration, focused tests and repository-owned compatibility documentation inside those module
boundaries. They are coupling indicators, not estimates of required code changes.

## Initial exact token counts

| PostgreSQL coupling token | Exact occurrences at Run #1328 | Compatibility decision |
| --- | ---: | --- |
| `jsonb` | 346 | MySQL JSON storage, binding, canonical readback and index semantics required |
| `timestamptz` | 367 | MySQL `datetime(6)` plus explicit UTC and canonical instant policy required |
| `bytea` | 4 | MySQL BLOB mapping and byte-exact hash/attachment round trip required |
| `ON CONFLICT` | 62 | each admission/upsert/CAS site requires an operation-specific MySQL protocol |
| `pg_advisory*` | 26 | advisory-lock sites require bounded MySQL serialization equivalents |
| `SKIP LOCKED` | 7 | claim ordering, fairness, lease and stale-worker behavior require real MySQL proof |
| `RETURNING` | 13 | MySQL readback must preserve affected-row, identity and CAS semantics explicitly |

Run #1328 stopped at the first missing classification after recording the rows above. The failure
is retained as useful R0 evidence: the scanner discovered `RETURNING` usage that the first
compatibility record had not explicitly classified. This document closes that documentation gap;
it does not implement the MySQL equivalent.

The following scanner categories remain to be emitted completely by the corrected natural Run:

- `PostgreSQLContainer` fixture sites;
- PostgreSQL JDBC URL sites;
- JSON-formatted PostgreSQL `EXPLAIN` sites;
- exact matching file paths for every token category.

## Reactor and test surface observed by Run #1328

The same natural Run reported:

- persistence production Java sources compiled: `81`;
- persistence test Java sources compiled: `89`;
- persistence main resources copied: `54`;
- isolated M6-F migration resources copied: `1`;
- unchanged Web, Mobile and Repository Hygiene gates: success;
- PostgreSQL persistence shards 1 and 2: success at the time the R0 failure was read;
- shard 3 executed all existing selected persistence behavior successfully and failed only the new
  R0 classification assertion.

The final selected-test count, migration names and production Store paths remain mandatory R0
outputs. They must be captured from a successful corrected Run before R0 is accepted.

## PostgreSQL-specific categories requiring path-level classification

Every matching path must be assigned to an owner and one of these dispositions:

1. `PORTABLE_UNCHANGED` — proven valid and semantically equivalent on PostgreSQL and MySQL;
2. `DIALECT_STATEMENT_PAIR` — bounded PostgreSQL/MySQL statement implementation;
3. `VENDOR_STORE_IMPLEMENTATION` — separate infrastructure implementation behind one application
   port;
4. `MYSQL_TRANSACTION_PROTOCOL` — invariant implemented through locks/CAS/transactions because
   MySQL cannot express the PostgreSQL DDL or statement primitive directly;
5. `POSTGRESQL_ONLY_TEST_REPLACED_BY_EQUIVALENT_MYSQL_PROOF` — vendor-specific test retained plus
   a separate MySQL test proving the same product invariant;
6. `RELEASE_BLOCKER` — no safe equivalent has yet been proven.

A generic conversion such as replacing every `ON CONFLICT` with one shared `ON DUPLICATE KEY`
fragment is prohibited. The affected-row and mutation semantics differ by operation and must be
reviewed separately.

## Required remaining R0 evidence

R0 remains incomplete until the branch records:

- every versioned SQL migration and Java migration through governed `V50`;
- every production JDBC implementation path;
- every coupling-token path and count;
- every selected persistence test class and shard assignment;
- every PostgreSQL-only test requiring a MySQL equivalent;
- Flowable database initialization and upgrade ownership;
- the accepted PostgreSQL checksum-preserving migration-location strategy;
- the accepted MySQL migration lineage strategy;
- an owner and target stage for every release blocker.

`R0_COUNTS_ARE_NOT_MYSQL_SUPPORT`

`MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED`
