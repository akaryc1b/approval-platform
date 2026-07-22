# M4 SLA Execution and Replay Governance

## Acceptance status

M4-D is accepted on the single draft pull-request branch `agent/m4-production-readiness-and-enterprise-governance`. The accepted code head is `5e48e80a95e682ba01ff797dd2bde9a3cb23c01e`; permanent workflow Run `29915034794` completed successfully. This record does not start M4-E/F implementation.

## Scope

M4-D converts the authoritative SLA timestamps and immutable policy/calendar snapshots accepted in M4-C into a durable execution queue. It covers tenant-scoped intents, append-only attempts, multi-worker claim/lease, retry/backoff, dead state, governed replay, lifecycle synchronization, low-cardinality observability and a governed Web operations surface.

Production code does not query Flowable internal tables. Browser/mobile clients cannot nominate tenant, operator, worker, lease owner, trusted permission, responsible identity, authoritative due time or audit-chain reference.

## Migration and records

Flyway `V31__create_sla_execution_intents_attempts_and_replay.sql` adds:

- `ap_sla_execution_intent`: versioned work ownership and outcome state;
- `ap_sla_execution_attempt`: append-only attempt evidence;
- `ap_sla_execution_replay`: immutable source/new-intent replay evidence.

V31 includes tenant keys, authoritative foreign keys, bounded status/action/lease checks, idempotency uniqueness and operational indexes. Database triggers reject update/delete of attempt and replay evidence. V28-V30 remain unchanged and the migration chain is contiguous through V31.

## Intent lifecycle

The closed lifecycle is:

- `READY -> CLAIMED -> SUCCEEDED`;
- `READY/RETRY_WAIT/expired CLAIMED -> CLAIMED -> RETRY_WAIT`;
- `READY/RETRY_WAIT/expired CLAIMED -> CLAIMED -> DEAD`;
- `READY/RETRY_WAIT/CLAIMED -> CANCELLED`.

Only due `READY`, due `RETRY_WAIT`, or expired `CLAIMED` rows are runnable. `SUCCEEDED`, `DEAD`, and `CANCELLED` are terminal. State mutations are tenant-scoped and compare tenant, intent ID, expected version and lease owner where applicable.

Action sequence and idempotency include the authoritative SLA lifecycle generation. Resume creates replacement intents with a new generation; it never mutates cancelled historical intents.

## Authoritative policy binding

Before enqueue, the application reloads the authoritative SLA instance and exact immutable policy version in the same ambient transaction. It validates tenant, approval/task/collaboration identity, policy/calendar versions, definition binding and the existing PROCESS-to-TASK-to-COLLABORATION fallback hierarchy. Missing, draft, cross-tenant or mismatched evidence fails before JDBC enqueue.

The stored payload includes immutable policy content hash, definition, target type, optional release/task binding and authoritative due/overdue timestamps.

## Claim and lease

Workers discover a bounded tenant set and claim bounded per-tenant batches using PostgreSQL `FOR UPDATE SKIP LOCKED`. Claim is a short transaction. Connector or governed business-action execution occurs after claim commit, so no database transaction surrounds remote work.

A claim records a server-owned worker and lease deadline. Another worker may reclaim only after expiry. Outcome compare-and-set prevents a stale worker from committing after reclaim.

## Retry

Retryability is server-decided. Retry uses bounded exponential backoff. Configuration validates:

- batch size `1..200`;
- lease duration up to one hour;
- maximum backoff up to 24 hours;
- maximum attempts `1..100`.

A retryable failure appends an attempt and moves to `RETRY_WAIT` with a server-calculated next time. Reaching maximum attempts moves the intent to `DEAD`.

## Dead

`DEAD` preserves the original intent, bounded error code/summary, attempts and dead timestamp. Unsupported or unsafe action adapters fail closed rather than reporting false success.

- `REMINDER` uses `OrganizationConnector` and the intent idempotency key as delivery deduplication evidence.
- `OVERDUE` records authoritative `last_action_sequence` through tenant-scoped optimistic locking and is safe when action-state commit precedes intent outcome commit.
- `ESCALATION` and `AUTOMATIC_ACTION` become `DEAD` when no explicit governed adapter is configured.
- Automatic approve/reject/transfer never uses a direct database shortcut and cannot forge an operator.

## Replay

Replay is allowed only from `DEAD`. It does not modify the source row. It atomically creates a new intent and immutable evidence containing source/new IDs, original bounded error evidence, reason, replay idempotency key, server-owned requester/request/trace and audit-chain reference.

A PostgreSQL advisory lock plus uniqueness constraints make concurrent replay idempotent. Same-request reuse returns existing evidence; conflicting reuse fails closed.

The endpoint uses existing high-risk capability `OPERATIONAL_FAILURE_REPLAY`. The management interceptor requires reason and idempotency key and writes audit authorization evidence before the handler. Audit failure prevents replay. The client sends no tenant, operator, worker, lease owner, target user or audit reference.

## Tenant isolation

Every intent, attempt, summary, detail, cancellation and replay query includes the authenticated tenant. Cross-tenant source lookup is non-disclosing. `ApprovalPrincipal` and the trusted server request wrapper own tenant/operator/correlation evidence.

Client criteria are limited to status, action, schedule, request ID, responsible user and pagination within the authenticated tenant.

## Transaction boundaries

1. Approval projection/collaboration mutation and SLA synchronization share the existing outer transaction.
2. SLA creation plus validated intent enqueue commit or roll back together.
3. Pause cancels active intents atomically.
4. Resume cancels old work and creates versioned replacements atomically.
5. Responsibility transfer updates SLA and future `READY`/`RETRY_WAIT` targets atomically.
6. Task, approval-instance and collaboration terminal transitions cancel affected intents atomically.
7. Collaboration cancellation queries all tenant/task SLAs and does not depend on a TASK-policy anchor.
8. Worker claim, external execution and outcome use separate short transaction boundaries.
9. Replay creates the replacement intent and immutable replay evidence atomically.

Real PostgreSQL tests prove outer rollback, lifecycle synchronization, multi-worker disjoint claim, lease recovery, stale CAS rejection, retry/dead behavior, replay immutability and concurrent overdue recording.

## Observability

Metric `approval.sla.execution.worker` has only closed tags `action`, `result`, and `failure_class`. Tenant, user, request/trace, intent, worker, lease owner and arbitrary error text are not metric tags.

## Web operations surface

The Web surface provides queue summary, status/action/request/responsible-user filtering, intent detail, append-only attempt evidence and governed `DEAD` replay. Replay has no body or query identity payload; it sends only bounded reason plus shared transport idempotency/correlation headers.

Permanent client boundaries, Vben type-check and production build validate this surface.

## EXPLAIN

A PostgreSQL 16 Testcontainers suite migrates V1-V31, seeds 40,000 rows, runs `ANALYZE`, and parses `EXPLAIN (FORMAT JSON)` without disabling sequential scans. Seven paths use V31 indexes:

1. due `READY` claim;
2. due `RETRY_WAIT` claim;
3. expired lease recovery;
4. tenant `DEAD` operations view;
5. tenant/idempotency lookup;
6. tenant/SLA history;
7. tenant/request correlation.

The accepted M4-C suite still proves nine calendar/SLA paths.

## Validation matrix

Final code-head evidence:

- 16-module Maven `BUILD SUCCESS`;
- application 90, persistence 183, server 54 tests; zero failures/errors/skips;
- execution store 7/7, lifecycle transactions 3/3, overdue recorder 2/2;
- cancellation guard 3/3, policy binding 2/2, worker 6/6;
- execution management security 6/6, metrics 1/1;
- execution EXPLAIN 7/7, migration upgrade 5/5, M4-C EXPLAIN 9/9;
- permanent M4 boundary 12/12 and client boundary 7/7;
- Vben pipeline 11/11, UniApp type-check, H5 and WeChat builds successful.

## Artifact and SHA-256 evidence

Downloaded final code-head artifacts were SHA-256 verified, expanded and read:

- Maven Artifact `8527606730`, SHA-256 `sha256:2c0a043f87a7aed2ea735ba11bc409d4b8423d969beab8ccbf61fc5df09ae01c`;
- Vben Artifact `8527534050`, SHA-256 `sha256:2c41b66c42a660dba442f3fed8037c6d193fe40f0d5d1d07221d56d5f0fe7bfa`;
- Mobile Artifact `8527516619`, SHA-256 `sha256:8909aab974c56c063451341defb116a13ae56f644c308da04f0b8b3d6b38624b`;
- Hygiene Artifact `8527495406`, SHA-256 `sha256:45575e2f74eaba0fa3634af7fd0159497eb325e30fba9b27636c3f9329bf7183`.

The final document-head Run and document-head Artifact/SHA-256 evidence are recorded in PR #55 after permanent verification.

## Frozen governance lineage

These records remain byte-for-byte frozen:

- `docs/M3_FINAL_ACCEPTANCE.md`;
- `docs/M4_IDENTITY_AND_TENANT_GOVERNANCE.md`;
- `docs/M4_AUTHORIZATION_AND_RESPONSIBILITY_GOVERNANCE.md`;
- `docs/M4_SLA_AND_CALENDAR_GOVERNANCE.md`.

This record is frozen by exact Git blob in the permanent M4 boundary after creation.

## M4-E/F handoff

M4-D does not implement M4-E/F. Process-version lifecycle and in-flight migration work must use public application ports and authoritative platform records, remain tenant-scoped and evidence-producing, and avoid Flowable internal tables. Migration dry-run remains non-mutating until an explicit governed command exists. Automatic SLA actions remain fail closed until a real authenticated/governed command adapter can be invoked without forging an operator.

PR #55 remains Open and Draft. M4-D acceptance does not mark ready, merge or enable auto-merge.
