# Purchase-Payment Golden Path Contract and Local Seed

```text
PURCHASE_PAYMENT_SCENARIO_CONTRACT_STATUS=AVAILABLE
DETERMINISTIC_DEMO_SEED_STATUS=IMPLEMENTED_LOCAL_OPT_IN
BACKEND_LOCAL_START_STATUS=VERIFIED_IN_EPHEMERAL_CI
BACKEND_PURCHASE_APPROVAL_CHAIN_STATUS=VERIFIED_IN_EPHEMERAL_CI
COMPLETION_OUTBOX_EVENT_STATUS=RECORDED_IN_EPHEMERAL_CI
SHARED_DEMO_ENVIRONMENT_SEED_STATUS=NOT_APPLIED
PURCHASE_APPROVAL_E2E_STATUS=NOT_YET_EXECUTED
CROSS_CLIENT_RUNTIME_STATUS=NOT_YET_EXECUTED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_STATUS=NOT_YET_EXECUTED
PRODUCTION_PAYMENT_INTEGRATION_STATUS=NOT_VERIFIED
```

This document governs the deterministic purchase-payment evidence used by the Golden Path delivery area in Issue #107. It binds one high-value request to the existing design, form, release, deployment, activation, purchase-payment, task-action, projection, audit and integration Outbox authorities.

The retained integration test starts a real random-port Spring Boot server against PostgreSQL, applies the explicit local seed, verifies Actuator health, replays the seed idempotently, and then uses the existing HTTP API to complete:

```text
managerApproval
-> financeReview
-> financeCountersign (ALL: two approvers)
-> COMPLETED
```

It verifies the final instance, empty participant task lists, five distinct timeline events and one completion Outbox record. It does **not** prove PC/H5/WeChat interaction, attachment binding/download, browser or accessibility behavior, Connector dispatch, payment sandbox delivery, a returned external result, outage recovery, or a shared demo environment.

## Read-only contract check

From the repository root:

```bash
pnpm demo:scenario:check
```

Machine-readable output:

```bash
node scripts/product-readiness/purchase-payment-scenario-contract.mjs --json
```

A successful static check emits:

```text
PURCHASE_PAYMENT_SCENARIO_CONTRACT_PASSED
DETERMINISTIC_DEMO_SEED_NOT_APPLIED
PURCHASE_APPROVAL_E2E_NOT_EXECUTED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
```

The command is read-only. Runtime availability and backend execution use separate, narrower claims:

```text
DETERMINISTIC_DEMO_SEED_IMPLEMENTED
BACKEND_LOCAL_START_VERIFIED
BACKEND_PURCHASE_APPROVAL_CHAIN_VERIFIED
COMPLETION_OUTBOX_EVENT_RECORDED
```

The boundary check is:

```bash
pnpm demo:seed:check
```

It verifies that the runner is `local`-profile-only, explicit, default-off, resource-backed, free of Demo REST endpoints and direct business-table or Flowable-table mutation, provisions the governed effective release through existing production services, and that permanent CI exercises the existing approval action endpoints through final completion.

## Governed inputs

Scenario contract:

```text
config/demo/purchase-payment-golden-path.json
```

Seed fixture:

```text
config/demo/purchase-payment-demo-seed.json
```

The fixture maps the two logical attachment IDs to fixed UUIDv5 values and non-sensitive text payloads. Both files are packaged into the executable server under `demo/`; no second copy is maintained in application resources.

## Deterministic identities

The contract defines one tenant and six non-secret local identities:

| Identity | Purpose |
| --- | --- |
| `demo-admin` | creates, publishes, deploys, activates and inspects the governed scenario |
| `demo-employee` | submits the request and reads the participant timeline |
| `demo-manager` | approves the manager task |
| `demo-finance-reviewer` | approves the high-value finance review |
| `demo-finance-approver-a` | completes the first parallel countersign |
| `demo-finance-approver-b` | completes the second parallel countersign |

The local-only, read-only `PurchasePaymentDemoOrganizationConnector` resolves:

```text
demo-employee.managerId -> demo-manager
FINANCE_REVIEWER        -> demo-finance-reviewer
FINANCE_APPROVER        -> demo-finance-approver-a, demo-finance-approver-b
```

These are identifiers, not credentials. No password, token, Secret, production user or authorization bypass is introduced.

## Deterministic request and attachment metadata

```text
businessKey:            DEMO-PP-0001
amount:                 12500.00
supplier:               Demo Industrial Supplies Ltd.
purchaseOrderReference: PO-DEMO-2026-0001
attachments:            2 fixed metadata UUIDs
```

The amount exercises the repository's `10000.00` high-value threshold.

Attachments are uploaded through `ApprovalAttachmentService` before the request starts. The current application layer does not expose an instance-binding command, so the fixtures remain unbound and this work does not call `ApprovalAttachmentStore.bindToInstance` directly. Binding and participant-readable download evidence remain future product work.

## Runtime implementation

The switch is available only with the `local` profile and is false by default:

```text
APPROVAL_DEMO_PURCHASE_PAYMENT_ENABLED=false
```

When explicitly enabled, startup:

1. loads and validates both governed JSON inputs;
2. provides the read-only demo organization connector;
3. waits for Flowable local schema bootstrap and applies repository migrations V1 through V50 through the explicit local migration boundary;
4. creates and publishes the canonical Form Package;
5. creates the immutable process Release Package and completes lifecycle/preflight evidence;
6. deploys and activates the governed effective release through existing production services;
7. invokes the singleton production `PurchasePaymentApplicationService` only after the release is effective;
8. uploads both fixed attachments through `ApprovalAttachmentService`;
9. starts the request through the existing service authority;
10. verifies one running instance and exactly one `managerApproval` task for `demo-manager`;
11. records bounded startup evidence and exposes it through the `purchasePaymentDemoSeed` health component;
12. fails application startup if any seed step fails.

The permanent integration test then:

1. replays the seed and verifies stable instance, task, attachment and timestamp identity;
2. approves the manager task and replays the same HTTP request/idempotency key;
3. approves the single finance-review task;
4. verifies two parallel countersign tasks assigned to the governed finance approvers;
5. approves the first countersign and verifies the second remains pending;
6. approves the second countersign and verifies `COMPLETED` with no active tasks;
7. verifies all four participant pending-task lists are empty;
8. reads the participant-authorized timeline and binds five distinct audit event IDs to the start and four task actions;
9. reads one final JDBC Outbox row for the governed connector, request and instance.

The production controller and singleton purchase-payment service are not replaced. The seed does not construct the legacy effective-release bridge. No Demo action controller, table writer, security exception or automatic production activation is added.

## Run locally

Start the repository backend and seed with the one-command candidate documented in [`QUICK_START.md`](QUICK_START.md):

```bash
pnpm demo:backend:start
```

The lower-level equivalent remains:

```bash
APPROVAL_DEMO_PURCHASE_PAYMENT_ENABLED=true \
mvn -B -ntp -f apps/server/pom.xml spring-boot:run \
  -Dspring-boot.run.profiles=local
```

Successful startup logs:

```text
PURCHASE_PAYMENT_DEMO_SEED_APPLIED
```

Verify aggregate health:

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
```

Inspect the seeded instance using the `instanceId` from the startup marker or health details:

```bash
curl -fsS \
  -H 'X-Tenant-Id: demo-purchase-payment' \
  -H 'X-Operator-Id: demo-admin' \
  -H 'X-Request-Id: demo-inspect-instance-v1' \
  -H 'X-Trace-Id: demo-inspect-instance-v1' \
  http://127.0.0.1:8080/api/approval/instances/INSTANCE_ID
```

Inspect the manager's pending task:

```bash
curl -fsS \
  -H 'X-Tenant-Id: demo-purchase-payment' \
  -H 'X-Operator-Id: demo-manager' \
  -H 'X-Request-Id: demo-inspect-manager-task-v1' \
  -H 'X-Trace-Id: demo-inspect-manager-task-v1' \
  http://127.0.0.1:8080/api/approval/tasks/pending
```

Approval actions use the existing endpoint and must provide a fresh request ID and idempotency key:

```text
POST /api/approval/tasks/{taskId}/approve
X-Tenant-Id
X-Operator-Id
X-Request-Id
Idempotency-Key
X-Trace-Id
Content-Type: application/json
```

The permanent CI test proves this backend sequence. A separate clean-machine, user-driven and client-driven execution is still required before any full E2E or Quick Start claim.

## Permanent runtime verification

`PurchasePaymentDemoSeedIntegrationTest` is part of the existing Maven reactor and the repository's single permanent validation workflow. It uses:

- PostgreSQL 16 Testcontainer;
- `@SpringBootTest` with a real random HTTP port;
- explicit `local` profile and seed switch;
- governed Form/Release/Deployment/Activation authorities;
- `/actuator/health`;
- existing instance, pending-task, approve and timeline endpoints;
- a second `PurchasePaymentDemoSeeder.apply()` call for idempotent seed replay;
- an identical manager action replay for HTTP/idempotency evidence;
- read-only JDBC verification of the completion Outbox row.

This is sufficient for:

```text
DETERMINISTIC_DEMO_SEED_IMPLEMENTED
BACKEND_LOCAL_START_VERIFIED
BACKEND_PURCHASE_APPROVAL_CHAIN_VERIFIED
COMPLETION_OUTBOX_EVENT_RECORDED
```

It is not sufficient for:

```text
QUICK_START_10_MINUTES_PASSED
PURCHASE_APPROVAL_E2E_PASSED
CROSS_CLIENT_RUNTIME_VERIFIED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_PASSED
PRODUCTION_PAYMENT_INTEGRATION_VERIFIED
```

## Existing API bindings

The scenario uses existing `/api/approval` mappings. Runtime task actions use:

```text
GET  /tasks/pending
POST /tasks/{taskId}/approve
GET  /instances/{instanceId}
GET  /instances/{instanceId}/timeline
```

Every mutating request retains:

```text
X-Tenant-Id
X-Operator-Id
X-Request-Id
Idempotency-Key
X-Trace-Id
```

## Evidence identity

The backend runtime evidence binds:

```text
tenantId
businessKey
instanceId
taskIds
auditEventIds
finalStatus
completionOutboxRequestId
connectorKey
```

Future PC, H5 and WeChat evidence must refer to the same business identity and final state. Three separately mocked client states do not satisfy this contract.

## Current non-claims

```text
SHARED_DEMO_ENVIRONMENT_SEED_NOT_APPLIED
QUICK_START_10_MINUTES_NOT_EXECUTED
PURCHASE_APPROVAL_E2E_NOT_EXECUTED
CROSS_CLIENT_RUNTIME_NOT_EXECUTED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
```
