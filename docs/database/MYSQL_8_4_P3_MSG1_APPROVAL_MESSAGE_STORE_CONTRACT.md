# MySQL 8.4 P3-MSG1 Approval Message Store Contract

Status: `IMPLEMENTATION_STAGED / ACCEPTANCE_PENDING`

Tracking:

- Issue `#91` remains Open;
- Draft PR `#92` remains Open / Draft / Unmerged;
- staging branch: `agent/mysql-8-4-p3-msg1-approval-message-store-staging`;
- PostgreSQL authority: `JdbcApprovalMessageStore`;
- application authority: `ApprovalMessageStore`.

## Bounded scope

P3-MSG1 converts only the platform-owned approval message center Store. It adds:

```text
JdbcApprovalMessageStoreFactory
JdbcMySqlApprovalMessageStore
ApprovalPlatformConfiguration -> trusted factory binding
```

It does not convert Comment, Attachment, Task Query, Participation Query, Timeline Query,
Notification, SLA or Operational Failure stores. It does not change PostgreSQL migrations or the
accepted PostgreSQL Store.

## Required semantic equivalence

The MySQL implementation must preserve:

- tenant- and recipient-scoped message reads;
- exact `URGE`, `COPY` and `MENTION` metadata round-trip;
- strict deduplication on `(tenant_id, dedup_key)` without `INSERT IGNORE`, `REPLACE` or broad
  `ON DUPLICATE KEY UPDATE` behavior;
- duplicate-key classification that accepts only an existing exact tenant/dedup tuple and fails
  closed for unrelated identity collisions;
- deterministic message pagination and copied-instance keyword lookup;
- unread counts, one-message read, all-message read and deterministic receipt ordering;
- exactly one `firstRead=true` result under concurrent read admission;
- exact UUID text, native JSON and UTC `datetime(6)` boundaries;
- nearest-microsecond canonicalization, including carry;
- case-sensitive tenant identity;
- transaction participation and outer rollback restoration;
- trusted vendor selection from JDBC metadata only.

MySQL replaces PostgreSQL `UPDATE ... RETURNING` with one transaction-bound affected-row update
followed by an exact tenant/recipient/message read. The update predicate includes `read_at is null`,
so concurrent readers produce one first reader and authoritative replay for every later reader.

## Test-first matrix

```text
JdbcApprovalMessageStoreFactoryTest
JdbcApprovalMessageStoreMySqlContractTest
JdbcApprovalMessageStoreMySqlIntegrationTest
JdbcApprovalMessageIntegrationTest                 # PostgreSQL retained regression
```

The real MySQL matrix covers strict deduplication, unrelated identity collision rejection,
concurrent duplicate admission, concurrent first-read fencing, message and copied-instance reads,
JSON/UUID/time round-trip, case-sensitive tenant isolation, mark-all-read, receipts and outer
transaction rollback.

## Non-claims

```text
MYSQL_P3_MSG1_APPROVAL_MESSAGE_STORE_STAGED
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
