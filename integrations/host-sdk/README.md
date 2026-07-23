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
- logical endpoint descriptors without addresses;
- nominal server-issued authentication contexts and reference-only credential leases;
- deterministic adapter open/exchange/close lifecycle and lease release.

Public requests, compatibility profiles, transport policies and logical endpoint descriptors have no tenant, operator, permission, authority or audit-evidence fields. The package has no network client, endpoint address, usable credential material, production clock, Flowable type, migration command, persistence or delivery worker.

## Deployment rules

- In-memory guards, static resolvers/providers and scripted adapters are only for tests and single-node development.
- Production starters must provide a distributed replay guard, normally backed by Redis `SET NX EX` semantics.
- Signing secrets and adapter credentials must be resolved outside the public SDK from encrypted configuration or a secret manager.
- Secrets, bearer tokens and raw authentication payloads must never be logged.
- Real endpoint resolution, authentication execution, clock, scheduling and network execution require separate accepted gates.
- No Flowable, approval database, Spring, Sa-Token or RuoYi dependency is allowed in this module.

## Planned implementations

- `ruoyi5-host-starter` — Java 17 and Spring Boot 3;
- `ruoyi6-host-starter` — Java 21 and Spring Boot 4;
- `generic-host-starter` — standard Spring host adapter.
