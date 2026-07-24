# Reference-only Telemetry and Audit Handoff Contract v1

## Version and execution boundary

- `contractVersion` is `1`; every unknown version fails closed.
- The contract consumes only reference identifiers, canonical digests, allowlisted low-cardinality attributes and an existing `AuditCompletenessProof`.
- No production telemetry backend, logger, message queue, persistence adapter, endpoint resolver, credential provider, clock or network client exists in this safe slice.

## Reference-only telemetry signal

A telemetry attribute policy defines:

- allowed signal names;
- allowed attribute keys;
- allowed values for every attribute key;
- maximum attribute count and value length;
- maximum atomic fake-export batch size.

Both key and value allowlists are mandatory. Authorization, token, password, secret, private-key, certificate, credential, tenant, operator, permission, authority and raw audit-reference attribute keys are prohibited.

A signal contains only:

- signal ID, name and kind (`counter` or `event`);
- logical endpoint, operation, request, trace, binding and authentication-context references;
- outcome, non-negative caller ordinal and quantity;
- configuration provenance digest;
- allowlisted attributes;
- canonical aggregation-identity and full-signal digests.

Counter quantities are positive. Event quantities equal `1`. No arbitrary body, exception, stack, endpoint address or credential material is accepted.

The aggregation identity digest covers signal name/kind, logical endpoint, operation, outcome, provenance digest and sorted allowlisted attributes. Request-specific references and quantities remain in the full signal digest but not the aggregation identity.

## Atomic fake telemetry exporter

`ScriptedTelemetryExporter` accepts an ordered batch atomically. It validates unique signal IDs/digests and non-decreasing caller ordinals before append.

Outcomes are:

- `exported` / `telemetry_batch_exported`;
- degraded `telemetry_exporter_capacity`;
- degraded `telemetry_exporter_failed`;
- degraded `telemetry_duplicate_batch`.

Capacity, duplicate and scripted failure outcomes leave the fake exporter unchanged. Raw scripted exception text is never returned.

## Audit handoff envelope

An audit handoff is created only from an existing complete audit proof. The envelope contains:

- logical handoff and destination references;
- caller-supplied handoff ordinal;
- audit batch and identity digests;
- record/attempt counts and first/terminal event IDs;
- canonical envelope digest.

The original audit records, tenant/operator evidence, raw audit references and credentials are deliberately absent.

## Deterministic fake handoff queue

`ScriptedAuditHandoffQueue` models acknowledgement without a real queue. A new handoff enters the bounded pending set before its scripted delivery outcome is evaluated.

Scripted outcomes are:

- `ack`: atomically moves the handoff from pending to acknowledged;
- `nack`: leaves the same handoff pending;
- `timeout_like`: leaves the same handoff pending without reading a clock;
- `failure`: returns `failed_closed` and leaves the same handoff pending.

An acknowledgement binds acknowledgement ID, handoff ID, logical destination, envelope digest, audit batch digest, delivery attempt and explicit acknowledged ordinal.

Submitting an already acknowledged identical envelope returns `duplicate_acknowledged` and the original acknowledgement. Reusing a handoff ID with another envelope digest fails closed. Capacity rejection creates no pending record. NACK, timeout-like and scripted failure never lose or partially acknowledge a handoff.

## Fixture

`fixtures/telemetry-handoff-v1.json` proves Java and TypeScript agreement on:

- two telemetry aggregation-identity digests and signal digests;
- telemetry batch digest `c9cb0c6909978b6830f152c62e1ce956591a1db4a9282da5cc27f42942a0e9c7`;
- audit handoff envelope digest `528ee4640104c345b06ef6c05c8ae55941056312d48543052e356cceef1fc456`;
- acknowledgement ID `ab17fc318192860f9e5dfe2dc6722905da027708dd8dab5faea92127bb0367b2` after NACK, timeout-like and ACK attempts;
- idempotent duplicate acknowledgement.

## Still outside this contract

Production OpenTelemetry/metrics exporters, logging, message brokers, audit queues, audit persistence, environment/file/vault configuration, clocks, scheduling, endpoint resolution, authentication execution, credentials and network transport require separate acceptance gates.
