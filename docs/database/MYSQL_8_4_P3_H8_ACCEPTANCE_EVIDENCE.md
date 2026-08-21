# MySQL 8.4 P3-H8 AI Durable Evidence Acceptance Evidence

## Status and authorization boundary

This record accepts the bounded P3-H8 implementation candidate:

```text
P3-H8 AI Durable Evidence and Retention-Tombstone Semantic Equivalence
PostgreSQL 16 -> MySQL 8.4
```

Status:

```text
IMPLEMENTATION_ACCEPTED
DOCUMENTED_HEAD_VALIDATION_REQUIRED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```

Tracking:

- Issue: `#91`;
- Draft PR: `#92`;
- formal branch: `agent/mysql-8-4-production-compatibility`;
- non-triggering staging branch: `agent/mysql-8-4-p3-h8-ai-evidence-staging`;
- current main integrated into the candidate: `4af817a46dcbefee7e84b78e022e87f24e0ffcda`;
- exact successful implementation Head: `49a99012fadb897ac0d3e66ef74f778949b7852e`;
- exact implementation Run: `32022978249 / #1518 / success`.

This record supersedes only the `STAGING / NOT_ACCEPTED` status of the source H8 contract after
the documented Head itself completes one fresh natural validation. It does not accept MySQL 8.4
as production supported, does not complete PRB-15, and does not authorize PR #92 to leave Draft or
be merged.

## Accepted scope

H8 supplies the first MySQL 8.4 implementation of the existing hash-only P4 durable evidence
authority:

```text
ApprovalAssistanceDurableEvidenceStore
JdbcApprovalAssistanceDurableEvidenceStoreFactory
JdbcMySqlApprovalAssistanceDurableEvidenceStore
ApprovalAssistanceProductionConfiguration -> trusted factory
MySqlV50AiEvidenceGuards
```

Database selection is derived only from trusted JDBC metadata. Browser, Mobile, HTTP, Connector,
Event, Template, Provider and business payloads cannot select the database vendor.

The MySQL implementation preserves the PostgreSQL canonical evidence and event hash domains.
It changes only JDBC value conversion, transaction serialization, physical guard installation and
vendor-selected composition.

## Accepted MySQL protocol

For one canonical tenant-scoped evidence record, the accepted MySQL authority proves:

1. evidence identity, request-evidence identity and evidence-content identity are serialized by
   transaction-bound named locks acquired in deterministic lexical order;
2. exact initial storage produces one immutable evidence row, one `STORED` event and one
   trigger-materialized `ACTIVE` revision-one state;
3. exact replay returns the original evidence and event identity without a duplicate write;
4. same request with different evidence and same evidence identity with changed content fail as
   conflicts without partial rows;
5. tenant identity remains case-sensitive and cannot leak across tenants;
6. retention-expired deletion is rejected before `retention_until`;
7. an allowed early-deletion reason produces one `TOMBSTONED` revision-two event and state;
8. concurrent store and tombstone operations produce one transition and one exact replay;
9. outer rollback removes evidence, event and trigger-materialized state together;
10. evidence and events reject UPDATE and DELETE, while state accepts only the exact event-driven
    revision-one to revision-two transition;
11. every read revalidates the exact current event/state linkage and fails closed on divergence;
12. canonical UTC microsecond instants are used before MySQL persistence participates in hashes;
13. no raw Provider input/output, Prompt body, advisory text, JSON payload, binary content, Secret,
    retry worker, scheduler, autonomous deletion, Flowable access or approval command is added.

The implementation contains no `INSERT IGNORE`, `REPLACE`, broad `ON DUPLICATE KEY UPDATE`,
`FOREIGN_KEY_CHECKS`, PostgreSQL SQL token, automatic retry or affected-row ambiguity.

## MySQL V50 physical authority

The governed MySQL V50 Java migration remains one clean-install snapshot and now installs:

```text
1 event tombstone_hash column/check constraint
9 AI evidence/event/state triggers
```

The inserted event is the only authority that creates or advances the state row. The tombstone
event must match exactly one active revision-one predecessor state. The after-insert trigger then
performs the exact revision/hash CAS to materialize revision two.

The reviewed guard bundle is bound by:

```text
Guard SQL SHA-256:
0da3fc83406c3002789c278a8ce9a60f2d16abf33031b8e5d43cef92b2156d6a

Guard Java hash:
2024742531

Governed MySQL V50 checksum:
-547102957
```

The H7 server-owned trigger-creation preflight remains unchanged. H8 does not issue `SET GLOBAL`,
`SET PERSIST`, disable binary logging, grant `SUPER`, alter the application privilege model, or
modify any applied PostgreSQL migration.

## Validation trail

### Run #1516 — static boundary defect

```text
Run ID:     32020251373
Run number: 1516
Head:       dccc5137d82968eb5542b1d911863da945e9c6fb
Conclusion: failure
```

The first candidate failed only because the Node boundary expected the unchecked local variable
name `evidence`, while production deliberately validated it as `exact` before persistence.
The correction required `requireCanonicalEvidence(evidence)` and the subsequent use of `exact`.
No product boundary was weakened and the failed Head was not rerun.

### Run #1517 — combined contract and trigger corrections

```text
Run ID:     32020623338
Run number: 1517
Head:       e94ec8f0ad06490680e85ac70ac899d48acfd6e5
Conclusion: failure
```

Maven Core, Vben, Mobile and Persistence JDBC shards 0, 1 and 2 succeeded. The retained failures
were:

- a literal SQL source marker was incorrectly interpreted as a regular expression;
- the R0 boundary still asserted pre-rebaseline generated documentation text;
- the raw-content schema test classified count metadata such as `observation_count` as raw text;
- the tombstone BEFORE INSERT trigger used one opaque compound rejection instead of separately
  proving exact predecessor-state authority and evidence completeness.

One correction Head fixed the complete failure set. Run #1517 was not rerun.

### Run #1518 — implementation acceptance

```text
Run ID:     32022978249
Run number: 1518
Head:       49a99012fadb897ac0d3e66ef74f778949b7852e
Event:      pull_request
Conclusion: success
Jobs:       9 / 9 success
```

| Job | ID | Result |
| --- | ---: | --- |
| Repository hygiene | `95366345860` | success |
| UniApp TypeScript / H5 / WeChat | `95366345924` | success |
| Persistence JDBC / shard 0 | `95366345962` | success |
| Vben TypeScript / production build | `95366345980` | success |
| Persistence JDBC / shard 2 | `95366346007` | success |
| Java 21 / Maven core | `95366346026` | success |
| Persistence JDBC / shard 1 | `95366346037` | success |
| Persistence JDBC / shard 3 | `95366346053` | success |
| Java 21 / Maven / PostgreSQL aggregate | `95367239605` | success |

No same-Head rerun, workflow dispatch, empty trigger commit, force push, rebase, squash, direct
`main` push, Ready transition, merge, deployment or production promotion occurred.

## Independent Maven reconstruction

```text
Java Core:                              1469 / 0 / 0 / 0
Persistence JDBC:                        626 / 0 / 0 / 0
Combined:                               2095 / 0 / 0 / 0

selected persistence test classes:       157
unique selected classes:                  157
Surefire report classes:                  156
expected abstract without report:          1
abstract class:
  AbstractJdbcApprovalMigrationProtocolIntegrationTest
duplicate selection count:                 0
non-abstract selected without report:       0
extra report classes:                       0
selection coverage:                     exact
aggregate reported persistence time: 1236.778 s
```

Deterministic shard distribution:

```text
shard 0: 41
shard 1: 38
shard 2: 37
shard 3: 41
unique: 157 / 157
```

The H8 suites were selected exactly once:

| Suite | Shard | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| `MySqlV50H8AiEvidenceSchemaContractTest` | 1 | 2 | 0 | 0 | 0 |
| `JdbcApprovalAssistanceDurableEvidenceStoreFactoryTest` | 2 | 2 | 0 | 0 | 0 |
| `JdbcApprovalAssistanceDurableEvidenceStoreMySqlContractTest` | 1 | 1 | 0 | 0 | 0 |
| `JdbcApprovalAssistanceDurableEvidenceStoreMySqlIntegrationTest` | 3 | 7 | 0 | 0 | 0 |
| retained `JdbcApprovalAssistanceDurableEvidenceStoreIntegrationTest` | 3 | 14 | 0 | 0 | 0 |

The synchronized documentation-authority regression also passed:

```text
DatabaseCompatibilityR0BoundaryTest: 3 / 0 / 0 / 0
```

## Independently verified Artifacts

Each ZIP was downloaded independently. Local bytes and SHA-256 exactly match GitHub metadata, every
ZIP passed full integrity verification, and all four are bound to branch
`agent/mysql-8-4-production-compatibility` and Head
`49a99012fadb897ac0d3e66ef74f778949b7852e`.

| Artifact | ID | Bytes | ZIP entries | SHA-256 |
| --- | ---: | ---: | ---: | --- |
| Maven | `9286136473` | `1218568` | `324` | `ed8aeb4913ef848ec803c0721c78712939d8c2830c525ca2590765285fe7d325` |
| Hygiene | `9286142824` | `153188` | `6` | `528c2f4602d8cf205bde5686ce7076ffd11a1a0944efbd290ba035fea846905b` |
| Vben | `9286072139` | `18892` | `8` | `87b7c380a799296d9e902e31882a3b1eeb5becda62293e29c31f9a9dd2e4285a` |
| Mobile | `9286055116` | `9810` | `5` | `bcf4ad02440a9c8d54b55bf779287275af9208d1293436cbd7028f7eb94edb28` |

The recorded expiration time is:

```text
2026-11-15T11:02:09Z
```

## Remaining PRB-15 boundary

H8 does not complete MySQL production equivalence. At minimum, the following remain outside this
acceptance:

- MySQL governance-history aggregation over immutable AI evidence;
- remaining PostgreSQL-only JDBC authorities;
- historical MySQL upgrade and restore rehearsals;
- future migration contract;
- real executable-server and Flowable scenarios on MySQL 8.4;
- complete permanent dual-vendor CI;
- query-plan, performance, backup, restore, rollback, fault and operations acceptance;
- formal PRB-15 acceptance, Ready, Merge Commit and natural post-main verification.

The next source-driven candidate is the read-only governance-history authority:

```text
ApprovalAssistanceGovernanceHistoryQuery
JdbcApprovalAssistanceGovernanceHistoryQuery
```

It still uses PostgreSQL aggregate `FILTER` syntax, is constructed directly by the production
composition root, and has no MySQL peer or trusted metadata factory. That inventory does not become
accepted H9 work until this H8 documented Head succeeds.

PR #92 remains Open, Draft and unmerged. Issues #91, #82 and #62 remain Open.

## Documented-Head rule

This file creates a new formal branch Head after the successful implementation Run. Therefore Run
`32022978249 / #1518` proves the exact implementation Head, not the commit containing this record.

H8 becomes formally proven only after one fresh natural `pull_request` Run succeeds at the exact
documented Head, all nine physical Jobs succeed, and the resulting Maven, Hygiene, Vben and Mobile
Artifacts are independently verified again. No same-Head rerun is authorized for a product or
contract failure.

```text
MYSQL_P3_H8_AI_DURABLE_EVIDENCE_IMPLEMENTATION_ACCEPTED
H8_IMPLEMENTATION_HEAD_GREEN
H8_DOCUMENTED_HEAD_VALIDATION_REQUIRED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PRB_15_REMAINS_OPEN
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
AI_IS_NOT_AN_OPERATOR
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
```
