# M4 SLA and Calendar Governance

Status: M4-C code and document evidence accepted on 2026-07-22; the metadata-head permanent validation is required before PR-body acceptance.

This is the single M4-C governance record. It was created only after the code head passed the permanent repository workflow and all four code-head artifacts were downloaded, SHA-256 checked, expanded, and read. It does not mark PR `#55` ready, enable auto-merge, merge the branch, or close Issues `#13` or `#14`.

## 1. Scope

M4-C establishes production-shaped SLA and work-calendar governance for the approval platform:

- tenant-scoped, versioned work calendars;
- immutable, versioned SLA policies;
- server-calculated SLA instances for process, task, and collaboration-participant targets;
- natural-time and working-time deadlines;
- reminder, due, and overdue evidence;
- pause, resume, terminal, and responsibility-change lifecycle evidence;
- participant-safe SLA visibility;
- governed management APIs and Web operations;
- PostgreSQL execution-plan evidence for operational query paths;
- permanent client, workflow, migration, credential, and Flowable boundary checks.

## 2. Explicit exclusions

M4-C does not implement a durable execution worker, claim/lease protocol, append-only execution attempts, retry queue, dead state, governed replay, external reminder delivery, overdue dispatcher, escalation dispatcher, or automatic-action dispatcher. Those are M4-D scope.

M4-C also does not implement M4-E process-definition lifecycle or M4-F in-flight migration assessment. It does not expose Flowable internal tables, allow clients to nominate trusted tenant/operator/permissions/roles, or allow clients to calculate authoritative SLA timestamps.

## 3. Data model

M4-C separates immutable design evidence from mutable runtime evidence:

- `ap_work_calendar` stores tenant-scoped calendar identity, key, display name, time zone, lifecycle status, active version, and optimistic version;
- `ap_work_calendar_version` stores a numbered snapshot, effective interval, content hash, lifecycle status, immutability, and publication evidence;
- version-owned weekly intervals and date overrides cannot be mixed across tenants or versions;
- `ap_sla_policy` stores tenant-scoped policy identity, key, lifecycle status, active version, and optimistic version;
- `ap_sla_policy_version` stores target matching, duration mode, duration, calendar binding, reminder rules, overdue offset, escalation policy, automatic-action policy, pause rules, and content hash;
- `ap_sla_instance` stores authoritative timestamps, immutable policy/calendar references, current and original responsibility, lifecycle state, requestId/traceId, action sequence, and optimistic version;
- `ap_sla_responsibility_change` stores append-only previous/new responsibility evidence, source, reason, actor, requestId, and traceId.

No M4-C table depends on a Flowable internal database table.

## 4. Migration upgrade matrix

| Migration | Purpose | Principal evidence | Compatibility rule |
| --- | --- | --- | --- |
| V28 | Versioned work calendars | calendar identity, version, weekly interval, date override | additive; V1-V27 remain unchanged |
| V29 | Immutable SLA policies | policy identity and immutable policy version | additive; published version evidence is not rewritten |
| V30 | SLA runtime and responsibility history | SLA instance and append-only responsibility change | additive; tenant-scoped and optimistically versioned |

The accepted code head retains a contiguous V1-V30 Flyway chain. V28, V29, and V30 were not edited during final acceptance. PostgreSQL plan evidence showed the released indexes were sufficient, so M4-C did not add V31. M4-D starts migration numbering at V31.

## 5. Tenant isolation

Every calendar, policy, SLA instance, and responsibility-history lookup includes tenant scope. Composite uniqueness, foreign-key relationships, and store methods bind tenant identity to resource identity.

Cross-tenant reads return empty or not-found behavior rather than existence disclosure. The authenticated `ApprovalPrincipal` supplies tenant and operator values through the server-owned request wrapper. A mismatched tenant claim fails before the controller with non-disclosing not-found semantics.

## 6. Calendar versioning, identity, and status model

A calendar identity owns a stable tenant-scoped `calendar_id` and `calendar_key`. Editable content lives in numbered versions rather than on the identity.

Calendar lifecycle states distinguish draft design evidence, published immutable evidence, the active version selected for new calculations, and inactive historical versions. `active_version` is explicit on the identity and protected by optimistic locking.

## 7. Calendar publish, activate, and inactive rules

Publishing requires an existing draft version and converts it to immutable publication evidence with publisher and publication time. Repeating publish for an already immutable version returns existing evidence rather than rewriting it.

Activation requires a published immutable version. The identity records the selected active version; a previously active version remains historical/inactive evidence rather than being deleted. Repeating activation for the current version is idempotent.

Calendar publish and activate are tenant-wide management operations governed by `SLA_PUBLISH` and `SLA_ACTIVATE`.

## 8. Calendar immutable snapshot

`ApprovalWorkingTimeCalculator.CalendarSnapshot` binds:

- calendar ID and tenant;
- calendar version;
- IANA time-zone identifier;
- weekly working intervals;
- date overrides;
- deterministic content hash.

An SLA policy and SLA instance refer to a specific version/snapshot. Later draft edits or activation of another calendar version cannot mutate timestamps calculated from the original immutable snapshot.

## 9. Time zone and DST handling

Calendar zones are validated with Java `ZoneId`. Working schedules are expressed in calendar-local dates and times, then resolved against that version's zone for each calculation date.

The working-time calculator therefore applies daylight-saving gaps and overlaps rather than assuming every day contains 24 hours. Natural-time deadlines use `Instant` duration arithmetic. Persisted runtime timestamps are absolute instants, while the bound time-zone identifier remains available for display and audit interpretation.

## 10. Working days, intervals, holidays, and overrides

A weekly schedule supports day-of-week entries containing validated, non-overlapping working intervals. Date overrides can close an otherwise working date or replace intervals for holidays, special working days, and local exceptions.

The calculator finds the next valid working instant and consumes duration only inside the selected immutable version's working windows. Empty or unusable schedules fail with stable working-time errors rather than silently falling back to natural time.

## 11. Policy immutability, identity, version, and status

A policy identity owns a stable tenant-scoped policy ID/key and active version. Numbered policy versions bind:

- process definition key and optional release version;
- process, task, or collaboration-participant target;
- optional task-definition key;
- duration mode and duration;
- immutable calendar reference where required;
- reminder and overdue rules;
- escalation and automatic-action policy;
- pause behavior;
- deterministic content hash.

Draft, published, active, and inactive evidence is preserved. Published versions are immutable and are not edited in place.

## 12. Policy and calendar snapshot binding

A working-time policy must reference a published immutable calendar version. Validation performs a real working-time calculation against that exact snapshot.

The policy records calendar ID, calendar version, calendar content hash, and time zone. Runtime calculation never resolves a mutable draft or silently switches to a later active calendar.

## 13. SLA duration mode

`NATURAL_TIME` measures an uninterrupted duration on the UTC instant timeline. `WORKING_TIME` consumes duration only inside the bound calendar's working intervals.

Duration mode is immutable policy evidence. The client cannot select or override it when an SLA instance is created.

## 14. Natural time and working time

For natural time, due time is the server-owned start instant plus policy duration. For working time, the calculator advances through versioned local working intervals until the required duration is consumed.

Pause/resume treatment is determined by immutable policy rules. The service, not browser or mobile code, calculates authoritative timestamps.

## 15. Reminder, due, and overdue calculation

An SLA instance records:

- `started_at`;
- authoritative `due_at`;
- optional `next_reminder_at`;
- authoritative `overdue_at`;
- action sequence and lifecycle timestamps.

The first reminder is derived from the immutable policy offset and bounded by due time. Overdue time is due time plus the policy overdue offset. M4-C records and exposes this evidence but does not dispatch side effects; durable execution belongs to M4-D.

## 16. SLA instance lifecycle

SLA instances are created for applicable process, task, and collaboration-participant targets using the effective active immutable policy. Existing active target evidence prevents duplicate lifecycle events from creating duplicate SLA rows.

Lifecycle states are active, paused, and terminal. Completion, rejection, withdrawal, task completion/cancellation, and collaboration participant outcomes map to exact terminal reasons. Terminal evidence is retained.

## 17. Pause and resume

Pause records the pause instant and bounded reason while retaining original authoritative evidence. Repeating pause on an already paused instance is idempotent.

Resume requires the expected optimistic version. It calculates new due, reminder, and overdue timestamps using the immutable policy and, for working time, the immutable calendar snapshot. Accumulated paused duration is retained. Repeating resume on an active instance is idempotent.

M4-D must cancel or suspend future execution intents on pause and create replacement intents on resume without overwriting history.

## 18. Optimistic locking

Calendar identity, policy identity, and SLA instance transitions use explicit versions. Store updates include expected versions and fail with stable conflicts when another writer has advanced the row.

Optimistic locking protects publication/activation selection, pause/resume, responsibility change, and later overdue/action-sequence updates. It is not replaced by last-write-wins behavior.

## 19. Idempotency

M4-C uses complementary idempotency boundaries:

- immutable publish and repeat activation return existing evidence when facts already match;
- unique target constraints and active-instance queries prevent duplicate process/task/collaboration SLA instances;
- terminal lifecycle operations are repeat-safe;
- high-risk management authorization requires an `Idempotency-Key` and appends idempotent audit-chain evidence before the handler proceeds.

M4-D adds intent-level and side-effect-level idempotency without weakening these boundaries.

## 20. requestId and traceId

SLA creation and responsibility changes preserve server-owned requestId and traceId from `ApprovalRequestEvidenceProvider`. Management authorization evidence also binds trusted requestId/traceId.

Correlation identifiers are bounded and sanitized by the identity filter. They are searchable evidence but are not metric labels.

## 21. Responsibility change

A task responsibility transfer updates only the current responsible user under optimistic locking and appends `ap_sla_responsibility_change` evidence containing previous user, new user, source, bounded reason, authenticated actor, change time, requestId, and traceId.

The original responsible user remains on the SLA instance. M4-D must update future intent targets while never rewriting completed attempts.

## 22. Participant visibility

The participant endpoint is `GET /api/approval/tasks/{taskId}/sla`. It accepts only server-owned tenant/operator headers produced by the authenticated request wrapper and the task ID path variable.

The query receives the authenticated operator as current user. It does not accept tenantId or userId in query/body data and cannot nominate another participant. Invisible or cross-tenant evidence returns the same not-found result as absent evidence.

## 23. Management capability

Calendar, policy, and SLA-instance management handlers declare explicit tenant-scoped capabilities:

- `SLA_READ` for governed reads;
- `SLA_DESIGN` for draft design and pause/resume operations;
- `SLA_PUBLISH` for immutable publication;
- `SLA_ACTIVATE` for active-version selection.

The centralized responsibility resolver remains the only enterprise role-to-capability matrix. The participant role has no management capability. An unrelated enterprise role such as connector administrator does not elevate a participant into SLA management.

## 24. High-risk publish and activate governance

Calendar and policy publish/activate handlers are tested against their real controller methods. Before execution they require:

- exact management capability within tenant scope;
- normalized bounded `X-Approval-Operation-Reason`;
- valid `Idempotency-Key`;
- trusted requestId/traceId;
- successful append of management audit-chain evidence.

Missing reason, missing idempotency key, or unavailable audit persistence fails closed. Audit unavailability returns retryable service-unavailable evidence and the handler does not proceed.

## 25. Transaction boundaries

The application layer owns policy and lifecycle decisions; JDBC stores own persistence and atomic state transitions. Transaction-aware projection and collaboration wrappers call SLA synchronization inside the surrounding approval transaction.

External side effects are not called inside these database transitions. M4-D must claim and record an attempt transactionally, release the transaction, dispatch externally with an idempotency key, then record a bounded result.

## 26. Rollback evidence

Real PostgreSQL/Testcontainers integration tests verify that an outer approval transaction rollback removes both enclosing approval changes and SLA rows created inside that boundary. JDBC state transitions also verify optimistic conflicts and tenant isolation.

This is PostgreSQL rollback evidence, not an in-memory mock. The full Flyway chain is applied to PostgreSQL 16 before tests run.

## 27. PostgreSQL index and EXPLAIN evidence

`JdbcApprovalSlaIndexPlanIntegrationTest` loads V1-V30 into PostgreSQL 16, seeds 25,000 calendar identities/versions, 25,000 policy identities/versions, 2,000 approval instances, 40,000 tasks, 40,000 SLA instances, and 40,000 responsibility changes, then runs `ANALYZE`.

It parses `EXPLAIN (FORMAT JSON)` and recursively requires an expected index or reasonable bitmap/index scan. It does not set `enable_seqscan=off`.

Nine verified paths are:

1. active calendar version via `idx_work_calendar_active_lookup` or equivalent active/key uniqueness;
2. effective active SLA policy via `idx_sla_policy_active_lookup` or `uk_sla_policy_active_target`;
3. upcoming active SLA via `idx_sla_instance_active_due`;
4. overdue active SLA via `idx_sla_instance_overdue` or bounded active-due lookup;
5. responsible active upcoming SLA via `idx_sla_instance_responsible_active_due`;
6. requestId lookup via `idx_sla_instance_request_id`;
7. approval-instance history via `idx_sla_instance_approval_instance`;
8. participant task visibility via `idx_sla_instance_task` and tenant/task evidence;
9. responsibility history via `idx_sla_responsibility_history`.

All nine passed on the accepted code run, so no M4-C V31 index patch was required.

## 28. Web client

The Vben application contains calendar management, SLA policy management, and SLA operations views, routes, and a shared governed SLA API client.

The client displays server-provided timestamps, statuses, policy/calendar version evidence, request tracing, and responsibility data. Management mutations use the common governance transport for reason/idempotency and structured error handling.

## 29. Mobile client

Mobile adds participant SLA retrieval and a task-detail SLA card. It displays server-provided due/reminder/overdue evidence and responsibility-change state.

Mobile exposes no management or worker interface. It treats not-found as non-visible/absent participant evidence and does not nominate tenant or user.

## 30. Client trusted-header boundary

Permanent scans verify browser and mobile sources do not send:

- `X-Tenant-Id`;
- `X-Operator-Id`;
- `X-Approval-Trusted-Permissions`.

They also verify clients do not construct or submit authoritative `dueAt`. Trusted tenant, operator, authorities, roles, and authoritative timestamps remain server-owned.

## 31. Permanent CI boundary

The only validation workflow is `.github/workflows/approval-platform-validation.yml`. M4-C is permanently wired through `scripts/tests/m4-sla-calendar-boundary.test.mjs`.

The accepted code run verifies ten boundaries: migration continuity through V30, permanent workflow exclusivity, client trusted-identity prevention, authoritative timestamp prevention, management capability/principal scope, participant non-nomination, Flowable internal-table prohibition, frozen accepted documents, tracked-tree hygiene/credential scanning, and governance document absent-or-complete behavior.

## 32. Test counts

Accepted code-head Maven module totals are:

| Maven module | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Approval Platform aggregator | 0 | 0 | 0 | 0 |
| approval-domain | 7 | 0 | 0 | 0 |
| approval-definition-compiler | 11 | 0 | 0 | 0 |
| approval-engine-spi | 0 | 0 | 0 | 0 |
| approval-connector-spi | 3 | 0 | 0 | 0 |
| approval-integration-core | 12 | 0 | 0 | 0 |
| approval-connector-generic | 6 | 0 | 0 | 0 |
| approval-integration-jdbc | 4 | 0 | 0 | 0 |
| approval-engine-flowable | 10 | 0 | 0 | 0 |
| approval-application | 78 | 0 | 0 | 0 |
| approval-persistence-jdbc | 161 | 0 | 0 | 0 |
| approval-host-sdk | 7 | 0 | 0 | 0 |
| approval-architecture-tests | 9 | 0 | 0 | 0 |
| approval-server-modules aggregator | 0 | 0 | 0 | 0 |
| approval-server | 44 | 0 | 0 | 0 |
| approval-generic-spring-host-example | 5 | 0 | 0 | 0 |

Focused evidence includes:

- `ApprovalSlaServiceTest`: 6 passed;
- `ApprovalWorkingTimeCalculatorTest`: 10 passed;
- `JdbcApprovalSlaStoreIntegrationTest`: 3 passed;
- `JdbcApprovalSlaIndexPlanIntegrationTest`: 9 passed;
- `JdbcApprovalMigrationUpgradeIntegrationTest`: 5 passed;
- `ApprovalSlaManagementSecurityContractTest`: 6 passed;
- `ApprovalIdentityContextFilterTest`: 5 passed;
- M4 SLA/calendar boundary: 10 passed;
- Vben client boundary: 7 passed;
- form renderer protocol: 3 passed;
- form designer section operations: 2 passed;
- Vben production pipeline: 11/11 tasks successful;
- UniApp type-check, H5 build, and WeChat Mini Program build: successful.

## 33. Code HEAD

Accepted M4-C code head: `897541ab33bb48549a02795ba2bb4af032c5ac06`.

Final evidence commits include:

- `9883f2d` — legal 64-character lowercase fixture content hash;
- `9b5bf15` — explicit `java.sql.Timestamp` binding for JDBC seed instants;
- `807245a` — explicit UUID type inference for nullable production SQL parameters;
- `8a49f43` — real PostgreSQL JSON EXPLAIN evidence;
- `897541a` — focused SLA management security/API contracts.

The nullable UUID change is a production SQL correction, not a test bypass.

## 34. Code run ID

Permanent workflow run: `29896781911`, run number `328`, status `completed`, conclusion `success`.

Jobs and all actual steps were inspected:

- Java 21 / Maven / PostgreSQL: job `88848527197`, success;
- UniApp TypeScript / H5 / WeChat: job `88848527231`, success;
- Vben TypeScript / production build: job `88848527243`, success;
- Repository hygiene: job `88848527267`, success.

The 16-module Maven reactor reported `BUILD SUCCESS` in 03:14.

## 35. Code artifacts and SHA-256 digests

All four ZIP artifacts were downloaded locally. Computed ZIP SHA-256 values matched GitHub artifact digests exactly before logs were expanded and read.

| Artifact | ID | GitHub digest / local ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-29896781911` | `8520339149` | `sha256:0e32c85b98f89e522f21ee3f4458c7f451f91cee70c4999e84232f5c9fd5ee3c` |
| `approval-vben-29896781911` | `8520307519` | `sha256:633761ce0e0048e7501373cb5017c5b1efef31e0ab5d47769f4604a43f019303` |
| `approval-mobile-29896781911` | `8520290650` | `sha256:50fc0321f29e8b13dc0be8e9c5d7fb46bd3f9f745ada89f3cc524622fa6ebe73` |
| `approval-hygiene-29896781911` | `8520273983` | `sha256:66274f234a2d71427732a2d978fd27bb6d8c6086a408514ee9c96f40d08497f8` |

Raw logs confirmed type-checks, Vben 11/11 production build tasks, H5 and WeChat `DONE Build complete`, and the M4-C boundary 10/10.

## 36. Document HEAD

Validated document evidence HEAD: `e1365cf0796c8f06f6d934cc2d2ec06aa958c6d5`.

This is the first complete governance-content head that passed the permanent workflow. The current metadata-only commit records that immutable evidence. Its own resulting head and second permanent run are recorded in PR `#55` after that run is independently verified, avoiding an impossible self-referential commit SHA while preserving one governance file.

## 37. Document run ID

Document evidence permanent workflow run: `29897533424`, run number `330`, status `completed`, conclusion `success`.

Jobs and every actual step were inspected:

- Java 21 / Maven / PostgreSQL: job `88850773697`, success;
- Repository hygiene: job `88850773706`, success;
- UniApp TypeScript / H5 / WeChat: job `88850773725`, success;
- Vben TypeScript / production build: job `88850773762`, success.

The Maven reactor reported 16 modules and `BUILD SUCCESS` in 03:50. The document completeness boundary passed 10/10.

## 38. Document artifacts and SHA-256 digests

All four document-evidence artifacts were downloaded locally, SHA-256 checked against GitHub, expanded, and read.

| Artifact | ID | GitHub digest / local ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-29897533424` | `8520622302` | `sha256:bef92e46fbab85056cd26f9b7f0b2e46517129457cd07eaf167c025241807c1a` |
| `approval-vben-29897533424` | `8520576365` | `sha256:e75b0f4cf93f757770430afd628fd0ffbc705caa3c938babb8be1f0edb183943` |
| `approval-mobile-29897533424` | `8520561961` | `sha256:13bf68fcda626bb6763e77818b8d18bd43686ddf762ddc25dec70a0f7b119df9` |
| `approval-hygiene-29897533424` | `8520545879` | `sha256:acded015bf92d92498b330744e5e29b569d9ff71caa855cefd09a707c74d4e5d` |

Raw evidence reconfirmed application 78 tests, persistence 161 tests, server 44 tests, PostgreSQL EXPLAIN 9/9, migration upgrade 5/5, SLA store 3/3, focused SLA security 6/6, Vben 11/11, mobile type-check, H5 and WeChat production builds, and M4-C boundary 10/10.

## 39. Remaining risks

M4-C records authoritative schedule evidence but does not execute reminders, overdue transitions, escalation, or automatic actions. A process restart therefore has no durable worker queue yet.

Operational scale beyond seeded planner evidence still requires production observation. Time-zone rule database updates can change future calculations for newly created instances; existing persisted instants remain authoritative. Client route hints remain a user-experience feature and cannot replace server authorization.

## 40. M4-D handoff

M4-D must start from V31 and build durable execution rather than a stateless polling side effect. The handoff requires:

- tenant-scoped immutable execution intents for reminder, overdue, escalation, and automatic action;
- transactionally consistent intent creation, cancellation, and rescheduling with SLA lifecycle changes;
- multi-worker claim/lease with `FOR UPDATE SKIP LOCKED` or strict CAS;
- append-only attempts, bounded retry/backoff, dead state, and lease recovery;
- external side-effect idempotency without long database transactions;
- governed replay that creates a new intent and preserves original dead evidence;
- participant denial and tenant non-disclosure for all worker management APIs;
- low-cardinality metrics and bounded structured logs;
- Web operations visibility without trusted client identity or authoritative timestamps;
- real PostgreSQL concurrency, rollback, and EXPLAIN tests;
- a separate `docs/M4_SLA_EXECUTION_AND_REPLAY_GOVERNANCE.md` only after the M4-D code head is fully verified.

No M4-D worker, execution intent, attempt, dead state, or replay implementation is claimed by this M4-C record.
