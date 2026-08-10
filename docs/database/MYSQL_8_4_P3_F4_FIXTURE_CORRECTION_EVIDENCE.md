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

The revised teardown attempted to delete the draft first, and MySQL correctly rejected that operation because the Package has the opposite real foreign key:

```text
ap_form_package_draft_fk
(tenant_id, source_draft_id)
    -> ap_form_design_draft (tenant_id, draft_id)
```

Together, Runs #1383 and #1384 prove the published relation is deliberately cyclic at the relational level:

```text
Draft --published_package_version--> Package
Package --source_draft_id----------> Draft
```

Neither delete ordering alone can dismantle that cycle while both references are populated.

## Failed natural Run #1385

An evidence-only append-only Head was created to retain the first two failure facts before the second fixture correction was ready:

```text
Run: 31367839212 / #1385
Head: 20a8c7fe0774d623533187b35533df57233d394a
result: failure
classification: KNOWN_INHERITED_TEST_FIXTURE_BUG / MUTUAL_DRAFT_PACKAGE_FK_CYCLE
```

This Head intentionally still contained the first delete-order correction and therefore inherited the same Package-to-Draft FK failure as #1384. It was not rerun in place and is retained as a natural failed Head. Core, Vben, UniApp, Repository hygiene and Persistence JDBC shards 1, 2 and 3 again succeeded; shard 0 failed in the F4 fixture teardown.

## Second correction and failed natural Run #1387

The second test-only correction changed teardown to clear the nullable Draft-to-Package reference before deleting either side of the cycle:

```text
b0c5bc84e1577fc19e5849c86e32014fdcef63d6
```

The fixture attempted:

```sql
update ap_form_design_draft
set published_package_version = null
where published_package_version is not null;
```

and then:

```text
delete from ap_form_package
delete from ap_form_design_draft
```

The correction and its accumulated evidence were advanced to:

```text
Head: 8eb579802ae65dc2a6458ec2a5b42d29d4acc65b
Run: 31368083214 / #1387
result: failure
classification: TEST_FIXTURE_BUG / PUBLISHED_PAIR_CHECK_INVARIANT
```

The real MySQL suite again reached all four selected methods but the three methods after the first failed during `@BeforeEach`. This time the foreign-key cycle was no longer the first failing operation. MySQL rejected the single-column update with:

```text
Check constraint 'ap_form_design_draft_published_pair_check' is violated
```

This matches the domain invariant in `FormDesignDraft`:

```text
status == PUBLISHED  <=>  publishedPackageVersion != null
```

A published draft cannot retain `PUBLISHED` while clearing only `published_package_version`, and a non-published draft cannot retain a package version.

Run #1387 therefore provides additional positive evidence that the MySQL baseline preserves the published-pair state invariant instead of allowing partial publication metadata.

## Third cycle- and CHECK-aware fixture correction

The third append-only test-only correction is:

```text
fc4718ada507cf2dd68ccd20c09b2c535708bd65
```

It changes only fixture cleanup. Published test rows are first moved to a valid non-published pair in one SQL statement:

```sql
update ap_form_design_draft
set status = 'VALIDATED',
    published_package_version = null
where published_package_version is not null;
```

The teardown then removes rows in this order:

```text
delete from ap_form_package
delete from ap_form_design_draft
delete from ap_form_ui_schema
delete from ap_form_definition
```

This sequence is deliberate:

1. the atomic status/package-version update satisfies `ap_form_design_draft_published_pair_check`;
2. clearing `published_package_version` removes the Draft-to-Package FK edge;
3. deleting the Package removes the Package-to-Draft FK edge;
4. the Draft can then be deleted normally;
5. no foreign key or CHECK constraint is disabled or weakened.

This is strictly test fixture teardown. It does not change the production schema, `FormDesignDraft`, `ApprovalFormDesignService`, the PostgreSQL implementation, the MySQL Package Store, Package immutability, publication semantics or any database constraint.

## Forbidden shortcuts retained

None of the corrections use or authorize:

```text
FOREIGN_KEY_CHECKS
constraint drop/disable
schema mutation
production status rewrite
automatic retry
same-Head rerun
force push
rebase
empty correction commit
```

The failures are retained because they prove the real MySQL clean schema enforces both relational provenance edges and the published-pair CHECK invariant.

## Evidence rules

- Runs #1383, #1384, #1385 and #1387 are retained and are not rerun in place.
- Every correction uses a new append-only Head.
- P3-F4 production code has not been changed by these fixture corrections.
- P3-F4 remains staged until the corrected implementation and permanent evidence pass a new natural Pull Request validation Run.

```text
MYSQL_P3_F4_FORM_PACKAGE_STORE_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
```
