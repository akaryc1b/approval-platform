# M6-F P6-D — Process-local Usage Observability Acceptance

## 1. Scope

P6-D adds tenant-isolated read-only visibility into dispatched AI request counts and the cost upper bounds calculated by the existing transport admission.

This slice does not add billing, durable accounting, a mutable budget, a new rate limiter, Provider activation, traffic control, a command path or automatic execution.

The production authority invariant remains:

`AI_IS_NOT_AN_OPERATOR`

The Action Whitelist remains:

`EMPTY_PENDING_EXISTING_COMMAND_AUDIT`

P5-A remains skipped:

`P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND`

## 2. Usage accounting semantics

`OpenAiResponsesRuntimeUsageLedger` is one process-local observational ledger owned by the same production Runtime Factory accepted in P6-C.

A request is recorded only after:

1. Kill Switch, CircuitBreaker, RateLimiter and cost-policy admission succeeds;
2. request hash and cancellation state are revalidated;
3. the rate reservation is committed by `markDispatched`.

The recorded cost is the existing conservative admission estimate:

`request bytes × input micros + maximum output tokens × output micros`

It is an upper bound, not actual Provider usage or billing.

Cancellation, rejected admission, cost-limit failure and closing a permit before dispatch record no usage.

A terminal success, failure or unknown result does not record a second usage item.

## 3. Bounded process-local ledger

The ledger:

- uses the existing configured rate window;
- is bounded by the existing per-tenant and global request limits;
- is bounded by `rate limit × maximum request micros`;
- stores only tenant hashes, counts and admitted upper bounds;
- reclaims old tenant buckets when a new window records work;
- resets when the process restarts;
- creates no database row, migration, queue, worker or scheduler.

The ledger does not authorize, reserve, rate-limit, bill, retry or dispatch work.

## 4. Tenant privacy boundary

P6-D adds:

`GET /api/approval/management/ai-governance/usage`

The endpoint requires:

- trusted canonical `X-Tenant-Id`;
- tenant-scoped management `READ` authority;
- `Cache-Control: no-store`.

The response contains only the requesting tenant's:

- current rate-window start and end;
- dispatched committed request count;
- configured tenant request limit and remaining count;
- committed cost upper bound;
- derived tenant envelope and remaining envelope;
- tenant saturation state;
- a global-saturation boolean.

The response does not expose:

- another tenant identifier, hash, count or cost;
- exact global request or cost usage;
- active tenant or binding counts;
- raw Secret material;
- Provider request or response content.

The tenant evidence hash deliberately excludes exact global usage, preventing enumeration of low-cardinality global counters.

## 5. Exact profile binding

The usage projection is accepted only when it exactly matches the P6-A governance snapshot:

- tenant rate limit;
- rate-window duration;
- maximum per-request cost;
- derived tenant envelope.

A mismatched usage profile fails closed rather than presenting stale or unrelated counters.

## 6. Usage health

The closed usage health states are:

- `NOT_CONFIGURED`;
- `WITHIN_DERIVED_ENVELOPE`;
- `TENANT_RATE_WINDOW_SATURATED`;
- `GLOBAL_RATE_WINDOW_SATURATED`.

The view always reports:

- `processLocal = true`;
- `durable = false`;
- `actualProviderCost = false`;
- cost basis `ADMISSION_ESTIMATE_UPPER_BOUND_NOT_ACTUAL_PROVIDER_BILLING`.

## 7. Fail-closed blockers

The view preserves blockers for:

- empty controlled-automation Action Whitelist;
- unavailable production reauthentication;
- runtime not configured;
- non-durable usage history;
- unavailable actual Provider cost;
- tenant rate-window saturation;
- global rate-window saturation.

No blocker can be overridden through the endpoint.

## 8. Explicitly unavailable capabilities

P6-D does not provide:

- actual token usage or Provider invoice reconciliation;
- durable or cross-process usage history;
- a monthly, daily or account-level financial budget;
- exact global usage to tenant-scoped callers;
- in-flight reservation counts;
- rate, cost, Kill Switch, Secret or Provider mutation;
- command execution, automatic retry or autonomous recovery;
- Canary, rollout or rollback execution.

## 9. Storage, workflow and migration boundary

P6-D adds no Flyway migration and no persistence table.

The highest governed migration remains `V50`.

The only automatic PR/main workflow remains:

`.github/workflows/approval-platform-validation.yml`

## 10. Acceptance tests

P6-D acceptance requires:

- process-local ledger window, isolation, capacity and overflow tests;
- dispatch-only accounting and duplicate-dispatch tests;
- Runtime Factory usage snapshot tests proving no Provider Binding is created;
- disabled, configured, saturation and profile-drift usage projection tests;
- GET-only tenant management controller tests;
- composition-root tests using the shared P6-C Runtime Holder;
- architecture tests prohibiting cross-tenant/global exact exposure, mutation, Provider invocation, Secret access, workers and schedulers;
- complete permanent workflow success and independent artifact verification.

## 11. Merge boundary

P6-D completion does not authorize Ready, merge, auto-merge or Issue closure.

PR #88 must remain Open + Draft.

Issues #81, #82, #62, #13 and #14 must remain Open.
