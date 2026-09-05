# MySQL 8.4 P3-H3 — Static Contract Correction Evidence

## Status

```text
MYSQL_P3_H3_MIGRATION_ATTEMPT_CLAIM_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```

This record permanently retains the second natural P3-H3 failure and its forward-only assertion correction. It does not mark H3 proven and does not authorize PR Ready, merge, Issue closure or MySQL production support.

## Failed Head

```text
Head:       e723eb7fa87b14cb128f167398bbb932f49880e0
Run:        31483447770 / #1426
Conclusion: failure
```

Run #1426 was a natural pull-request workflow run. The failed Head is not rerun. No empty commit, amend, rebase, force push or history rewrite is used.

## Physical result

```text
Java 21 / Maven core:              success
Persistence JDBC / shard 0:        success
Persistence JDBC / shard 1:        success
Persistence JDBC / shard 2:        success
Persistence JDBC / shard 3:        failure
Repository hygiene:                success
Vben TypeScript / production build success
UniApp TypeScript / H5 / WeChat:   success
Java 21 / Maven / PostgreSQL:      aggregate inherited shard-3 failure
```

## Focused H3 evidence at the failed Head

The final Maven evidence artifact for #1426 was independently downloaded and its Surefire XML was inspected.

```text
JdbcApprovalMigrationAttemptClaimStoreFactoryTest:
2 / 0 / 0 / 0, 0.071 s
selected shard 1

JdbcApprovalMigrationAttemptClaimStoreMySqlIntegrationTest:
6 / 0 / 0 / 0, 16.457 s
selected shard 2

JdbcApprovalMigrationAttemptClaimStoreMySqlContractTest:
3 tests, 1 assertion failure, 0 errors, 0 skipped, 0.305 s
selected shard 3
```

Therefore all six real MySQL claim/lease methods passed naturally before the static assertion correction. There is no H3 product-semantic failure at #1426.

## Exact failure

Classification:

```text
STATIC_CONTRACT_FALSE_POSITIVE / UNBOUNDED_ON_CONFLICT_TOKEN
```

The contract test lower-cased the complete Java source and rejected the bare substring:

```text
on conflict
```

The MySQL Store does not contain PostgreSQL `ON CONFLICT` SQL. The bare substring crossed a normal Java identifier/token boundary at:

```text
MigrationAttemptClaimConflictException conflict(...)
```

The tail of `Exception` followed by the method name `conflict` contains the character sequence `on conflict`, so an unbounded string scan misclassified ordinary Java source as PostgreSQL SQL.

## Forward-only correction

Correction-2 modifies only the static contract assertion. Production source and real MySQL tests are unchanged.

The PostgreSQL-upsert prohibition is retained but narrowed to actual SQL forms:

```text
on conflict (
on conflict do 
on conflict on constraint 
```

The existing explicit prohibitions remain for:

```text
::text
::jsonb
as jsonb
for share
pg_advisory
foreign_key_checks
insert ignore
replace into
on duplicate key update
```

This correction does not weaken the MySQL dialect boundary; it removes a token-boundary false positive while continuing to reject PostgreSQL `ON CONFLICT` statement forms.

## Non-claims

This correction does not itself authorize:

- H3 PROVEN status;
- D3/D4/D6/D7 compatibility;
- Flowable execution compatibility;
- MySQL production support;
- PR Ready or merge;
- Issue #91 closure.

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_H2_MIGRATION_ATTEMPT_PROVISIONING_PROVEN
MYSQL_P3_H3_MIGRATION_ATTEMPT_CLAIM_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
