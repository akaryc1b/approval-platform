# M3 Final Acceptance

## Acceptance status

M3 code acceptance is validated on committed HEAD `2cd81ceee7ab0a9a433c3462a73dc5d07a74f2ad` by the permanent workflow `.github/workflows/approval-platform-validation.yml`, run `29800271445`.

All four permanent jobs completed successfully:

- Repository hygiene;
- Java 21 / Maven / PostgreSQL;
- Vben TypeScript / production build;
- UniApp TypeScript / H5 / WeChat.

The final code-head artifacts are:

- `approval-maven-29800271445`, digest `sha256:179ae2b45debb504397fc683ead5a570a392d37ce6e9f553cba9e267f207db61`;
- `approval-vben-29800271445`, digest `sha256:28f8521a0dad46ba187d9674d22dd8459dc2fd624e948deeab98ccec721ec269`;
- `approval-mobile-29800271445`, digest `sha256:fff3c2bae08ec8cd648f36c966fc747fd4efc3f9af75dae4f65e6011034133db`.

This file is the single M3 final acceptance record and is not edited after creation.

## M3 scope A–H10

### A–C: delegation, responsibility and identity

M3 provides:

- tenant-scoped, time-bounded delegation rules with overlap protection;
- exact definition-scoped or all-definition delegation;
- real delegated task responsibility evidence instead of display-only substitution;
- immutable principal/delegate identity evidence;
- task completion, supersession and cancellation evidence;
- exact connector-directory identity references;
- employee handover policies and task handover evidence;
- immediate transfer of current pending tasks and governed handling of later tasks;
- idempotent and concurrency-safe handover commands;
- tenant isolation across principals, delegates and successors.

### D–E: task collaboration

M3 supports add-sign and remove-sign governance with:

- `ALL`, `ANY`, `VOTE` and `WEIGHTED` completion modes;
- stable participant identities and optional weights;
- participant uniqueness and bounded participant counts;
- threshold reachability validation;
- deterministic terminal status evaluation;
- optimistic version checks and PostgreSQL task-scoped serialization;
- late-decision rejection and terminal-task protection;
- exactly-once completion effects under concurrent decisions.

### F: notification governance

M3 provides:

- in-app, email and connector notification channels;
- user preferences, quiet hours, digest settings and emergency bypass;
- durable notification intents and per-delivery attempts;
- worker claims, leases and stale-worker protection;
- retry classification, bounded attempts and dead-letter state;
- governed replay through the existing notification state machine;
- multi-channel independent outcomes without duplicate business effects;
- provider references and original failure evidence retention.

### G: comments, mentions and attachments

M3 provides:

- comments, one-level replies and immutable revision evidence;
- optimistic edit/delete conflict handling;
- deletion tombstones rather than evidence removal;
- participant and mentioned-only visibility;
- exact mention notifications;
- tenant- and instance-bound attachment authorization;
- cross-tenant and unrelated-user denial;
- idempotency-key replay and payload-conflict protection;
- terminal-instance read-only enforcement across PC, H5 and WeChat.

### H1–H3: audit contract and integrity

M3 provides:

- versioned audit schemas and stable action contracts;
- per-tenant monotonic audit sequences;
- SHA-256 payload hashes and tenant hash chains;
- atomic chain-state advancement;
- bounded audit query and structured export;
- integrity verification with first-invalid-event evidence;
- server-generated timeline summaries;
- explicit assurance language that does not overstate legal non-repudiation.

### H4: detect-only consistency governance

M3 scans platform-owned tables for:

- instance/task state mismatches;
- delegation and handover evidence mismatches;
- collaboration policy state problems;
- notification delivery evidence problems;
- comment/revision and attachment reference problems;
- missing audit/business evidence.

Consistency checks are detect-only. They create a new immutable check and findings; they do not directly rewrite approval results, Flowable state or original failed checks. Check timestamps remain monotonic at PostgreSQL microsecond precision.

### H5: unified operational failure governance

M3 exposes one tenant-scoped operational view over:

- notification dead letters;
- business Outbox `DEAD` records;
- failed consistency checks;
- connector failures attributed to their owning notification or Outbox responsibility.

Single and bounded batch replay use the original state machines and conditional updates. Original failures and attempt histories remain available. Batch size is limited to 50 items, and partial acceptance is reported explicitly.

### H6: management permissions

All `/api/approval/management/**` handlers must declare a closed management capability. Undeclared handlers fail closed even when the configurable permission boundary is otherwise bypassed. Participant APIs outside the management path are unaffected.

The capability matrix is:

| Capability | Authority |
| --- | --- |
| Global management override | `approval.management.admin` |
| Read | `approval.management.read` |
| Design | `approval.management.design` |
| Publish | `approval.management.publish` |
| Deploy | `approval.management.deploy` |
| Activate | `approval.management.activate` |
| Transfer / employee handover | `approval.management.transfer` |
| Audit read | `approval.management.audit.read` |
| Audit export | `approval.management.audit.export` |
| Audit integrity verification | `approval.management.audit.verify` |
| Consistency read | `approval.management.consistency.read` |
| Detect-only consistency run | `approval.management.consistency.run` |
| Operational failure read | `approval.management.operational-failure.read` |
| Operational failure replay | `approval.management.operational-failure.replay` |

Authority sources are authenticated principal authorities or a bounded trusted-host header supplied only by a trusted host adapter or gateway. Browser, H5 and WeChat code never manufacture trusted management authorities. Permission logs and metrics use bounded, low-cardinality fields and do not expose the caller-supplied authority set.

### H7: deterministic failure injection and recovery

Real PostgreSQL transactions verify rollback and recovery across:

- command idempotency;
- audit insertion and chain-tail advancement;
- notification replay;
- business Outbox replay and attempt evidence;
- consistency replay;
- duplicate audit event IDs;
- nested transactional failure boundaries.

Concurrency tests use PostgreSQL locks, conditions, fixed clocks and deterministic transaction boundaries rather than random sleeps. Recovery never bypasses the existing state machines or deletes original evidence.

### H8: performance, indexing and migration scale

M3 validates tenant-prefixed, stable-order and status-queue indexes with production-shaped PostgreSQL queries and `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`.

Scale fixtures include, per tenant:

- 12,000 audit events;
- 12,000 notification intents;
- 12,000 consistency checks and 24,000 findings;
- 12,000 Outbox records;
- 6,000 instances, tasks, delegation rules, handovers, collaboration policies and participants;
- 20,000 comments and 20,000 comment revisions;
- 12,000 task-status records for pending/completed queue plans.

Deep and ordinary pages remain stable and tenant-scoped. The legacy general task-assignee index is replaced by pending and completed partial indexes aligned with actual filters and sort order.

### H9: PC, H5 and WeChat boundaries

PC management pages provide audit, detect-only consistency and operational recovery capabilities. Route authorities are user-experience hints only; backend permissions remain authoritative.

H5 and WeChat provide participant capabilities only. They do not reference tenant-management endpoints. All approval requests use governed transports that preserve stable backend error code, message, HTTP status, retryability and request ID. Sensitive management successes expose a request ID for support correlation.

Participant clients render server-generated timeline summaries and server-provided form/read-only state. They do not parse arbitrary audit attributes or reproduce backend workflow, collaboration, notification or replay state machines.

### H10: complete acceptance

H10 adds final committed evidence for:

- fresh V1 to V27 installation;
- M2 V13 to V27 upgrade;
- pre-H8 V23 to V27 upgrade;
- Flyway validation and final M3 table/index contracts;
- delegated-task, collaboration, private-comment, exact-notification and audit evidence in one instance;
- idempotent re-execution without duplicate policy, comment, notification or audit evidence;
- cross-tenant denial in the combined scenario;
- terminal-instance rejection of comment edit/delete and collaboration add/decision;
- detect-only consistency findings that preserve the inconsistent source state;
- audit integrity success followed by deliberate tamper detection;
- final tracked-tree repository hygiene.

## Final architecture boundaries

### Platform-owned persistence

Governance operates on platform-owned projections and evidence tables. Tenant IDs are mandatory on sensitive records and are included in read, mutation and replay predicates. Durable state transitions use constraints, unique keys, optimistic versions, conditional updates, transaction boundaries and advisory or row locks where required.

### Flowable boundary

The platform uses formal Flowable APIs through the engine SPI. M3 does not read, write, index or depend on Flowable internal tables. Repository searches found no production `ACT_` or `act_` table references. Consistency checks and operational replay never repair Flowable state by direct SQL.

Some dedicated Flowable adapter tests may use an in-memory Flowable engine to verify the adapter contract. Database governance and M3 persistence tests use real PostgreSQL/Testcontainers.

### Connector boundary

Organization lookup, notification delivery and business callback integrations remain behind connector ports. Connector failures retain provider/request evidence and are attributed to their owning notification or Outbox state machine.

### Security boundary

Frontend visibility, disabled states and route metadata improve user experience only. Backend identity, tenant isolation, capability checks, idempotency, concurrency control and audit evidence are the security boundary.

## Migration chain

M3 extends the existing migration chain without modifying committed migrations:

| Migration | Purpose |
| --- | --- |
| V14 | Delegation rules |
| V15 | Task delegation assignments |
| V16 | Employee handovers and task handover assignments |
| V17 | Task collaboration policies and participants |
| V18 | Vote and weighted collaboration extensions |
| V19 | Notification preferences, intents and attempts |
| V20 | Comment lifecycle and revisions |
| V21 | Versioned audit events and tenant hash chain |
| V22 | Detect-only consistency checks and findings |
| V23 | Operational failure and Outbox attempt governance |
| V24 | Management query-path indexes |
| V25 | Scaled participant and stable pagination indexes |
| V26 | Pending/completed task queue index replacement |
| V27 | Completed-task partial-index predicate alignment |

Final migration acceptance verifies V1→V27, V13→V27 and V23→V27 on isolated PostgreSQL databases. Flyway validation succeeds and the final M3 table/index contract is present.

## Final test and CI evidence

### PostgreSQL persistence module

`approval-persistence-jdbc` executed 147 tests with 0 failures, 0 errors and 0 skipped.

Key M3 integration classes include:

| Test class | Tests |
| --- | ---: |
| `JdbcApprovalDelegationIntegrationTest` | 3 |
| `JdbcDelegatingApprovalEngineIntegrationTest` | 3 |
| `JdbcApprovalHandoverIntegrationTest` | 4 |
| `JdbcApprovalTaskCollaborationIntegrationTest` | 19 |
| `JdbcApprovalCommentIntegrationTest` | 15 |
| `JdbcApprovalNotificationIntegrationTest` | 10 |
| `JdbcApprovalAuditIntegrationTest` | 8 |
| `JdbcApprovalConsistencyIntegrationTest` | 6 |
| `JdbcApprovalOperationalFailureIntegrationTest` | 7 |
| `JdbcApprovalCrossModuleFaultIntegrationTest` | 6 |
| `JdbcApprovalIndexPlanIntegrationTest` | 5 |
| `JdbcApprovalManagementScaleIntegrationTest` | 2 |
| `JdbcApprovalParticipantQueryScaleIntegrationTest` | 3 |
| `JdbcApprovalTaskStatusPlanIntegrationTest` | 3 |
| `JdbcApprovalMigrationUpgradeIntegrationTest` | 3 |
| `JdbcApprovalM3FinalAcceptanceIntegrationTest` | 3 |

All listed classes completed with 0 failures, 0 errors and 0 skipped.

### Server and permission coverage

`apps/server` executed 17 tests with 0 failures, 0 errors and 0 skipped, including:

- management interceptor: 6;
- automatic endpoint contract: 1;
- explicit permission coverage: 1;
- requirement matrix uniqueness: 1;
- PostgreSQL container wiring: 1;
- server serialization and transfer protocol regressions: 7.

### Client and repository contracts

- client boundary contract: 7 passed;
- final tracked-tree hygiene contract: 5 passed;
- Vben type-check and production build: 11 of 11 tasks successful;
- UniApp type-check: successful;
- H5 production build: successful;
- WeChat Mini Program build: successful.

### Complete reactor

All 16 Maven reactor modules completed successfully and the final result was `BUILD SUCCESS`.

## Repository hygiene result

The final tracked tree contains no committed:

- dependency or build directories;
- test/build logs;
- patch, diff, ZIP, TGZ or base64 payloads;
- compiled binaries;
- temporary PR workflows or patch helpers;
- accidental debug/output root files;
- rendered TODO, FIXME or debug comments in client Vue templates.

The only permanent validation workflow remains `.github/workflows/approval-platform-validation.yml`.

## Known non-goals

M3 intentionally does not:

- provide automatic consistency repair;
- claim legal-grade non-repudiation from the application hash chain;
- expose tenant-level management operations to H5 or WeChat;
- allow clients to choose backend authorities;
- read or modify Flowable internal tables;
- replace host authentication, directory or gateway integrations;
- provide unlimited audit export, replay batches or unbounded query windows;
- delete original failure, attempt, revision or audit evidence during replay;
- create an M4 branch or pull request.

## Suggested M4 directions

Possible M4 work, subject to a separate explicit decision, includes:

- production host authentication adapters and signed trusted-authority propagation;
- service-level objectives, dashboards and alert routing for worker lag, dead letters and consistency findings;
- long-term audit and attempt archival with retention/legal-hold policies;
- partitioning and online index maintenance for substantially larger tenants;
- connector certification suites and provider-specific throttling controls;
- accessibility, localization and end-to-end browser/device automation;
- controlled disaster-recovery exercises and multi-region operational procedures.

These are recommendations only. M3 acceptance does not create or modify an M4 branch, issue or pull request.
