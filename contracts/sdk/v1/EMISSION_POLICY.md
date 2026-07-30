# Diagnostic Emission and Audit Completeness Policy v1

## Version and execution boundary

- `contractVersion` is `1`; every unknown version fails closed.
- The policy consumes only an already-redacted `SafeDiagnostic` or reference-only `AdapterAuditEvent`.
- Ordinals and occurrence times are supplied explicitly. No production clock, scheduler, logger, storage, endpoint or network implementation exists in this contract.

## Diagnostic emission

A diagnostic policy defines:

- minimum severity: `info`, `warning` or `error`;
- sampling numerator and denominator;
- a non-empty deterministic sample salt;
- a caller-supplied ordinal deduplication window;
- a bounded deduplication tracker capacity.

The diagnostic fingerprint is SHA-256 of canonical JSON containing code, severity, message, context, redaction count and provenance digest. The sampling input is:

```text
<sample-salt>\n<sample-key>\n<diagnostic-fingerprint>
```

The first eight hexadecimal characters of its SHA-256 digest are interpreted as an unsigned 32-bit value and reduced modulo the denominator. A bucket smaller than the numerator is selected.

Diagnostic decisions are deterministic:

- below threshold: suppressed;
- sampled out: suppressed;
- duplicate inside the ordinal window: suppressed;
- accepted by the fake sink: emitted and recorded for deduplication;
- sink capacity: stable degraded result, without recording a successful emission;
- scripted sink failure: stable degraded result with no raw exception text.

The bounded tracker expires entries outside the caller ordinal window and evicts the least-recently-observed fingerprint, using lexical fingerprint order as a deterministic tie break.

## Audit completeness

An audit completeness policy requires exactly:

```text
started + expectedAttemptCount × attempt + terminal
```

A complete batch has:

- contiguous zero-based sequence numbers;
- the required phase and event type at every position;
- unique event IDs;
- one logical endpoint, operation, request, trace, binding, authentication-context and provenance identity;
- non-decreasing explicit occurrence times;
- exactly one terminal record in the last position.

The completeness proof contains record count, attempt count, first/terminal event IDs, a canonical identity digest and a canonical full-batch digest.

## Atomic fake audit sink

`ScriptedAtomicAuditSink` accepts a complete batch atomically. Capacity rejection, duplicate batch digest or scripted failure leaves the sink unchanged. Audit incompleteness and every sink failure return `failed_closed`; no partial audit records are committed and no raw sink exception is exposed.

The diagnostic sink is an observability degradation boundary. The audit sink is a mandatory completeness boundary.

## Fixture

`fixtures/emission-policy-v1.json` proves Java and TypeScript agreement on:

- diagnostic fingerprint `f41e920268cbe411335463b67d62cabb8b71123a62bf9c5c8220e0ce23337929`;
- selected sample bucket `0` and sampled-out bucket `3`;
- audit identity digest `c915940364de75b2cdec36dbcc4b8a07d7a657ac2aae06f49a0146c50f93852e`;
- audit batch digest `2e2fb8d7bc061ac0f7e0a0dbffd8816b63dd3ab1e23498707c0f2a117a6135ab`;
- four-record atomic commit for two transport attempts.

## Still outside this contract

Production logging, telemetry exporters, audit persistence, environment/file/vault configuration, clocks, scheduling, endpoint resolution, authentication execution, credentials and network transport require separate acceptance gates.
