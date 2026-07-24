# Telemetry Aggregation and Audit Handoff Reconciliation v1

## Version and boundary

- `contractVersion` is `1`; unknown versions fail closed.
- The aggregation store consumes only validated `ReferenceTelemetrySignal` values and recomputes both aggregation-identity and full-signal digests.
- The reconciliation store consumes only reference-only handoff envelopes, pending-envelope snapshots and acknowledgement snapshots.
- All windows and observations use caller-supplied ordinals. No system clock, scheduler, exporter backend, broker, database or network client exists.

## Caller-ordinal aggregation windows

A policy defines:

- window size in ordinals;
- retained deduplication horizon;
- maximum active windows;
- maximum aggregation identities across active windows;
- maximum tracked signal digests.

Window start is `floor(ordinal / windowSize) × windowSize`. Signals sharing the same aggregation-identity digest in the same window accumulate quantity and signal count while preserving first/last signal references and ordinals.

The fake store:

- rejects ordinal regression fail closed;
- suppresses duplicate signal digests inside the retained horizon;
- deterministically evicts the oldest tracked digest by ordinal then lexical digest;
- rolls over the oldest active window before accepting a later window;
- returns every rolled-over aggregate as a canonical snapshot;
- rejects identity-capacity overflow without changing active state;
- exposes no request, trace, binding or authentication-context value in aggregate snapshots.

Each snapshot has a canonical SHA-256 digest over its reference-only fields, quantities and caller-ordinal window.

## Handoff reconciliation

Reconciliation compares one expected handoff envelope with optional pending and acknowledgement evidence. Classifications are:

- `pending_confirmed`;
- `acknowledged_confirmed`;
- `acknowledgement_missing`;
- `missing_no_evidence`;
- `conflicting_evidence`.

Only `acknowledged_confirmed` sets `safeToFinalize=true`. Pending, missing and conflicting evidence never authorize finalization.

The proof records only envelope/acknowledgement references, expected state, classification, reconciliation ordinal, evidence digest and proof digest. Original audit records and trusted identity evidence remain absent.

`ScriptedHandoffReconciliationStore` is bounded and atomic. Duplicate proof, capacity rejection and scripted failure append no partial proof and expose no raw exception text.

## Fixture

`fixtures/aggregation-reconciliation-v1.json` proves Java and TypeScript agreement on:

- duplicate signal suppression;
- quantity accumulation from `2 + 3 = 5`;
- ordinal-window rollover from `[10,20)` to `[20,30)`;
- snapshot digests;
- acknowledgement-missing, acknowledged, missing and conflicting classifications;
- deterministic evidence/proof digests;
- duplicate reconciliation-proof rejection.

## Still outside this contract

Production telemetry aggregation, durable deduplication, OpenTelemetry or metrics backends, message brokers, audit queues, reconciliation persistence, clocks, scheduling, endpoint resolution, credentials and network execution require separate acceptance gates.
