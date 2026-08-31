# Approval Platform — 10-Minute Quick Start

```text
QUICK_START_COMMAND_STATUS=IMPLEMENTED
QUICK_START_ACCEPTANCE_SOURCE=EXACT_HEAD_RUNTIME_EVIDENCE
QUICK_START_10_MINUTES_NOT_YET_ACCEPTED
```

This guide is the supported local Product Alpha entry path tracked by Issue #133. It starts the existing platform stack and proves that the governed purchase-payment request is visible in both the PC and H5 clients. The guide does not turn a successful build or an unmeasured run into a 10-minute acceptance claim.

## Outcome

From the repository root, one command performs the complete startup lifecycle:

```bash
pnpm demo:quickstart
```

A successful ready state means all of the following are true on the same source tree:

```text
repository and workstation preflight passed
PostgreSQL 16 and Redis 7.4 are ready
Spring Boot + Flowable reached Actuator UP
the deterministic purchase-payment Seed was applied
PC is reachable on port 5777 as demo-manager
H5 is reachable on port 9000 as demo-manager
DEMO-PP-0001 is visible in both real client pages
startup timing and environment evidence were written
```

The command then remains attached. Press `Ctrl-C` once to stop the clients and backend and remove the disposable local containers, network, PostgreSQL volume and occupied ports.

## Prerequisites

Use a clean macOS, Linux or Windows workstation with:

- Java 21;
- Maven 3.9.6 or newer;
- Node 22.18+ within the 22.x line, or Node 24.x;
- pnpm 10; this repository declares pnpm 10.33.4;
- Docker Engine or Docker Desktop with Docker Compose v2;
- Google Chrome, Chromium or Microsoft Edge. Set `APPROVAL_DEMO_CHROME_PATH` only when automatic browser discovery cannot find the executable;
- enough memory and disk for the Maven reactor, generated frontend workspaces, PostgreSQL and Redis.

The command runs the existing `demo-preflight.mjs` before changing runtime state. Missing or unsupported tools fail closed with a remediation message.

## Inspect the plan first

The plan command is read-only:

```bash
pnpm demo:quickstart:plan
```

It prints the governed tenant, business key, actor-scoped URLs, 600-second limit, lifecycle stages, evidence location, gated claims and explicit non-claims. It does not start a process, container or database.

The existing component lifecycle remains available for focused diagnosis:

```bash
pnpm demo:backend:plan
pnpm demo:backend:start
pnpm demo:backend:stop
```

Static boundaries can be checked separately:

```bash
pnpm demo:preflight -- --repository-only
pnpm demo:scenario:check
pnpm demo:clients:check
pnpm demo:quickstart:check
```

These checks do not prove timed startup.

## Existing component evidence vocabulary

The following markers remain documented for compatibility with the narrower commands and permanent boundary tests:

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

These are command-scoped markers, not one global status block. For example, `PURCHASE_PAYMENT_SCENARIO_CONTRACT_PASSED` describes the read-only manifest validator, while its `_NOT_EXECUTED` markers state that the validator itself did not start a runtime. They do not erase the separately retained purchase-to-payment E2E acceptance. Likewise, `QUICK_START_10_MINUTES_NOT_EXECUTED` remains the preflight/backend-component non-claim until exact-Head Quick Start evidence releases the timed claim.

## Run the Quick Start

```bash
pnpm demo:quickstart
```

Do not run a separate backend or client command in parallel. The Quick Start owns the local ports and lifecycle:

| Component | Address | Governed identity |
| --- | --- | --- |
| Backend health | `http://127.0.0.1:8080/actuator/health` | N/A |
| PC workbench | printed as `QUICK_START_PC_URL` | `demo-manager` |
| H5 task list | printed as `QUICK_START_H5_URL` | `demo-manager` |

The PC development login is local demo authentication only:

```text
username: vben
password: 123456
```

The browser automation completes the local login slider and verifies the task card. It does not approve the task or mutate the workflow.

When ready, the terminal prints:

```text
QUICK_START_RUN_ID=<run-id>
QUICK_START_READY_SECONDS=<measured-seconds>
QUICK_START_PC_URL=<url>
QUICK_START_H5_URL=<url>
QUICK_START_TENANT=demo-purchase-payment
QUICK_START_BUSINESS_KEY=DEMO-PP-0001
QUICK_START_PC_ACTOR=demo-manager
QUICK_START_H5_ACTOR=demo-manager
QUICK_START_EVIDENCE=.runtime/quick-start/<run-id>
```

## Evidence

Every run writes to an untracked directory:

```text
.runtime/quick-start/<run-id>/
```

The bounded evidence set includes:

```text
source-identity.json
environment.json
contract.json
backend-health.json
quick-start-browser-evidence.json
quick-start-pc.png
quick-start-h5.png
startup-summary.json
cleanup-evidence.json
runtime-summary.json
playwright/trace.zip
backend.log
pc.log
h5.log
```

`source-identity.json` binds the checked-out tree to the exact Head. `environment.json` records the operating system, architecture, CPU count/model, memory and tool versions. `startup-summary.json` records the UTC start and ready timestamps and the measured duration. A run over 600 seconds fails and resets the consecutive-run ledger.

No `.runtime` content is committed.

## Acceptance rule

One successful execution records only the first clean run. The following gated claims are allowed only after two distinct clean run IDs on the same exact commit and tree, with complete cleanup after each run:

- gated claim: `QUICK_START_10_MINUTES_PASSED`
- gated claim: `DEMO_BACKEND_READY_PASSED`
- gated claim: `PC_DEMO_READY_PASSED`
- gated claim: `H5_DEMO_READY_PASSED`
- gated claim: `TWO_CONSECUTIVE_CLEAN_QUICK_START_RUNS_PASSED`

The existing permanent Workflow executes the two-run CI form only when the changed path set is relevant. Documentation-only changes do not select the full timed runtime. Exact Head, Run IDs, artifact digest and observed claim markers belong in the PR acceptance comment, not in this living guide.

## Stop and cleanup

Press `Ctrl-C` in the Quick Start terminal. Cleanup is mandatory and fail-closed:

```text
stop H5
stop PC
stop backend
remove approval-platform-demo containers
remove the disposable PostgreSQL volume
remove the Compose network
release ports 5432, 5777, 6379, 8080 and 9000
write cleanup-evidence.json
```

A cleanup failure makes the command fail even when the clients had reached ready state.

For recovery after an externally interrupted process, use the existing explicit reset command:

```bash
node scripts/product-readiness/demo-backend.mjs reset --confirm-local-data-loss
```

This deletes only the disposable `approval-platform-demo` resources. The script does not update platform tables or Flowable `ACT_*` tables.

## Next product paths

The Quick Start stops at a visible, operable seeded task. The complete purchase-to-payment Alpha path remains available separately:

```bash
pnpm demo:runtime:purchase-payment:e2e
```

See:

- [User Guide](USER_GUIDE.md)
- [Administrator Guide](ADMIN_GUIDE.md)
- [Operator Guide](OPERATOR_GUIDE.md)
- [Purchase-Payment Golden Path](PURCHASE_PAYMENT_GOLDEN_PATH.md)

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

The Quick Start is a local Product Alpha path, not a Release or production deployment procedure.
