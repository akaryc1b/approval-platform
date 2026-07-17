# Purchase Payment Approval

This directory contains the first versioned process and form fixtures used by the end-to-end approval vertical slice.

## Routing

```text
Start
  -> Initiator manager approval
  -> Amount condition
       amount >= 10000 -> High-value finance approval
       otherwise       -> skip finance review
  -> Parallel finance countersign (all approvers)
  -> Completed
```

## Runtime variables

```text
amount               BigDecimal-compatible number
managerAssignee      resolved manager user ID
financeReviewer      resolved high-value finance reviewer user ID
financeApprovers     non-empty list of finance countersigner user IDs
```

Assignee resolution happens before the engine is started. Runtime instances bind the resolved identities as a snapshot so later organization changes do not silently alter the running approval.

## Versioning

- Approval DSL version: `1`
- Form Schema version: `1`
- Schema version: `1.0`
- Compiler version: recorded in the compiled artifact
- BPMN resource: `purchase-payment-v1.bpmn20.xml`

Published versions are immutable. Changes require a new process or form version.

## Security

The DSL does not accept arbitrary expression language. Conditions are typed comparisons and assignee variables must pass safe-identifier validation before BPMN generation.
