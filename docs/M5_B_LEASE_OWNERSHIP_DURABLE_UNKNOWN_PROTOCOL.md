# M5-B4 Lease Ownership and Durable UNKNOWN Protocol

M5-B4 status: `IMPLEMENTED_AWAITING_PERMANENT_VALIDATION`

M5-B remains `IN_PROGRESS`. This slice strengthens the platform-owned migration persistence protocol only.
It does not authorize M5-C, migration-plan creation or approval, a migration executor, a worker,
Flowable invocation, runtime-binding mutation, an API, or Web/Mobile execution controls.

PR #58 remains Open + Draft. Issues #13, #14 and #56 remain Open.

## Lease ownership

A migration attempt may enter `CLAIMED` only with a nonblank lease owner, a lease expiry strictly after
the transition time, and an append-only event naming the lease actor and resulting lease evidence.
Revision remains the database fencing token.

The transition application port has an explicit `leaseActor` overload. JDBC persists that actor in the
current attempt's latest-transition evidence and in the matching attempt event. The pre-M5-B4 overload
remains only for source compatibility and derives the actor from the authoritative current row or the
resulting claim; it does not accept caller-manufactured authority.

Closed lease outcomes are:

- `PENDING -> CLAIMED`: actor and resulting owner must be identical;
- `CLAIMED -> CLAIMED`, same owner: the current owner may renew only before expiry and the new expiry
  must strictly extend the previous expiry;
- `CLAIMED -> CLAIMED`, different owner: takeover is accepted only at or after the previous expiry,
  and the actor must become the resulting owner;
- `CLAIMED -> another state`: only the current owner may advance the attempt, and only before expiry;
- a former or stale owner cannot renew, take over, or advance a revision after another owner succeeds;
- concurrent takeover attempts compete on the same revision and exactly one can own the next revision.

There is no automatic lease acquisition, renewal, takeover, scheduler, polling loop, worker, or retry.

## Durable attempt and event columns

V37 adds independently queryable evidence to `ap_process_migration_attempt`:

- `lease_actor`;
- `engine_request_reference`;
- `failure_class`;
- `error_summary`.

It adds append-only result evidence to `ap_process_migration_attempt_event`:

- `engine_outcome`;
- `lease_actor`;
- `lease_owner`;
- `lease_until`;
- `engine_request_reference`;
- `failure_class`;
- `error_summary`.

New event JSON contains the same evidence. Historical pre-V37 event JSON remains readable even where
lease-result fields were not previously available. Current rows and new events must match their durable
columns at transaction commit.

## Durable UNKNOWN

`ENGINE_REQUESTED -> UNKNOWN` is accepted only when all of the following are durable and mutually
consistent:

- engine outcome is `UNKNOWN`;
- the exact engine request reference is preserved;
- failure class is `ENGINE_OUTCOME_UNKNOWN`;
- bounded nonblank error evidence is present;
- no lease remains.

`UNKNOWN` is never retryable and has no transition to `FAILED_RETRYABLE`. Never retry `UNKNOWN`
automatically.

Before `UNKNOWN -> RECONCILING`, the same tenant-scoped attempt must already have an `OPEN` or
`MANUAL_REVIEW_REQUIRED` reconciliation sequence. The attempt preserves its engine outcome and request
reference and records failure class `RECONCILIATION_REQUIRED`.

An attempt whose event history contains `UNKNOWN` cannot leave `RECONCILING` for a terminal attempt
state until the latest reconciliation evidence is one of:

- `RESOLVED_SOURCE`;
- `RESOLVED_TARGET`;
- `RESOLVED_TERMINAL`;
- `UNRESOLVED`.

This is durable evidence gating, not rollback. No external engine call is part of the platform database
transaction.

## Flyway scope

V33-V36 remain unchanged. M5-B4 adds only:

`V37__strengthen_process_migration_lease_unknown_guards.sql`

V37 creates no new table and no execution capability. The same six M5-B tables remain. No V38 or later
migration is introduced.

## Permanent test boundary

`JdbcApprovalMigrationLeaseUnknownIntegrationTest` uses PostgreSQL 16 Testcontainers and closes eight
scenarios:

1. current-owner claim and renewal with durable actor evidence;
2. stale/different owner rejection before expiry;
3. expiry takeover and former-owner fencing;
4. concurrent expired takeover with exactly one revision owner;
5. independent durable UNKNOWN current/event columns;
6. rejection of UNKNOWN progression without open reconciliation evidence;
7. terminal reconciliation evidence required before UNKNOWN-derived attempt closure;
8. direct lease, UNKNOWN and append-only tampering rejection.

The concurrent scenario uses independent JDBC stores, executor threads, start gates and bounded future
timeouts. It uses no sleep, retry annotation or scheduler.

## Explicit absences

- no worker, scheduler or polling loop;
- no automatic lease renewal or takeover;
- no migration-plan creation, authorization or consumption;
- no Flowable migration invocation;
- no runtime-binding transition;
- no execute, force, rollback or reconciliation endpoint;
- no Web or Mobile execution control;
- no automatic migration;
- no automatic retry of `UNKNOWN`;
- no direct Flowable `ACT_*` access;
- no M6 change;
- no second automatic workflow;
- no Ready, auto-merge, merge or issue closure;
- this slice does not authorize M5-C.
