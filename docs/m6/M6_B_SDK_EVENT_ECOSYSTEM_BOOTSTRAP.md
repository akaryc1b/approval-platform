# M6-B SDK and Event Ecosystem Bootstrap

Status: `SAFE_SLICE_8_IMPLEMENTED`

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

- exact signal-name and key/value attribute allowlists;
- canonical aggregation-identity, signal and batch digests;
- bounded atomic fake telemetry exporter;
- reference-only handoff envelope created from audit completeness proof;
- NACK/timeout/failure retain pending handoff;
- ACK atomically moves pending to acknowledged;
- duplicate/conflicting replay semantics.

## Safe slice 8 — telemetry aggregation windows and handoff reconciliation

### Caller-ordinal aggregation

- aggregation/reconciliation contract version `1`; unknown versions fail closed;
- fixed caller-ordinal windows with no clock reads;
- bounded active windows, aggregation identities and tracked signal digests;
- recomputation of aggregation-identity and full-signal digests before acceptance;
- deterministic duplicate suppression inside the retained ordinal horizon;
- quantity/count accumulation with first/last reference evidence;
- oldest-window rollover with canonical reference-only snapshots;
- ordinal regression, digest mismatch, quantity overflow and scripted store failure fail closed;
- identity-capacity rejection leaves active state unchanged.

### Audit handoff reconciliation

- consumes expected envelope plus optional pending/acknowledgement snapshots only;
- classifications: pending confirmed, acknowledged confirmed, acknowledgement missing, missing evidence and conflicting evidence;
- only acknowledged confirmation is safe to finalize;
- pending, missing and conflicting evidence remain non-finalizable;
- canonical evidence and proof digests;
- bounded scripted reconciliation proof store;
- duplicate, capacity and scripted failure append no partial proof.

## Tests and permanent validation

Java and TypeScript use `aggregation-reconciliation-v1.json` and must agree on duplicate suppression, quantity accumulation, window rollover, snapshot digests, reconciliation classifications, evidence/proof digests and duplicate-proof handling. Additional tests cover ordinal regression, forged signal digests, identity capacity, scripted failures, pending confirmation, conflicting acknowledgement, proof-store capacity and safe-finalization invariants.

The existing root `postinstall` gate executes `pnpm sdk:test`; the Maven reactor executes Java tests. Permanent boundaries remain inside the single workflow `.github/workflows/approval-platform-validation.yml`.

## Still blocked

- production environment/file/vault configuration source;
- production diagnostic logger or telemetry backend;
- production telemetry aggregation or durable deduplication store;
- production message broker, audit queue, reconciliation store or audit persistence;
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

The next M6-B gate requires explicit acceptance of caller-ordinal telemetry aggregation and reference-only handoff reconciliation before introducing production aggregation, durable deduplication, broker, audit queue or reconciliation persistence. Endpoint addresses, credentials and network execution remain separately blocked.
