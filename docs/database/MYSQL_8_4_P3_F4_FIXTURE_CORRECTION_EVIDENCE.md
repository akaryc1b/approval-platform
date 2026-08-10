# MySQL 8.4 P3-F4 Fixture Correction Evidence

## Scope

This record retains the first natural P3-F4 Pull Request validation failure and the exact append-only correction. It does not authorize P3-F5, Approval Release Lifecycle, Flowable MySQL, MySQL production support, Ready, merge or Issue closure.

## Failed natural Run

```text
Run: 31367201065 / #1383
implementation Head: 99534232f90930b0eb939fe3e0d00ae218b39a7c
result: failure
classification: TEST_FIXTURE_BUG / REAL_FK_DELETE_ORDER
```

The implementation compiled with zero Checkstyle violations. Java Core, Vben, UniApp, Repository hygiene and Persistence JDBC shards 1, 2 and 3 all succeeded. Persistence JDBC shard 0 failed while running the new real MySQL 8.4 Form Package suite. The final PostgreSQL aggregate evidence job consequently failed because one persistence shard was not successful; it was not an independent PostgreSQL product regression.

## Exact failure

`JdbcApprovalFormPackageStoreMySqlIntegrationTest` was selected on shard 0 and reported:

```text
Tests run: 4
Failures: 0
Errors: 3
Skipped: 0
```

The first method completed. The remaining three methods failed in `@BeforeEach reset()` before their test bodies executed.

The fixture originally attempted:

```text
delete from ap_form_package
delete from ap_form_design_draft
```

After the first test published a Package, the draft contained `published_package_version` and the real MySQL baseline enforced:

```text
ap_form_design_draft_package_fk
(tenant_id, form_key, published_package_version)
    -> ap_form_package
```

MySQL therefore correctly rejected deletion of the referenced parent Package row.

This failure is positive evidence that the governed draft-to-package relational foreign key remains enabled. It is not a production-store defect and must not be bypassed with `FOREIGN_KEY_CHECKS` or constraint weakening.

## Append-only correction

Correction commit:

```text
a3ccda157e466998415949548c10911b5f6ae274
```

Only the test fixture cleanup order changed:

```text
delete from ap_form_design_draft
delete from ap_form_package
```

All remaining cleanup statements and every P3-F4 production file remained unchanged.

The correction deliberately deletes the child draft row before its parent Package row, honoring the real relational graph instead of disabling referential integrity.

## Evidence rules

- Run #1383 is retained and is not rerun in place.
- No force push, rebase or empty correction commit is permitted.
- The correction must receive a new natural Pull Request validation Run on a new Head.
- P3-F4 remains staged until the corrected implementation and its permanent evidence pass naturally.

```text
MYSQL_P3_F4_FORM_PACKAGE_STORE_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
