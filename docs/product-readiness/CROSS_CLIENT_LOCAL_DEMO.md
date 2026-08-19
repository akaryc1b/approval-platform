# PC / H5 / WeChat Local Purchase-Payment Demo

This guide wires the existing deterministic purchase-payment seed to the existing PC workbench, UniApp H5 client and WeChat Mini Program development build.

It provides repeatable launch configuration and a governed handoff plan. It does **not** claim that a retained end-to-end UI run has already been executed.

## Current evidence boundary

Implemented by this slice:

```text
LOCAL_CROSS_CLIENT_LAUNCHERS_IMPLEMENTED
LOCAL_DEMO_IDENTITY_HEADERS_GUARDED
PC_APPROVAL_PROXY_BOUNDED
H5_APPROVAL_PROXY_BOUNDED
WECHAT_PRIVATE_BACKEND_ORIGIN_REQUIRED
```

Still not executed:

```text
CROSS_CLIENT_RUNTIME_NOT_EXECUTED
PURCHASE_APPROVAL_E2E_NOT_EXECUTED
WECHAT_PHYSICAL_DEVICE_NOT_VERIFIED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED
```

A successful build, a printed launch plan, an opened browser page or a prepared Mini Program bundle is not cross-client acceptance.

## Governed scenario

All clients use the same repository contract:

```text
Tenant:       demo-purchase-payment
Business key: DEMO-PP-0001
Supplier:     Demo Industrial Supplies Ltd.
Amount:       12500.00
Purchase PO:  PO-DEMO-2026-0001
```

The expected user-visible handoff is:

| Order | Client | Actor | Expected task |
| ---: | --- | --- | --- |
| 1 | PC | `demo-manager` | `managerApproval` |
| 2 | H5 | `demo-finance-reviewer` | `financeReview` |
| 3 | WeChat | `demo-finance-approver-a` | `financeCountersign` |
| 4 | WeChat | `demo-finance-approver-b` | `financeCountersign` |
| 5 | PC | `demo-employee` | Read final `COMPLETED` state |

The machine-readable source is [`../../config/demo/cross-client-local-demo.json`](../../config/demo/cross-client-local-demo.json). It is checked against [`../../config/demo/purchase-payment-golden-path.json`](../../config/demo/purchase-payment-golden-path.json) before a client process starts.

## Security boundary

The backend local profile uses `local-headers` identity mode. The clients may send local tenant and operator headers only when all of the following are true:

1. `VITE_APPROVAL_LOCAL_IDENTITY_HEADERS=true` is set by the fixed launcher;
2. the client is running in Vite development mode;
3. the approval API is same-origin or an HTTP loopback/private-network target;
4. the actor is listed in the governed scenario manifest.

The governed transports remove caller-supplied `X-Tenant-Id` and `X-Operator-Id` values before applying the launcher-owned values. They never add `X-Approval-Trusted-Permissions`.

Production builds continue to require a trusted host principal. The local bridge fails closed for public hosts, HTTPS internet targets, embedded credentials and unsupported ports.

## 1. Inspect the plan

From the repository root:

```bash
pnpm demo:client:plan
```

This command is read-only. It prints the canonical tenant, business key, four task handoffs, final read role, evidence keys and non-claims.

## 2. Start the real backend

In terminal A:

```bash
pnpm demo:backend:start
```

Keep the process attached. Wait for:

```text
DEMO_BACKEND_ONE_COMMAND_STARTED
BACKEND_LOCAL_START_VERIFIED
PURCHASE_PAYMENT_DEMO_SEED_APPLIED
```

The default backend origin used by the client launcher is:

```text
http://127.0.0.1:8080
```

A different loopback or private-network HTTP origin may be supplied with:

```bash
--backend-origin http://192.168.1.20:8080
```

Public origins are rejected.

## 3. PC: manager approval

In terminal B:

```bash
pnpm demo:client:pc -- --actor demo-manager
```

The default page is:

```text
http://127.0.0.1:5777/approval/workbench
```

In **待我处理**, open the `DEMO-PP-0001` request and approve the `managerApproval` task.

The PC launcher uses `/approval-api` for only the Approval Platform backend. The Vben shell's existing `/api` mock proxy remains separate.

A different local port may be used:

```bash
pnpm demo:client:pc -- --actor demo-manager --port 5780
```

## 4. H5: finance review

In terminal C:

```bash
pnpm demo:client:h5 -- --actor demo-finance-reviewer
```

Open the printed H5 task-center URL, locate `DEMO-PP-0001`, and approve the `financeReview` task.

The default H5 port is `9000`. A different local port may be used:

```bash
pnpm demo:client:h5 -- --actor demo-finance-reviewer --port 9001
```

## 5. WeChat: two-person countersign

Prepare the first finance approver role:

```bash
pnpm demo:client:wechat -- --actor demo-finance-approver-a
```

Open `pages/task/list` in WeChat Developer Tools and approve the first `financeCountersign` task.

Then stop that client process and prepare the second role:

```bash
pnpm demo:client:wechat -- --actor demo-finance-approver-b
```

Approve the second `financeCountersign` task.

For WeChat Developer Tools, set `VITE_WX_APPID` in the invoking environment when an App ID is required. The backend origin must be reachable from the Developer Tools environment. A real phone may require a LAN address rather than `127.0.0.1`:

```bash
pnpm demo:client:wechat -- \
  --actor demo-finance-approver-a \
  --backend-origin http://192.168.1.20:8080
```

This repository slice does not claim physical-device verification, WeChat domain-whitelist approval or production Mini Program publication.

## 6. PC: final state agreement

Start a second PC role on another port:

```bash
pnpm demo:client:pc -- --actor demo-employee --port 5778
```

Open **我发起的** and verify that `DEMO-PP-0001` is `已完成` and has no current task. Compare the timeline and business identifiers with the evidence captured from the preceding clients.

## Evidence to retain

A future accepted runtime run must retain at least:

```text
tenantId
businessKey
instanceId
taskIds
auditEventIds
finalStatus
```

Recommended retained evidence for each client step:

- timestamp and client/runtime version;
- actor ID and task definition key;
- screenshot before and after the action;
- request ID returned by the backend;
- instance ID and task ID;
- final PC/H5/WeChat state comparison;
- WeChat Developer Tools or physical-device information;
- any browser, console or network errors.

Only after the same instance has been completed through supported client UIs and its final state agrees across those clients may the narrower `PURCHASE_APPROVAL_E2E_PASSED` claim be considered.

## Reusing installed workspaces

After a client workspace has already been generated and installed, startup may skip installation:

```bash
pnpm demo:client:pc -- --actor demo-manager --skip-install
pnpm demo:client:h5 -- --actor demo-finance-reviewer --skip-install
pnpm demo:client:wechat -- --actor demo-finance-approver-a --skip-install
```

This option only skips dependency installation. It does not skip scenario validation or relax identity and origin checks.

## Stop without deleting data

Stop each attached client with `Ctrl-C`, then stop the backend process. To stop PostgreSQL and Redis while preserving the local volume:

```bash
pnpm demo:backend:stop
```

Preserving the volume is intentional. The backend Seed has a permanent integration test proving it can be replayed after the approval has progressed or completed without creating a second instance.

## Forbidden conclusions

Do not infer any of the following from these launchers or from client build success:

```text
QUICK_START_10_MINUTES_PASSED
PURCHASE_APPROVAL_E2E_PASSED
PC_H5_WECHAT_RUNTIME_PASSED
BROWSER_COMPATIBILITY_PASSED
ACCESSIBILITY_BASELINE_PASSED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_PASSED
PRODUCTION_PAYMENT_INTEGRATION_VERIFIED
```

The next evidence step is an actual timed and retained run, not another static contract or build-only result.
