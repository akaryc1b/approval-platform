# Product Readiness / 产品可用性证据

Tracking issue: [#107 — Prove the product is usable, scalable, and recoverable](https://github.com/akaryc1b/approval-platform/issues/107)

This directory is the current index for product-level evidence. It does not turn a build, boundary test, mock, scenario contract, documentation update, launcher implementation, or backend-only runtime slice into a product acceptance claim.

> A successful preflight or backend integration test is not product acceptance; neither is a launcher plan or build. The product is ready only when users can complete work across the supported clients and operators can keep or recover the service.

## Current baseline

This living index follows the current pull-request base. Exact base and Head SHAs belong in PR metadata and retained workflow evidence; they are intentionally not hard-coded here as a moving `main` status.

| Evidence | Current status | Meaning |
| --- | --- | --- |
| Repository and workstation preflight | `IMPLEMENTED` | Read-only prerequisite and configuration check only |
| One-command local backend launcher | `DEMO_BACKEND_ONE_COMMAND_IMPLEMENTED` | Exact preflight/isolated-Compose/build/backend/health/seed orchestration exists with non-destructive planning and fail-closed reset; it is not clean-machine timed |
| Source-based backend start path | `BACKEND_LOCAL_START_VERIFIED_IN_EPHEMERAL_CI` | The real Spring Boot server reached Actuator `UP` against PostgreSQL in permanent Maven validation; no clean-machine timing claim |
| New-user startup within 10 minutes | `NOT_YET_VERIFIED` | Requires a timed run from a clean supported environment |
| Deterministic demo tenant, users and data | `LOCAL_OPT_IN_SEED_IMPLEMENTED_AND_CI_VERIFIED` | Explicit `local`-profile seed loads governed identities, fixed attachment metadata and one request; no shared demo environment is claimed |
| Purchase-payment backend approval chain | `BACKEND_APPROVAL_CHAIN_VERIFIED_FULL_PRODUCT_E2E_NOT_EXECUTED` | Real HTTP actions completed manager approval, finance review and two-person countersign against PostgreSQL; final projection, participant timeline and completion Outbox evidence were verified |
| PC/H5/WeChat product scenario | `NOT_YET_EXECUTED` | Builds do not prove that the clients complete the same business scenario or show the same final state |
| Payment sandbox integration | `NOT_YET_VERIFIED` | The completion Outbox row is not an external delivery result; mocks and in-memory adapters do not count |
| Browser and accessibility matrix | `NOT_YET_VERIFIED` | Requires automated and manual scenario evidence |
| Capacity and performance envelope | `NOT_YET_MEASURED` | No unsupported TPS or capacity claim is allowed |
| Upgrade, backup/restore and RPO/RTO | `NOT_YET_REHEARSED` | Runbooks alone do not count as a recovery exercise |

## Claim vocabulary

Use the following terms narrowly:

- `DEMO_REPOSITORY_CONTRACT_PASSED`: required files and fail-closed/local configuration contracts were present. Local tool availability was not checked.
- `DEMO_PREFLIGHT_PASSED`: repository contracts and all documented local tool checks passed. No service or user scenario was executed.
- `DEMO_BACKEND_ONE_COMMAND_IMPLEMENTED`: the non-destructive launcher and exact plan exist, use the isolated local Compose project and explicit local seed, and require separate confirmation before volume deletion. It does not mean the complete command passed a clean-machine timed run.
- `PURCHASE_PAYMENT_SCENARIO_CONTRACT_PASSED`: the deterministic high-value request, identities, assignee resolution, API mappings and evidence keys match repository source. This command remains read-only.
- `DETERMINISTIC_DEMO_SEED_IMPLEMENTED`: a default-off, `local`-profile-only runner consumes the governed manifest and fixture through existing application services.
- `BACKEND_LOCAL_START_VERIFIED`: the executable server started against PostgreSQL and its bounded health check returned `UP`.
- `BACKEND_PURCHASE_APPROVAL_CHAIN_VERIFIED`: the seeded high-value request was advanced through the existing HTTP task-action API from `managerApproval` to `financeReview`, two `financeCountersign` tasks and final `COMPLETED`, with no demo action endpoint or authorization bypass.
- `COMPLETION_OUTBOX_EVENT_RECORDED`: the final approval transaction wrote one completion event to the real JDBC Outbox for the governed connector and instance. This is not proof of Connector dispatch or payment.
- `QUICK_START_10_MINUTES_PASSED`: an unfamiliar user completed the published startup outcome on a clean supported environment within 600 seconds, with retained timing and environment evidence.
- `PURCHASE_APPROVAL_E2E_PASSED`: reserved for the complete supported-client approval workflow with user-visible state agreement; backend-only CI does not satisfy it.
- `PURCHASE_TO_PAYMENT_SANDBOX_E2E_PASSED`: a request left the platform, reached an explicitly identified sandbox, and returned a verified idempotent result.
- `PRODUCTION_PAYMENT_INTEGRATION_VERIFIED`: reserved for separately authorized real-provider acceptance; it must never be inferred from an Outbox row, mock or sandbox.

## Current executable contracts

- [`QUICK_START.md`](QUICK_START.md) — one-command backend candidate, explicit local seed and remaining timed/user/client boundary;
- `pnpm demo:preflight` — read-only repository and workstation checks;
- `pnpm demo:backend:plan` — machine-readable, non-destructive startup plan;
- `pnpm demo:backend:start` — isolated Compose, Maven preparation, real backend, health and seed verification in one attached command;
- `pnpm demo:backend:stop` — stops the isolated local Compose project without deleting its data;
- [`PURCHASE_PAYMENT_GOLDEN_PATH.md`](PURCHASE_PAYMENT_GOLDEN_PATH.md) — deterministic purchase-payment contract, backend approval-chain evidence and exact non-claims;
- `pnpm demo:scenario:check` — read-only scenario/API/template contract;
- `pnpm demo:seed:check` — fail-closed local-only, migration ordering, backend-chain and no-direct-business-SQL boundary;
- `PurchasePaymentDemoSeedIntegrationTest` — PostgreSQL Testcontainer, real random-port Spring Boot, Actuator health, idempotent seed replay, four real approval actions, final projection, participant timeline and JDBC completion Outbox verification.

The destructive local reset is intentionally not a normal package shortcut. It requires the explicit command and confirmation flag documented in `QUICK_START.md`.

## Static-contract versus runtime status

The deployment-neutral scenario validator still emits:

```text
DETERMINISTIC_DEMO_SEED_NOT_APPLIED
PURCHASE_APPROVAL_E2E_NOT_EXECUTED
```

Those markers describe what the **read-only validator command itself** does and the fact that the full supported-client product E2E has not run. They do not overwrite the narrower runtime evidence:

```text
DETERMINISTIC_DEMO_SEED_IMPLEMENTED
BACKEND_LOCAL_START_VERIFIED
BACKEND_PURCHASE_APPROVAL_CHAIN_VERIFIED
COMPLETION_OUTBOX_EVENT_RECORDED
```

The launcher has permanent plan and safety boundary tests, while its component runtime path is covered by the Spring Boot integration test. Those facts do not manufacture a clean-machine duration or unfamiliar-user result.

## Current non-claims

```text
SHARED_DEMO_ENVIRONMENT_SEED_NOT_APPLIED
QUICK_START_10_MINUTES_NOT_EXECUTED
PURCHASE_APPROVAL_E2E_NOT_EXECUTED
CROSS_CLIENT_RUNTIME_NOT_EXECUTED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
```

The backend test does not prove PC/H5/WeChat interaction, browser compatibility, accessibility, attachment binding/download, notification delivery, Connector dispatch, external payment, outage recovery, capacity or operational recovery.

The two seeded attachment objects are uploaded through `ApprovalAttachmentService` and referenced by the request. They remain unbound until the platform exposes an application-layer bind operation; this work intentionally does not call `ApprovalAttachmentStore.bindToInstance` directly.

## Lightweight delivery sequence

Issue #107 uses three coherent delivery areas, not another gate hierarchy:

1. Demo and Guides;
2. Golden Path;
3. Capacity and Recovery.

The launcher safety boundary and backend approval chain remain in the existing permanent validation workflow. Expensive browser, cross-client, capacity and recovery suites should be scheduled or release-candidate scoped. Documentation-only corrections must not manufacture readiness or create another automatic workflow.

## Safety boundary

The Product Readiness work must not:

- enable production migration, AI, Connector, Secret, Provider, scheduler or traffic mutation;
- commit credentials or customer data;
- replace authorization with a demo bypass;
- represent local headers, backend-only actions, Outbox persistence, mocks, static responses or successful builds as production payment or full product evidence;
- create a second automatic PR/main workflow.

Start with [`QUICK_START.md`](QUICK_START.md), then validate and run the [`purchase-payment contract`](PURCHASE_PAYMENT_GOLDEN_PATH.md).
