# Approval SDK and Event Contract v1

## Compatibility

- `schemaVersion` is `1.0`; readers reject every unknown schema version fail-closed.
- Unknown fields on a supported version are accepted, covered by the Webhook signature, and ignored by v1 typed readers.
- JSON numbers must be integers in `[-9007199254740991, 9007199254740991]`. Decimal and larger integer values use strings.
- Object keys are lexicographically sorted, arrays preserve order, strings use JSON escaping, and UTF-8 bytes are hashed.
- RFC 3339 UTC timestamps are serialized as strings and never converted to local time.

SDK, event-schema, Webhook-protocol, capability, deprecation and support-window negotiation is defined in [COMPATIBILITY.md](COMPATIBILITY.md). Compatibility manifest version `1` and strict semantic versions fail closed on unsupported or malformed input.

Request budgets, timeout classification, retry policy, response mapping and adapter conformance are defined in [TRANSPORT_POLICY.md](TRANSPORT_POLICY.md). Transport policy version `1` is deterministic, bounded and transport-free.

Logical endpoints, server-issued authentication context, reference-only credential leases and adapter lifecycle are defined in [ADAPTER_BINDING.md](ADAPTER_BINDING.md). Adapter binding version `1` contains no endpoint address or usable credential material.

Configuration provenance, diagnostic redaction and reference-only adapter audit events are defined in [DIAGNOSTICS_AUDIT.md](DIAGNOSTICS_AUDIT.md). Diagnostics/audit version `1` uses fake sources and an in-memory sink only.

Deterministic diagnostic sampling, ordinal deduplication, bounded fake sinks, audit completeness proofs and atomic failure-mode conformance are defined in [EMISSION_POLICY.md](EMISSION_POLICY.md). Emission policy version `1` contains no production logger or audit persistence.

Reference-only telemetry signals, key/value attribute allowlists, atomic fake export and audit handoff acknowledgement are defined in [TELEMETRY_HANDOFF.md](TELEMETRY_HANDOFF.md). Telemetry/handoff version `1` contains no production telemetry backend, broker, queue or storage.

## Event delivery semantics

Delivery is at least once. Ordering is guaranteed only within the same optional `orderingKey`; consumers must not infer global ordering. Consumers deduplicate by `eventId`, retain terminal decisions, and retry only `RETRYABLE_REJECTION`. `PERMANENT_REJECTION`, `EXPIRED_EVENT`, and `UNSUPPORTED_SCHEMA_VERSION` are terminal. Replay uses the original event identity and requires the same idempotent consumer behavior.

## Signed Webhook protocol

Headers carry timestamp epoch seconds, nonce, `hmac-sha256`, key reference, and lowercase hexadecimal signature. The signature input is:

```text
approval-webhook-v1\n
<timestamp>\n
<nonce>\n
<hmac-sha256>\n
<key-reference>\n
<sha256(canonical-payload)>\n
<canonical-payload-bytes>
```

Verification checks algorithm, bounded clock skew, key resolution, canonical payload, signature, then reserves the nonce. Invalid signatures do not consume nonces.

## Trust boundary

Event tenant context and producer identity are server-produced inbound evidence. Public client requests, compatibility profiles, transport policies, logical endpoint descriptors, diagnostics, emission decisions, telemetry signals, audit events and handoff acknowledgements cannot provide trusted tenant, operator, permission, authority or raw audit evidence. Host authentication and production observability remain server-owned boundaries.
