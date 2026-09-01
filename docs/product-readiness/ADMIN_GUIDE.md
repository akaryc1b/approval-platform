# Local Demo Administrator Guide

The Quick Start is governed by checked-in manifests rather than ad hoc script constants.

## Authorities

| Purpose | Authority |
| --- | --- |
| Purchase-payment tenant, users, request and workflow | `config/demo/purchase-payment-golden-path.json` |
| PC/H5/WeChat routes and actor allowlists | `config/demo/cross-client-local-demo.json` |
| Quick Start deadline, ready clients and claims | `config/demo/quick-start.json` |
| Authoritative process nodes | `PurchasePaymentTemplate` |

The Quick Start validates these files before starting services. A mismatch fails before a readiness claim can be produced.

## Governed local identities

The Quick Start publishes only the actor needed for the initial task:

```text
demo-manager
```

Other seeded roles remain available to the separate golden-path E2E, including the employee, finance reviewer and two finance countersigners. The browser cannot manufacture a trusted permission header, change tenant assignment or write workflow state directly.

## Read-only inspection

After the backend is ready, administrators may inspect health:

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
```

The deterministic request is identified by `DEMO-PP-0001`. Use the existing product UI for normal inspection. Do not edit PostgreSQL rows or Flowable `ACT_*` tables to repair or advance the demo.

## Configuration changes

Do not customize the Quick Start by editing generated `.upstream` workspaces. Product changes belong in the maintained overlays or governed manifests and must pass:

```bash
pnpm demo:scenario:check
pnpm demo:clients:check
pnpm demo:quickstart:check
```

The `local` profile and local-header identity are demo-only. They must never become production defaults.

## Reset

A normal `Ctrl-C` stop deletes the disposable runtime. When an externally terminated process leaves resources behind, use:

```bash
node scripts/product-readiness/demo-backend.mjs reset --confirm-local-data-loss
```

The confirmation flag is mandatory. The reset is limited to the `approval-platform-demo` Compose project and its disposable data.

## Evidence ownership

Environment, timing, screenshots and cleanup evidence are written under `.runtime/quick-start/`. Do not commit this directory. Permanent acceptance is retained through the existing Workflow artifact and an exact-Head PR comment.
