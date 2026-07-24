# M6-B SDK and Event Ecosystem Bootstrap

Status: `SAFE_SLICE_6_IMPLEMENTED`

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

### Diagnostic decision policy

- emission policy contract version `1`; unknown versions fail closed;
- explicit minimum severity;
- deterministic SHA-256 numerator/denominator sampling;
- caller-supplied monotonic ordinal window, with no clock reads;
- bounded tracker with deterministic expiry and eviction;
- already-redacted diagnostics only.

### Fake diagnostic sink

- bounded `ScriptedInMemoryDiagnosticSink`;
- accepted, capacity and scripted failure outcomes;
- only accepted emissions enter the deduplication tracker;
- sink failures return stable reason codes without raw exception text;
- diagnostic sink loss is surfaced as degraded observability, not silently ignored.

### Audit completeness proof

- exactly one started record, the expected attempt records and one terminal record;
- contiguous sequence, required phase/type, unique event IDs and non-decreasing explicit times;
- stable endpoint, operation, request, trace, binding, authentication-context and provenance identity;
- canonical identity digest and full-batch digest;
- missing, reordered, duplicate, identity-drifted or time-regressed records fail closed.

### Atomic fake audit sink

- bounded `ScriptedAtomicAuditSink` only;
- complete batches commit atomically;
- capacity, duplicate-batch and scripted failures leave no partial records;
- audit incompleteness or sink failure is always `FAILED_CLOSED`;
- no production logger, exporter, queue or audit persistence exists.

## Tests and permanent validation

Java and TypeScript use `emission-policy-v1.json` and must agree on diagnostic fingerprint/sample bucket, audit identity digest, audit batch digest and atomic commit result. Additional tests cover severity suppression, sampling, ordinal deduplication, diagnostic capacity/failure, missing/reordered/identity-drifted audit records, audit capacity/failure/duplicate batches and unsafe bounds.

The existing root `postinstall` gate executes `pnpm sdk:test`; the Maven reactor executes Java tests. The permanent workflow remains `.github/workflows/approval-platform-validation.yml`.

## Still blocked

- production environment/file/vault configuration source;
- production diagnostic logger, telemetry exporter or audit persistence;
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

The next M6-B gate requires explicit acceptance of deterministic diagnostic emission and atomic audit completeness/failure-mode conformance. Production logging, telemetry, audit storage, endpoint addresses, credentials and network execution remain separately blocked.
