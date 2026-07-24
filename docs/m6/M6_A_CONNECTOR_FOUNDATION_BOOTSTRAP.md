# M6-A Connector Foundation Bootstrap

Status: `COMPATIBILITY_ORCHESTRATION_SLICE_IMPLEMENTED`

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
  - `0c7aa76e5206ba31ac3ab5261ce5038eae628a78`
- typed-payload and selection slice commits:
  - `9e647065c0bd83761dc3d06f48426af05ca78088`
  - `0955afcc6e7b95f1a9d04a978236b0b53635ac88`
  - `29e01a594bfab47bc71031fc7cb03bf223bead1d`
- compatibility and orchestration slice commits:
  - `2d8911014e0a7b799d90391c912c29b16ef36633`
  - `0e1786249a1779edd2fc74a02d5189d802e15cee`

## Parallel milestone boundary

M6-A develops in parallel with M5 but remains independent from Issue #56 and Draft PR #58. This branch does not use the M5 branch as a base, copy M5 migration implementation, or commit connector work to PR #58. Later `main` changes are incorporated by merge commit only; rebase and force push remain prohibited.

## Slice 1 — provider-neutral execution contracts

The first safe slice establishes:

- credential-free `ProviderDescriptor` and the closed `ConnectorOperation` set;
- typed request/result/error, provider-result and idempotency evidence;
- `TrustedConnectorExecutionContext` and opaque `CredentialReference`;
- timestamp, nonce, signing-algorithm, validity-window and replay evidence;
- deterministic SHA-256 payload hashing and bounded secret redaction;
- explicit success, rejection, rate-limit, retryable failure, permanent failure, timeout and unknown outcomes;
- deterministic mock outcomes, tenant isolation and idempotency replay.

`TIMEOUT` and `UNKNOWN` require reconciliation before retry and never authorize blind automatic replay.

## Slice 2 — provider registry, credential scope and reconciliation

The second safe slice adds:

- immutable `ConnectorProviderRegistry` entries keyed by provider and operation;
- exact typed request/response binding through `ConnectorProviderBinding<P, R>`;
- deterministic registry fingerprints;
- callback-only `ConnectorCredentialResolver` access and deterministic scope zeroization;
- immutable reconciliation request, evidence and result contracts;
- deterministic reconciliation fixtures for confirmed success, rejection, permanent failure, still unknown, not found and conflict.

`ConnectorReconciliationResult.automaticRetryAllowed()` always returns `false`.

## Slice 3 — closed typed payloads and deterministic provider selection

`ConnectorOperationPayloads` defines exact provider-neutral business payload contracts for:

| Contract | Connector operation |
| --- | --- |
| `DirectoryReadCommand` / `DirectoryReadResult` | `ORGANIZATION_READ` |
| `IdentityResolveCommand` / `IdentityResolveResult` | `IDENTITY_RESOLVE` |
| `MessageSendCommand` / `MessageSendResult` | `NOTIFICATION_SEND` |
| `ExternalTodoCommand` / `ExternalTodoResult` | `EXTERNAL_TODO_CREATE` |
| `ExternalTodoCommand` / `ExternalTodoResult` | `EXTERNAL_TODO_UPDATE` |
| `BusinessCallbackCommand` / `BusinessCallbackResult` | `BUSINESS_CALLBACK_DELIVER` |

Each operation is bound by `ConnectorOperationContract<P, R>` with a stable contract key, closed operation, exact request type and exact response type.

Typed commands contain no trusted tenant, operator, authority, audit or credential identity. They reject credential-like metadata keys, defensively copy bounded collections, normalize semantically unordered values and produce deterministic canonical payload hashes. Identity resolution remains provider evidence and never establishes trusted platform identity.

`DeterministicConnectorProviderSelector` accepts only one exact operation contract, an explicit provider allowlist, an optional allowlisted preference, an optional exact protocol version and a bounded policy version.

The closed selection outcomes are:

| Selection status | Meaning |
| --- | --- |
| `SELECTED` | exactly one eligible provider, or the explicit preferred provider is eligible |
| `NO_ELIGIBLE_PROVIDER` | no allowlisted provider satisfies every requirement |
| `AMBIGUOUS` | more than one provider is eligible and no explicit preference exists |
| `PREFERRED_PROVIDER_INELIGIBLE` | the explicit preferred provider is not eligible; no fallback occurs |

Selection is planning only. It never invokes `ConnectorExecutionPort.execute`, chooses by registration order or randomness, or performs health, classpath or network discovery.

## Slice 4 — provider compatibility evidence

`DeterministicConnectorProviderCompatibilityMatrix` assesses one exact operation contract against 1–32 explicit provider keys using immutable Registry evidence only.

The closed compatibility classifications are:

| Compatibility status | Meaning |
| --- | --- |
| `COMPATIBLE` | enabled provider, capability, operation binding, exact types and protocol all match |
| `PROVIDER_UNKNOWN` | provider key is absent from the Registry |
| `PROVIDER_DISABLED` | provider descriptor is disabled |
| `CAPABILITY_UNSUPPORTED` | descriptor lacks the operation capability |
| `OPERATION_UNREGISTERED` | provider has no binding for the exact operation |
| `CONTRACT_TYPE_MISMATCH` | registered request or response type differs from the operation contract |
| `PROTOCOL_MISMATCH` | an exact required protocol version does not match |

Compatibility assessment:

- does not invoke a provider adapter;
- does not use network, health checks, metrics, runtime observation, reflection or `ServiceLoader`;
- sorts provider rows and rejects duplicate provider keys;
- binds matrix version, Registry fingerprint, contract key, contract fingerprint, required protocol and every classification into deterministic SHA-256 evidence.

`ConnectorProviderRegistry.findBinding` is a read-only optional lookup used to distinguish an unregistered operation from an exact type mismatch. Existing strict `resolve` behavior remains unchanged.

## Server-owned authorization evidence

`ConnectorInvocationAuthorizationEvidence` is an opaque connector-local decision binding. It does not replace the shared authorization or audit model.

It binds:

- authorization decision ID and policy version;
- tenant, selected provider, contract key and operation;
- request ID, idempotency key and canonical payload hash;
- provider-selection evidence hash;
- authorization time and expiry.

Authorization validity must be positive and no longer than ten minutes. The evidence is server-owned and is never accepted from browser or mobile contracts.

## No-network orchestration planning

`DeterministicConnectorOrchestrationPlanner` validates one complete server-side planning request and produces an immutable `ConnectorOrchestrationPlan`.

Planning requires matching evidence for:

- current Registry fingerprint and selected binding registration;
- exact operation contract and payload types;
- provider selection and selected provider;
- compatibility report, contract fingerprint and selected compatible row;
- trusted tenant/provider context and server-owned credential reference;
- request ID, trace ID, idempotency key and canonical payload hash;
- server authorization decision and validity window.

When a payload implements `CanonicalConnectorPayload`, the planner verifies the request hash against the payload canonical hash.

The resulting plan stores only hashes and bounded identifiers. The credential reference is represented by a deterministic hash; its reference value is not rendered in plan evidence.

The plan deliberately contains no `ConnectorProviderBinding` or `ConnectorExecutionPort`. It provides:

- `automaticExecutionAllowed() == false`;
- `automaticRetryAllowed() == false`;
- `requiresExplicitExecution() == true`.

The planner never calls `.execute`, resolves credential bytes, sends network traffic, performs retries or mutates approval process state. A later execution gate must independently re-resolve current server state, authorization and credentials.

## Retry and reconciliation semantics

| Initial outcome | Disposition |
| --- | --- |
| `SUCCESS` | do not retry |
| `REJECTED` | do not retry |
| `RATE_LIMITED` | retry with backoff under a later reviewed execution policy |
| `RETRYABLE_PROVIDER_FAILURE` | retry with backoff under a later reviewed execution policy |
| `PERMANENT_PROVIDER_FAILURE` | do not retry |
| `TIMEOUT` | reconcile before any retry decision |
| `UNKNOWN` | reconcile before any retry decision |

Only `TIMEOUT` and `UNKNOWN` enter `ConnectorReconciliationPort`. No selection, compatibility, orchestration-plan or reconciliation result directly authorizes replay of an uncertain provider side effect.

## Test and permanent boundary coverage

Focused coverage by slice:

- first slice: 18 tests;
- provider-resolution slice: 15 tests;
- typed-payload and selection slice: 18 tests;
- compatibility and orchestration slice: 18 tests.

The fourth slice adds:

- 15 contract tests for every compatibility classification, stable matrix evidence, read-only Registry lookup, bounded authorization, deterministic planning, stale Registry rejection, compatibility mismatch rejection, tenant/provider/request/idempotency binding, expired authorization, payload-hash mismatch and disabled automatic execution/retry;
- 3 permanent boundary tests proving compatibility uses immutable evidence only, orchestration cannot execute or authorize retry, and Web/Mobile cannot manufacture trusted authorization or orchestration evidence.

Accumulated focused M6-A coverage before the full repository regression suite is 69 tests.

All permanent tests execute through the existing Maven backend job in `.github/workflows/approval-platform-validation.yml`; no workflow file is added or modified.

## Explicitly absent

- no `V33` or other Flyway migration;
- no real DingTalk or Feishu network call;
- no production provider transport, endpoint or health discovery;
- no production token, secret, credential material, customer domain or private address;
- no production secret-store or key-management integration;
- no connector-owned persistence, worker, lease, scheduler or production retry orchestration;
- no random, weighted, implicit-priority, fallback, load-balanced or network-based provider selection;
- no selector, compatibility matrix or orchestration planner triggered provider invocation;
- no plan-carried executable binding or raw credential reference;
- no automatic execution or automatic retry authorization;
- no direct Flowable API exposure;
- no connector-driven approve, reject, transfer, withdraw, terminate or migrate operation;
- no direct approval process-state modification;
- no browser-manufactured trusted provider, tenant, operator, authority, audit, authorization or signing identity;
- no M5 source or semantic modification;
- no second permanent or temporary GitHub Actions workflow;
- no PR readiness, auto-merge or merge action.

## Still blocked until a later gate

- real provider adapters and transport;
- production credentials and secret-store integration;
- compatibility, selection, authorization or plan persistence;
- provider tenant-routing configuration;
- provider fallback, weighted routing, load balancing or health-based routing;
- connector workers, leases, schedules, recovery and execution orchestration;
- schema ownership and any `V33`;
- automatic retry of uncertain outcomes;
- approval-state actions;
- marking PR #67 ready, enabling auto-merge or merging the PR.
