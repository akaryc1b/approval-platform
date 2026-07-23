# M6-B SDK and Event Ecosystem Bootstrap

Status: `SAFE_SLICE_2_IMPLEMENTED`

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

The second safe slice adds pure Java and TypeScript compatibility negotiation with a shared deterministic fixture.

### Version and support policy

- compatibility manifest version `1`; unknown versions fail closed;
- strict `major.minor.patch` SDK and contract semantic versions;
- minimum-client enforcement before capability negotiation;
- RFC 3339 UTC `supportedUntil` boundary;
- contract support is expired at or after the declared boundary.

### Schema, protocol and capability negotiation

- server-preference selection for event schema and Webhook protocol;
- terminal rejection when no common schema or protocol exists;
- unknown required capabilities fail closed;
- unknown optional capabilities are omitted with warnings;
- duplicate schema, protocol, capability and deprecation declarations are invalid;
- enabled capability order is deterministic.

### Deprecation governance

- `deprecatedSince`, `sunsetAt` and replacement identity;
- warning while a deprecated capability remains supported;
- required capability fails at sunset;
- optional capability is omitted at sunset;
- sunset must follow the deprecation instant.

### Host integration boundary

Credential-free Generic REST and RuoYi starter blueprints demonstrate where negotiation is bound without adding Spring, RuoYi, Sa-Token or network dependencies to the SDK. Client profiles contain only SDK/schema/protocol/capability data and cannot manufacture tenant, operator, permission, authority or audit evidence.

## Tests and permanent validation

Java and TypeScript suites cover the shared compatibility fixture, semantic versions, minimum-client rejection, schema/protocol mismatch, required and optional unknown capabilities, support expiry, deprecation warnings, sunset behavior, unknown manifest rejection and trusted-evidence absence.

The existing root `postinstall` gate continues to execute `pnpm sdk:test`; the Maven reactor executes Java tests. The permanent workflow remains `.github/workflows/approval-platform-validation.yml`.

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

The next M6-B gate requires explicit acceptance of compatibility governance before adding any real transport adapter, subscription model, event persistence or delivery runtime. Schema and runtime ownership must be coordinated with M5 and other M6 workstreams while preserving server-derived identity and audit evidence.
