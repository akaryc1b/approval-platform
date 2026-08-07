# MySQL 8.4 P2 Staging Evidence

Status: `P2_STAGED_FOR_NATURAL_PR_VALIDATION`

Date: `2026-08-07`

- source Draft PR branch Head before staging merge: `d383301b251c64438aadd0cbf64016fd282e3491`;
- staging branch: `agent/mysql-8-4-p2-staging`;
- PostgreSQL V1–V50 files are unchanged;
- MySQL begins with the current governed `V50__Baseline_approval_platform` lineage;
- the compressed baseline is split into nine repository resources and bound by an explicit checksum;
- clean migration is exercised against a real MySQL 8.4 Testcontainers instance;
- MySQL support remains blocked until structural, JDBC, concurrency, Flowable, operations and dual-database CI gates complete.

`STAGED_SCHEMA_IS_NOT_PRODUCTION_SUPPORT`
