# Purchase Payment Approval

This directory contains the versioned process, form and request fixtures used by the end-to-end approval vertical slice.

## Routing

```text
Start
  -> Initiator manager approval
  -> Amount condition
       amount >= 10000 -> High-value finance approval
       otherwise       -> skip finance review
  -> Parallel finance countersign (all approvers)
  -> Completed
  -> Signed business callback through the transactional Outbox

Any approval task may reject
  -> Initiator revision
  -> Resubmit
  -> Initiator manager approval
```

A rejection is not a terminal failure. It creates one pending `initiatorRevision` task assigned to the original initiator. Resubmission restarts the approval route from the manager step. In parallel countersign, the first rejection satisfies the multi-instance completion condition and Flowable cancels the remaining countersign tasks before creating the revision task.

## Dynamic assignee rules

The public start API does not accept the final approver IDs. It accepts organization rules:

```text
connectorKey
initiator external ID
finance reviewer role code
finance countersigner position code
maximum finance countersigner count
```

Before Flowable starts, Approval Platform:

1. verifies the initiator external ID matches the authenticated operator;
2. loads the active initiator through the configured organization connector;
3. resolves the first active manager;
4. requires the finance reviewer role to resolve exactly one active user;
5. resolves active finance-position members;
6. de-duplicates and sorts the countersigners by canonical external ID;
7. rejects an empty or oversized countersign group;
8. persists the complete immutable user identity snapshots.

See `start-request.json` for a copy-ready request body.

## Runtime variables

```text
amount               BigDecimal-compatible number
initiatorAssignee    authenticated initiator user ID
managerAssignee      resolved manager user ID
financeReviewer      resolved high-value finance reviewer user ID
financeApprovers     non-empty list of finance countersigner user IDs
approvalDecision     PENDING, APPROVED, REJECTED or RESUBMITTED
```

Runtime instances bind the resolved identities as a snapshot so later organization changes do not silently alter the running approval. The snapshot retains external ID, username, display name, email, mobile, departments, roles, positions and connector attributes.

## Task actions

Rejecting requires a non-empty reason and is idempotent:

```http
POST /api/approval/tasks/{taskId}/reject
```

Only the original initiator may resubmit the generated revision task:

```http
POST /api/approval/tasks/{taskId}/resubmit
```

Approving, rejecting and resubmitting use separate idempotency operation namespaces. A revision task cannot be processed through the normal approve or reject actions.

The current M1 resubmit action restarts the existing request and immutable assignee snapshot. Editing form values during revision is planned with the dynamic Form Schema renderer and is not silently simulated by this API.

## Completion callback

Only a completed instance creates a `purchase-payment.completed.v1` Outbox event. Intermediate approvals, rejection and resubmission do not create a completion event. Replaying the final approval command returns its persisted result and the Outbox unique key prevents duplicate completion events.

The existing Generic REST dispatcher signs and delivers the callback asynchronously. Its payload includes:

- instance and business keys;
- completed status;
- amount, supplier, purchase-order reference and attachments;
- initiator and assignee snapshots;
- process, form and compiler versions;
- compiled artifact content hash.

## Versioning

- Approval DSL version: `2`
- Form Schema version: `1`
- Schema version: `1.0`
- Compiler version: `1.1.0`
- BPMN resource: `purchase-payment-v2.bpmn20.xml`

Published versions are immutable. Version 1 instances remain reproducible and approvable, but reject-to-initiator is available only to version 2 instances.

## Security

The DSL does not accept arbitrary expression language. Conditions are typed comparisons, assignee variables must pass safe-identifier validation, and process cycles are rejected unless the loop explicitly contains a HANDLE revision node.

Connector secrets are loaded from server configuration, are never accepted in the start request and are used by the existing HMAC-signed Generic REST organization and callback transports.
