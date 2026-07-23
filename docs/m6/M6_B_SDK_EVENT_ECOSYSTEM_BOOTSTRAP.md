# M6-B SDK and Event Ecosystem Bootstrap

Status: `SAFE_SLICE_1_IMPLEMENTED`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #64
- Draft PR: #68
- branch: `agent/m6-b-sdk-event-ecosystem`
- target branch: `main`
- verified main baseline: `d769722cf7dd5418739a91ad4c45ca1a1c147502`

## Parallel milestone boundary

M6-B develops in parallel with M5 and remains independent from Issue #56 and Draft PR #58. Public SDK contracts expose neither Flowable internals nor unaccepted M5 migration execution commands. Main drift is incorporated by merge commit only; rebase and force push remain prohibited.

## Implemented first safe slice

### Versioned event contract

`EventEnvelopeV1` and the TypeScript `EventEnvelopeV1` describe schema `1.0` with:

- event ID, event type and RFC 3339 `occurredAt`;
- server-produced tenant context;
- aggregate/resource type, ID and optional safe-integer version;
- request ID and trace ID;
- canonical payload and SHA-256 payload hash;
- producer service and instance identity;
- optional ordering, causation and correlation identities.

Unknown schema versions fail closed. Unknown fields on version `1.0` are accepted for forward compatibility, included in canonical signature bytes, and ignored by typed v1 readers.

### Cross-language canonical JSON

Java and TypeScript share `contracts/sdk/v1/fixtures/event-envelope-v1.json`. Canonical JSON sorts object keys, preserves array order, uses UTF-8 and restricts numeric values to cross-language safe integers. Decimals and larger integers are strings. The fixture fixes canonical payload bytes, payload SHA-256, canonical event bytes, signature input bytes and HMAC-SHA256 output, including Unicode and maximum-safe-integer boundaries.

### Delivery semantics

The contract declares at-least-once delivery. Ordering is scoped only to an optional `orderingKey`; there is no global order. Consumers deduplicate by event ID and must be idempotent. Retryable rejection is not committed. Accepted, permanent rejection, expired event and unsupported schema version are terminal. Replay preserves the original event identity.

### Signed Webhook protocol

Java and TypeScript verification helpers implement:

- canonical payload bytes;
- epoch-second timestamp and bounded clock skew;
- nonce;
- `hmac-sha256` algorithm identifier;
- key reference;
- lowercase hexadecimal signature;
- nonce reservation only after valid signature verification;
- explicit verified, skew, replay, unsupported algorithm, missing key, invalid signature and invalid payload results.

The fixture secret is deterministic test data only. No real secret, token or endpoint is committed.

### SDK abstractions

Java `ApprovalSdk` and TypeScript `ApprovalClient` provide:

- typed client and transport interfaces;
- request/trace correlation;
- deterministic idempotency-key helper;
- structured result and error categories;
- in-memory mock transport;
- idempotent event-consumer helpers.

Public client requests contain only operation, payload, correlation and idempotency key. They cannot submit trusted tenant, operator, permission, authority or audit evidence.

## Tests and permanent validation

Java tests cover event parsing/serialization, version rejection, safe integer and Unicode policy, payload hash, signature verification, clock skew, nonce replay, payload tampering, duplicate delivery, retryable delivery, idempotency, structured error and client trust boundaries.

TypeScript tests cover the same fixture and verification semantics. The root `postinstall` gate executes `pnpm sdk:test`, so both existing web and mobile permanent CI jobs compile and run the SDK compatibility and boundary suite after root dependency installation. The Maven reactor executes the Java tests.

The boundary suite proves:

- no Flowable service type or `ACT_*` contract exposure;
- no M5 migration execute, force or rollback command;
- no real network transport;
- no subscription/event persistence or delivery worker;
- no `V33`;
- exactly one automatic PR/main workflow: `.github/workflows/approval-platform-validation.yml`.

## Still blocked

- subscription persistence or delivery tables;
- production event store or Outbox ownership changes;
- production delivery/retry worker;
- real HTTP transport or customer endpoint;
- production key/secret resolution implementation;
- SDK migration execution commands before M5 acceptance;
- any Flyway migration, including `V33`;
- a second permanent workflow;
- Ready, auto-merge or merge of PR #68.

## Next gate

The next M6-B gate requires explicit acceptance of this contract slice before adding a real transport adapter, subscription model, event persistence or delivery runtime. Any schema ownership must be coordinated with M5 and other M6 workstreams, while preserving server-derived identity and audit evidence.
