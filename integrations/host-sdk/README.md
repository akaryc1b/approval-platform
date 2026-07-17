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

## Deployment rules

- `InMemoryReplayGuard` is only for tests and single-node development.
- Production starters must provide a distributed replay guard, normally backed by Redis `SET NX EX` semantics.
- Signing secrets must be resolved from encrypted configuration or a secret manager.
- Secrets, bearer tokens and raw authentication payloads must never be logged.
- No Flowable, approval database, Spring, Sa-Token or RuoYi dependency is allowed in this module.

## Planned implementations

- `ruoyi5-host-starter` — Java 17 and Spring Boot 3;
- `ruoyi6-host-starter` — Java 21 and Spring Boot 4;
- `generic-host-starter` — standard Spring host adapter.
