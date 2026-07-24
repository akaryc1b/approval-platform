# Approval Host SDK

Framework-neutral Java 17 support for RuoYi and other host systems that expose the Generic REST Connector contract.

## Responsibilities

- verify HMAC-SHA256 requests from Approval Platform;
- enforce timestamp freshness before host service invocation;
- resolve tenant-scoped signing keys by key ID;
- reserve nonces after successful signature validation;
- expose immutable authentication and error response models;
- prevent host framework entities from leaking into the public contract.

## Verification order

```text
validate required headers
        ↓
parse timestamp
        ↓
check allowed clock skew
        ↓
resolve tenant and key ID
        ↓
verify HMAC signature
        ↓
reserve nonce atomically
        ↓
invoke host authentication or organization service
```

Invalid signatures are rejected before nonce reservation. This prevents an attacker from consuming valid nonces with forged requests.

## Signature input

```text
<epoch-seconds>\n<nonce>\n<raw-request-body>
```

The signature header format is:

```text
v1=<lowercase-hex-hmac-sha256>
```

## M6-B public SDK v1

The independent `io.github.akaryc1b.approval.sdk.v1` package provides:

- inbound-only `EventEnvelopeV1` with server-produced tenant context;
- deterministic canonical JSON restricted to cross-language safe integers;
- HMAC-SHA256 signed Webhook verification with timestamp, key reference and nonce replay guard;
- structured client result/error contracts and request correlation;
- deterministic idempotency keys and in-memory mock transport;
- at-least-once delivery decisions and an idempotent consumer helper;
- SDK/schema/protocol/capability compatibility governance;
- deprecation, sunset and support-window enforcement;
- bounded request budgets, structured response mapping and deterministic adapter conformance;
- logical endpoints, server-issued authentication context and reference-only credential leases;
- deterministic adapter lifecycle with no endpoint address or network execution;
- fake configuration provenance, diagnostic redaction and reference-only adapter audit events;
- deterministic severity/sampling/deduplication emission policy;
- bounded fake diagnostic sink and atomic fake audit completeness sink;
- reference-only telemetry signals with exact key/value attribute allowlists;
- bounded atomic fake telemetry export;
- no-loss pending audit handoff and deterministic acknowledgement semantics;
- caller-ordinal telemetry aggregation with bounded rollover snapshots;
- reference-only handoff reconciliation proofs with acknowledged-only finalization.

Public requests, compatibility profiles, transport policies, logical endpoints, diagnostics, emission decisions, telemetry signals, aggregate snapshots, audit events, handoff acknowledgements and reconciliation proofs cannot manufacture trusted identity, permission or raw audit evidence. The package has no network client, production endpoint, production clock, Flowable type, migration command, persistence or delivery worker.

## Deployment rules

- `InMemoryReplayGuard`, M6-B in-memory guards, scripted adapters, diagnostic sinks, telemetry exporters, aggregation stores, audit sinks, handoff queues and reconciliation stores are only for tests and single-node development.
- Production starters must provide a distributed replay guard, normally backed by Redis `SET NX EX` semantics.
- Signing secrets must be resolved from encrypted configuration or a secret manager.
- Secrets, bearer tokens, raw authentication payloads, exception messages and stack traces must never be logged or exported.
- Diagnostic or telemetry sink loss must be surfaced as degraded observability; mandatory audit incompleteness, handoff uncertainty or conflicting reconciliation evidence must remain non-finalizable or fail closed.
- Real configuration sources, logging, telemetry backends, aggregation storage, brokers, audit persistence, endpoint resolution, authentication, clock, scheduling and network execution require separate accepted gates.
- No Flowable, approval database, Spring, Sa-Token or RuoYi dependency is allowed in this module.

## Planned implementations

- `ruoyi5-host-starter` — Java 17 and Spring Boot 3;
- `ruoyi6-host-starter` — Java 21 and Spring Boot 4;
- `generic-host-starter` — standard Spring host adapter.
