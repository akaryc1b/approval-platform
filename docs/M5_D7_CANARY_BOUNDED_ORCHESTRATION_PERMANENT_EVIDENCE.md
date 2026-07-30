# M5-D7 Canary, Bounded Orchestration and Kill Switch Permanent Evidence

M5-D7 status: `COMPLETE / PERMANENTLY_VALIDATED`

M5-D remains `IN_PROGRESS`. Production migration execution remains `NOT_AUTHORIZED`.
PR #58 remains Open + Draft. Issues #13, #14 and #56 remain Open.
M5-D8, M5-E, M5-F and M5-G remain not started.

## Scope

D7 adds one internal, server-owned, default-disabled one-shot orchestration boundary over the accepted
D1 through D6 protocol. It does not create another migration protocol, a scheduler, a resident worker,
a public execution endpoint, a definition-wide engine operation, a batch Flowable invocation, a retry
of `UNKNOWN`, rollback semantics or production execution authority.

The accepted one-shot order is:

1. one short PostgreSQL preparation transaction selects or replays one deterministic canary and one
   immutable orchestration run;
2. the existing D2 bounded claim acquires at most the configured closed limit;
3. before every new D3 dispatch, one immutable server-owned Kill Switch observation is recorded;
4. each admitted Attempt still executes the existing D3, D4 and D5 single-instance pipeline;
5. one short PostgreSQL finalization transaction records immutable bounded-batch and pause/completion
   evidence.

Every Flowable migration/readback remains outside every platform transaction.

## Deterministic canary

The versioned algorithm is `CANONICAL_FIRST_V1`.

The only valid canary is sequence 1 in the sealed plan's canonical selected-instance sequence. The
client cannot supply or replace it. PostgreSQL V47 validates the exact consumed plan, intent, canonical
sequence, approval instance and instance evidence hash before accepting the immutable selection.

The same plan produces one selection under exact replay and under a two-node race. Changed replay,
a forged second instance, cross-tenant identity or mismatched evidence fails closed.

## Bounded orchestration

The internal `ApprovalMigrationBoundedOrchestrationService`:

- is tenant-, plan- and intent-scoped;
- accepts only limits from 1 through 100;
- reuses D2 `FOR UPDATE SKIP LOCKED` claim and command fence;
- processes each Attempt separately and in canonical claim order;
- uses one finite `for` loop and contains no scheduler or polling loop;
- stops the batch at the first non-`EXACTLY_COMPLETED` disposition;
- records one immutable run, event lineage and optional bounded-batch evidence;
- uses revision and predecessor hashes for exact replay;
- pauses on unresolved, incomplete, stale or conflicting evidence.

The plan remains paused for `UNKNOWN`, `RECONCILING`, manual review, binding CAS conflict, terminal
failure, stale worker, stale lease, missing/incomplete evidence, empty batch, Kill Switch or revision
drift.

## Kill Switch

The configured Kill Switch is server-owned and checked again before every new engine dispatch.

An observation records expected/observed revision, enabled state, request hash, result and evidence
hash. Dispatch is allowed only when the switch is disabled and the revision exactly matches. An active
or stale switch creates immutable blocked evidence and no D3 call.

The Kill Switch never deletes an engine request/outcome, changes verification or runtime binding,
converts `UNKNOWN`, fabricates rollback/cancellation, or claims that an already-sent Flowable call was
cancelled.

## Cross-node replay serialization

`PostgresSerializedApprovalMigrationOrchestrationStore` acquires a dedicated session advisory lock
before any delegate replay read:

- preparation is serialized by tenant and intent;
- dispatch authorization and finalization are serialized by tenant and run;
- the existing JDBC delegate retains its short transaction ownership;
- the lock is explicitly released before the connection returns to the pool;
- advisory-lock failure fails closed and requires exact replay;
- no Flowable call occurs while the outer serialization lock owns a platform transaction.

The production Spring configuration exposes this serialized store as the primary D7 boundary.

## Immutable V47 evidence

Flyway V47 adds:

- `ap_process_migration_canary_selection`;
- `ap_process_migration_orchestration_run`;
- `ap_process_migration_orchestration_event`;
- `ap_process_migration_orchestration_batch`;
- `ap_process_migration_kill_switch_observation`.

V47 binds payload JSON to durable columns and validates:

- consumed plan and governed intent lineage;
- canonical sequence-one canary identity and instance evidence;
- canary/run plan and intent ownership;
- run revision and predecessor hash;
- event sequence, attempt ownership and predecessor hash;
- bounded batch ownership of the exact D2 claim and attempt list;
- Kill Switch run/attempt/revision ownership.

All five tables are append-only. Direct update or deletion fails closed. V1 through V46 are unchanged.

## Default-disabled internal gate

The one-shot runner requires all three switches:

- `approval.migration.execution.enabled=true`;
- `approval.migration.worker.enabled=true`;
- `approval.migration.orchestration.enabled=true`.

All default to `false`. The Kill Switch configuration also defaults to disabled with a server-owned
positive revision. No Controller, REST route, Web action, Mobile action or scheduled method was added.

## Upgrade and scale validation

The repository Flyway sequence is continuous through V47.

Committed-head validation covers:

- fresh V1 through V47;
- historical upgrade paths through V47;
- explicit V46-to-V47 upgrade;
- the retained V27 path with 5,000 approval instances/tasks;
- D2's 5,000-attempt bounded-claim index/canonical-order plan;
- zero Flowable calls or execution side effects from migration-only upgrades.

## Permanent test boundary

D7-focused tests cover:

- domain immutable evidence and closed values: 2/2;
- application one-shot canary/bounded/Kill Switch behavior: 4/4;
- PostgreSQL deterministic selection, exact/changed replay, two-node serialization, Kill Switch
  evidence, audit rollback, forged canary/tamper and cross-tenant rejection: 6/6;
- permanent D7 Node boundaries: 5/5.

Inherited D2 through D6 suites remain authoritative for bounded claim, worker/lease fencing, takeover,
stale Attempt revision, single-instance dispatch, exact verification, binding CAS conflict, durable
`UNKNOWN`, reconciliation and no automatic redispatch.

## Retained validation lineage

No failed run was rerun, cancelled, deleted or hidden. Each received a new isolated fix commit:

- Run #716 / `30251751586`: older permanent boundaries still expected Flyway V46;
- Run #718 / `30258263863`: the first D7 PostgreSQL test used the earlier `PrepareRequest` shape;
- Run #719 / `30258804736`: real PostgreSQL concurrency exposed missing outer replay serialization and
  the first forged-canary test exposed insufficient V47 lineage guards;
- Run #720 / `30260250145`: the serialization class compiled but was not yet the production primary
  boundary;
- Run #721 / `30260815333`: production serialization was active while the test still invoked the raw
  delegate, and forged Canary remained intentionally failing before V47 hardening;
- Run #722 / `30262170925`: V47 canonical/payload guards passed; the only remaining failure was the
  test bypassing the production serialized store.

## Permanent successful implementation validation

Approval Platform Validation Run `30262849130` / #723 at head
`f9b4c0946baaeed05e2c1cd19ff99a0eb3853ecd` completed successfully.

All four jobs succeeded:

- Repository hygiene;
- Java 21 / Maven / PostgreSQL;
- Vben TypeScript / production build;
- UniApp TypeScript / H5 / WeChat.

Test evidence:

- Maven aggregate: `652 / 0 / 0 / 0`;
- D7 focused domain/application/PostgreSQL: `12/12`;
- D7 PostgreSQL scenarios: `6/6`;
- D7 Node boundaries: `5/5`;
- Vben type-check and production build passed;
- UniApp type-check, H5 and WeChat builds passed.

All four artifacts were downloaded and the local ZIP SHA-256 exactly matched GitHub digest:

| Artifact | ID | SHA-256 |
| --- | ---: | --- |
| `approval-maven-30262849130` | `8651774800` | `60e83aefbbb23539ab4c2555fa40d47b82d2acb172d5bc1b5bf8ccafdd5a2f8d` |
| `approval-vben-30262849130` | `8651632821` | `56ba05949ecb08c6e33a258a63de09fad22578acdf96c7c674c4371b33a7e967` |
| `approval-mobile-30262849130` | `8651610957` | `ec299b7a640a039049190de8a2313572f49ac3e2c83b3bfd4f483f5e886cfc89` |
| `approval-hygiene-30262849130` | `8651590814` | `9872974d0f0d1c69f5166bedc2bb748a3f8adf9d57f7cb720cb7622b7b28d562` |

## Explicit absences

D7 provides or authorizes none of the following:

- automatic retry or second dispatch of `UNKNOWN`;
- rollback, force success, compensation fiction or cross-system atomicity;
- cancellation claims for an in-flight or completed Flowable request;
- a scheduler, infinite loop, resident worker or automatic cross-tenant scan;
- definition-wide or multi-instance Flowable migration;
- public execute, retry, force, rollback or reconciliation endpoints;
- Web or Mobile execution controls;
- direct Flowable `ACT_*` or implementation-class access;
- production execution;
- M5-D8, M5-E, M5-F or M5-G;
- M6 changes;
- a second automatic workflow;
- Ready, auto-merge, merge or issue closure.
