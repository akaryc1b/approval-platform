# Product Readiness / 产品可用性证据

Tracking issue: [#107 — Prove the product is usable, scalable, and recoverable](https://github.com/akaryc1b/approval-platform/issues/107)

This directory separates runnable local product evidence from build, architecture, release and production-support claims. Exact commit SHAs, Workflow Run IDs and artifact digests belong in immutable PR/Issue acceptance comments; this living index does not copy moving identities.

## Current product paths

| Product outcome | Current default-branch status | Meaning |
| --- | --- | --- |
| Repository/workstation preflight | `IMPLEMENTED` | Read-only prerequisite and configuration validation |
| One-command backend | `IMPLEMENTED_AND_RUNTIME_VERIFIED` | Existing PostgreSQL/Redis/Spring Boot/Flowable/Seed lifecycle |
| Purchase-to-payment golden path | `MERGED_LOCAL_ALPHA_H5_SURROGATE_ACCEPTED` | Real PC/H5 actions, completion Outbox, signed local sandbox 503/recovery and exactly-one side effect; not production payment or real WeChat runtime |
| One-command PC/H5 Quick Start | `IMPLEMENTED_TIMED_ACCEPTANCE_PENDING` | `pnpm demo:quickstart` starts the governed visible demo and records a 600-second measured result; exact-Head two-run evidence is still the acceptance authority |
| WeChat runtime and physical-device evidence | `NOT_VERIFIED` | Build success and H5 surrogate evidence do not count |
| Browser/accessibility matrix | `NOT_VERIFIED` | Requires separate automated and manual evidence |
| Performance/capacity envelope | `NOT_MEASURED` | No unsupported TPS or capacity claim |
| Upgrade, backup/restore and RPO/RTO | `NOT_REHEARSED` | Runbooks alone are insufficient |
| Release and production deployment | `NOT_CREATED` | Default branch is not a Release |

## Start here

- [10-Minute Quick Start](QUICK_START.md)
- [Local Demo User Guide](USER_GUIDE.md)
- [Local Demo Administrator Guide](ADMIN_GUIDE.md)
- [Local Demo Operator Guide](OPERATOR_GUIDE.md)
- [Purchase-Payment Golden Path](PURCHASE_PAYMENT_GOLDEN_PATH.md)
- [Cross-Client Local Demo](CROSS_CLIENT_LOCAL_DEMO.md)
- [PC/H5 Runtime Smoke](PC_H5_RUNTIME_SMOKE.md)

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
```

`pnpm demo:quickstart` owns startup, visible PC/H5 readiness, timing evidence and cleanup. `pnpm demo:runtime:purchase-payment:e2e` is the separate complete local approval-to-sandbox path. Neither command is a production deployment procedure.

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

These markers are scoped to the command that emitted or documented them. The read-only scenario validator can correctly report `_NOT_EXECUTED` while a separately authorized exact-Head runtime has accepted the purchase-to-payment E2E. The component vocabulary is retained for compatibility and does not downgrade the merged Alpha evidence or release a pending Quick Start claim.

## Claim vocabulary

- `DEMO_REPOSITORY_CONTRACT_PASSED`: repository prerequisites are present; workstation tools were not necessarily checked.
- `DEMO_PREFLIGHT_PASSED`: documented local tools and repository contracts passed; no service was started.
- `DEMO_BACKEND_READY_PASSED`: the existing local backend reached Actuator `UP` with the deterministic Seed during a bounded Quick Start run.
- `PC_DEMO_READY_PASSED`: the governed seeded request was visible in the real PC workbench.
- `H5_DEMO_READY_PASSED`: the same governed request was visible in the real H5 task list.
- `QUICK_START_10_MINUTES_PASSED`: one exact source tree reached the published ready outcome within 600 seconds in two independent clean runs.
- `PURCHASE_APPROVAL_E2E_PASSED`: the complete local approval path reached authoritative `COMPLETED` through visible client controls.
- `PURCHASE_TO_PAYMENT_SANDBOX_E2E_PASSED`: the completion event reached the identified signed local sandbox, recovered from an initial HTTP 503 and produced one accepted idempotent result.
- `PRODUCTION_PAYMENT_INTEGRATION_VERIFIED`: reserved for separately authorized real-provider acceptance; never inferred from a sandbox.

A marker name appearing in documentation does not release the marker. Exact-Head runtime evidence and the permanent Workflow are authoritative.

## Quick Start evidence

Every run writes only to:

```text
.runtime/quick-start/<run-id>/
```

Evidence includes source/tree identity, OS and tool versions, UTC timing, Actuator health, governed tenant/business key/actors, PC/H5 screenshots, Playwright trace and cleanup results. `.runtime/` remains untracked.

The timed acceptance requires two different run IDs on the same commit and tree. A startup or cleanup failure resets the consecutive-run ledger. Documentation-only changes are excluded from the heavy runtime by the existing path scope.

## Safety boundary

Product Readiness work must not:

- create a second automatic PR/main Workflow;
- create a second backend, database, Seed, Outbox, sandbox or client launcher;
- write platform or Flowable `ACT_*` tables to advance a process;
- bypass authorization through browser-supplied trusted permissions;
- use mock readiness, fixed sleeps, swallowed exceptions or unbounded waits;
- turn a local profile, H5 surrogate, build, sandbox or default-branch commit into a production claim;
- modify or depend on the independent MySQL 8.4 PR #92.

## Explicit non-claims

```text
WECHAT_DEVTOOLS_RUNTIME_NOT_VERIFIED
WECHAT_PHYSICAL_DEVICE_NOT_VERIFIED
PRODUCTION_DEPLOYMENT_NOT_VERIFIED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
PERFORMANCE_CAPACITY_NOT_VERIFIED
BROWSER_COMPATIBILITY_NOT_VERIFIED
ACCESSIBILITY_NOT_VERIFIED
RPO_RTO_NOT_VERIFIED
MYSQL_8_4_NOT_VERIFIED
RELEASE_NOT_CREATED
```
