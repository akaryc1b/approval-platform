# Purchase-Payment Golden Path Contract and Local Seed

```text
PURCHASE_PAYMENT_SCENARIO_CONTRACT_STATUS=AVAILABLE
DETERMINISTIC_DEMO_SEED_STATUS=IMPLEMENTED_LOCAL_OPT_IN
BACKEND_LOCAL_START_STATUS=VERIFIED_IN_EPHEMERAL_CI
SHARED_DEMO_ENVIRONMENT_SEED_STATUS=NOT_APPLIED
PURCHASE_APPROVAL_E2E_STATUS=NOT_YET_EXECUTED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_STATUS=NOT_YET_EXECUTED
PRODUCTION_PAYMENT_INTEGRATION_STATUS=NOT_VERIFIED
```

This document governs the first runtime slice for the Golden Path delivery area in Issue #107. It binds a deterministic high-value purchase-payment scenario to the existing `PurchasePaymentController`, `PurchasePaymentTemplate`, application services and connector SPI.

The retained integration test now starts a real random-port Spring Boot server against PostgreSQL, applies the explicit local seed, verifies Actuator health, reads the instance and manager pending task through the existing API, and replays the seed idempotently.

It does **not** approve the manager task, complete finance review or countersign, contact a payment sandbox, verify attachment binding, prove client consistency, or seed a shared environment.

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

`DETERMINISTIC_DEMO_SEED_NOT_APPLIED` is deliberately retained in this command because the command is read-only. Runtime seed availability is a separate claim:

```text
DETERMINISTIC_DEMO_SEED_IMPLEMENTED
BACKEND_LOCAL_START_VERIFIED
```

The boundary check is:

```bash
pnpm demo:seed:check
```

It verifies that the runner is `local`-profile-only, explicit, default-off, resource-backed, free of Demo REST endpoints, and contains no direct SQL or Flowable-table mutation.

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
| `demo-admin` | publishes and inspects the scenario |
| `demo-employee` | submits the request |
| `demo-manager` | initiator manager approval |
| `demo-finance-reviewer` | high-value finance review |
| `demo-finance-approver-a` | parallel finance countersign |
| `demo-finance-approver-b` | parallel finance countersign |

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

The amount exercises the repository's `10000.00` high-value threshold and preserves the expected future sequence:

```text
managerApproval
-> financeReview
-> financeCountersign (ALL: two approvers)
-> completed
```

The verified runtime slice currently stops after `managerApproval` is created and assigned to `demo-manager`.

Attachments are uploaded through `ApprovalAttachmentService` before the request starts. The current application layer does not expose an instance-binding command, so this slice records them as unbound and does not call `ApprovalAttachmentStore.bindToInstance` directly. Binding and participant-readable download evidence remain part of the future full E2E slice.

## Runtime implementation

The switch is available only with the `local` profile and is false by default:

```text
APPROVAL_DEMO_PURCHASE_PAYMENT_ENABLED=false
```

When explicitly enabled, startup:

1. loads and validates both governed JSON inputs;
2. provides the read-only demo organization connector;
3. publishes the purchase-payment definition through the existing `PurchasePaymentApplicationService`;
4. uploads both fixed attachments through `ApprovalAttachmentService`;
5. starts the request through a local-only `PurchasePaymentApplicationService` instance using the service's existing legacy effective-release bridge;
6. verifies one running instance and exactly one `managerApproval` task for `demo-manager`;
7. records bounded startup evidence and exposes it through the `purchasePaymentDemoSeed` health component;
8. fails application startup if any seed step fails.

The production controller bean is not replaced. No Demo controller, table writer, security exception or automatic production activation is added.

## Run locally

Start the repository PostgreSQL service and load the normal local variables as described in [`QUICK_START.md`](QUICK_START.md). Then run:

```bash
APPROVAL_DEMO_PURCHASE_PAYMENT_ENABLED=true \
mvn -B -ntp -f apps/server/pom.xml spring-boot:run \
  -Dspring-boot.run.profiles=local
```

Successful startup logs a bounded marker:

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

Repeated startup against the same database uses existing idempotency and business-key authorities. It must return the same platform instance, task and attachment identities or fail closed.

## Permanent runtime verification

`PurchasePaymentDemoSeedIntegrationTest` is part of the existing Maven reactor and the repository's single permanent validation workflow. It uses:

- PostgreSQL 16 Testcontainer;
- `@SpringBootTest` with a real random HTTP port;
- explicit `local` profile and seed switch;
- `/actuator/health`;
- existing instance and pending-task endpoints;
- a second `PurchasePaymentDemoSeeder.apply()` call to prove idempotent replay.

This is sufficient for:

```text
DETERMINISTIC_DEMO_SEED_IMPLEMENTED
BACKEND_LOCAL_START_VERIFIED
```

It is not sufficient for:

```text
QUICK_START_10_MINUTES_PASSED
PURCHASE_APPROVAL_E2E_PASSED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_PASSED
PRODUCTION_PAYMENT_INTEGRATION_VERIFIED
```

## Existing API bindings

The scenario continues to use the existing `/api/approval` mappings:

```text
POST /definitions/purchase-payment/publish
POST /instances/purchase-payment
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

Approval actions remain the responsibility of the existing authorized task endpoints.

## Cross-client evidence identity

PC, H5 and WeChat evidence must eventually refer to the same values:

```text
tenantId
businessKey
instanceId
taskIds
auditEventIds
finalStatus
```

The current client status remains `NOT_YET_EXECUTED`. Three separately mocked client states do not satisfy this contract.

## Current non-claims

```text
SHARED_DEMO_ENVIRONMENT_SEED_NOT_APPLIED
PURCHASE_APPROVAL_E2E_NOT_EXECUTED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
```
