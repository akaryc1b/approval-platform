# M3 H4 Platform Consistency Governance

## Status

Increment H4 extends PR #54 on branch `agent/m3-collaboration-delegation-and-notification-hardening`.

The PR remains open, Draft and based directly on `main`. H4 does not enable repair, auto-merge, direct Flowable database access or branch-history rewriting.

## Scope

H4 adds detect-only consistency checks over platform-owned approval projections and evidence. The check engine reports findings and suggested actions, but never changes responsibility, approval results, historical evidence, task state or workflow-engine state.

The implementation reads only `ap_*` platform tables. It does not read or modify Flowable tables.

## Persistence

Flyway `V22__create_consistency_findings.sql` adds:

- `ap_consistency_check` for tenant-scoped check executions;
- `ap_consistency_finding` for immutable findings;
- explicit check status, check type and severity constraints;
- tenant/request uniqueness for idempotent check execution;
- indexes for tenant/time/status, check/type/severity and affected aggregates.

A check records:

- check ID;
- tenant ID;
- requesting operator;
- request ID and trace ID;
- tenant scope;
- running, completed or failed status;
- start and completion times;
- finding count;
- bounded failure details when the scanner itself fails.

A finding records:

- finding ID and check ID;
- check type and severity;
- affected aggregate type and ID;
- detection time;
- structured details;
- a suggested governed action.

## Detect-only checks

`JdbcApprovalConsistencyStore` evaluates eight platform relationships.

### Instance and task projections

It detects:

- running instances without an active projected task;
- terminal instances that still have active projected tasks.

Suggested remediation is limited to inspection and replay through existing application services or Engine SPI. Direct workflow-table updates are prohibited.

### Delegation evidence

It detects active delegation assignments with:

- missing platform tasks;
- non-pending projected tasks;
- projected assignee and delegate mismatches;
- rule-scope or definition mismatches.

No assignment is silently rewritten or cancelled.

### Employee handover evidence

It detects active handover assignments with:

- missing platform tasks;
- non-pending projected tasks;
- projected assignee and successor mismatches;
- policy principal or successor mismatches.

Any future replay must use the existing handover service and engine/projection ports.

### Collaboration policy evidence

It detects:

- active policies attached to non-pending tasks;
- active policies with no active participants;
- unreachable vote thresholds;
- unreachable weighted thresholds;
- terminal policies that retain pending participants.

Unreachable decision thresholds are classified as critical. The scanner never modifies participants, thresholds or decisions.

### Notification delivery evidence

It detects:

- connector or email delivery marked delivered without a successful attempt;
- invalid dead-letter evidence;
- intent attempt counts that disagree with attempt rows;
- expired processing locks.

Notification intents, attempts and dead letters remain separate from the business callback Outbox. H4 does not replay them automatically.

### Comment revision evidence

It detects:

- comments with no revision evidence;
- current revision numbers that disagree with the maximum structured revision;
- active comments whose latest revision is delete;
- deleted comments whose latest revision is not delete.

Historical revision and tombstone evidence is never rewritten.

### Attachment references

It validates current-comment and historical-revision attachment references for:

- missing attachments;
- unbound attachments;
- instance mismatches;
- current-comment author/uploader mismatches.

The scanner cannot relink or delete attachments.

### Audit and business evidence

It detects edit and delete comment revisions without the expected versioned audit action for the same comment and revision.

The scanner does not synthesize, backdate or alter audit evidence.

## Application and API contract

`ApprovalConsistencyService` provides:

- idempotent tenant-scope check execution;
- paginated check history;
- paginated and filtered findings;
- a versioned `CONSISTENCY_CHECK_EXECUTED` audit event containing check ID, scope, status, finding count and `detectOnly=true`.

The service uses a lowercase SHA-256 request hash required by the durable idempotency guard.

Management APIs are:

- `POST /api/approval/management/consistency/checks`;
- `GET /api/approval/management/consistency/checks`;
- `GET /api/approval/management/consistency/checks/{checkId}/findings`.

Fail-closed authorities are separated:

- `approval.management.consistency.read`;
- `approval.management.consistency.run`.

There is no repair endpoint in H4.

## PC operations workspace

The existing operations page is now a real detect-only consistency workspace. It provides:

- check execution and history;
- status filtering;
- finding counts and severity summaries;
- finding type and severity filtering;
- affected aggregates;
- structured details and suggested actions;
- explicit warnings that automatic repair is unavailable.

H5 and WeChat do not expose tenant-wide consistency administration.

## PostgreSQL/Testcontainers validation

Code head `757232e0d872d2c021d8ce081fd4f85bc47cb6cb` passed permanent workflow run `29755921232`.

The permanent run verified:

- repository hygiene: passed;
- Java 21 / Maven / PostgreSQL: passed;
- `JdbcApprovalConsistencyIntegrationTest`: 6 tests, 0 failures, 0 errors, 0 skipped;
- `JdbcApprovalAuditIntegrationTest`: 8 tests, 0 failures, 0 errors, 0 skipped;
- `JdbcApprovalNotificationIntegrationTest`: 10 tests, 0 failures, 0 errors, 0 skipped;
- `JdbcApprovalCommentIntegrationTest`: 15 tests, 0 failures, 0 errors, 0 skipped;
- approval PostgreSQL module: 115 tests, 0 failures, 0 errors, 0 skipped;
- full 16-module Maven reactor: `BUILD SUCCESS`;
- Vben TypeScript and production build: passed;
- UniApp TypeScript, H5 and WeChat builds: passed.

The H4 integration matrix covers:

- a clean tenant with zero findings;
- durable idempotency and one audit event on replay;
- proof that checks do not alter task status;
- instance/task inconsistency and tenant isolation;
- delegation, handover and unreachable collaboration findings;
- notification evidence findings without queue mutation;
- comment revision, attachment and audit-evidence findings;
- check and finding filters and pagination.

## CI corrections

Permanent CI exposed and verified these corrections before H4 became green:

- the PC history page was made safe for an empty first item;
- the PostgreSQL test imported the explicit `ConsistencyCheck` type;
- the consistency run request was changed from a literal scope string to a lowercase SHA-256 idempotency hash.

## Next stage

H5 will unify operational visibility for notification dead letters, business callback Outbox failures and connector failures while keeping their responsibilities and state machines separate. Replay will remain permission protected, bounded, concurrency safe and audited.
