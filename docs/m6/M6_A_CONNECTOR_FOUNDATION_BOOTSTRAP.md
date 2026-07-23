# M6-A Connector Foundation Bootstrap

Status: `PROVIDER_RESOLUTION_SLICE_IMPLEMENTED`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #63
- branch: `agent/m6-a-connector-foundation`
- target branch: `main`
- verified main baseline: `d769722cf7dd5418739a91ad4c45ca1a1c147502`
- first contract slice commits:
  - `755a3c69a425c011ebfd6a36d741262f7a001d09`
  - `d811f4995e8b2e96106d0a870b6f5be64e147f0d`
  - `880cc5b71cf23f70739e8645f6ff8a71dbd88c90`
  - `9e75e2018d7c9af5b4e6eac9d83ba68486b7d6a9`
- provider-resolution slice commits:
  - `280eb50be6a024edc342010607c09546bc690a4b`
  - `a3dfa49b78b99c7b1dac4805c0b7b51139cac23e`

## Parallel milestone boundary

M6-A develops in parallel with M5 but remains independent from Issue #56 and Draft PR #58. This branch does not use the M5 branch as a base, copy M5 migration implementation, or commit connector work to PR #58. Later `main` changes are incorporated by merge commit only; rebase and force push remain prohibited.

## First implemented contract slice

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

## Provider registry and typed resolution

The second safe slice adds an immutable, provider-neutral server registry:

- `ConnectorProviderRegistry` groups bindings by provider key and closed operation;
- one provider may register multiple operations with different request and response payload types;
- registry construction rejects duplicate provider-operation bindings and inconsistent descriptors;
- resolution fails closed for unknown providers, disabled providers, missing capabilities, unregistered operations and payload-type mismatches;
- `ConnectorProviderBinding<P, R>` binds one descriptor, operation, request type, response type, execution port and optional reconciliation port;
- execution and reconciliation both validate the trusted provider context and exact operation before invoking an adapter;
- registration ordering is normalized and produces a deterministic SHA-256 registry fingerprint;
- no classpath scanning, reflection-based provider loading or dynamic network discovery is introduced.

## Server-side credential resolution

`ConnectorCredentialResolver` defines a callback-only boundary for server-owned credential material:

- the trusted execution context carries only an opaque `CredentialReference`;
- adapters receive credential material only inside `withCredential` and `withSecretBytes` callbacks;
- there is no raw secret, token or password getter;
- the resolver does not return credential bytes to browser, mobile or public result contracts;
- `DeterministicCredentialResolver` copies fixture material into a bounded callback scope and zeroes every scoped copy after use;
- tenant and credential-reference mismatches fail closed;
- credential scopes become inactive after the callback and cannot be reused;
- string representations retain only redacted credential evidence.

The deterministic resolver is a test adapter. It does not represent a production secret store, production credential, production key-management implementation or customer configuration.

## Retry and reconciliation semantics

Initial provider execution remains classified as follows:

| Outcome | Disposition |
| --- | --- |
| `SUCCESS` | do not retry |
| `REJECTED` | do not retry |
| `RATE_LIMITED` | retry with backoff |
| `RETRYABLE_PROVIDER_FAILURE` | retry with backoff |
| `PERMANENT_PROVIDER_FAILURE` | do not retry |
| `TIMEOUT` | reconcile before retry |
| `UNKNOWN` | reconcile before retry |

Only `TIMEOUT` and `UNKNOWN` results may enter `ConnectorReconciliationPort`.

Reconciliation produces immutable evidence bound to:

- original request ID;
- original idempotency key;
- original canonical payload hash;
- original operation and uncertain outcome;
- optional provider request ID;
- bounded observation timestamp, status and details;
- deterministic reconciliation evidence hash.

The reconciliation decision matrix is closed:

| Reconciliation status | Decision |
| --- | --- |
| `CONFIRMED_SUCCESS` | complete success |
| `CONFIRMED_REJECTION` | complete failure |
| `CONFIRMED_PERMANENT_FAILURE` | complete failure |
| `STILL_UNKNOWN` | reconcile again |
| `NOT_FOUND` | reconcile again |
| `CONFLICT` | manual review |

`ConnectorReconciliationResult.automaticRetryAllowed()` always returns `false`. A reconciliation result never directly authorizes replay of the original side effect. Any later retry requires a separately reviewed orchestration policy, fresh authorization, idempotency checks and evidence binding.

## Deterministic adapters

The current test-only adapters remain side-effect free:

- `DeterministicMockConnector` returns all initial outcome classes without network access;
- `DeterministicCredentialResolver` validates tenant/reference boundaries and zeroes scoped credential copies;
- `DeterministicReconciliationPort` uses immutable fixed-clock fixtures for confirmed success, confirmed rejection, confirmed permanent failure and still-unknown evidence;
- missing evidence produces `NOT_FOUND`;
- payload-hash or provider-request-ID mismatch produces `CONFLICT`;
- deterministic adapters do not call Flowable or modify approval process state.

## Test and permanent boundary coverage

The first slice added 18 focused tests. The provider-resolution slice adds 15 more tests:

- 12 contract tests for multi-operation typed resolution, stable registry fingerprints, duplicate/inconsistent registration rejection, disabled and unsupported providers, trusted provider/operation boundaries, credential redaction and zeroization, credential-scope escape rejection, reconciliation admission, deterministic evidence hashes, confirmed success/failure, still-unknown, not-found/conflict and missing reconciliation ports;
- 3 permanent boundary tests proving callback-only credential access, absence of raw credential getters, mandatory reconciliation for uncertain results, prohibition of blind automatic retry, immutable registry construction, duplicate-operation rejection and absence of reflection/service-loader discovery.

The accumulated M6-A focused coverage is 33 tests before counting the existing repository regression suite.

The permanent boundary tests execute through the existing Maven backend job in `.github/workflows/approval-platform-validation.yml`; no workflow file is added or modified.

## Credential and client boundary

- requests do not contain trusted tenant, operator, authority, audit identity or credential reference fields;
- trusted tenant routing and credential references are supplied by server-side composition only;
- descriptor, provider-result and reconciliation metadata reject credential-like keys;
- bounded errors redact bearer values, named token/secret/password/API-key values and common provider-token shapes;
- no browser or mobile TypeScript contract is added for trusted connector context or credential material.

## Still blocked until a later gate

- real DingTalk or Feishu network calls;
- production provider transport or customer endpoint configuration;
- production credentials, tokens, secrets, customer domains or private endpoints;
- production secret-store or key-management integration;
- connector-owned persistence or Flyway migrations;
- any `V33` migration;
- connector workers, leases, schedules or production retry orchestration;
- automatic retry of `TIMEOUT`, `UNKNOWN`, `STILL_UNKNOWN` or `NOT_FOUND` results;
- direct connector mutation of approval process state;
- modification of M5 migration plan, intent, attempt, verification, reconciliation or runtime-binding semantics;
- browser-supplied trusted provider, tenant, operator, authority, audit or signing identity;
- a second permanent or temporary GitHub Actions workflow;
- marking PR #67 ready, enabling auto-merge or merging the PR.
