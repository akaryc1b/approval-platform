# Purchase-Payment Demo Contract

```text
PURCHASE_PAYMENT_DEMO_STATUS=CONTRACT_PROVIDED_NOT_RUNTIME_SEEDED
PURCHASE_APPROVAL_E2E_STATUS=NOT_YET_EXECUTED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_STATUS=NOT_YET_EXECUTED
PC_RUNTIME_STATUS=NOT_YET_EXECUTED
H5_RUNTIME_STATUS=NOT_YET_EXECUTED
WECHAT_RUNTIME_STATUS=NOT_YET_EXECUTED
```

This document binds the existing purchase-payment form, process DSL, start request and HTTP API to one deterministic, non-production scenario. It is the repository contract for the next Product Readiness run; it is not evidence that the application has been started or that a user has completed the scenario.

## Verify the repository contract

From the repository root:

```bash
node scripts/product-readiness/verify-purchase-payment-demo.mjs
```

Machine-readable output:

```bash
node scripts/product-readiness/verify-purchase-payment-demo.mjs --json
```

A successful result may be described only as:

```text
PURCHASE_PAYMENT_DEMO_CONTRACT_PASSED
```

It must not be described as `PURCHASE_APPROVAL_E2E_PASSED`, `PURCHASE_TO_PAYMENT_SANDBOX_E2E_PASSED`, cross-client acceptance, or production payment verification.

## Deterministic scenario

The source of truth is [`examples/purchase-payment/demo-manifest.json`](../../examples/purchase-payment/demo-manifest.json).

| Persona | Operator | Purpose |
| --- | --- | --- |
| Demo Requester | `100` | submits the purchase-payment request and handles revision |
| Demo Manager | `200` | approves the initiator-manager task |
| Demo Finance Reviewer | `300` | performs the high-value finance review |
| Demo Finance Approver A | `400` | completes one parallel countersign task |
| Demo Finance Approver B | `401` | completes the other parallel countersign task |
| Demo Administrator | `900` | publishes the definition and inspects evidence |

The deterministic request is the existing `PO-2026-0001` fixture for `25000.00`, so it must execute the high-value branch:

```text
managerApproval
-> financeReview
-> financeCountersign (all)
-> end
```

The organization contract resolves requester `100` to manager `200`, role `finance-reviewer` to user `300`, and position `finance-countersigner` to users `400` and `401`.

## Runtime API sequence

After a future local organization fixture is wired and the backend is running with the explicit local profile, the runtime exercise must use the existing API rather than a demo bypass:

1. administrator publishes `purchase-payment`;
2. requester starts the instance with `start-request.json`;
3. manager reads and approves the pending manager task;
4. finance reviewer reads and approves the high-value review task;
5. both finance approvers complete the parallel countersign tasks;
6. administrator reads the final instance and timeline;
7. evidence verifies exactly one `purchase-payment.completed.v1` Outbox event;
8. PC, H5 and WeChat read the same tenant, instance and final state.

Every mutating request must carry the documented tenant, operator, request and idempotency headers. Dynamic instance and task IDs must come from API responses; they must not be hard-coded in retained evidence.

## Evidence required for the first runtime pass

Retain at least:

```text
COMMIT_SHA=
TENANT_ID=demo-purchase-tenant
BUSINESS_KEY=PO-2026-0001
INSTANCE_ID=
MANAGER_TASK_ID=
FINANCE_REVIEW_TASK_ID=
FINANCE_COUNTERSIGN_TASK_IDS=
OUTBOX_EVENT_ID=
OUTBOX_EVENT_TYPE=purchase-payment.completed.v1
PC_FINAL_STATUS=
H5_FINAL_STATUS=
WECHAT_FINAL_STATUS=
```

The first runtime pass may claim `PURCHASE_APPROVAL_E2E_PASSED` only after the backend, database, identity context, all approval tasks, final instance state, timeline and exactly-once completion event have been asserted against one exact commit.

## Remaining blockers

This slice deliberately does not provide or claim:

- an imported runtime seed or a running local organization Connector fixture;
- a clean-machine 10-minute startup result;
- browser automation, product screenshots or an unedited recording;
- PC, H5 or WeChat runtime execution;
- a payment Sandbox response;
- Connector outage/backlog recovery;
- production credentials, customer data or a production payment integration.

The runtime seed/fixture and the executable approval smoke belong in the same Golden Path delivery area, but only after this contract is accepted. No second automatic Workflow is required.
