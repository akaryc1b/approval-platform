# Product Readiness / 产品可用性证据

Tracking issue: [#107 — Prove the product is usable, scalable, and recoverable](https://github.com/akaryc1b/approval-platform/issues/107)

This directory is the current index for product-level evidence. It does not turn build, boundary, mock, scenario-contract, documentation, or a partial runtime slice into a product acceptance claim.

> A successful preflight is not product acceptance. The product is ready only when a user can complete work and operators can keep or recover the service.

## Current baseline

This living index follows the current pull-request base. Exact base and Head SHAs belong in PR metadata and retained workflow evidence; they are intentionally not hard-coded here as a moving `main` status.

| Evidence | Current status | Meaning |
| --- | --- | --- |
| Repository and workstation preflight | `IMPLEMENTED` | Read-only prerequisite and configuration check only |
| Source-based backend start path | `BACKEND_LOCAL_START_VERIFIED_IN_EPHEMERAL_CI` | The real Spring Boot server reached Actuator `UP` against PostgreSQL in permanent Maven validation; no clean-machine timing claim |
| New-user startup within 10 minutes | `NOT_YET_VERIFIED` | Requires a timed run from a clean supported environment |
| Deterministic demo tenant, users and data | `LOCAL_OPT_IN_SEED_IMPLEMENTED_AND_CI_VERIFIED` | Explicit `local`-profile seed loads governed identities, fixed attachment metadata and one request; no shared demo environment is claimed |
| Product screenshots or online demo | `NOT_YET_PROVIDED` | Build output is not substituted for a product demonstration |
| Purchase-payment golden path | `RUNTIME_START_SLICE_VERIFIED_FULL_E2E_NOT_EXECUTED` | Definition publish, seed, instance start, first manager task, API reads and idempotent replay are verified; approval actions are not |
| Payment sandbox integration | `NOT_YET_VERIFIED` | Mock and in-memory adapters do not count as an external result |
| Browser and accessibility matrix | `NOT_YET_VERIFIED` | Requires automated and manual scenario evidence |
| Capacity and performance envelope | `NOT_YET_MEASURED` | No unsupported TPS or capacity claim is allowed |
| Upgrade, backup/restore and RPO/RTO | `NOT_YET_REHEARSED` | Runbooks alone do not count as a recovery exercise |

## Claim vocabulary

Use the following terms narrowly:

- `DEMO_REPOSITORY_CONTRACT_PASSED`: required files and fail-closed/local configuration contracts were present. Local tool availability was not checked.
- `DEMO_PREFLIGHT_PASSED`: repository contracts and all documented local tool checks passed. No service or user scenario was executed.
- `PURCHASE_PAYMENT_SCENARIO_CONTRACT_PASSED`: the deterministic high-value request, identities, assignee resolution, API mappings and evidence keys match repository source. This command remains read-only.
- `DETERMINISTIC_DEMO_SEED_IMPLEMENTED`: a default-off, `local`-profile-only runner consumes the governed manifest and fixture through existing application services.
- `BACKEND_LOCAL_START_VERIFIED`: the executable server started against PostgreSQL and its bounded health check returned `UP`. The retained CI test also verifies the seeded instance and manager pending-task APIs.
- `QUICK_START_10_MINUTES_PASSED`: an unfamiliar user completed the published startup outcome on a clean supported environment within 600 seconds, with retained timing and environment evidence.
- `PURCHASE_APPROVAL_E2E_PASSED`: the complete approval workflow passed, but no external payment result is implied.
- `PURCHASE_TO_PAYMENT_SANDBOX_E2E_PASSED`: a request left the platform, reached an explicitly identified sandbox, and returned a verified idempotent result.
- `PRODUCTION_PAYMENT_INTEGRATION_VERIFIED`: reserved for separately authorized real-provider acceptance; it must never be inferred from a mock or sandbox.

## Current executable contracts

- [`QUICK_START.md`](QUICK_START.md) — source-based startup, explicit local seed switch and remaining timed-run boundary;
- [`PURCHASE_PAYMENT_GOLDEN_PATH.md`](PURCHASE_PAYMENT_GOLDEN_PATH.md) — deterministic purchase-payment contract, seed/start evidence and exact non-claims;
- `pnpm demo:scenario:check` — read-only scenario/API/template contract;
- `pnpm demo:seed:check` — fail-closed local-only and no-direct-SQL boundary;
- `PurchasePaymentDemoSeedIntegrationTest` — PostgreSQL Testcontainer, real random-port Spring Boot, Actuator health, instance/pending-task reads and idempotent replay.

## Static-contract versus runtime status

The deployment-neutral scenario validator still emits:

```text
DETERMINISTIC_DEMO_SEED_NOT_APPLIED
```

That marker describes what the **read-only validator command itself** does. It does not overwrite the narrower runtime evidence from the opt-in local runner and its permanent integration test.

## Current non-claims

```text
SHARED_DEMO_ENVIRONMENT_SEED_NOT_APPLIED
PURCHASE_APPROVAL_E2E_NOT_EXECUTED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
```

The two seeded attachment objects are uploaded through `ApprovalAttachmentService` and referenced by the request. They remain unbound until the platform exposes an application-layer bind operation; this slice intentionally does not call `ApprovalAttachmentStore.bindToInstance` directly.

## Lightweight delivery sequence

Issue #107 uses three coherent delivery areas, not another gate hierarchy:

1. Demo and Guides;
2. Golden Path;
3. Capacity and Recovery.

Expensive browser, capacity and recovery suites should be scheduled or release-candidate scoped. Documentation-only corrections must not manufacture readiness or require a new automatic workflow.

## Safety boundary

The Demo and Guides work must not:

- enable production migration, AI, Connector, Secret, Provider, scheduler or traffic mutation;
- commit credentials or customer data;
- replace authorization with a demo bypass;
- represent local headers, mocks, static responses, seeded first-task evidence or successful builds as production evidence;
- create a second automatic PR/main workflow.

Start with [`QUICK_START.md`](QUICK_START.md), then validate and run the [`purchase-payment contract`](PURCHASE_PAYMENT_GOLDEN_PATH.md).
