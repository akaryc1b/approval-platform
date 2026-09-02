# Product Readiness / 产品可用性证据

Tracking issue: [#107 — Prove the product is usable, scalable, and recoverable](https://github.com/akaryc1b/approval-platform/issues/107)

This directory separates runnable local product evidence from build, architecture, release and production-support claims. Exact commit SHAs, Workflow Run IDs and artifact digests belong in immutable PR/Issue acceptance comments; this living index does not copy moving identities.

## Current product paths

| Product outcome | Current default-branch status | Meaning |
| --- | --- | --- |
| Repository/workstation preflight | `IMPLEMENTED` | Read-only prerequisite and configuration validation |
| One-command backend | `IMPLEMENTED_AND_RUNTIME_VERIFIED` | Existing PostgreSQL/Redis/Spring Boot/Flowable/Seed lifecycle |
| Purchase-to-payment golden path | `MERGED_LOCAL_ALPHA_H5_SURROGATE_ACCEPTED` | Real PC/H5 actions, completion Outbox, signed local sandbox 503/recovery and exactly-one side effect; not production payment or real WeChat runtime |
| One-command PC/H5 Quick Start | `IMPLEMENTED_EXACT_HEAD_EVIDENCE_GATED` | `pnpm demo:quickstart` starts the governed visible demo and records a 600-second measured result; exact-Head two-run evidence and the permanent Workflow are the acceptance authority |
| WeChat runtime and physical-device evidence | `NOT_VERIFIED` | Build success and H5 surrogate evidence do not count |
| Browser/accessibility matrix | `MERGED_ENGINE_BASELINE_ACCEPTED` | Chromium baseline plus Firefox and Playwright WebKit smoke, CJK rendering and authenticated PC keyboard evidence; not real Safari, broad browser support or full WCAG |
| Performance/capacity envelope | `INITIAL_SMALL_DEMO_EXECUTABLE_EVIDENCE_GATED` | Small Demo configured-point measurement is executable; Standard Deployment, Large Tenant, peak and production envelopes remain unverified |
| Upgrade, backup/restore and RPO/RTO | `NOT_REHEARSED` | Runbooks, migration history and local Outbox recovery are insufficient |
| Release and production deployment | `NOT_CREATED` | Default branch is not a Release |

## Start here

- [10-Minute Quick Start](QUICK_START.md)
- [Local Demo User Guide](USER_GUIDE.md)
- [Local Demo Administrator Guide](ADMIN_GUIDE.md)
- [Local Demo Operator Guide](OPERATOR_GUIDE.md)
- [Purchase-Payment Golden Path](PURCHASE_PAYMENT_GOLDEN_PATH.md)
- [Cross-Client Local Demo](CROSS_CLIENT_LOCAL_DEMO.md)
- [PC/H5 Runtime Smoke](PC_H5_RUNTIME_SMOKE.md)
- [Browser and Baseline Accessibility Matrix](BROWSER_ACCESSIBILITY_MATRIX.md)
- [Capacity and Recovery Operating Envelope](CAPACITY_RECOVERY_ENVELOPE.md)

## Executable commands

```bash
pnpm demo:preflight
pnpm demo:backend:plan
pnpm demo:backend:start
pnpm demo:backend:stop
pnpm demo:quickstart:plan
pnpm demo:quickstart
pnpm demo:quickstart:check
pnpm demo:runtime:purchase-payment:e2e
pnpm demo:runtime:browser-accessibility
pnpm demo:runtime:capacity-recovery:plan
pnpm demo:runtime:capacity-recovery:check
pnpm demo:runtime:capacity-recovery
```

`pnpm demo:quickstart` owns startup, visible PC/H5 readiness, timing evidence and cleanup. `pnpm demo:runtime:purchase-payment:e2e` is the separate complete local approval-to-sandbox path. `pnpm demo:runtime:browser-accessibility` reuses Quick Start for the bounded engine/accessibility matrix. `pnpm demo:runtime:capacity-recovery` measures one Small Demo workload point and reuses accepted Outbox recovery evidence. None of these commands is a production deployment procedure.

## Component and validator markers

The permanent boundaries still require the narrower backend, Seed and read-only scenario-contract vocabulary:

```text
DEMO_BACKEND_ONE_COMMAND_IMPLEMENTED
PURCHASE_PAYMENT_SCENARIO_CONTRACT_PASSED
DETERMINISTIC_DEMO_SEED_IMPLEMENTED
BACKEND_LOCAL_START_VERIFIED
BACKEND_PURCHASE_APPROVAL_CHAIN_VERIFIED
COMPLETION_OUTBOX_EVENT_RECORDED
SHARED_DEMO_ENVIRONMENT_SEED_NOT_APPLIED
QUICK_START_10_MINUTES_NOT_EXECUTED
PURCHASE_APPROVAL_E2E_NOT_EXECUTED
CROSS_CLIENT_RUNTIME_NOT_EXECUTED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
```

These markers are scoped to the command that emitted or documented them. The read-only scenario validator can correctly report `_NOT_EXECUTED` while a separately authorized exact-Head runtime has accepted the purchase-to-payment E2E. The component vocabulary is retained for compatibility and does not downgrade merged Product Alpha evidence or itself release a Quick Start, browser or capacity claim.

## Claim vocabulary

- `DEMO_REPOSITORY_CONTRACT_PASSED`: repository prerequisites are present; workstation tools were not necessarily checked.
- `DEMO_PREFLIGHT_PASSED`: documented local tools and repository contracts passed; no service was started.
- `DEMO_BACKEND_READY_PASSED`: the existing local backend reached Actuator `UP` with the deterministic Seed during a bounded Quick Start run.
- `PC_DEMO_READY_PASSED`: the governed seeded request was visible in the real PC workbench.
- `H5_DEMO_READY_PASSED`: the same governed request was visible in the real H5 task list.
- `QUICK_START_10_MINUTES_PASSED`: one exact source tree reached the published ready outcome within 600 seconds in two independent clean runs.
- `PURCHASE_APPROVAL_E2E_PASSED`: the complete local approval path reached authoritative `COMPLETED` through visible client controls.
- `PURCHASE_TO_PAYMENT_SANDBOX_E2E_PASSED`: the completion event reached the identified signed local sandbox, recovered from an initial HTTP 503 and produced one accepted idempotent result.
- `PC_H5_CHROMIUM_COMPATIBILITY_BASELINE_PASSED`: the bounded authenticated Chromium PC/H5 surface passed the published engine checks.
- `PC_H5_FIREFOX_COMPATIBILITY_SMOKE_PASSED`: the bounded Firefox PC/H5 smoke passed; it is not broad Firefox acceptance.
- `PC_H5_WEBKIT_ENGINE_COMPATIBILITY_SMOKE_PASSED`: Playwright WebKit passed the bounded engine smoke; it is not real Safari acceptance.
- `SMALL_DEMO_CAPACITY_BASELINE_PASSED`: the exact configured Small Demo point passed its declared thresholds; it is not a maximum or production capacity claim.
- `OUTBOX_CONNECTOR_RECOVERY_REUSED_AND_MEASURED`: exact-Head E2E PENDING/503 and DELIVERED evidence was reused and its retained evidence interval measured; it is not production RTO.
- `PRODUCTION_PAYMENT_INTEGRATION_VERIFIED`: reserved for separately authorized real-provider acceptance; never inferred from a sandbox.

A marker name appearing in documentation does not release the marker. Exact-Head runtime evidence and the permanent Workflow are authoritative.

## Runtime evidence roots

Quick Start writes to:

```text
.runtime/quick-start/<run-id>/
```

Browser/accessibility writes to:

```text
.runtime/browser-accessibility/<run-id>/
```

Capacity/recovery writes to:

```text
.runtime/capacity-recovery/<run-id>/
```

The purchase-payment E2E retains its independent source evidence under `.runtime/purchase-payment-e2e/`. Every root remains untracked. A startup, runtime, threshold or cleanup failure prevents the corresponding claim. Documentation-only changes remain outside the heavy runtime path scopes.

## Safety boundary

Product Readiness work must not:

- create a second automatic PR/main Workflow;
- create a second backend, database, Seed, Outbox, sandbox or client launcher;
- write platform or Flowable `ACT_*` tables to advance a process;
- bypass authorization through browser-supplied trusted permissions;
- use mock readiness, fixed sleeps, swallowed exceptions or unbounded waits;
- turn a local profile, H5 surrogate, browser engine, build, sandbox or default-branch commit into a production claim;
- publish a capacity, RPO or RTO number that was not measured;
- modify or depend on the independent MySQL 8.4 PR #92.

## Explicit non-claims

```text
WECHAT_DEVTOOLS_RUNTIME_NOT_VERIFIED
WECHAT_PHYSICAL_DEVICE_NOT_VERIFIED
PRODUCTION_DEPLOYMENT_NOT_VERIFIED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
FULL_BROWSER_COMPATIBILITY_NOT_VERIFIED
SAFARI_BROWSER_NOT_VERIFIED
IOS_SAFARI_NOT_VERIFIED
ANDROID_CHROME_NOT_VERIFIED
WECHAT_WEBVIEW_NOT_VERIFIED
FULL_WCAG_CONFORMANCE_NOT_VERIFIED
SCREEN_READER_MANUAL_TEST_NOT_VERIFIED
STANDARD_DEPLOYMENT_CAPACITY_NOT_VERIFIED
LARGE_TENANT_CAPACITY_NOT_VERIFIED
PRODUCTION_CAPACITY_NOT_VERIFIED
PEAK_RESOURCE_ENVELOPE_NOT_VERIFIED
MULTI_NODE_CAPACITY_NOT_VERIFIED
UPGRADE_REHEARSAL_NOT_VERIFIED
BACKUP_RESTORE_NOT_VERIFIED
RPO_RTO_NOT_VERIFIED
MYSQL_8_4_NOT_VERIFIED
RELEASE_NOT_CREATED
```
