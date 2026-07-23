# M6-A Connector Foundation Bootstrap

Status: `CONTRACT_SLICE_IMPLEMENTED`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #63
- branch: `agent/m6-a-connector-foundation`
- target branch: `main`
- verified main baseline: `d769722cf7dd5418739a91ad4c45ca1a1c147502`
- implementation commits:
  - `755a3c69a425c011ebfd6a36d741262f7a001d09`
  - `d811f4995e8b2e96106d0a870b6f5be64e147f0d`

## Parallel milestone boundary

M6-A develops in parallel with M5 but remains independent from Issue #56 and Draft PR #58. This branch does not use the M5 branch as a base, copy M5 migration implementation, or commit connector work to PR #58. Later `main` changes are incorporated by merge commit only; rebase and force push remain prohibited.

## Implemented contract slice

The first safe slice extends the existing framework-neutral Connector SPI without replacing the existing authentication, organization, file, notification, business-callback, form-data or external-todo ports.

It adds:

- credential-free `ProviderDescriptor` with provider key, provider type, protocol version, closed capabilities, enabled/disabled state and bounded compatibility metadata;
- strict parsing for the existing closed `ConnectorProvider.Capability` set;
- a closed `ConnectorOperation` set mapped to existing capabilities;
- a typed request/result/error envelope carrying request, trace, idempotency, payload-hash, provider-result and bounded failure evidence;
- `TrustedConnectorExecutionContext` as the only holder of trusted tenant routing and server-owned credential reference;
- `CredentialReference`, whose string representation never renders the reference value;
- HMAC-SHA256-v1 signing identifier, timestamp, nonce, maximum ten-minute validity window, replay result and canonical payload hash evidence;
- deterministic SHA-256 payload hashing and bounded secret redaction;
- explicit success, rejection, rate-limit, retryable failure, permanent failure, timeout and unknown outcomes;
- deterministic mock behavior for all outcome classes, tenant isolation and idempotency replay.

## Retry and reconciliation semantics

| Outcome | Disposition |
| --- | --- |
| `SUCCESS` | do not retry |
| `REJECTED` | do not retry |
| `RATE_LIMITED` | retry with backoff |
| `RETRYABLE_PROVIDER_FAILURE` | retry with backoff |
| `PERMANENT_PROVIDER_FAILURE` | do not retry |
| `TIMEOUT` | reconcile before retry |
| `UNKNOWN` | reconcile before retry |

`TIMEOUT` and `UNKNOWN` never authorize blind automatic retry because the remote side may have accepted the operation.

## Credential and client boundary

- requests do not contain trusted tenant, operator, authority, audit identity or credential reference fields;
- trusted tenant routing and credential references are supplied by server-side composition only;
- descriptor and provider-result metadata reject credential-like keys;
- bounded errors redact bearer values, named token/secret/password/API-key values and common provider-token shapes;
- no browser or mobile TypeScript contract is added for trusted connector context; TypeScript SDK work remains outside this M6-A slice.

## Deterministic mock adapter

`DeterministicMockConnector`:

- performs no network access;
- uses an injected fixed clock and deterministic provider request IDs;
- returns success, rejection, rate limit, retryable failure, permanent failure, timeout and unknown outcomes;
- returns `REPLAYED_SAME_RESULT` for the same idempotency key and payload hash;
- returns `CONFLICT` for the same idempotency key with a different payload hash;
- rejects a trusted tenant outside its configured boundary;
- does not call Flowable or change approval process state.

## Test and permanent boundary coverage

The slice adds 18 focused tests covering:

- provider descriptor validation and deterministic serialization;
- unknown capability rejection;
- client/trusted-context separation;
- signing hash, timestamp, nonce and validity-window validation;
- deterministic payload hashing;
- credential and error redaction;
- retry and reconciliation classification;
- all deterministic mock outcomes;
- idempotency replay and conflict handling;
- tenant isolation;
- no `V33` or other new Flyway migration;
- no M5 migration source crossing into this branch;
- no real DingTalk/Feishu adapter or provider network call;
- no direct Flowable API or approval-state mutation;
- no browser-manufactured trusted connector context;
- the single permanent automatic workflow boundary;
- byte-for-byte preservation of the frozen M3/M4 governance documents.

The permanent boundary test runs through the existing Maven backend job in `.github/workflows/approval-platform-validation.yml`; no workflow file was added or modified.

## Still blocked until a later gate

- real DingTalk or Feishu network calls;
- production credentials, tokens, customer domains or private endpoints;
- connector-owned persistence or Flyway migrations;
- any `V33` migration;
- direct connector mutation of approval process state;
- modification of M5 migration plan, intent, attempt, verification, reconciliation or runtime-binding semantics;
- a connector worker that has not passed external-call, lease, recovery and reconciliation review;
- browser-supplied trusted provider, tenant, operator, authority, audit or signing identity;
- a second permanent or temporary GitHub Actions workflow;
- marking PR #67 ready, enabling auto-merge or merging the PR.
