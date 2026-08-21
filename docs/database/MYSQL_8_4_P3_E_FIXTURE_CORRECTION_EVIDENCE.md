# MySQL 8.4 P3-E Fixture Correction Evidence

## Failed natural gate retained

The first formal P3-E natural `pull_request` validation is retained as a failed acceptance attempt:

```text
Run: 31234512290
Number: #1365
Head: be304bbe19ea2c780057c418c4d44f0385fa2dc0
Conclusion: failure
Same-Head rerun: none
```

Job results:

| Job | ID | Result |
| --- | ---: | --- |
| Vben TypeScript / production build | `93044485247` | success |
| UniApp TypeScript / H5 / WeChat | `93044485248` | success |
| Java 21 / Maven core | `93044485249` | success |
| Repository hygiene | `93044485262` | success |
| Persistence JDBC / shard 0 | `93044485295` | success |
| Persistence JDBC / shard 1 | `93044485281` | success |
| Persistence JDBC / shard 2 | `93044485276` | failure |
| Persistence JDBC / shard 3 | `93044485321` | success |
| Java 21 / Maven / PostgreSQL aggregate | `93044783257` | failure downstream |

The failing shard completed:

```text
Tests run: 59
Failures: 1
Errors: 6
Skipped: 0
```

Its retained partial Artifact is:

```text
Artifact ID: 9014943135
Bytes: 79365
SHA-256: 6f8616aa74daaacd4df01c24e2444b151c2d164822a4b1b00bb1d143dea2d97b
```

## Root-cause classification

```text
TEST_FIXTURE_BUG / INCOMPLETE_RELEASE_PROVENANCE_GRAPH
```

The P3-E integration fixture created `InstanceProjection` values containing a complete release snapshot:

```text
release version / package hash
form package version / hash
UI schema version / hash
engine definition identity
```

The MySQL V50 schema correctly enforces that the instance release tuple references an existing governed `ap_approval_release_package`. That package in turn requires the complete design, form, UI and compiled-artifact lineage.

The fixture inserted only the legacy `ap_definition_version` projection before creating the instance. It did not insert:

```text
ap_form_definition
ap_form_ui_schema
ap_form_design_draft
ap_form_package
ap_approval_design_draft
ap_approval_definition
ap_approval_compiled_artifact
ap_approval_release_package
```

MySQL therefore rejected the instance insert through:

```text
ap_approval_instance_release_package_fk
```

Six P3-E scenarios failed at the same missing prerequisite. The lock-through-rollback scenario then timed out waiting for a latch that the failed first transaction never reached; that assertion failure was downstream of the same fixture defect, not evidence that the named lock escaped rollback.

## Correction

The correction is test-only and append-only:

1. add `MySqlApprovalProjectionProvenanceFixture`;
2. seed the exact relational provenance graph for both test tenants;
3. use strict inserts and all real MySQL foreign keys;
4. clean fixture rows in child-to-parent order;
5. bind test instance constants to the fixture's exact hashes and versions;
6. retain the complete release snapshot instead of nulling it or disabling constraints.

The correction does not:

```text
modify production SQL
modify MySQL V50 or PostgreSQL migrations
disable FOREIGN_KEY_CHECKS
remove or weaken any foreign key
replace the complete snapshot with nulls
change executable-server binding
add retry or fail-open behavior
```

## Pending correction gate

The correction may be accepted only after a new natural PR Run at a new Head proves:

- all nine physical Jobs successful;
- all nine P3-E real-MySQL scenarios successful;
- exact deterministic test selection;
- all four final Artifacts independently downloaded and verified;
- no same-Head rerun, rebase, force push or direct `main` update.

Until then:

```text
MYSQL_P3_E_FIXTURE_CORRECTION_PENDING
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
```
