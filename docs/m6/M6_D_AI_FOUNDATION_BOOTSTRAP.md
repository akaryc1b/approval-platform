# M6-D AI Foundation — Governed Routing and Usage Evidence

Status: `SECOND_SAFE_SLICE_IMPLEMENTED`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #66
- branch: `agent/m6-d-ai-foundation`
- Draft PR: #70
- target branch: `main`
- implementation baseline `main`: `d769722cf7dd5418739a91ad4c45ca1a1c147502`
- Flyway remains V1–V32
- automatic PR/main validation remains `.github/workflows/approval-platform-validation.yml`

## Repository boundary

M6-D remains independent from M5 Issue #56 / Draft PR #58. The AI modules remain framework neutral and are not wired to participant endpoints, management endpoints, task completion, process migration, persistence or a production provider adapter.

Trusted identity and authorization continue to come from server-owned request context and authorized resource evidence. Browser and Mobile input cannot select a provider, model, tenant, operator, permission, credential, prompt, knowledge source or audit identity.

## First safe slice retained

The first slice continues to provide:

- provider-neutral SPI and closed advisory capabilities;
- independent provider, model, prompt-template, knowledge-source, policy and output-schema versions;
- server-owned request construction;
- Form Schema and UI Schema field-permission enforcement;
- masking and bounded data minimization;
- advisory-only structured results;
- exact provider failure classifications;
- timeout and cancellation boundaries;
- audit and low-cardinality metrics contracts;
- deterministic test-only provider and leakage/security tests.

Every result remains `ADVISORY`, `UNVERIFIED_ADVISORY` and `needsHumanReview = true`. No result or orchestration type can express an approval command or authoritative approval status.

## Second safe slice

### Exact provider registry

`AiProviderRegistry` registers providers by exact provider ID and provider version. It rejects duplicate exact registrations and resolves no provider from untrusted request input.

A route is usable only when:

- the exact provider version is registered;
- the descriptor supports the exact model version;
- all route capabilities are enabled by the descriptor;
- the route input-character budget does not exceed the provider capability descriptor limit.

There is still no production provider implementation. The only `AiAdvisoryProvider` implementation remains the deterministic fixture under `src/test`.

### Server-owned routing policy

`AiProviderRoutingPolicy` and `AiProviderRoute` define deterministic candidate order using bounded priority and stable route ID ordering.

Each route binds:

- route identity;
- exact provider and model versions;
- exact prompt-template version and hash;
- exact knowledge-source version and hash;
- exact input policy version and hash;
- exact output-schema version;
- allowed capabilities;
- timeout, input-character, field-count and minimum-confidence budgets.

All candidates in one routing policy must use the same input/masking policy version. Customer knowledge data remains rejected at route construction.

Routing is disabled unless server configuration explicitly supplies an enabled policy. The untrusted advisory intent still contains only capability and resource ID.

### Fallback safety

The coordinator may skip a candidate before invocation when:

- its exact provider version is not registered;
- its descriptor does not authorize the route;
- its provider-health circuit is open.

Pre-invocation candidate fallback is explicit and configurable. It performs no provider call on a skipped candidate.

After one provider invocation starts, the coordinator returns that result and never invokes another provider. `TIMEOUT`, `PROVIDER_UNAVAILABLE`, `INVALID_OUTPUT` and `UNKNOWN` do not trigger automatic fallback or retry. The policy and result constructors reject `allowPostInvocationFallback = true` and `postInvocationFallbackAttempted = true`.

This preserves one advisory attempt, one exact version lineage and one audit interpretation.

### Circuit-breaker boundary

`AiProviderCircuitBreaker` provides an in-memory health gate with:

- `CLOSED`;
- `OPEN`;
- `HALF_OPEN`;
- bounded consecutive-failure threshold;
- bounded open duration;
- exactly one in-flight half-open probe.

Only provider-health outcomes affect the circuit:

- `TIMEOUT`;
- `PROVIDER_UNAVAILABLE`;
- `INVALID_OUTPUT`;
- `UNKNOWN`.

`SUCCESS` and `LOW_CONFIDENCE` close the circuit. Policy, authorization and unsupported-capability outcomes do not count as provider-health failures. The breaker does not retry, schedule work or persist state.

### Invocation budgets

`AiInvocationBudget` authorizes:

- maximum timeout;
- maximum minimized input characters;
- maximum minimized field count;
- minimum accepted confidence.

The budget is checked after field permission, masking and minimization, but before provider invocation. An exceeded budget returns `POLICY_BLOCKED`; it does not call the provider or open the provider-health circuit.

### Usage evidence

`AiUsageEvidence` records bounded evidence for:

- minimized input characters;
- optional provider-reported input tokens;
- optional provider-reported output tokens;
- optional total tokens;
- optional provider latency;
- platform-observed latency;
- optional estimated cost and currency;
- evidence source: unavailable, platform-observed, provider-reported or mixed.

The current coordinator records only facts it can observe: minimized input characters and elapsed platform time. It does not invent token counts, provider latency or cost. Provider token/cost reporting remains unavailable until a later provider-adapter gate.

Usage values are evidence fields, not metric tags, and contain no prompt or response content.

### Coordinated audit evidence

`AiAdvisoryExecutionEvidence` can link:

- requestId and traceId;
- tenant and operator;
- resource type, resource ID and authorization reference;
- capability;
- selected route and exact versions;
- result classification;
- usage evidence;
- circuit state before and after;
- skipped pre-invocation candidates;
- whether a provider invocation started;
- proof that post-invocation fallback was not attempted.

This is a contract-only sink. M6-D still creates no database table or durable AI execution record.

### Routing metrics

Provider-routing metrics use only closed low-cardinality dimensions:

- capability;
- routing result;
- failure class;
- circuit state.

They do not contain tenantId, operatorId, userId, instanceId, taskId, requestId, traceId, route ID, provider ID, prompt content, model response or arbitrary error text.

## Evaluation coverage

The second slice adds deterministic tests for:

- exact provider-version lookup;
- duplicate provider registration rejection;
- route/provider/model/capability matching;
- disabled routing;
- circuit-open pre-invocation candidate skip;
- one half-open probe under concurrent selection;
- success closing a half-open circuit;
- policy outcomes not poisoning provider health;
- timeout opening the provider circuit;
- timeout and unknown results never invoking a backup provider;
- route-budget rejection before invocation;
- platform-observed usage evidence without invented tokens or cost;
- inconsistent token/cost evidence rejection;
- post-invocation fallback configuration rejection;
- low-cardinality routing metric structure.

The permanent Node boundary now also verifies that production source contains exactly one coordinator call site for `advisoryService.advise`, that post-invocation fallback is structurally prohibited, and that routing metrics contain no high-cardinality identity or business-content dimensions.

## Permanent safety boundary

The combined M6-D slices still contain:

- no real provider network call or network client;
- no production provider adapter;
- no production credential or API key loading;
- no production Prompt asset;
- no customer knowledge data;
- no attachment-content extraction;
- no JDBC, SQL, Flyway or persistence dependency;
- no `V33`;
- no task/instance approval-state command;
- no automatic retry after provider invocation;
- no post-invocation provider fallback;
- no production deterministic mock;
- no second automatic workflow;
- no M5 migration, runtime-binding or frozen M3/M4 governance modification.

## Still blocked

This slice does not add:

- production provider configuration or Spring wiring;
- a concrete model-network adapter;
- production credentials;
- production prompt templates;
- customer knowledge retrieval or embeddings;
- provider-reported token, latency or cost integration;
- durable circuit state or AI audit persistence;
- provider retries or background workers;
- participant or management AI endpoints;
- Web or Mobile AI controls;
- approval-state automation;
- M6-E or M6-F behavior.

A production adapter requires a separate gate covering secret management, network egress, exact model authorization, provider-specific output validation, budget enforcement, operational disablement and failure drills. Any controlled automation remains a later independent gate requiring human confirmation, server-side reauthorization, bounded reason, idempotency, audit and risk acceptance.
