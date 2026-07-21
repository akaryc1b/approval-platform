# M3 H9 Client Boundary Governance

## Status and evidence

H9 is validated on code HEAD `528321d82db4244759647251c0cec408a49206e7` by the permanent workflow `.github/workflows/approval-platform-validation.yml`, run `29798890487`.

The code-head run completed all four permanent jobs successfully:

- Repository hygiene;
- Java 21 / Maven / PostgreSQL;
- Vben TypeScript / production build;
- UniApp TypeScript / H5 / WeChat.

Downloaded artifacts confirm:

- client boundary contract: 7 tests, 7 passed;
- `approval-persistence-jdbc`: 143 tests, 0 failures, 0 errors, 0 skipped;
- `apps/server`: 17 tests, 0 failures, 0 errors, 0 skipped;
- complete 16-module Maven reactor: `BUILD SUCCESS`;
- Vben type-check and production build: 11 of 11 tasks successful;
- UniApp type-check successful;
- H5 production build successful;
- WeChat Mini Program build successful.

## Client responsibility matrix

### PC management client

The PC client may provide tenant-management user experiences for:

- audit query, bounded export and integrity verification;
- detect-only consistency checks and finding inspection;
- operational failure query and attempt evidence;
- single and bounded batch replay;
- employee handover administration.

These routes contain host-side capability hints only for navigation and user experience. The hints are not authorization decisions. Every management endpoint remains protected by the backend fail-closed management permission interceptor and its explicit capability annotations.

Management operations state their effect precisely:

- consistency checks remain detect-only;
- operational replay returns work to the original queue state machine;
- replay does not delete original failure evidence or attempts;
- batch replay remains bounded to 50 items;
- audit integrity verification detects mutation or chain breaks but does not claim legal non-repudiation.

### H5 and WeChat participant clients

The mobile overlay provides participant-scoped capabilities only:

- pending, processed and initiated approval views;
- approve, reject, resubmit, transfer, withdraw and retrieve actions when allowed by the server;
- delegation and collaboration participation;
- comments, private comments, replies, revisions and attachments;
- notification and message centers;
- server-generated approval timeline summaries;
- server-provided form runtime field permissions and terminal read-only state.

The mobile overlay does not expose or reference:

- tenant audit query or export;
- audit integrity verification;
- tenant consistency scans;
- tenant operational failure queues;
- operational batch replay;
- any `/api/approval/management/**` endpoint.

## Trusted authority boundary

Browser, H5 and WeChat source code must not set `X-Approval-Trusted-Permissions`.

H9 removed all client-forged trusted management authorities from audit, consistency, operational failure and handover API modules. The trusted-header authority source remains a backend deployment boundary intended for a trusted host adapter or gateway, not an untrusted browser or Mini Program.

A permanent client boundary test recursively scans Web and Mobile source trees and fails if:

- either overlay writes the trusted permission header;
- the mobile overlay references a management endpoint;
- a management API bypasses the governed Web transport;
- a participant API bypasses the governed Mobile transport.

## Governed transports

### Web transport

The shared Web approval transport:

- sends tenant and operator context without manufacturing permissions;
- uses same-origin credentials;
- generates request and trace identifiers for reads and commands;
- preserves stable backend `code`, `message`, `retryable`, `requestId`, occurrence time and HTTP status in `ApprovalApiError`;
- represents network failures as a stable retryable `APPROVAL_NETWORK_ERROR`;
- supports traced successful responses for sensitive management commands.

Successful audit verification, audit export, employee handover creation/revocation and operational single/batch replay expose a request ID in the resulting page message, alert or export identity.

### Mobile transport

The shared Mobile approval transport governs task, form, message, delegation, identity, notification, collaboration and comment requests. It:

- generates request and trace identifiers for reads and commands;
- preserves stable backend error code, message, retryability, request ID and HTTP status;
- represents network failures with a stable retryable error;
- supports explicit 404-as-absence only where the API contract permits it;
- carries idempotency keys for participant mutations;
- provides the same structured failure semantics to H5 and WeChat builds.

Attachment upload and download continue to use the platform-specific UniApp file APIs, but use the same tenant/operator/request context and structured error model. They do not depend on response-header fields that UniApp does not expose consistently.

## Server-authoritative behavior

The clients do not duplicate backend state machines. They submit intent and display the authoritative server response.

Examples include:

- collaboration completion remains governed by the backend `ALL`, `ANY`, `VOTE` and `WEIGHTED` state machine;
- replay uses existing notification, Outbox and consistency transitions;
- comment edit/delete availability and terminal read-only state come from server responses;
- form field access and required state come from the server form runtime;
- action availability remains protected by backend tenant isolation, identity, status, version and idempotency checks.

Hiding or disabling a button is an experience optimization and never the security boundary.

## Timeline and privacy

Participant clients render the server-generated timeline `summary`, schema name and schema version. They do not interpret arbitrary audit attributes to recreate lifecycle semantics.

Comment privacy, mention scope and attachment access remain enforced by backend audience queries and attachment authorization. A mobile display decision cannot expand the server-authorized audience.

## User states

The PC and mobile clients retain explicit states for:

- loading;
- empty data;
- permission or request failure;
- terminal read-only content;
- optimistic-lock or state conflict;
- retryable network/server failure.

Errors shown to users include stable backend context and request IDs where available, so support and operations can correlate a client failure without exposing trusted authority data.

## Non-goals

H9 does not:

- copy all tenant-management features to mobile;
- make route metadata an authorization mechanism;
- let a browser choose backend capabilities;
- parse Flowable internal tables;
- reproduce backend workflow, collaboration, notification or replay state machines in TypeScript;
- add mock production data or client-side security shortcuts.
