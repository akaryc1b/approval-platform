# Purchase-Payment Golden Path Contract

```text
PURCHASE_PAYMENT_SCENARIO_CONTRACT_STATUS=AVAILABLE
DETERMINISTIC_DEMO_SEED_STATUS=NOT_YET_APPLIED
PURCHASE_APPROVAL_E2E_STATUS=NOT_YET_EXECUTED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_STATUS=NOT_YET_EXECUTED
PRODUCTION_PAYMENT_INTEGRATION_STATUS=NOT_VERIFIED
```

This document defines the first executable contract for the Golden Path delivery area in Issue #107. It binds a deterministic high-value purchase-payment scenario to the repository's existing `PurchasePaymentController` and `PurchasePaymentTemplate` boundaries.

It does not load a database, start the application, execute a task, contact a payment sandbox, or prove client consistency. Those outcomes require later implementation and retained runtime evidence.

## Run the contract check

From the repository root:

```bash
pnpm demo:scenario:check
```

Machine-readable output:

```bash
node scripts/product-readiness/purchase-payment-scenario-contract.mjs --json
```

A successful check emits:

```text
PURCHASE_PAYMENT_SCENARIO_CONTRACT_PASSED
DETERMINISTIC_DEMO_SEED_NOT_APPLIED
PURCHASE_APPROVAL_E2E_NOT_EXECUTED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
```

The validator is read-only. It verifies the manifest shape, deterministic identity resolution, the high-value route, request limits, API mappings, controller headers, client evidence keys, sandbox non-production state, and exact non-claim vocabulary.

## Governed manifest

The source is:

```text
config/demo/purchase-payment-golden-path.json
```

The validator canonicalizes the JSON object and reports a SHA-256 digest so later seed, runtime and client evidence can identify the exact scenario contract without hard-coding a moving Git commit in this living document.

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

The directory contract resolves:

```text
demo-employee.managerId -> demo-manager
FINANCE_REVIEWER        -> demo-finance-reviewer
FINANCE_APPROVER        -> demo-finance-approver-a, demo-finance-approver-b
```

These are data-contract identifiers, not credentials. No password, token, Secret, production user, or authorization bypass is introduced.

## Purchase request

The deterministic request is intentionally above the repository's `10000.00` high-value threshold:

```text
businessKey:            DEMO-PP-0001
amount:                 12500.00
supplier:               Demo Industrial Supplies Ltd.
purchaseOrderReference: PO-DEMO-2026-0001
attachments:            2 metadata identifiers
```

This forces the governed sequence:

```text
managerApproval
-> financeReview
-> financeCountersign (ALL: two approvers)
-> completed
```

A future seed implementation must preserve this business identity or explicitly version the manifest.

## Existing API bindings

The contract binds the existing `/api/approval` controller mappings:

```text
POST /definitions/purchase-payment/publish
POST /instances/purchase-payment
GET  /tasks/pending
POST /tasks/{taskId}/approve
GET  /instances/{instanceId}
GET  /instances/{instanceId}/timeline
```

Every mutating call retains the current request-context headers:

```text
X-Tenant-Id
X-Operator-Id
X-Request-Id
Idempotency-Key
X-Trace-Id
```

The manifest does not add a demo-only REST controller or weaken tenant, operator, idempotency, trace, task-assignee, management-permission or audit boundaries.

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

## Next implementation slice

The next code slice should consume this manifest to provide a local-only, repeatable seed path that:

1. creates or verifies the demo tenant and directory identities without a database bypass;
2. publishes an effective purchase-payment release through existing application authority;
3. creates attachment metadata and the deterministic request;
4. starts the real executable backend and returns the instance/business identifiers;
5. leaves every approval action to the existing authorized task endpoints;
6. can be reset without editing platform or Flowable tables directly.

Only after a real run may the project consider narrower runtime markers such as backend start or purchase-approval E2E. A sandbox result requires a separately identified external sandbox and signed, idempotent callback evidence.

## Permanent non-claims

```text
DETERMINISTIC_DEMO_SEED_NOT_APPLIED
PURCHASE_APPROVAL_E2E_NOT_EXECUTED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
```
