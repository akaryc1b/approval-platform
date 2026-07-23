# M6-A Connector Foundation Bootstrap

Status: `TYPED_PAYLOAD_SELECTION_SLICE_IMPLEMENTED`

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

## Parallel milestone boundary

M6-A develops in parallel with M5 but remains independent from Issue #56 and Draft PR #58. This branch does not use the M5 branch as a base, copy M5 migration implementation, or commit connector work to PR #58. Later `main` changes are incorporated by merge commit only; rebase and force push remain prohibited.

## Slice 1 — provider-neutral execution contracts

The first safe slice extends the existing framework-neutral Connector SPI without replacing the existing authentication, organization, file, notification, business-callback, form-data or external-todo ports.

It establishes:

- credential-free `ProviderDescriptor`;
- the closed `ConnectorOperation` set;
- typed request/result/error and idempotency evidence;
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
- callback-only `ConnectorCredentialResolver` access;
- deterministic credential-scope zeroization;
- immutable reconciliation request, evidence and result contracts;
- deterministic reconciliation fixtures for confirmed success, confirmed rejection, confirmed permanent failure, still unknown, not found and conflict.

`ConnectorReconciliationResult.automaticRetryAllowed()` always returns `false`.

## Slice 3 — closed typed payloads

`ConnectorOperationPayloads` now defines exact provider-neutral business payload contracts for:

| Contract | Connector operation |
| --- | --- |
| `DirectoryReadCommand` / `DirectoryReadResult` | `ORGANIZATION_READ` |
| `IdentityResolveCommand` / `IdentityResolveResult` | `IDENTITY_RESOLVE` |
| `MessageSendCommand` / `MessageSendResult` | `NOTIFICATION_SEND` |
| `ExternalTodoCommand` / `ExternalTodoResult` | `EXTERNAL_TODO_CREATE` |
| `ExternalTodoCommand` / `ExternalTodoResult` | `EXTERNAL_TODO_UPDATE` |
| `BusinessCallbackCommand` / `BusinessCallbackResult` | `BUSINESS_CALLBACK_DELIVER` |

Each operation is bound by `ConnectorOperationContract<P, R>` with a stable contract key, closed operation, exact request type and exact response type.

Typed business commands:

- contain no trusted tenant, operator, authority, audit or credential identity;
- reject credential-like metadata keys;
- defensively copy bounded collections and metadata;
- validate closed directory-query and result-state shapes;
- provide deterministic canonical payload hashes;
- normalize Map ordering and semantically unordered recipient ordering;
- do not establish a trusted platform identity.

`IdentityResolveResult.establishesTrustedPlatformIdentity()` is always `false`. Identity resolution remains provider evidence that must pass later server authentication, authorization and audit boundaries.

## Deterministic provider selection policy

`DeterministicConnectorProviderSelector` implements a server-owned, fail-closed selection policy.

Selection input requires:

- one exact `ConnectorOperationContract`;
- an explicit non-empty allowlist of no more than 32 provider keys;
- an optional preferred provider that must already be in the allowlist;
- an optional exact protocol version;
- a bounded policy version.

Eligibility requires:

- the provider exists in the immutable registry;
- the provider is enabled;
- the descriptor supports the operation capability;
- the exact operation binding is registered;
- request and response types match;
- the exact required protocol version matches when supplied.

The closed outcomes are:

| Selection status | Meaning |
| --- | --- |
| `SELECTED` | exactly one eligible provider, or the explicit preferred provider is eligible |
| `NO_ELIGIBLE_PROVIDER` | no allowlisted provider satisfies every requirement |
| `AMBIGUOUS` | more than one provider is eligible and no explicit preference exists |
| `PREFERRED_PROVIDER_INELIGIBLE` | the explicit preferred provider is not eligible; no fallback occurs |

The selector never chooses by registration order, random choice, implicit priority, classpath discovery or network discovery.

An ineligible explicit preferred provider never falls back to another eligible provider.

## Selection evidence and invocation boundary

`ConnectorProviderSelectionEvidence` binds:

- policy version;
- registry fingerprint;
- operation contract key;
- sorted allowed and eligible provider keys;
- optional preferred provider;
- optional required protocol version;
- optional selected provider;
- closed selection status;
- deterministic SHA-256 evidence hash.

Selection is planning only. `DeterministicConnectorProviderSelector` never calls `ConnectorExecutionPort.execute`.

An adapter invocation occurs only after server code explicitly obtains the selected `ConnectorProviderBinding` and separately supplies:

- a `TrustedConnectorExecutionContext`;
- the exact typed `ConnectorRequest`;
- a server-owned `CredentialReference`;
- request, trace, idempotency and payload-hash evidence.

`DeterministicTypedConnectorPort` is a network-free test adapter used to prove this separation.

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

The reconciliation decision matrix remains closed:

| Reconciliation status | Decision |
| --- | --- |
| `CONFIRMED_SUCCESS` | complete success |
| `CONFIRMED_REJECTION` | complete failure |
| `CONFIRMED_PERMANENT_FAILURE` | complete failure |
| `STILL_UNKNOWN` | reconcile again |
| `NOT_FOUND` | reconcile again |
| `CONFLICT` | manual review |

No selection or reconciliation result directly authorizes retry of an uncertain provider side effect.

## Test and permanent boundary coverage

The first slice added 18 focused tests. The provider-resolution slice added 15. The typed-payload and selection slice adds 18:

- 15 contract tests covering exact operation/type binding, closed directory shapes, stable payload hashes, sensitive-key rejection, identity trust boundaries, to-do/callback evidence, explicit allowlists, unique selection, ambiguity, explicit preference, no fallback, protocol/type mismatch, stable selection evidence and explicit adapter invocation;
- 3 permanent boundary tests proving typed payloads cannot carry trusted or credential identity, provider selection contains no network/reflection/random/execution behavior, and Web/Mobile cannot manufacture server-owned provider selection.

Accumulated focused M6-A coverage before the full repository regression suite is 51 tests.

All permanent tests execute through the existing Maven backend job in `.github/workflows/approval-platform-validation.yml`; no workflow file is added or modified.

## Explicitly absent

- no `V33` or other Flyway migration;
- no real DingTalk or Feishu network call;
- no production provider transport or customer endpoint configuration;
- no production token, secret, credential material, customer domain or private address;
- no production secret-store or key-management implementation;
- no connector-owned persistence, worker, lease, scheduler or production retry orchestration;
- no random, weighted, implicit-priority or network-based provider selection;
- no selector-triggered provider invocation;
- no automatic retry of `TIMEOUT`, `UNKNOWN`, `STILL_UNKNOWN` or `NOT_FOUND`;
- no direct Flowable API exposure;
- no connector-driven approve, reject, transfer, withdraw, terminate or migrate operation;
- no direct approval process-state modification;
- no browser-manufactured trusted provider, tenant, operator, authority, audit or signing identity;
- no M5 source or semantic modification;
- no second permanent or temporary GitHub Actions workflow;
- no PR readiness, auto-merge or merge action.

## Still blocked until a later gate

- real provider adapters and transport;
- production credentials and secret-store integration;
- provider selection persistence or tenant routing configuration;
- provider fallback, weighted routing, load balancing or health-based routing;
- connector workers, leases, schedules and recovery orchestration;
- schema ownership and any `V33`;
- automatic retry of uncertain outcomes;
- approval-state actions;
- marking PR #67 ready, enabling auto-merge or merging the PR.
