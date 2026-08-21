# MySQL 8.4 P3-CMT1 Approval Comment Store Contract

Status: `IMPLEMENTATION_STAGED / ACCEPTANCE_PENDING`

Tracking:

- Issue `#91` remains Open;
- Draft PR `#92` remains Open / Draft / Unmerged;
- staging branch:
  `agent/mysql-8-4-p3-cmt1-approval-comment-store-staging`;
- PostgreSQL authority: `JdbcApprovalCommentStore`;
- application authority: `ApprovalCommentStore`.

## Bounded scope

P3-CMT1 converts only the platform-owned Approval Comment Store. It adds:

```text
JdbcApprovalCommentStoreFactory
JdbcMySqlApprovalCommentStore
ApprovalPlatformConfiguration -> trusted factory binding
```

It does not convert Notification, SLA, Operational Failure, Task Query,
Participation Query or Timeline Query stores. It does not change PostgreSQL
migrations, the accepted PostgreSQL Store, the comment application service or
the public API contract.

## Required semantic equivalence

The MySQL implementation must preserve:

- strict tenant, instance and comment identity fencing;
- one-level reply parent-author projection;
- deterministic comment ordering by creation time and comment identity;
- immutable revision ordering by revision number;
- `PARTICIPANTS` and `MENTIONED_ONLY` server-side audience filtering;
- author and exact-mentioned-user visibility without client-side filtering;
- attachment-reference discovery across current and historical revisions;
- attachment readability determined by the current comment audience;
- additional participant discovery from approval messages and governed task
  collaboration;
- optimistic version compare-and-swap for edit and delete;
- tombstone deletion with complete deleted metadata;
- comment mutation and immutable revision insertion in one transaction;
- rollback of the comment mutation if revision persistence fails;
- participation in an already active surrounding transaction;
- exact UUID text, native JSON and UTC `datetime(6)` boundaries;
- nearest-microsecond canonicalization, including carry;
- case-sensitive tenant identity;
- trusted vendor selection from JDBC metadata only.

MySQL replaces PostgreSQL `jsonb_exists` with exact
`JSON_CONTAINS(..., JSON_QUOTE(...))` predicates. JSON arrays remain native
MySQL JSON documents. No application-side visibility filtering is permitted.

## Concurrency and failure boundary

A valid edit or delete requires:

```text
tenant_id
instance_id
comment_id
status = ACTIVE
version = expectedVersion
```

Exactly one concurrent writer may advance the row. A stale writer receives the
stable `APPROVAL_COMMENT_CONCURRENT_MODIFICATION` conflict and may not append a
revision.

Create, edit and delete use transaction-bound comment-plus-revision
persistence. Duplicate or otherwise failed revision evidence must roll back the
preceding comment mutation. No automatic retry or silent overwrite is added.

## Test-first matrix

```text
JdbcApprovalCommentStoreFactoryTest
JdbcApprovalCommentStoreMySqlContractTest
JdbcApprovalCommentStoreMySqlLifecycleIntegrationTest
JdbcApprovalCommentStoreMySqlAccessIntegrationTest
JdbcApprovalCommentIntegrationTest                 # PostgreSQL retained regression
```

The real MySQL 8.4 matrix covers:

- create, edit, delete and immutable revision round-trip;
- public, author-only and exact-mentioned-user audience decisions;
- reply parent-author restoration;
- current and historical attachment references;
- message and collaboration participant discovery;
- concurrent same-version single-winner CAS;
- failed-revision rollback after a successful row update;
- surrounding transaction rollback;
- UUID, JSON, Unicode and microsecond time round-trip;
- case-sensitive tenant isolation;
- trusted PostgreSQL/MySQL factory selection.

## Forbidden shortcuts

```text
NO_JSONB_ON_MYSQL_PATH
NO_APPLICATION_SIDE_AUDIENCE_FILTER
NO_INSERT_IGNORE
NO_REPLACE_INTO
NO_BROAD_ON_DUPLICATE_KEY_UPDATE
NO_FOREIGN_KEY_CHECKS_BYPASS
NO_POSTGRESQL_STORE_WEAKENING
NO_POSTGRESQL_MIGRATION_EDIT
NO_AUTOMATIC_RETRY
```

## Non-claims

```text
MYSQL_P3_CMT1_APPROVAL_COMMENT_STORE_STAGED
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
PR_92_REMAINS_OPEN_DRAFT
ISSUE_91_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
NO_READY
NO_MAIN_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
```
