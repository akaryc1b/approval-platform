# M6-F P6-C — Shared Runtime Control Health Acceptance

## 1. Scope

P6-C establishes one honest read-only control-health surface for the exact production AI runtime already used by approval assistance.

This slice does not add a Provider, model, Prompt, command, traffic allocator, deployment controller, control mutation API or automatic recovery path.

The production authority invariant remains:

`AI_IS_NOT_AN_OPERATOR`

The Action Whitelist remains:

`EMPTY_PENDING_EXISTING_COMMAND_AUDIT`

P5-A remains skipped:

`P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND`

## 2. Shared runtime correction

Before P6-C, the generation composition root and the P6-A governance snapshot each constructed their own `OpenAiResponsesProductionRuntimeFactory` from the same Environment profile.

That was safe for static metadata but was not sufficient for honest live circuit health because the two factories owned different process-local CircuitBreaker and RateLimiter instances.

P6-C introduces one server-owned `ApprovalAssistanceProductionRuntime` holder.

Both of the following now consume the same holder:

1. `ApprovalAssistanceGenerationService`;
2. P6 governance snapshot and control-health sources.

The default-disabled behavior is preserved through `Optional<OpenAiResponsesProductionRuntimeFactory>` inside the holder.

## 3. Read-only control snapshot

`OpenAiResponsesProductionRuntimeFactory.controlSnapshot()` returns metadata-only evidence without:

- creating a tenant binding;
- reserving a rate permit;
- acquiring a circuit permit;
- reading Secret material;
- opening a network connection;
- invoking the Provider;
- changing circuit, rate, cost or Kill Switch state.

The snapshot includes only:

- observation time;
- Kill Switch enabled posture, generation and evidence hash;
- cost-policy evidence hash and effective window;
- Secret-version-reference hash and effective window;
- configured tenant/global rate limits and window;
- configured circuit threshold and open duration;
- live process-local CircuitBreaker state and generation;
- maximum per-request cost;
- explicit `false` values for rate-usage exposure and cumulative-budget exposure.

No tenant identifier, tenant hash, active binding count, raw Secret, request payload or Provider response is exposed.

## 4. Management endpoint

P6-C adds:

`GET /api/approval/management/ai-governance/control-health`

The endpoint requires:

- trusted canonical `X-Tenant-Id`;
- tenant-scoped management `READ` authority;
- `Cache-Control: no-store`.

The endpoint has no POST, PUT, PATCH or DELETE mapping.

## 5. Control-health semantics

The health view is deterministic and tamper-evident.

It binds the P6-A source snapshot hash to the shared runtime control snapshot and reports:

- `EXACT_FROZEN_PROFILE` or `DRIFT_DETECTED`;
- Kill Switch admission enabled/disabled posture;
- cost-policy window: not-yet-active/current/expired;
- Secret-version window: not-yet-active/current/expired;
- Circuit state: closed/open/half-open;
- circuit generation;
- configured rate and cost caps;
- rate usage as `CONFIGURED_USAGE_NOT_EXPOSED`;
- budget history as `REQUEST_CAP_ONLY_CONSUMPTION_NOT_EXPOSED`.

Drift is detected when any P6-A control hash or configured limit differs from the shared runtime snapshot.

## 6. Fail-closed blockers

The view always preserves the empty Action Whitelist and production reauthentication blocker.

Additional blockers are emitted for:

- runtime not configured;
- runtime drift;
- Kill Switch disabled;
- cost policy not current;
- Secret version not current;
- Circuit open or half-open;
- rate usage not exposed;
- cumulative budget consumption not available.

No blocker can be overridden through this endpoint.

## 7. Explicitly unavailable capabilities

P6-C does not provide:

- rate usage by tenant or globally;
- cumulative cost or budget consumption;
- durable circuit history;
- durable governance-health history;
- Kill Switch mutation;
- circuit reset;
- rate-limit mutation;
- cost-policy mutation;
- Secret rotation;
- Provider/model/Prompt/policy activation;
- Canary, rollout or rollback execution;
- command execution or automatic retry.

The absence of rate and budget usage is reported honestly because the accepted runtime does not currently persist those counters as governance evidence.

## 8. Storage, workflow and migration boundary

P6-C adds no Flyway migration and no persistence table.

The highest governed migration remains `V50`.

The only automatic PR/main workflow remains:

`.github/workflows/approval-platform-validation.yml`

## 9. Acceptance tests

P6-C acceptance requires:

- runtime control snapshot contract tests;
- disabled/configured/drift/expired/open-circuit health tests;
- GET-only controller tests;
- shared Runtime Holder wiring tests;
- architecture tests prohibiting Provider invocation, permit acquisition, Secret access, mutation and automatic execution;
- complete permanent workflow success and independent artifact verification.

## 10. Merge boundary

P6-C completion does not authorize Ready, merge, auto-merge or Issue closure.

PR #88 must remain Open + Draft.

Issues #81, #82, #62, #13 and #14 must remain Open.
