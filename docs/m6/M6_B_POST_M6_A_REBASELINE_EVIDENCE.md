# M6-B-R0 Post-M6-A Rebaseline Permanent Evidence

Status: `REBASELINED_ON_POST_M6_A_MAIN / IMPLEMENTATION_PERMANENTLY_VALIDATED`

Production SDK transport execution: `NOT_AUTHORIZED`

## Scope and governance

This record covers M6-B-R0 for Draft PR #68 and branch
`agent/m6-b-sdk-event-ecosystem`.

R0 incorporated the formally accepted M6-A `main` history with a normal two-parent
merge commit. It did not add an SDK or event product capability, introduce a real
network transport, acquire usable credentials, create durable delivery or
reconciliation persistence, add a worker or scheduler, modify approval state, or
authorize production execution.

The following operations were not used:

- rebase, squash, amend, reset or history rewrite;
- force push;
- auto-merge;
- Ready-for-review transition;
- PR merge.

## Pre-rebaseline verified state

- repository: `akaryc1b/approval-platform`;
- post-M6-A `main`: `ebe7cb1ef92cb835810146f3120bd23ea94c586a`;
- M6-A PR #67: Merged / Closed;
- M6-A merge commit: `ebe7cb1ef92cb835810146f3120bd23ea94c586a`;
- M6-A Issue #63: Closed / completed;
- M6-B initial Head: `330dbdd035e436459ffdedf0d2b0c8e07dac7e6c`;
- initial relation to current `main`: ahead `20`, behind `443`;
- PR #68: Open + Draft, unmerged and mergeable;
- reviews, requested reviewers and unresolved review threads: none;
- repository auto-merge: disabled;
- Flyway: accepted mainline history continuous through `V48`;
- `V49` or higher: absent;
- only automatic PR/main workflow:
  `.github/workflows/approval-platform-validation.yml`.

Parallel workstreams remained isolated:

- PR #69: `72acb3ba18602c09c28bfe08b58f8b91e6efe6e4`;
- PR #70: `9d588215e869c8f1332c0bc1a2809fbd235c2efa`.

Issues #62, #64, #13 and #14 remained Open.

## Normal merge commit

R0 advanced the M6-B branch to the conflict-free GitHub-generated two-parent merge
commit with a non-forced fast-forward ref update:

- merge commit: `c68f3482dea6116a12c5a0e601f288531ee7e05d`;
- first parent: M6-B Head
  `330dbdd035e436459ffdedf0d2b0c8e07dac7e6c`;
- second parent: post-M6-A `main`
  `ebe7cb1ef92cb835810146f3120bd23ea94c586a`;
- branch ref update: `force=false`;
- merge conflicts: none.

The resulting branch relation was ahead `21`, behind `0`. An exact current-main to
branch comparison showed the same 111 M6-B SDK/event files as the pre-rebaseline
workstream. M5 and M6-A files visible in branch history are inherited mainline
history, not M6-B semantic modifications.

## Retained failed permanent workflow

The merge commit naturally triggered Approval Platform Validation Run
`30513600301` / #894 at Head
`c68f3482dea6116a12c5a0e601f288531ee7e05d`.

Job results:

- Repository hygiene: success;
- Java 21 / Maven / PostgreSQL: success;
- Vben TypeScript / production build: failure during root `postinstall`;
- UniApp TypeScript / H5 / WeChat: failure during the same root `postinstall`.

The failed Run was not cancelled, deleted, hidden or directly rerun.

### Root cause

The complete TypeScript SDK suite executed 84 tests and reported 83 passes and one
failure. The stale pre-M5 boundary `Flyway remains frozen through V32` rejected the
accepted mainline migration
`V33__create_process_migration_intents.sql` inherited from `main`.

This was a repository-baseline assertion defect. Java completed the full reactor
successfully, including all 73 host-SDK tests. No runtime, SDK contract, event
protocol, persistence or migration semantic failed.

### Failed Run artifact chain

Every ZIP was downloaded and independently hashed. Each local SHA-256 exactly
matched the GitHub artifact digest.

| Artifact | ID | GitHub digest / downloaded ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30513600301` | `8748143865` | `616b96be1cdbe49c4018281c51a885ca8638a810d137bb8cb3ba31436cda0caf` — exact match |
| `approval-vben-30513600301` | `8748018415` | `e74c09a2f28d4776dd175d6f1932447568132e4b1d89c2429f2ffc9640fabaab` — exact match |
| `approval-mobile-30513600301` | `8748019198` | `990d98428374a20684ac34f4fe28ba24609d4115db1f7a3952c4f3385f512c08` — exact match |
| `approval-hygiene-30513600301` | `8748018257` | `568f1019e8f9913e646d9af332fdba3928ca8c8a422a05e3224f7cc0462077a5` — exact match |

## Minimal post-merge compatibility fix

Commit:

`47db2fc86fa2e2fa2067f6f1e427265cdb7dde7b`

Message:

`fix(m6-b): rebaseline Flyway boundary on post-M6-A main`

The commit changed only `scripts/tests/m6-sdk-event-boundary.test.mjs`. It:

- recognizes the accepted inherited mainline migrations through `V48`;
- requires a `V48` migration to remain present;
- continues to reject every `V49` or higher migration;
- preserves all no-network, no-worker, no-persistence, no-trusted-client-authority,
  no-M5-execution-command and single-workflow boundaries.

It did not change production source, SDK contracts, event fixtures, Flyway files,
workflow configuration, Web behavior or Mobile behavior. The update was an
ordinary non-forced fast-forward.

## Successful implementation validation

Approval Platform Validation Run `30514030381` / #895 completed successfully at
Head `47db2fc86fa2e2fa2067f6f1e427265cdb7dde7b`.

Job results:

- Repository hygiene: success;
- Java 21 / Maven / PostgreSQL: success;
- Vben TypeScript / production build: success;
- UniApp TypeScript / H5 / WeChat: success.

Validation evidence:

- Maven aggregate: `1198 / 0 / 0 / 0`;
- host SDK: `73 / 0 / 0 / 0`;
- TypeScript SDK and permanent boundary suite: `84 / 84`;
- Maven reactor: `BUILD SUCCESS`;
- Vben type-check and production build: success;
- UniApp type-check, H5 build and WeChat build: success.

### Successful Run artifact chain

Every ZIP was downloaded and independently hashed. Each local SHA-256 exactly
matched the GitHub artifact digest.

| Artifact | ID | GitHub digest / downloaded ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30514030381` | `8748302268` | `d4501742264767414b5bb9dc358b71dc787c886d2cec8f18b069b6901dfd1795` — exact match |
| `approval-vben-30514030381` | `8748194563` | `ef1cdb2c6ddb88d7e42fcfd048e36e442d80622bba0358871fb10fd8c2bbacc7` — exact match |
| `approval-mobile-30514030381` | `8748183292` | `c6bec7d7199afc6cf3c2c2f4509c765339e862d43a10c1425e5e895d71c210b7` — exact match |
| `approval-hygiene-30514030381` | `8748168826` | `4268015b0ab4892b98bbb2c55cc0d0090de929df4c3381da8d6ddca099120e66` — exact match |

## Rebaseline invariants

After the fix Head:

- relation to `main`: ahead `22`, behind `0`;
- net M6-B changed files: `111`;
- Flyway remains continuous through `V48`;
- no `V49` or higher migration exists;
- no second automatic PR/main workflow exists;
- no production endpoint, DNS, discovery or routing implementation exists;
- no usable credential material or authentication executor exists;
- no durable subscription, event delivery, aggregation, checkpoint, escalation,
  reconciliation or audit persistence exists;
- no production broker, queue, worker, scheduler, clock or retry executor exists;
- no approval-state mutation or M5 migration execution command is exposed;
- PR #68 remains Open + Draft and unmerged;
- auto-merge remains disabled;
- PR #69 and PR #70 Heads remain unchanged.

## R0 acceptance

```text
M6-B R0:
  REBASELINED_ON_POST_M6_A_MAIN
  IMPLEMENTATION_PERMANENTLY_VALIDATED

Flyway:
  V48
  NO_V49

PR #68:
  OPEN
  DRAFT
  NOT_MERGED

Production SDK/event execution:
  NOT_AUTHORIZED
```
