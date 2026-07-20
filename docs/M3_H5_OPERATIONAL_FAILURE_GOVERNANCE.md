# M3 H5 Operational Failure Governance

## Scope

Increment H5 provides one management view over operational failures while preserving each owning state machine and responsibility boundary.

The unified view covers:

- notification delivery intents in `DEAD_LETTER`;
- business callback Outbox messages in `DEAD`;
- detect-only consistency checks in `FAILED`;
- connector failures classified under their owning notification or business callback responsibility.

H5 does not create a fourth connector-failure queue, merge status enums, delete failure evidence or access Flowable tables.

## Durable evidence

Flyway `V23__create_operational_failure_governance.sql` adds:

- business Outbox replay metadata;
- `ap_outbox_delivery_attempt`;
- tenant, status, connector and attempt-history indexes;
- a PostgreSQL trigger that records every `IN_FLIGHT` transition to `PENDING`, `DELIVERED` or `DEAD`.

Notification attempts remain in `ap_notification_delivery_attempt`. Consistency executions remain in `ap_consistency_check`. The unified JDBC query uses `UNION ALL` only for management visibility.

## Governed replay

Replay is routed to the existing owning state machine:

- notifications use `ApprovalNotificationStore.replayDeadLetter`;
- business callbacks use a conditional `ap_outbox.status = 'DEAD'` transition;
- consistency failures start a new detect-only `ApprovalConsistencyService` run and retain the original failed check.

Single and batch commands are idempotent. Batches accept between one and 50 unique items and return a result for every item. Concurrent Outbox replay allows only one state transition. Existing attempts and original failed records are retained.

Successful replay creates the versioned `OPERATIONAL_FAILURE_REPLAYED` audit event using schema `approval.operational-failure`.

## Permissions and clients

Management authorities are separated:

- `approval.management.operational-failure.read`;
- `approval.management.operational-failure.replay`.

The PC management console provides filters, paging, attempt evidence, single replay and bounded batch replay. H5 and WeChat participant clients do not expose tenant-wide operational queue administration.

## Validation evidence

H5 code head `bf0907fef2eb186d0ae1e80e2197036db9b0eafd` passed permanent workflow run `29761511616`:

- Repository hygiene: passed;
- Java 21 / Maven / PostgreSQL: passed;
- `JdbcApprovalOperationalFailureIntegrationTest`: 7 tests, 0 failures, 0 errors, 0 skipped;
- `JdbcApprovalConsistencyIntegrationTest`: 6 tests, 0 failures, 0 errors, 0 skipped;
- `JdbcApprovalAuditIntegrationTest`: 8 tests, 0 failures, 0 errors, 0 skipped;
- `JdbcApprovalNotificationIntegrationTest`: 10 tests, 0 failures, 0 errors, 0 skipped;
- `JdbcApprovalCommentIntegrationTest`: 15 tests, 0 failures, 0 errors, 0 skipped;
- approval PostgreSQL module: 122 tests, 0 failures, 0 errors, 0 skipped;
- full 16-module Maven reactor: `BUILD SUCCESS`;
- Vben TypeScript and production build: passed;
- UniApp TypeScript, H5 and WeChat builds: passed.

Permanent CI exposed and verified the PC template label-index typing correction before the code head became green.

This document is the single H5 governance record and is not modified after its initial commit.
