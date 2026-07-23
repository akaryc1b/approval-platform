# SDK transport policy v1

## Scope

This contract defines deterministic request budgets, response classification, retry decisions and adapter conformance. It does not perform network discovery, DNS, connection management, sleeping, scheduling, persistence or event delivery.

## Policy version

- `policyVersion` is `1`; every unknown policy version fails closed.
- Each policy is bound to exactly one SDK operation.
- A request whose operation does not match the policy is rejected before adapter invocation.

## Request budget

A bounded request budget contains:

- `maxAttempts`, limited to 1–10;
- `totalBudgetMillis`, limited to 1–300000;
- `attemptTimeoutMillis`, which must fit inside the total budget;
- deterministic exponential backoff with `baseBackoffMillis` and `maxBackoffMillis`.

The policy never sleeps. Conformance tests advance a virtual elapsed-time counter. A retry is allowed only when the remaining total budget can contain both the selected delay and at least one millisecond of the next attempt timeout.

## Retry safety

- `never` disables retries for the operation.
- `idempotent` permits retries only when the request contains its required idempotency key.
- Retryable responses stop at `maxAttempts`.
- A larger `retryAfterMillis` hint takes precedence over exponential backoff, but it cannot exceed the remaining total request budget.
- Attempt timeout is classified as retryable; total-budget exhaustion is terminal for the current invocation.

## Response mapping

Adapters return a normalized status, payload, optional structured error fields and optional retry-after hint. The v1 mapping is:

- `200–299`: success;
- policy-listed statuses: retryable;
- `401` or `403`: unauthorized;
- `409`: conflict;
- `410`: expired;
- `426`: unsupported version;
- other supported statuses: permanent failure;
- status outside `100–599`: malformed response.

Every failure produces the existing structured SDK error model and preserves the originating request ID.

## Adapter conformance

Java `ScriptedAdapter` and TypeScript `ScriptedConformanceAdapter` are deterministic, in-memory conformance harnesses. They preserve the exact request, correlation and idempotency identity across attempts and record timeout, duration, response category and scheduled delay. They contain no URL, endpoint, credential, token or production transport implementation.

## Trust boundary

Transport policy and adapter response metadata cannot provide trusted tenant, operator, permission, authority or audit evidence. Those values remain server-derived outside the public SDK.

## Fixture

`fixtures/transport-policy-v1.json` is deterministic cross-language test data. Java and TypeScript must produce the same attempt trace, virtual elapsed time, final result and request-identity preservation result.
