# M6-F P6-A — AI Governance Inventory and Read-only Operations Acceptance

## 1. Scope

P6-A adds one server-owned, tenant-scoped, GET-only governance projection for the exact AI Provider profile already accepted in M6-D and M6-E.

This slice does not create a second Provider registry, a second production runtime, a deployment engine, a canary executor, a rollout controller, a rollback command, an approval command, or an autonomous automation path.

The authority boundary remains:

`AI advisory -> typed non-executable proposal -> fresh server policy/precondition evaluation -> fresh authorization preview -> explicit human confirmation -> existing application command service -> immutable audited result`

No existing command qualifies for controlled automation. Therefore:

- Action Whitelist: `EMPTY_PENDING_EXISTING_COMMAND_AUDIT`;
- P5-A: `P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND`;
- production reauthentication: unavailable;
- command execution authority: false;
- automatic retry authority: false;
- `AI_IS_NOT_AN_OPERATOR`.

## 2. Reused authoritative components

P6-A reuses the existing accepted production profile and exact version vocabulary:

- `ApprovalAssistanceProductionConfiguration.runtime(...)` for fail-closed configuration validation;
- `OpenAiResponsesAdvisoryProvider` for the exact Provider, model, Prompt and output-schema versions;
- `OpenAiResponsesTransportControls.KillSwitchSnapshot` for kill-switch evidence;
- `OpenAiResponsesTransportControls.CostPolicy` for cost-policy evidence;
- `OpenAiResponsesProductionRuntimeFactory.RuntimeProfile` for configured rate, circuit and request-cost limits.

P6-A does not read the API key, resolve Secret material, create a network client or call the Provider.

## 3. Read-only endpoint

The endpoint is:

`GET /api/approval/management/ai-governance/snapshot`

Mandatory controls:

- tenant-scoped management `READ` permission;
- trusted canonical `X-Tenant-Id` header;
- `Cache-Control: no-store`;
- no POST, PUT, PATCH or DELETE mapping;
- no raw Prompt body, tenant identifier, Secret value, token, credential or Provider response;
- no command, canary, rollout or rollback mutation surface.

## 4. Exact inventory

The snapshot contains exactly the three closed approval-assistance capabilities:

1. `APPROVAL_SUMMARY`;
2. `MATERIAL_COMPLETENESS`;
3. `RISK_SIGNALS`.

Each inventory entry carries exact metadata-only references for:

- Provider ID and version;
- model ID and version;
- Prompt template ID, version and content hash;
- knowledge-source version (`none` for this production profile);
- policy ID, version and content hash;
- output-schema ID and version.

The inventory cannot select a Provider or authorize invocation.

## 5. Runtime posture

When the production Provider runtime is absent, the snapshot reports:

- runtime `NOT_CONFIGURED`;
- activation `BLOCKED`;
- canary `NOT_CONFIGURED`;
- drift `NOT_OBSERVED`;
- rollout `BLOCKED`;
- rollback `ALREADY_DISABLED`;
- circuit posture `NOT_AVAILABLE`;
- no runtime-control object.

When the exact accepted runtime profile is configured, the snapshot reports:

- runtime `CONFIGURED_ADVISORY_ONLY`;
- activation `ADVISORY_ONLY`;
- canary `NOT_CONFIGURED`;
- drift `EXACT_FROZEN_PROFILE`;
- rollout `ADVISORY_ONLY`;
- rollback posture `DISABLE_RUNTIME_FLAG`;
- circuit posture `LIVE_STATE_NOT_EXPOSED`.

The configured snapshot exposes only bounded control metadata and hashes:

- kill-switch generation and evidence hash;
- cost-policy evidence hash;
- Secret-version reference evidence hash, never Secret material;
- per-tenant and global rate limits;
- rate-window seconds;
- circuit failure threshold and open duration;
- maximum request cost in micros.

## 6. Evidence integrity

Every snapshot receives a deterministic SHA-256 evidence hash over the exact normalized:

- snapshot version and observation time;
- runtime, activation, canary, drift, rollout, rollback and circuit posture;
- sorted exact inventory;
- bounded control metadata, when configured;
- sorted blocker codes;
- Action Whitelist state and P5 decision.

A reconstructed snapshot cannot change any governed field without changing the evidence hash. Constructors reject an evidence mismatch and reject any attempt to set Provider mutation, canary mutation, rollback mutation, command execution, automatic retry or raw-Secret exposure to true.

## 7. Explicit limitations and deferred work

P6-A is not the complete M6-F P6 governance capability.

The following remain intentionally unavailable and must not be inferred from this slice:

- live circuit-breaker state and runtime counters;
- durable Provider governance history;
- Provider/model/Prompt/policy mutation APIs;
- canary creation, admission, traffic allocation or promotion;
- drift observation from a second live source;
- rollout or rollback execution;
- budget consumption history or cross-process rate usage;
- a production reauthentication mechanism;
- any controlled automation command;
- P6-B or later P6 stages;
- P7 adversarial/fault/concurrency acceptance;
- P8 Formal Acceptance, Ready, merge or post-main verification;
- M6-G.

Rollback remains an operator runbook posture: disable the existing `APPROVAL_AI_OPENAI_ENABLED` runtime flag and redeploy through the established release process. P6-A exposes no endpoint that performs this action.

## 8. Validation and permanent evidence

The slice is guarded by:

- contract tests for disabled and configured posture;
- deterministic evidence-hash tests;
- reconstruction tests that reject authority escalation;
- Controller tests for GET-only, no-store and tenant-scoped management permission;
- configuration tests for exact runtime-profile projection without raw Secret exposure;
- architecture tests prohibiting mutation mappings, Provider invocation, application command services, direct connector execution, network clients, schedulers, automatic execution and new migrations.

The exact final Head, permanent workflow Run, nine physical Job results, test totals and independently verified artifact SHA-256 values are recorded in the PR acceptance comment created only after the exact final Head completes the permanent workflow.

PR #88 must remain Open + Draft after P6-A acceptance. Issues #81, #82, #62, #13 and #14 remain Open.
