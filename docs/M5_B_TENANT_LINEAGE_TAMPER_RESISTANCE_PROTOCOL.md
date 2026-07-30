# M5-B3 Tenant Isolation, Lineage and Tamper Resistance Protocol

M5-B3 status: `PERMANENTLY_VALIDATED`

M5-B remains `IN_PROGRESS`. This slice strengthens the governed migration persistence protocol only.
It does not authorize M5-C, migration-plan creation, authorization, execution, a worker, Flowable
invocation, runtime-binding mutation, an API, or a client action.

PR #58 remains Open + Draft. Issues #13, #14 and #56 remain Open.

## Tenant isolation

All six migration protocol tables retain tenant-scoped identities, primary keys, unique constraints and foreign
keys. The same stable UUID may exist independently in different tenants. Every JDBC read requires the
caller-supplied tenant and cannot return another tenant's intent, attempt, verification or
reconciliation evidence.

Permanent PostgreSQL tests prove:

- identical intent, attempt, verification and reconciliation UUIDs coexist in two tenants;
- tenant A reads only tenant A durable records and tenant B reads only tenant B records;
- cross-tenant attempt-to-intent and evidence-to-attempt bindings fail closed;
- a retry cannot bind a parent attempt that exists only in another tenant.

## Retry lineage

V36 strengthens the database insert protocol. Attempt 1 has no parent. Every later attempt must bind
the immediate `FAILED_RETRYABLE` parent in the same tenant, intent and approval instance, with
`attemptNumber = parent.attemptNumber + 1`.

The database rejects:

- a parent in another tenant;
- a parent in another intent;
- a parent for another approval instance;
- a parent that is not `FAILED_RETRYABLE`;
- skipped attempt numbers;
- a fabricated parent on attempt 1;
- a retry represented by updating the original attempt.

A valid retry always creates a new attempt identity and one revision-1 event. Persistence does not
schedule or automatically create retries.

## Current row and event atomicity

V36 adds deferred constraint triggers for intent and attempt current rows. At transaction commit, every
inserted or advanced current row requires a matching event with the same tenant, stable identity,
revision and resulting status.

This preserves the existing short transaction boundary while preventing a current row from being
created or advanced without its authoritative event. Event chains must be contiguous: revision N must
name revision N-1's resulting status as its source status.

## Payload and stable evidence resistance

Current-row JSON payloads must match their indexed tenant, identity, status, revision and hash columns.
On transition, only the closed mutable state/evidence fields may change; the remaining canonical
payload is immutable.

Intent/attempt identity hashes and stable identity columns remain immutable. Intent events, attempt
events, verification evidence and reconciliation evidence remain append-only. Direct update or delete
fails closed. Revision and evidence sequence gaps remain rejected.

Changed-evidence replay never returns an old object. Reusing an evidence identity with any different
canonical content produces `MigrationProtocolConflictException`.

## Terminal closure

Terminal intent and attempt states cannot return to an active state. Terminal reconciliation cannot be
updated, deleted or followed by additional reconciliation evidence. No retry is represented by
reopening or mutating a terminal attempt.

## Flyway scope

V33-V35 remain unchanged. M5-B3 adds only:

`V36__strengthen_process_migration_tenant_lineage_tamper_guards.sql`

V36 creates no new table and no execution capability. It strengthens payload consistency, retry
lineage, event-chain continuity and deferred current-row/event atomicity for the existing six tables.
No V37 or later migration is introduced.

## Permanent validation

- workflow: `Approval Platform Validation`;
- Run ID: `30083687987`;
- run number: `#523`;
- head: `7faa492161f5587ed4c02d9e62cedf992417b47d`;
- result: `success`;
- Repository hygiene, Java 21 / Maven / PostgreSQL, Vben and UniApp jobs all succeeded;
- raw logs for all four jobs were read.

### Artifact verification

| Artifact | ID | GitHub digest and downloaded ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30083687987` | `8592942933` | `90a56008b0055180fd56304d5ba7ae1af639b371d7afc0e4ad91becbd6d8da0f` — exact match |
| `approval-vben-30083687987` | `8592852935` | `04231889c91734701e1c21c6eb463168562863a84d56d9c9461abf746a938af5` — exact match |
| `approval-mobile-30083687987` | `8592834602` | `47b698fcda09928d462190cf2c6c28d459cb3bc386f296fb308dd13d66671e47` — exact match |
| `approval-hygiene-30083687987` | `8592813226` | `1561bc0041ecfa3e83a8ceb353662f240ee9179497bfef26d46243b371e0599c` — exact match |

### Test and build evidence

- Maven aggregate: `532` tests, `0` failures, `0` errors, `0` skipped;
- persistence-jdbc: `219/219`;
- M5-B domain and JDBC protocol tests: `29/29`;
- M5-B2 concurrent PostgreSQL/JDBC tests: `11/11`;
- M5-B3 tenant/lineage/tamper PostgreSQL tests: `8/8`;
- M5-A Flowable capability tests: `30/30`;
- M5 migration permanent Node boundaries: `24/24`;
- M4 SLA/calendar boundaries: `13/13`;
- M4 release governance boundaries: `5/5`;
- Vben client boundaries: `10/10`, type-check and production build passed;
- UniApp type-check, H5 build and WeChat Mini Program build passed;
- upgrade paths and the 5,000-instance/task evidence reach and validate Flyway V36.

## Explicit absences

- No worker or scheduler.
- No Flowable invocation or direct `ACT_*` access.
- No migration-plan creation, authorization or consumption.
- No execution, force, rollback or reconciliation endpoint.
- No runtime-binding mutation.
- No Web or Mobile migration action.
- No automatic retry, including `UNKNOWN`.
- No M6 dependency or semantic change.
- No second automatic workflow.
- No Ready, auto-merge, merge or issue closure.
- This slice does not authorize M5-C.

## Next gate

M5-B remains `IN_PROGRESS`. The next allowed slice is M5-B4 — Lease Ownership and Durable
`UNKNOWN`. M5-C remains blocked until all M5-B slices are complete and the user explicitly accepts
M5-B.
