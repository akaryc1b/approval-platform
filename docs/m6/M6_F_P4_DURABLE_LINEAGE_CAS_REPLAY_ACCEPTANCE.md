# M6-F P4 — Durable Lineage, CAS, Replay, Concurrency and UNKNOWN Semantics

Status: `P4_IMPLEMENTED_NON_EXECUTING / FINAL_HEAD_VALIDATION_REQUIRED`

Permanent conclusion: `AI_IS_NOT_AN_OPERATOR`

## 1. Preconditions retained

- Pull Request #88 remains Open + Draft;
- Action Whitelist remains `EMPTY_PENDING_EXISTING_COMMAND_AUDIT`;
- P5-A remains `P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND`;
- production reauthentication remains `REAUTHENTICATION_UNAVAILABLE`;
- P1 Proposal remains `NON_EXECUTABLE_PROPOSAL`;
- P2 remains `READ_ONLY_NON_EXECUTING_PREVIEW`;
- P3 confirmation remains `NON_EXECUTABLE_CONFIRMATION` with `commandAdmitted = false`;
- no Ready, merge, auto-merge or Issue closure is authorized.

P4 adds durable evidence infrastructure only. No command service is referenced or invoked.

## 2. Exact durable model

`ControlledAutomationLineageStore` accepts only server-owned hash evidence:

- Proposal and confirmation IDs;
- tenant and operator evidence hashes;
- Proposal lineage and confirmation evidence hashes;
- canonical Action type;
- resource evidence hash;
- whitelist and policy versions;
- idempotency key and payload hashes;
- confirmation, expiry and terminal evidence timestamps.

It stores no raw Proposal parameter value, advisory text, Prompt, Provider body, credential, Secret,
HTTP body, SQL, script, executable expression or connector payload.

## 3. V50 ownership

M6-F P4 owns exactly one recursively discovered Flyway migration:

`m6f/db/migration/V50__create_ai_controlled_automation_lineage.sql`

Historical ownership is unchanged:

- M5 remains V33–V48;
- M6-E remains exact V49;
- M6-F P4 owns exact V50;
- no V51 or later migration is introduced.

Flyway scans configured migration locations recursively, allowing the M6-F migration to remain in an
explicit ownership subdirectory while retaining global version ordering.

## 4. CAS and replay semantics

Registration provides:

- exact replay for the same Proposal, confirmation and idempotency payload;
- conflict for reused keys with different payload or identity;
- tenant-scoped uniqueness;
- one immutable revision-one `CONFIRMED` state;
- one append-only `REGISTERED` event.

Terminal transition provides:

- row lock plus exact expected revision/status CAS;
- one concurrent winner;
- exact idempotent replay;
- conflict for changed idempotency payload;
- one append-only terminal event chained to its predecessor;
- one terminal revision-two state.

Database constraints and deferred state/event cross-checks require the current state and append-only
event to agree exactly.

## 5. Closed terminal vocabulary

The only states are:

- `CONFIRMED` — revision one, zero attempts, no outcome;
- `CANCELLED` — revision two, zero attempts;
- `SUCCEEDED` — revision two, one attempt;
- `FAILED` — revision two, one attempt;
- `PARTIAL` — revision two, one attempt;
- `UNKNOWN` — revision two, one attempt.

`commandAttempts` is permanently bounded to `0..1` and `automaticRetryAllowed` is permanently
`false` in Java and PostgreSQL. `UNKNOWN` is terminal and cannot be retried automatically. A new
idempotency key cannot create a second attempt after any terminal state.

## 6. Verification coverage

Permanent Core, architecture, PostgreSQL and Node tests cover:

- deterministic hash-only registration evidence;
- tamper rejection;
- exact registration replay;
- registration key/payload conflicts without partial writes;
- tenant/operator isolation;
- concurrent terminal transitions with one winner;
- cancellation with zero command attempts;
- terminal PARTIAL and UNKNOWN vocabulary;
- exact UNKNOWN replay and rejection of a second attempt;
- immutable state identity and append-only events;
- physical update/delete rejection;
- fresh and historical Flyway upgrades from V1 through V49 to V50;
- zero rows in the new lineage tables after migration-only upgrades;
- no command, Provider, connector, Flowable, network or scheduling authority.

## 7. Explicit non-authorization

P4 does not provide:

- a production reauthentication adapter;
- a confirmation or command HTTP endpoint;
- a non-empty Action Whitelist;
- an application command adapter;
- command execution, notification, connector call or business-state mutation;
- Queue, Worker, Scheduler, listener, polling, automatic retry or fallback;
- P5-A, P6, P7, P8, M6-G, Ready, Merge or Issue closure.

The durable lineage can record test-only terminal evidence, but it cannot cause a command attempt.
The authority chain remains blocked before application-command admission.

## 8. Final validation gate

The P4 final Head must pass the existing permanent workflow with all nine physical jobs successful.
The four final artifacts must be independently SHA-256 verified and the exact evidence will be
recorded in the PR conversation without a follow-up documentation commit.

Until that evidence exists:

`P4_FINAL_ACCEPTANCE_PENDING`

`AI_IS_NOT_AN_OPERATOR`
