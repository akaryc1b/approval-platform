# M6-A Connector Foundation Bootstrap

Status: `EXECUTION_ADMISSION_ACCEPTANCE_REVIEW_SLICE_IMPLEMENTED`

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
  - `9a6be281bfcc06d74df85acde168ce8747ee4730`
- execution-admission and foundation-review slice commits:
  - `3b66507415e1b2b2d923a58b7fcdc4f54aab2b81`
  - `afc67757ca0815c9fe11b255419322f6beda6a50`

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

## Slice 4 — provider compatibility and no-network orchestration planning

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

Compatibility assessment does not invoke an adapter, query network or health state, use runtime metrics, reflection, classpath discovery or `ServiceLoader`. It binds matrix version, Registry fingerprint, contract fingerprint, required protocol and sorted classifications into deterministic evidence.

`ConnectorInvocationAuthorizationEvidence` is server-owned, valid for no more than ten minutes and binds one exact tenant, provider, contract, operation, request, idempotency key, payload hash and selection-evidence hash. It does not replace the shared platform authorization or audit model.

`DeterministicConnectorOrchestrationPlanner` verifies current Registry, exact binding registration, contract, selection, compatibility, trusted context, request evidence and authorization. It produces a hash-only `ConnectorOrchestrationPlan` containing no executable binding or raw credential reference.

The plan always reports:

- `automaticExecutionAllowed() == false`;
- `automaticRetryAllowed() == false`;
- `requiresExplicitExecution() == true`.

The planner never calls `.execute`, resolves credential bytes, sends network traffic, performs retries or mutates approval process state.

## Slice 5 — execution-admission revalidation

`DeterministicConnectorExecutionAdmissionPolicy` revalidates one existing orchestration plan against current server evidence immediately before a later explicit invocation gate.

Admission input includes:

- the immutable orchestration plan;
- current immutable Registry;
- exact operation contract;
- current selected provider and selection evidence;
- current compatibility report;
- current authorization evidence;
- current trusted tenant/provider/credential-reference context;
- current typed request;
- an explicit check timestamp.

The closed admission classifications are:

| Admission status | Meaning |
| --- | --- |
| `ADMITTED` | all current evidence matches the plan at the check timestamp |
| `PLAN_TIME_INVALID` | the check predates the plan |
| `REGISTRY_STALE` | the current Registry fingerprint differs from the planned Registry |
| `CONTRACT_MISMATCH` | contract key, operation or fingerprint differs |
| `SELECTION_MISMATCH` | selected provider or selection evidence differs |
| `COMPATIBILITY_MISMATCH` | compatibility evidence is stale, missing or non-compatible |
| `BINDING_UNAVAILABLE` | the exact current binding is missing or changed |
| `TRUSTED_CONTEXT_MISMATCH` | trusted tenant, provider or planning-time context differs |
| `REQUEST_MISMATCH` | request, trace, idempotency, operation, type or payload hash differs |
| `CREDENTIAL_MISMATCH` | the server credential-reference hash differs |
| `AUTHORIZATION_MISMATCH` | authorization identity or bound evidence differs |
| `AUTHORIZATION_EXPIRED` | authorization is not valid at admission time |

`ADMITTED` is only a deterministic current-evidence conclusion. It is not a provider invocation receipt, execution command, capability token or retry authorization.

`ConnectorExecutionAdmission` contains no `ConnectorProviderBinding` or `ConnectorExecutionPort` and always reports:

- `automaticExecutionAllowed() == false`;
- `automaticRetryAllowed() == false`;
- `requiresExplicitInvocation() == true`.

Admission evidence stores only bounded identifiers and hashes. The credential reference is represented only by its deterministic hash. The admission policy never calls `.execute`, resolves secret bytes, sends network traffic, sleeps, schedules work, retries a result or mutates approval process state.

## M6-A foundation review evidence

`DeterministicConnectorFoundationAcceptanceEvaluator` consolidates deterministic foundation evidence for a later formal review.

It checks:

- unique closed operation coverage for every `ConnectorOperation`;
- operation-contract fingerprints;
- current Registry fingerprint;
- selection, compatibility, orchestration and admission policy versions;
- non-empty admitted evidence;
- matching admission policy versions;
- admission timestamps not later than evaluation;
- admission Registry fingerprints matching the current Registry.

The closed review statuses are:

| Foundation review status | Meaning |
| --- | --- |
| `READY_FOR_FORMAL_ACCEPTANCE_REVIEW` | contract coverage and current admitted evidence are complete |
| `INCOMPLETE_CONTRACT_COVERAGE` | one or more closed operations are missing or duplicated |
| `INCOMPLETE_ADMISSION_EVIDENCE` | admission evidence is absent, rejected, version-mismatched or future-dated |
| `REGISTRY_EVIDENCE_MISMATCH` | admitted evidence references another Registry fingerprint |

`READY_FOR_FORMAL_ACCEPTANCE_REVIEW` does not grant formal acceptance. `ConnectorFoundationAcceptanceEvidence` always reports:

- `formalAcceptanceGranted() == false`;
- `productionEnabled() == false`;
- `automaticExecutionEnabled() == false`;
- `automaticRetryEnabled() == false`;
- `requiresExplicitFormalAcceptance() == true`.

Every review result retains all production safety blocks:

- `REAL_PROVIDER_TRANSPORT`;
- `PRODUCTION_CREDENTIALS`;
- `PERSISTENCE`;
- `CONNECTOR_WORKER`;
- `AUTOMATIC_EXECUTION`;
- `AUTOMATIC_RETRY`;
- `HEALTH_BASED_ROUTING`;
- `APPROVAL_STATE_MUTATION`.

Formal M6-A acceptance, PR readiness, production enablement and execution remain separate explicit gates.

## Retry and reconciliation semantics

| Initial outcome | Disposition |
| --- | --- |
| `SUCCESS` | do not retry |
| `REJECTED` | do not retry |
| `RATE_LIMITED` | retry with backoff only under a later reviewed execution policy |
| `RETRYABLE_PROVIDER_FAILURE` | retry with backoff only under a later reviewed execution policy |
| `PERMANENT_PROVIDER_FAILURE` | do not retry |
| `TIMEOUT` | reconcile before any retry decision |
| `UNKNOWN` | reconcile before any retry decision |

Only `TIMEOUT` and `UNKNOWN` enter `ConnectorReconciliationPort`. No selection, compatibility, plan, admission, foundation review or reconciliation result directly authorizes replay of an uncertain provider side effect.

## Test and permanent boundary coverage

Focused coverage by slice:

- first slice: 18 tests;
- provider-resolution slice: 15 tests;
- typed-payload and selection slice: 18 tests;
- compatibility and orchestration slice: 18 tests;
- execution-admission and foundation-review slice: 18 tests.

The fifth slice adds:

- 15 contract tests covering successful non-executing admission; every admission mismatch class; deterministic admission evidence; credential-reference redaction; review-ready foundation evidence; complete blocked-capability retention; incomplete operation coverage; rejected admission evidence; and stale Registry evidence;
- 3 permanent boundary tests proving admission cannot invoke adapters, resolve credentials, access network or schedule work; foundation review cannot grant formal acceptance or production enablement; and Web/Mobile cannot manufacture admission, authorization, trusted-context or foundation-review evidence.

Accumulated focused M6-A coverage before the full repository regression suite is 87 tests:

- connector contract tests: 68;
- permanent boundary tests: 19.

All permanent tests execute through the existing Maven backend job in `.github/workflows/approval-platform-validation.yml`; no workflow file is added or modified.

## Explicitly absent

- no `V33` or other Flyway migration;
- no real DingTalk or Feishu network call;
- no production provider transport, endpoint or health discovery;
- no production token, secret, credential material, customer domain or private address;
- no production secret-store or key-management integration;
- no connector-owned persistence, worker, lease, scheduler, recovery or production retry orchestration;
- no random, weighted, implicit-priority, fallback, load-balanced or network-based provider selection;
- no selector, compatibility matrix, planner, admission policy or foundation evaluator triggered provider invocation;
- no plan- or admission-carried executable binding or raw credential reference;
- no automatic execution or automatic retry authorization;
- no formal acceptance or production enablement;
- no direct Flowable API exposure;
- no connector-driven approve, reject, transfer, withdraw, terminate or migrate operation;
- no direct approval process-state modification;
- no browser-manufactured trusted provider, tenant, operator, authority, audit, authorization, admission or signing identity;
- no M5 source or semantic modification;
- no second permanent or temporary GitHub Actions workflow;
- no PR readiness, auto-merge or merge action.

## Next explicit gate

The M6-A contract foundation is eligible only for a later formal acceptance review after permanent validation of this slice.

Until explicitly authorized, the following remain blocked:

- formal M6-A acceptance;
- marking PR #67 ready;
- real provider adapters or transport;
- provider execution;
- production credentials and secret-store integration;
- selection, compatibility, authorization, plan or admission persistence;
- provider tenant-routing configuration;
- provider fallback, weighted routing, load balancing or health-based routing;
- connector workers, leases, schedules and recovery;
- schema ownership and any `V33`;
- automatic retry of uncertain outcomes;
- approval-state actions;
- auto-merge or merge.
