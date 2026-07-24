# M6-B SDK and Event Ecosystem Bootstrap

Status: `SAFE_SLICE_7_IMPLEMENTED`

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

- versioned event envelope and canonical cross-language JSON;
- payload hashes and deterministic signed Webhook fixture;
- at-least-once, ordering, replay, deduplication and idempotency semantics;
- Java/TypeScript typed clients, structured errors and mock transports.

## Safe slice 2 — compatibility governance

- manifest and semantic-version validation;
- minimum-client and support-window enforcement;
- event-schema, Webhook-protocol and capability negotiation;
- deprecation, sunset and replacement governance.

## Safe slice 3 — transport policy abstraction

- bounded request budget and attempt limit;
- deterministic virtual-time timeout/backoff decisions;
- retry safety tied to idempotency;
- structured response mapping and scripted adapter conformance.

## Safe slice 4 — adapter contract and security binding

- logical endpoint descriptors without addresses;
- server-issued authentication context;
- reference-only short-lived credential leases;
- deterministic adapter lifecycle and binding identity preservation.

## Safe slice 5 — diagnostic redaction, provenance and adapter audit

- fake configuration source with explicit public/sensitive classification;
- canonical configuration provenance and reference-only sensitive identities;
- diagnostic literal/key redaction and generic exception rendering;
- reference-only adapter audit events and in-memory sink.

## Safe slice 6 — diagnostic emission and audit completeness

- explicit severity and deterministic sampling policy;
- caller-supplied ordinal deduplication with bounded fake diagnostic sink;
- complete started/attempt/terminal audit proof;
- atomic fake audit batch sink;
- diagnostic degradation and mandatory audit fail-closed behavior.

## Safe slice 7 — reference-only telemetry and audit handoff acknowledgement

### Telemetry signal contract

- telemetry/handoff contract version `1`; unknown versions fail closed;
- explicit signal-name allowlist;
- exact attribute-key and attribute-value allowlists;
- prohibited credential, trusted-identity and raw-audit attribute names;
- logical endpoint/correlation references, quantity, caller ordinal and provenance digest only;
- canonical aggregation-identity and full-signal digests;
- no arbitrary signal body, raw exception, endpoint address or credential material.

### Atomic fake telemetry exporter

- bounded `ScriptedTelemetryExporter` only;
- unique signal IDs/digests and non-decreasing caller ordinals;
- canonical batch proof;
- capacity, duplicate and scripted failures append no partial signals;
- exporter loss is returned as an explicit degraded result without raw exception text.

### Audit handoff acknowledgement

- envelope created only from `AuditCompletenessProof`;
- logical destination, proof digests/counts and canonical envelope digest only;
- original audit records and trusted identity evidence are absent;
- NACK, timeout-like and scripted failure retain the exact pending handoff;
- ACK atomically moves pending to acknowledged;
- identical replay returns the original acknowledgement;
- conflicting handoff identity fails closed;
- bounded queue capacity creates no partial pending entry.

## Tests and permanent validation

Java and TypeScript use `telemetry-handoff-v1.json` and must agree on telemetry aggregation/signal/batch digests, audit handoff envelope digest, acknowledgement ID and NACK/timeout/ACK/duplicate-ACK state transitions. Additional tests cover key/value allowlists, unknown versions, exporter capacity/failure/duplicates, ordinal regression, queue capacity, no-loss pending recovery, conflicting replay and incomplete proof rejection.

The existing root `postinstall` gate executes `pnpm sdk:test`; the Maven reactor executes Java tests. The permanent workflow remains `.github/workflows/approval-platform-validation.yml`.

## Still blocked

- production environment/file/vault configuration source;
- production diagnostic logger or telemetry backend;
- production message broker, audit queue or audit persistence;
- real HTTP or other network transport;
- production endpoint address, discovery and routing;
- production authentication executor or usable credential material;
- production clock, scheduler or sleep implementation;
- subscription persistence or delivery tables;
- production event store or Outbox ownership changes;
- production delivery/retry worker;
- SDK migration execution commands before M5 acceptance;
- any Flyway migration, including `V33`;
- a second permanent workflow;
- Ready, auto-merge or merge of PR #68.

## Next gate

The next M6-B gate requires explicit acceptance of reference-only telemetry and audit handoff acknowledgement semantics before introducing any production telemetry backend, message broker, audit queue or persistence. Endpoint addresses, credentials and network execution remain separately blocked.
