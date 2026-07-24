# M5-B4 Lease Ownership and Durable UNKNOWN Protocol

M5-B4 status: `PERMANENTLY_VALIDATED`

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

Jackson may serialize `Instant` values as a numeric epoch or as an ISO-8601 string. V37 uses the closed
`ap_migration_json_instant_v37` conversion function for both forms and rejects every other JSON type.
This preserves exact timestamp evidence without weakening current-row/event consistency.

## Durable UNKNOWN

`ENGINE_REQUESTED -> UNKNOWN` is accepted only when all of the following are durable and mutually
consistent:

- engine outcome is `UNKNOWN`;
- the exact engine request reference is preserved;
- failure class is `ENGINE_OUTCOME_UNKNOWN`;
- bounded nonblank error evidence is present;
- no lease remains.

`UNKNOWN` is never retryable and has no transition to `FAILED_RETRYABLE`.
Never retry `UNKNOWN` automatically.

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

## Retained failed validation

Run `30088435734` / #525 at head `bc19c441bfef6daa6054fe531d328eeeac5db2c4`
is permanently retained and was not rerun.

Vben and Mobile succeeded. Repository hygiene failed only because this document split the exact
`Never retry UNKNOWN automatically` boundary phrase across two lines. Java reached the persistence-jdbc
module but failed before the new scenarios could execute because the first V37 trigger directly cast a
Jackson numeric epoch value to `timestamptz`.

The minimal correction in commit `d11928f6e0e237e21fbccb48ba26dc1ddf17b3b7`:

- adds one closed JSON timestamp conversion function supporting numeric epoch and ISO-8601 string values;
- uses it for lease, created, updated and event timestamps;
- places the permanent UNKNOWN prohibition phrase on one line;
- changes no lease transition, UNKNOWN, reconciliation, test outcome or execution-authority rule.

All four Run #525 artifacts were downloaded. Their ZIP SHA-256 values exactly matched GitHub digests:

| Artifact | ID | SHA-256 |
| --- | ---: | --- |
| `approval-maven-30088435734` | `8594775973` | `6ed2c035621ba1b5e2c577c72c32bd6846beb84242af1cb28eb38e32c714a7a9` |
| `approval-vben-30088435734` | `8594701157` | `b3c71c7d036e2186af9d215ff047f08799a46727a730714be278d2cb39448139` |
| `approval-mobile-30088435734` | `8594764993` | `7ea60b42248a6dde8d63936b486b1cb046a1f22bf7d6947fd9461dc401f473a2` |
| `approval-hygiene-30088435734` | `8594665415` | `dae2fa5ee728f77a67275e33570cd3babbd6657c499522215007e3922d77a22b` |

## Permanent successful validation

Approval Platform Validation Run `30089052236` / #526 at head
`d11928f6e0e237e21fbccb48ba26dc1ddf17b3b7` completed successfully. All four raw job logs were read.

Test and build evidence:

- Maven aggregate: 540 tests, 0 failures, 0 errors, 0 skipped;
- persistence-jdbc: 227/227 passed;
- M5-B domain and JDBC protocol tests: 37/37 passed;
- M5-B2 concurrent PostgreSQL/JDBC tests: 11/11 passed;
- M5-B3 tenant/lineage/tamper PostgreSQL tests: 8/8 passed;
- M5-B4 lease/UNKNOWN PostgreSQL tests: 8/8 passed;
- M5-A Flowable capability tests: 30/30 passed;
- M5 migration permanent Node boundaries: 28/28 passed;
- M4 SLA/calendar boundaries: 13/13 passed;
- M4 release governance boundaries: 5/5 passed;
- Vben client boundaries: 10/10 passed;
- Vben type-check and production build passed;
- UniApp type-check, H5 build and WeChat Mini Program build passed;
- fresh, historical, V36-to-V37 and 5,000-instance/task upgrade paths reached and validated V37.

All four Run #526 artifacts were downloaded. Their ZIP SHA-256 values exactly matched GitHub digests:

| Artifact | ID | SHA-256 |
| --- | ---: | --- |
| `approval-maven-30089052236` | `8595033536` | `9f55ce4df4c4a4b7f8932e8f95a6029d4e088ac99fa929b3dd9bb6edbed5e52f` |
| `approval-vben-30089052236` | `8594942861` | `333bad1049db3b611aa26dde415a2bc31bb78e11222464472248ded11f347144` |
| `approval-mobile-30089052236` | `8594927803` | `51d21c68447829b2d7c9fa11dd434129581a75a95f1080f3147bf33a429520b1` |
| `approval-hygiene-30089052236` | `8594908230` | `540d062faf94ea4979b109a0d960a37eba7a07c11dd55e70b0bf4c54509028c2` |

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
