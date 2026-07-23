# M6-B SDK and Event Ecosystem Bootstrap

Status: `SAFE_SLICE_3_IMPLEMENTED`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #64
- Draft PR: #68
- branch: `agent/m6-b-sdk-event-ecosystem`
- target branch: `main`
- verified main baseline: `d769722cf7dd5418739a91ad4c45ca1a1c147502`

## Parallel milestone boundary

M6-B develops in parallel with M5 and remains independent from Issue #56 and Draft PR #58. Public SDK contracts expose neither Flowable internals nor unaccepted M5 migration execution commands. Main drift is incorporated by merge commit only; rebase and force push remain prohibited.

## Safe slice 1 — event and SDK foundation

The first safe slice established:

- versioned `EventEnvelopeV1` and canonical cross-language JSON;
- payload SHA-256 and deterministic Java/TypeScript fixture bytes;
- at-least-once delivery, ordering-key, replay, deduplication and idempotent-consumer semantics;
- signed Webhook timestamp, nonce, algorithm, key-reference and replay verification;
- Java and TypeScript client, structured error, correlation, idempotency and mock-transport abstractions;
- permanent tests proving no Flowable API, M5 command, real network, persistence, worker, V33 or second automatic workflow.

## Safe slice 2 — compatibility governance

The second safe slice established:

- compatibility manifest version `1` and strict semantic versions;
- minimum-client and support-window enforcement;
- server-preference schema and Webhook protocol selection;
- required/optional capability negotiation;
- deprecation, sunset and replacement governance;
- credential-free Generic REST and RuoYi binding blueprints.

## Safe slice 3 — transport policy abstraction

The third safe slice adds deterministic Java and TypeScript request-budget, response-mapping and adapter-conformance semantics without introducing a real transport.

### Bounded request budget

- transport policy version `1`; unknown versions fail closed;
- policy is bound to exactly one SDK operation;
- maximum attempts are limited to 10;
- total request budget is limited to 300000 milliseconds;
- per-attempt timeout must fit inside the total budget;
- exponential backoff is deterministic and capped;
- retry-after hints cannot exceed the remaining total budget.

### Retry safety

- `never` disables retries;
- `idempotent` requires the existing SDK idempotency key;
- retryable failures stop at the attempt limit;
- attempt timeout is retryable;
- budget exhaustion and attempts exhaustion are explicit terminal execution results;
- request, request ID, trace ID and idempotency identity remain unchanged across attempts.

### Structured response mapping

- success: `200–299`;
- policy-declared retryable status values;
- unauthorized: `401` and `403`;
- conflict: `409`;
- expired: `410`;
- unsupported version: `426`;
- other supported status values are permanent failures;
- status outside `100–599` is malformed and fails closed.

Every failure maps to the existing structured SDK error model with the originating request ID.

### Adapter conformance boundary

Java `ScriptedAdapter` and TypeScript `ScriptedConformanceAdapter` use deterministic in-memory scripts and virtual elapsed time. They never sleep, schedule, resolve an endpoint or perform network I/O. Generic REST and RuoYi examples describe policy binding only.

Transport policy and adapter metadata cannot manufacture tenant, operator, permission, authority or audit evidence.

## Tests and permanent validation

The shared `transport-policy-v1.json` fixture validates identical Java and TypeScript attempt traces, response categories, delays, timeouts, total elapsed time and request identity. Additional tests cover unsupported policy versions, duplicate retry statuses, budget bounds, unauthorized/conflict/version mapping, retry disabled, missing idempotency, retry-after exhaustion, attempt timeout, max attempts and operation mismatch.

The existing root `postinstall` gate executes `pnpm sdk:test`; the Maven reactor executes Java tests. The permanent workflow remains `.github/workflows/approval-platform-validation.yml`.

## Still blocked

- subscription persistence or delivery tables;
- production event store or Outbox ownership changes;
- production delivery/retry worker;
- real HTTP transport or customer endpoint;
- production clock, scheduler or sleep implementation;
- production key/secret resolution implementation;
- SDK migration execution commands before M5 acceptance;
- any Flyway migration, including `V33`;
- a second permanent workflow;
- Ready, auto-merge or merge of PR #68.

## Next gate

The next M6-B gate requires explicit acceptance of transport policy abstraction before adding a real transport adapter. Production endpoint resolution, authentication, scheduling, persistence, subscription and delivery runtime remain separate coordinated gates.
