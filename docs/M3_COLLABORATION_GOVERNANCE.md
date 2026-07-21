# M3 Collaboration, Delegation and Notification Governance

## Status and branch governance

M3 is active on `agent/m3-collaboration-delegation-and-notification-hardening` from the fully merged M2 baseline on `main`.

PR #54 must remain:

- open;
- Draft;
- based directly on `main`;
- not stacked, merged, closed or auto-merged;
- updated without force push or branch deletion.

Issues #13 and #14 remain open until the complete M3 scope is finished and an explicit close request is given.

## Permanent validation contract

Every committed implementation and documentation head is validated by:

`.github/workflows/approval-platform-validation.yml`

The permanent workflow verifies:

- repository hygiene and rejection of temporary PR artifacts;
- Java 21, the full Maven reactor and PostgreSQL/Testcontainers tests;
- Vben TypeScript and the production build;
- UniApp TypeScript, H5 and the WeChat Mini Program build.

No PR-only workflow, self-committing CI, helper payload, patch, archive, downloaded artifact or generated build output may be committed.

## Architecture guardrails

- Platform-owned projections and application ports are authoritative.
- Flowable tables are never read or mutated directly.
- Identity selection uses exact immutable identity evidence rather than free-text user identifiers.
- Delegation, handover, collaboration, notification and comment decisions are tenant isolated, idempotent, concurrency safe and auditable.
- Notification delivery remains independent from the business callback Outbox.
- PC, H5 and WeChat consume the same server-authoritative semantics.
- Frontend visibility never replaces backend authorization or database/platform query filtering.
- Audit hash chaining is platform tamper-evidence and is not represented as legal non-repudiation.

## Completed increment evidence

| Increment | Scope | Verified head | Permanent run |
| --- | --- | --- | --- |
| A | delegation policy foundation | `d4308b0e6d4ab281aaff484d4d486fd2ad0db74d` | `29713024085` |
| B | delegated task assignment and responsibility evidence | `c4cd5e235d65c49f2a258ba949e94b0522406b8c` | `29714577565` |
| C | exact identities and employee handover | `81e5b31ec20c816e13f2861546df8431a70a8532` | `29718536112` |
| D | governed add-sign and remove-sign | `6195341eea242651b783ddfdfc1a98d6065a0601` | `29720696299` |
| E | vote and weighted collaboration | `e02088e65f35984c7988e4c08165ab5e7cb24ba5` | `29726800409` |
| F | notification preferences and reliable delivery | `47ce510e6a906461855e071875d3dbb89eaeaace` | `29732695556` |
| G | governed comments, mentions and collaboration records | `c808abcf5cec103dbff7002bd3ccaa3733d105c5` | `29742019154` |
| H1–H3 | versioned audit contracts, integrity and bounded export | `10a6153f3d490c44a6f0b864976d5739eddcbee4` | `29751940560` |

### Increment A — delegation policy foundation

Increment A established tenant-owned global and definition-specific delegation rules, overlap-safe PostgreSQL writes, deterministic precedence, idempotent create/revoke commands, stable errors and immutable audit evidence.

### Increment B — delegated task assignment

Increment B connected effective delegation to real Flowable user tasks through the existing engine SPI while preserving original-principal responsibility evidence and synchronizing completion, transfer, retrieve and terminal states.

### Increment C — exact identity and employee handover

Increment C removed free-text identity entry, added connector-backed immutable identity snapshots and implemented current/future task handover with formal handover precedence over temporary delegation.

### Increment D and E — collaboration policies

The existing collaboration aggregate supports `ALL`, `ANY`, `VOTE` and `WEIGHTED`, exact participants, count and weight thresholds, real-time reachability, governed add/remove operations, immutable decisions and parent-task state gates. The original task owner remains the Flowable responsibility owner.

### Increment F — reliable notification delivery

Increment F added notification intents independent from the business callback Outbox, `IN_APP`, `CONNECTOR` and `EMAIL` preferences, timezone-aware quiet hours, emergency bypass, retries, exponential backoff, delivery attempts, dead letters, replay and shared PC/H5/WeChat notification centers. `JdbcApprovalNotificationIntegrationTest` executed 10 tests with no failures, errors or skips.

## Increment G — governed comments, mentions and collaboration records

Increment G extends the existing comment, attachment, message and notification subsystems. It does not create a parallel comment implementation.

### Comment lifecycle and revision evidence

The existing `ApprovalCommentService`, `ApprovalCommentStore`, `JdbcApprovalCommentStore` and `ApprovalCommentController` now support:

- `ACTIVE` and `DELETED` lifecycle states;
- creation and one-level replies;
- author-only edits and tombstone deletion;
- a 15-minute edit/delete window;
- `expectedVersion` optimistic concurrency;
- stable tombstone text while retaining historical evidence;
- mandatory persisted delete reasons;
- immutable structured `CREATE`, `EDIT` and `DELETE` revisions;
- revision number, body, mentions, attachments, visibility, operator, reason and occurrence time;
- revision-body access restricted to the comment author or approval initiator;
- terminal approval instances retaining readable history while the comment area becomes read-only.

Historical bodies, mention evidence and attachment references are never physically deleted and are not stored only inside generic audit JSON.

### Visibility and private replies

Comments support:

- `PARTICIPANTS`: approval participants may read the current comment;
- `MENTIONED_ONLY`: only the author and explicitly mentioned recipients may read it.

Private comments must mention at least one valid approval participant. Replies inherit the parent visibility, cannot expand a private audience and must preserve the parent author in the private reply audience when required.

Comment-list visibility is filtered by PostgreSQL/platform query criteria before records are returned. Clients do not receive an unrestricted list for frontend-only hiding.

### Precise mention identities

Mention candidates are assembled from platform-owned evidence for:

- the initiator;
- current and historical task participants represented by approval projections;
- copy/message participants;
- collaboration/add-sign participants;
- immutable approval identity snapshots.

Candidate evidence includes internal user ID, display name, identity source, object type and external identity value. Create and edit commands reject users who are not valid participants in the approval instance.

The existing `MENTION` message and `COMMENT_MENTION` notification paths are reused. A create revision notifies its recipients once; an edit revision notifies only newly added recipients. Existing recipients are not notified again merely because the body changed.

### Attachment governance

Comment attachments must:

- be uploaded by the current comment author;
- belong to the same tenant and approval instance;
- never reference another user's upload or another approval instance;
- pass the same ownership validation during edits.

Attachment download authorization checks comment references. When an attachment is referenced by private comments, a participant cannot bypass private comment visibility by requesting the attachment ID directly.

### Collaboration participant authority

Add-sign/collaboration participants are formal comment participants. They may read visible comments, create and reply, be precisely mentioned and download visible comment attachments. Collaboration status does not grant authority to modify another author's comment, read unrelated private comments, read all revision bodies or perform comment administration.

### API contract

The existing controller path exposes:

- `POST /api/approval/instances/{instanceId}/comments`;
- `PUT /api/approval/instances/{instanceId}/comments/{commentId}`;
- `DELETE /api/approval/instances/{instanceId}/comments/{commentId}`;
- `GET /api/approval/instances/{instanceId}/comments/{commentId}/revisions`;
- `GET /api/approval/instances/{instanceId}/comments/options`;
- `GET /api/approval/instances/{instanceId}/comments`.

Stable errors distinguish invalid request, unauthorized access, not found, expired edit window, read-only instance, concurrent modification, invalid mention, private audience expansion, attachment ownership conflict and deleted-comment state conflict.

### Database migration

Flyway `V20__govern_approval_comment_lifecycle.sql` adds:

- status, visibility, current revision and optimistic version fields;
- update and deletion metadata;
- the structured comment-revision table;
- lifecycle and metadata constraints;
- visibility, mention, attachment and revision indexes;
- compatibility backfill for legacy comments as `ACTIVE`, `PARTICIPANTS`, revision 1 with initial revision evidence.

The migration uses only platform-owned tables.

### PC, H5 and WeChat clients

The existing PC and mobile comment components share the server contract for:

- visibility selection and private markers;
- exact mention candidates with identity evidence;
- attachments and one-level replies;
- author edit and delete actions;
- mandatory delete reasons;
- edited state and tombstones;
- revision history;
- terminal read-only state;
- collaboration-participant use.

The mobile implementation avoids browser-only APIs. H5 and WeChat use the same UniApp page and both are built by permanent CI. No native Mini Program `switch` uses `v-model`.

### PostgreSQL validation evidence

Code head `4e887fe47e0bd5564418a0a10a67f0e58ea0fbed` passed permanent workflow run `29741633579`:

- repository hygiene: passed;
- Java 21 / Maven / PostgreSQL: passed;
- `JdbcApprovalCommentIntegrationTest`: 15 tests, 0 failures, 0 errors, 0 skipped;
- full 16-module Maven reactor: `BUILD SUCCESS`;
- Vben TypeScript and production build: passed;
- UniApp TypeScript, H5 and WeChat builds: passed.

The PostgreSQL/Testcontainers matrix covers create/edit revisions, new-mention notifications, private visibility and attachment bypass prevention, private reply inheritance, author permissions, the 15-minute window, optimistic conflicts, tombstones, terminal read-only state, collaboration participants, attachment ownership, revision permissions, tenant isolation, idempotency, audit evidence and mention-notification deduplication.

The permanent workflow exposed and verified real implementation corrections before the code head became green:

- constructor wiring was updated after attachment download became comment-audience aware;
- PC and mobile page models were updated for the server-owned read-only flag;
- a test fixture was corrected to reuse an already published tenant definition rather than weaken the production uniqueness constraint.

## Increment H1–H3 — audit contract, integrity and administration

H1–H3 evolve the existing `AuditEvent`, `AuditEventSink` and `JdbcAuditEventSink` path. They do not create a second audit fact store.

### Versioned event contracts

`AuditEventContract` assigns stable schema names and schema versions across delegation, employee handover, collaboration, voting, notifications, comments, task lifecycle, process lifecycle and audit administration.

Newly emitted events include:

- event ID;
- tenant ID;
- aggregate type and aggregate ID;
- action;
- schema name and schema version;
- operator ID;
- request ID and trace ID;
- occurrence time;
- normalized attributes.

The domain validates current contract versions and required attributes before persistence. Comment lifecycle events require comment ID, revision and visibility evidence. The pre-revision compatibility action `INSTANCE_COMMENTED` remains readable and usable by the existing notification path without weakening the new `CREATED`, `EDITED` and `DELETED` contracts.

Legacy persisted events are backfilled as schema version 0 and remain queryable. Participant timelines expose server-produced `summary`, `schemaName` and `schemaVersion`; PC, H5 and WeChat no longer interpret arbitrary audit attributes for timeline descriptions.

### Tenant-isolated integrity evidence

Flyway `V21__version_and_chain_audit_events.sql` adds:

- schema name and version columns;
- per-tenant sequence numbers;
- previous, canonical payload and current SHA-256 hashes;
- a tenant chain-state table;
- deterministic PostgreSQL hash functions;
- compatibility backfill for existing audit rows;
- tenant/time/action, operator, aggregate, request and trace indexes.

`JdbcAuditEventSink` locks a tenant chain-state row before appending, inserts the event and advances the chain state in one transaction. A failed insert cannot leave a half-written event or advance chain state. Tenant chains are independent.

The verification API checks sequence continuity, previous hashes, canonical payload hashes, current hashes and stored chain-tail state. It returns the first invalid event and sequence when corruption is detected. The API and PC page explicitly state that this is platform tamper-evidence, not a claim of legal non-repudiation.

### Bounded query and export

Management APIs are protected by the existing fail-closed permission interceptor with separate authorities:

- `approval.management.audit.read`;
- `approval.management.audit.export`;
- `approval.management.audit.verify`.

The API supports filtering by operator, action, aggregate, request ID, trace ID and time range with server pagination. Time ranges are limited to 31 days. Structured CSV and JSON exports are generated server-side, capped at 10,000 rows and rejected when the result would exceed the requested maximum. Sensitive attribute names are redacted before management query or export results are returned.

Audit export and integrity verification are idempotent operations and generate their own versioned audit events.

### PC and participant clients

The PC audit-governance workspace provides:

- bounded filters and pagination;
- schema/version and hash details;
- integrity verification;
- server-side CSV or JSON export;
- clear separation from ordinary participant functionality.

Participant PC, H5 and WeChat timelines consume the same server-authoritative versioned summaries. Mobile clients do not expose tenant-wide audit query, export or verification controls.

### PostgreSQL and permanent-CI evidence

Code head `10a6153f3d490c44a6f0b864976d5739eddcbee4` passed permanent workflow run `29751940560`:

- repository hygiene: passed;
- Java 21 / Maven / PostgreSQL: passed;
- `JdbcApprovalAuditIntegrationTest`: 8 tests, 0 failures, 0 errors, 0 skipped;
- `JdbcApprovalNotificationIntegrationTest`: 10 tests, 0 failures, 0 errors, 0 skipped;
- `JdbcApprovalCommentIntegrationTest`: 15 tests, 0 failures, 0 errors, 0 skipped;
- approval PostgreSQL module: 109 tests, 0 failures, 0 errors, 0 skipped;
- full 16-module Maven reactor: `BUILD SUCCESS`;
- Vben TypeScript and production build: passed;
- UniApp TypeScript, H5 and WeChat builds: passed.

The audit PostgreSQL matrix covers schema/version assignment, tenant-chain isolation, 24-way concurrent append serialization, deterministic hash linkage, payload tamper detection, transaction rollback after duplicate failure, bounded filtering/pagination, sensitive-field redaction, range limits, idempotent audited export/verification and current-contract required fields.

Permanent CI exposed and verified these corrections before the stage became green:

- the audit store transaction-manager import was corrected after a real Java compile failure;
- the concurrency fixture was corrected from an eight-thread deadlock to 24 simultaneous writers;
- the legacy `INSTANCE_COMMENTED` notification action was retained as a compatibility contract while new lifecycle actions remain strict;
- frontend timeline rendering was changed to server-owned versioned summaries rather than arbitrary audit attributes.

## Next increment

Increment H4 and later stages will add projection-consistency findings, carefully governed repair/replay actions, separated failure-queue operations, the unified permission matrix, cross-module fault tests, index-plan evidence and final M3 acceptance without direct Flowable-table access.

## Initial M3 baseline

M3 started from `main` merge commit `4e468f9f049b52cb20855872cddf44eaa237501b`, which contains the complete M2 delivery and the permanent validation workflow.
