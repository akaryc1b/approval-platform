# M3 H8 Performance and Index Governance

## 1. Scope and boundary

H8 validates the PostgreSQL query paths introduced or exercised by M3 across task participation, delegation, employee handover, collaboration, comments, notifications, audit, consistency checks, business Outbox delivery, and the unified operational failure view.

The implementation remains inside platform-owned projection and governance tables. It does not read, modify, or depend on Flowable internal tables. Process-engine interaction continues to use the existing formal engine boundary.

H8 does not add an alternative persistence framework, SQL dump, benchmark artifact, production-only test hook, or in-memory database substitute. All scale and migration evidence runs against PostgreSQL 16 through Testcontainers and Flyway.

## 2. Index design principles

The H8 index set follows these rules:

- tenant isolation is the leading key for all tenant-scoped query paths;
- queue indexes use status-specific partial indexes when the state predicate is stable;
- stable pagination keys include both the business timestamp and deterministic identifier;
- existing M3 indexes are reused when they already match the actual SQL;
- a general-purpose index is removed only after status-specific replacements cover both read paths;
- no combinatorial index set is added for every optional filter;
- no index is added to Flowable-owned tables.

## 3. Migration chain

H8 extends the immutable Flyway chain from V23 through V27.

### V24 — management query paths

`V24__optimize_management_query_paths.sql` adds:

- `idx_audit_event_tenant_action_time` for tenant/action/time audit queries;
- `idx_notification_dead_management` for notification dead-letter management pagination;
- `idx_notification_dead_connector` for connector-specific dead-letter queries;
- `idx_consistency_failed_management` for failed consistency-check pagination.

### V25 — stable participant and history pagination

`V25__complete_m3_scaled_query_indexes.sql` adds:

- `idx_approval_task_pending_assignee_page` on tenant, assignee, creation time, and task ID for pending-task pagination;
- `idx_delegation_principal_history` for principal delegation history;
- `idx_handover_principal_history` for principal handover history;
- `idx_collaboration_participant_pending_page` for pending collaboration participation.

The pending-task and collaboration indexes are partial indexes restricted to `PENDING` rows.

### V26 and V27 — task status queues

`V26__replace_legacy_task_assignee_index.sql` introduces a completed-task ordering index and removes the legacy general index `ap_approval_task_assignee_idx` after the pending and completed paths have dedicated replacements.

`V27__align_completed_task_index_predicate.sql` recreates the completed-task index with the exact production predicate `status = 'COMPLETED'`. This alignment is intentional: PostgreSQL can use a partial index only when the query predicate proves the index predicate. The final index is:

- `idx_approval_task_completed_assignee_page` on tenant, assignee, completion time descending, and task ID descending;
- partial predicate `status = 'COMPLETED'`.

Previously committed migrations were not edited. V27 is an additive correction in the migration chain.

## 4. Query and ordering contract

The verified stable orderings include:

| Query path | Tenant/filter prefix | Stable ordering |
| --- | --- | --- |
| Pending tasks | tenant, assignee, `PENDING` | `created_at`, `task_id` |
| Completed tasks | tenant, assignee, `COMPLETED` | `completed_at desc`, `task_id desc` |
| Delegation history | tenant, principal | `created_at desc`, `rule_id desc` |
| Handover history | tenant, principal | `created_at desc`, `handover_id desc` |
| Pending collaboration | tenant, participant, `PENDING` | `added_at`, `participant_id` |
| Comment thread | tenant, instance and visibility | `created_at`, `comment_id` |
| Notification history | tenant, recipient | `created_at desc`, `intent_id desc` |
| Notification dead letters | tenant and dead-letter state | `updated_at desc`, `intent_id` |
| Audit aggregate timeline | tenant, aggregate type and ID | `occurred_at desc`, `tenant_sequence desc` |
| Audit action/time query | tenant, action and time range | index-backed action/time range |
| Failed consistency checks | tenant and `FAILED` | `completed_at desc`, `check_id` |
| Consistency findings | tenant and check | `detected_at`, `finding_id` |
| Dead Outbox queue | tenant and `DEAD` | `updated_at desc`, `id` |

Existing indexes for comment visibility/revisions, notification recipient history, audit aggregate lookup, consistency findings, and Outbox failures remain in use; H8 does not duplicate them.

## 5. PostgreSQL scale evidence

H8 adds responsibility-focused Testcontainers classes rather than one oversized fixture.

### Management query scale

`JdbcApprovalManagementScaleIntegrationTest` creates two tenants. Per tenant it generates:

- 12,000 audit events;
- 12,000 notification intents;
- 12,000 consistency checks;
- 24,000 consistency findings;
- 12,000 Outbox messages.

It validates natural planner selection for audit action/time, audit aggregate timeline, notification dead-letter, connector dead-letter, recipient history, failed consistency checks, consistency findings, and dead Outbox paths.

### Participant query scale

`JdbcApprovalParticipantQueryScaleIntegrationTest` creates two tenants. Per tenant it generates:

- 6,000 approval instances and 6,000 tasks;
- 6,000 delegation rules;
- 6,000 handovers;
- 6,000 collaboration policies and 6,000 participants;
- 20,000 current comments and 20,000 comment revisions.

It validates pending-task, delegation-history, handover-history, pending-collaboration, and comment query paths. It also verifies repeatable pages and tenant isolation.

### Task status queue scale

`JdbcApprovalTaskStatusPlanIntegrationTest` creates 12,000 tasks per tenant, split across pending and completed states. It verifies:

- both status-specific indexes exist;
- the legacy general assignee index is absent;
- pending and completed production-shaped queries use their intended indexes;
- completed deep pagination is deterministic and tenant-scoped.

### Plan assertions

The scale tests use:

`EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`

The new scale evidence does not disable sequential scans to force a desired answer. PostgreSQL must naturally choose the target index under the generated distribution. Assertions inspect index identity without depending on brittle full-plan text or narrow millisecond thresholds.

## 6. Pagination evidence

Both ordinary and deeper offset pages are executed repeatedly and compared for deterministic results. Covered offsets include:

- pending tasks: offset 1,000;
- comments: offset 1,000;
- completed tasks: offset 500;
- notification recipient history: a second page at offset 20.

For each applicable path, tests verify that repeated reads return the same identifiers, adjacent pages do not overlap, and another tenant's page cannot contribute identifiers.

H8 retains the existing bounded `limit`/`offset` API contract. It does not introduce a second pagination protocol or claim that unbounded export is permitted.

## 7. Flyway installation and upgrade evidence

`JdbcApprovalMigrationUpgradeIntegrationTest` uses two isolated PostgreSQL databases in one Testcontainers server so database-level extensions and schema histories cannot contaminate each other.

It verifies:

1. a fresh database migrates from V1 through V27;
2. a pre-H8 database migrates through V23, then upgrades from V23 to V27;
3. Flyway validation succeeds after both paths;
4. the final H8 index contract exists in both databases;
5. the legacy task assignee index is absent.

V23 is the last H5 database migration. H6 and H7 did not add migrations, so V23 is also the correct pre-H8 upgrade boundary.

## 8. CI and artifact evidence

H8 code HEAD:

`d2e14f75678f149e5c3977b079d9ea8dfa22870a`

Permanent workflow run:

`29796514751`

All four permanent jobs succeeded:

- Repository hygiene;
- Java 21 / Maven / PostgreSQL;
- Vben TypeScript / production build;
- UniApp TypeScript / H5 / WeChat.

Artifact evidence:

- `approval-maven-29796514751`, digest `sha256:ec9093c46d03617bcabe67be691662bd11398c486f3d470ca4f595dffaa52546`;
- `approval-vben-29796514751`, digest `sha256:e3006a02da2673cc96acbcb375ec5c71d7e541892d1e0588ce0ac26e30df9337`;
- `approval-mobile-29796514751`, digest `sha256:0ac0f2c7e5d738e8dad1caf44657a706a7a9fee069e45fbe2dcc08ed0c544306`.

The Maven artifact confirms these H8 classes:

- `JdbcApprovalIndexPlanIntegrationTest`: 5 tests;
- `JdbcApprovalManagementScaleIntegrationTest`: 2 tests;
- `JdbcApprovalMigrationUpgradeIntegrationTest`: 2 tests;
- `JdbcApprovalParticipantQueryScaleIntegrationTest`: 3 tests;
- `JdbcApprovalTaskStatusPlanIntegrationTest`: 3 tests.

H8-specific total: 15 tests, with 0 failures, 0 errors, and 0 skipped.

The same artifact confirms:

- PostgreSQL persistence module: 143 tests, 0 failures, 0 errors, 0 skipped;
- `apps/server`: 17 tests, 0 failures, 0 errors, 0 skipped;
- complete 16-module Maven reactor: `BUILD SUCCESS`;
- H7 cross-module fault tests: 6 passed;
- H5 operational failure tests: 7 passed;
- consistency integration tests: 6 passed.

Client artifacts confirm:

- Vben `vue-tsc` passed;
- Vben production build completed with 11 of 11 tasks successful;
- UniApp `vue-tsc` passed;
- H5 build completed;
- WeChat Mini Program build completed.

## 9. Governance outcome

H8 establishes that M3's principal participant and management queries are tenant-prefixed, use deterministic ordering, retain bounded pagination, and have PostgreSQL execution-plan evidence at controlled scale. The migration chain supports both fresh installation and upgrade from the pre-H8 V23 boundary. No Flowable internal table is included in the index or query plan contract.
