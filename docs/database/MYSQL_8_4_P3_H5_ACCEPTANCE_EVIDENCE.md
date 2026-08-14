# MySQL 8.4 P3-H5 — Migration Exact Verification Acceptance Evidence

## Decision

P3-H5 product implementation evidence is accepted at exact implementation Head `37cda995ca6098914e8a6472d4dd898e6b94e5c8` and natural Run `31762708230 / #1452`.

The commit carrying this record also adds a fail-closed canonical OSV finding-identity check at the R2B/H5 compatibility facade. That security-evidence hardening does not change H5 database behavior, but the current documented Head must receive a new natural successful validation before the H5 marker may be consumed by the next database gate.

```text
POSTGRESQL_16_SUPPORTED
MYSQL_P3_H4_MIGRATION_ENGINE_EXECUTION_PROVEN
MYSQL_P3_H5_MIGRATION_EXACT_VERIFICATION_IMPLEMENTATION_ACCEPTED
H5_DOCUMENTED_HEAD_VALIDATION_REQUIRED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
NO_READY
NO_MERGE
NO_DEPLOYMENT
```

## Exact implementation identity

```text
main:        779c4fbd09dcf17d45cc523e725222797cc5cb85
formal:      agent/mysql-8-4-production-compatibility
impl Head:   37cda995ca6098914e8a6472d4dd898e6b94e5c8
PR:          #92 remains Open + Draft + unmerged
Issue:       #91 remains Open
Run:         31762708230 / #1452
Conclusion:  completed / success
Physical:    9 / 9 success
```

## Accepted H5 scope

H5 accepts PostgreSQL/MySQL-equivalent persistence for the existing D4 exact-verification authority only:

- trusted database-vendor factory selection;
- real H2 -> H3 -> H4 -> H5 lineage;
- complete immutable H4 request/outcome relational, payload and hash validation;
- server-derived exact-target and mismatch classifications;
- successful snapshot self-proof through `m5-exact-engine-snapshot-v1`;
- request-bound read-failure self-proof through `m5-verification-read-failure-v1`;
- stored D4 request/evidence hash recomputation before strict replay;
- Attempt relational `engine_outcome` consistency;
- deterministic first-finalization concurrency;
- atomic evidence, Attempt transition, event and audit rollback;
- tenant, worker, Attempt revision, Fence revision and immutable-lineage fail-closed rejection;
- PostgreSQL rejected-finalization semantic correction and permanent regression.

H5 adds no Flyway version, public endpoint, scheduler, retry, D6 reconciliation launch, D7 orchestration, direct `ACT_*` access, deployment, traffic mutation or production-promotion authority.

## Natural validation trail

The failed Heads were not rerun in place.

```text
#1448 / 31696972899 / de04fd43e62e00ef5ffa77336f54e698d99bc4f0
  product matrix green; Hygiene failed on reviewed E2 graph drift

#1450 / 31761012236 / e4c08cac36b1c0d5afc1982ee6b630269cf1f717
  product matrix green; Hygiene exposed current OSV identity-set drift

#1451 / 31762358914 / 3e3b69326382c5b034523572dca8dcc5948cce4a
  product matrix green; Hygiene failed on missing compatibility export

#1452 / 31762708230 / 37cda995ca6098914e8a6472d4dd898e6b94e5c8
  9 / 9 physical Jobs success
```

The graph and OSV corrections preserve every reviewed historical identity, retain additive findings as `UNRESOLVED`, reject deletion/duplication, add no suppression, exception or severity downgrade, and do not close PRB-16, PRB-17 or Issue #97.

## Independent Persistence reconstruction

The final Maven artifact was downloaded and parsed independently.

```text
selected persistence classes:             143
unique selected classes:                  143
duplicate selections:                       0
Surefire report classes:                  142
expected abstract without report:           1
non-abstract selected without report:       0
extra reports:                               0
selection coverage:                     exact

shard 0: 34 classes
shard 1: 35 classes
shard 2: 35 classes
shard 3: 39 classes

Persistence JDBC:
579 / 0 failures / 0 errors / 0 skipped
aggregate reported persistence time: 1019.176 s
```

Focused H5 and retained PostgreSQL regression classes:

```text
JdbcApprovalMigrationEngineExecutionRejectedFinalizationIntegrationTest  1 / 0 / 0 / 0
JdbcApprovalMigrationExactVerificationStoreFactoryTest                    4 / 0 / 0 / 0
JdbcApprovalMigrationExactVerificationStoreMySqlContractTest              7 / 0 / 0 / 0
JdbcApprovalMigrationExactVerificationStoreMySqlIntegrationTest           8 / 0 / 0 / 0
JdbcApprovalMigrationEngineSnapshotHashTest                               4 / 0 / 0 / 0
MySqlV50H5ExactVerificationSchemaContractTest                             1 / 0 / 0 / 0
focused total                                                             25 / 0 / 0 / 0
```

## Independently verified implementation Artifacts

All four #1452 ZIPs were independently downloaded. Local byte size and SHA-256 exactly match GitHub metadata, and each archive passes complete ZIP integrity verification.

| Artifact | ID | Bytes | SHA-256 | Entries |
| --- | ---: | ---: | --- | ---: |
| Maven | `9205239180` | `1154375` | `e37c8fcea6d73ab5a7513310b71793dfb9301ecf252b67f54d3a8ecfbbdf3d5b` | `296` |
| Vben | `9205209381` | `18904` | `97f359ae2b62f847ee1a62b793544effc77a781db379b28abb6cca9502bff1d9` | `8` |
| Mobile | `9205196547` | `9820` | `f2982bee0f95e4f42c92f103f06015def938ab59d90ac99a7787266103e52a28` | `5` |
| Hygiene | `9205246838` | `152566` | `749e98c99c5a4cfc3243cb7f74b3e120645b2cdc390920429edfeb1d34fcdecc` | `6` |

## Append-only discipline

H5 used no same-Head rerun, empty trigger commit, amend, rebase, force push, force merge, direct main push or deployment. Every failed Head remains permanent evidence and every correction moved forward through ordinary commits or ordinary Merge Commits.

## Remaining scope

H5 does not close PRB-15 or Issue #91. Remaining database production-equivalence work includes the later migration protocol stores and orchestration gates, complete clean-install/historical-upgrade/restore coverage, executable Flowable/server acceptance, dual-vendor performance and plan evidence, backup/restore and operations evidence, final formal acceptance, Merge Commit, and natural post-main validation.

After the current documented Head is naturally green, the next database gate must be selected from the live remaining inventory rather than inferred from an outdated PR body.

```text
MYSQL_P3_H5_MIGRATION_EXACT_VERIFICATION_PROVEN_AFTER_CURRENT_HEAD_SUCCESS
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PRB_15_REMAINS_OPEN
ISSUE_91_REMAINS_OPEN
PR_92_REMAINS_OPEN_DRAFT
AI_IS_NOT_AN_OPERATOR
```
