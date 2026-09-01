# Local Demo User Guide

This guide describes the ordinary local Product Alpha experience created by:

```bash
pnpm demo:quickstart
```

It does not describe production authentication or a shared hosted environment.

## Demo identity and request

The Quick Start reads the identities and request from the governed demo manifests:

```text
tenant: demo-purchase-payment
business key: DEMO-PP-0001
current task: managerApproval
PC actor: demo-manager
H5 actor: demo-manager
```

The same manager task is intentionally shown in both clients so a new user can confirm cross-client visibility without changing workflow state.

## Open the PC workbench

Use the exact `QUICK_START_PC_URL` printed by the command. The local PC login is:

```text
username: vben
password: 123456
```

Complete the visible slider and sign in. The workbench must show the request card with business key `DEMO-PP-0001`.

## Open the H5 task list

Use the exact `QUICK_START_H5_URL` printed by the command. The URL carries the allowlisted local demo operator `demo-manager`. The H5 task list must show the same `DEMO-PP-0001` request.

Do not remove or replace the `demoOperator` query parameter. The client cannot use it to create trusted permissions; the server remains authoritative for tenant, actor and task assignment.

## What the Quick Start proves

The timed readiness check proves:

- the backend is healthy;
- the deterministic Seed exists;
- the expected manager task is visible in the PC workbench;
- the same task is visible in the H5 task list;
- both pages are backed by the same tenant and business key.

The check does not approve the task. To exercise the complete local approval and payment-sandbox path, stop the Quick Start and run:

```bash
pnpm demo:runtime:purchase-payment:e2e
```

That separate command uses visible PC/H5 controls and cleans its own runtime.

## Finish

Return to the Quick Start terminal and press `Ctrl-C`. Wait for the cleanup markers before closing the terminal. The command is unsuccessful when cleanup cannot stop a managed process or release a port.

## Limits

This guide does not verify a WeChat Developer Tools runtime, physical mobile device, broad browser matrix, accessibility, production login, production payment or production deployment.
