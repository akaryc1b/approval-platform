# MySQL 8.4 P3-F4 Fixture Correction Evidence

## Scope

This record retains the natural P3-F4 Pull Request validation failures and their exact append-only fixture corrections. It does not authorize P3-F5, Approval Release Lifecycle, Flowable MySQL, MySQL production support, Ready, merge or Issue closure.

## Failed natural Run #1383

```text
Run: 31367201065 / #1383
implementation Head: 99534232f90930b0eb939fe3e0d00ae218b39a7c
result: failure
classification: TEST_FIXTURE_BUG / REAL_FK_DELETE_ORDER
```

The implementation compiled with zero Checkstyle violations. Java Core, Vben, UniApp, Repository hygiene and Persistence JDBC shards 1, 2 and 3 all succeeded. Persistence JDBC shard 0 failed while running the new real MySQL 8.4 Form Package suite. The final PostgreSQL aggregate evidence job consequently failed because one persistence shard was not successful; it was not an independent PostgreSQL product regression.

`JdbcApprovalFormPackageStoreMySqlIntegrationTest` reported:

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

The first append-only correction was:

```text
a3ccda157e466998415949548c10911b5f6ae274
```

It reversed those two fixture deletes so the draft was removed before the Package. No production file changed.

## Failed natural Run #1384

The first correction received its own natural Pull Request Run:

```text
Run: 31367603507 / #1384
Head: a3ccda157e466998415949548c10911b5f6ae274
result: failure
classification: TEST_FIXTURE_BUG / MUTUAL_DRAFT_PACKAGE_FK_CYCLE
```

Again, Java Core, Vben, UniApp, Repository hygiene and Persistence JDBC shards 1, 2 and 3 succeeded. Shard 0 reached the real P3-F4 suite and reported:

```text
Tests run: 4
Failures: 0
Errors: 3
Skipped: 0
```

The revised teardown now attempted to delete the draft first, and MySQL correctly rejected that operation because the Package has the opposite real foreign key:

```text
ap_form_package_draft_fk
(tenant_id, source_draft_id)
    -> ap_form_design_draft (tenant_id, draft_id)
```

Together, the two retained failures prove the published relation is deliberately cyclic at the relational level:

```text
Draft --published_package_version--> Package
Package --source_draft_id----------> Draft
```

Neither delete ordering alone can dismantle that cycle while both references are populated.

## Correct cycle-aware fixture teardown

The second append-only fixture correction is:

```text
b0c5bc84e1577fc19e5849c86e32014fdcef63d6
```

The test teardown now first removes only the nullable Draft-to-Package edge from test data:

```sql
update ap_form_design_draft
set published_package_version = null
where published_package_version is not null;
```

It then deletes in this order:

```text
delete from ap_form_package
delete from ap_form_design_draft
```

This is test-only cleanup. It does not modify the production schema, disable a constraint, change `ApprovalFormDesignService`, weaken Package immutability or use `FOREIGN_KEY_CHECKS`.

The correction deliberately honors both real foreign keys. The Package-to-Draft edge remains enforced until the Package row itself is removed; the nullable Draft-to-Package edge is cleared first so the relational cycle can be dismantled without bypassing referential integrity.

## Evidence rules

- Runs #1383 and #1384 are retained and are not rerun in place.
- No force push, rebase, empty correction commit or foreign-key bypass is permitted.
- Every correction must receive a new natural Pull Request validation Run on a new Head.
- P3-F4 remains staged until the corrected implementation and its permanent evidence pass naturally.

```text
MYSQL_P3_F4_FORM_PACKAGE_STORE_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
