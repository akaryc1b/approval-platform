# Product Readiness / 产品可用性证据

Tracking issue: [#107 — Prove the product is usable, scalable, and recoverable](https://github.com/akaryc1b/approval-platform/issues/107)

This directory is the current index for product-level evidence. It does not turn build, boundary, mock, or documentation results into a product acceptance claim.

> A successful preflight is not product acceptance. The product is ready only when a user can complete work and operators can keep or recover the service.

## Current baseline

This first slice is based on `main` commit:

```text
779c4fbd09dcf17d45cc523e725222797cc5cb85
```

| Evidence | Current status | Meaning |
| --- | --- | --- |
| Repository and workstation preflight | `IMPLEMENTED` | Read-only prerequisite and configuration check only |
| Source-based backend start path | `DOCUMENTED_NOT_YET_TIMED` | Candidate commands are documented; no clean-machine timing is claimed |
| New-user startup within 10 minutes | `NOT_YET_VERIFIED` | Requires a timed run from a clean supported environment |
| Deterministic demo tenant, users and data | `NOT_YET_PROVIDED` | No demo identity or seed-data claim is made |
| Product screenshots or online demo | `NOT_YET_PROVIDED` | Build output is not substituted for a product demonstration |
| Purchase-payment golden path | `NOT_YET_VERIFIED` | PC, H5 and WeChat must use the same business instance and state |
| Payment sandbox integration | `NOT_YET_VERIFIED` | Mock and in-memory adapters do not count as an external result |
| Browser and accessibility matrix | `NOT_YET_VERIFIED` | Requires automated and manual scenario evidence |
| Capacity and performance envelope | `NOT_YET_MEASURED` | No unsupported TPS or capacity claim is allowed |
| Upgrade, backup/restore and RPO/RTO | `NOT_YET_REHEARSED` | Runbooks alone do not count as a recovery exercise |

## Claim vocabulary

Use the following terms narrowly:

- `DEMO_REPOSITORY_CONTRACT_PASSED`: required files and fail-closed/local configuration contracts were present. Local tool availability was not checked.
- `DEMO_PREFLIGHT_PASSED`: repository contracts and all documented local tool checks passed. No service or user scenario was executed.
- `BACKEND_LOCAL_START_VERIFIED`: the executable server started with the documented local profile and its bounded health check returned `UP`.
- `QUICK_START_10_MINUTES_PASSED`: an unfamiliar user completed the published startup outcome on a clean supported environment within 600 seconds, with retained timing and environment evidence.
- `PURCHASE_APPROVAL_E2E_PASSED`: the complete approval workflow passed, but no external payment result is implied.
- `PURCHASE_TO_PAYMENT_SANDBOX_E2E_PASSED`: a request left the platform, reached an explicitly identified sandbox, and returned a verified idempotent result.
- `PRODUCTION_PAYMENT_INTEGRATION_VERIFIED`: reserved for separately authorized real-provider acceptance; it must never be inferred from a mock or sandbox.

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
- represent local headers, mocks, static responses or successful builds as production evidence;
- create a second automatic PR/main workflow.

Start with [`QUICK_START.md`](QUICK_START.md).
