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

## Bounded local administrator rehearsal

The explicit local purchase-payment demo supports the working-calendar, SLA-policy,
form authoring and process designer pages with `?demoOperator=demo-admin`. This is
not a production login. The server grants only the capability needed by each
allowlisted HTTP method and canonical endpoint (SLA read/design/publish/activate;
form/process read/design/publish and publication preflight), for the exact seeded tenant and
administrator, and only after matching seed evidence exists. The adapter is
absent outside the local profile, with production profiles, or when the demo or
seed is disabled. Browser-supplied permission headers are never used by this
adapter. Archive/copy, deployment/activation of process releases, export/import,
migration and other administrator modules are not enabled by this rehearsal.

The PC/H5 runtime suite additionally exercises delegation create/cancel/revoke
and calendar/policy v1-to-v2 publishing, immutable versions, repeated draft
saves, real read failures and wrong-actor/tenant denials. These run against the
existing disposable backend and retain the existing capture deadline. Their
receipts and screenshots are separate from the original payment handoff receipt.
The suite also covers form-package publication and process design, including
explicit publication reasons, cancel/repeat, immutable package hashes, real-save
delivery races and preservation of newer edits. No process release is deployed.
These tests do not establish comprehensive platform or production acceptance.
