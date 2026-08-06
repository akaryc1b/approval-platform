# M6-F P6-F — Composite Governance and Incident Readiness Acceptance

## 1. Scope

P6-F adds one tenant-scoped, GET-only incident-readiness projection that composes the already accepted P6-A through P6-E evidence surfaces:

- P6-A exact Provider/model/Prompt/policy inventory and frozen runtime profile;
- P6-B non-executable rollback review;
- P6-C shared-runtime control health;
- P6-D process-local tenant usage;
- P6-E V49-backed durable history.

P6-F does not add a new control, mutation, Provider call, notification, worker, scheduler, migration or execution path.

## 2. Endpoint

```text
GET /api/approval/management/ai-governance/incident-readiness
    ?from=<canonical UTC Instant>
    &to=<canonical UTC Instant>
```

The endpoint requires:

- canonical trusted `X-Tenant-Id`;
- tenant-scoped management `READ`;
- canonical UTC `Instant` query values;
- `Cache-Control: no-store`.

The history window remains governed by the P6-E limits:

- positive `[from, to)` interval;
- no more than 31 days;
- no more than 3,650 days of lookback;
- `to` cannot exceed the exact P6-A snapshot observation time.

## 3. Single-snapshot composition

The composition root reads exactly one P6-A `OperationsView` and derives all component evidence from that snapshot:

1. P6-C Control Health uses the same snapshot hash;
2. P6-D Usage uses the same snapshot hash and tenant;
3. P6-E History uses the snapshot observation time;
4. P6-B Rollback Review uses the same snapshot hash.

Construction fails closed if any component reports a different snapshot hash, runtime state or required observation time.

P6-F does not call `bind(...)` and therefore does not create a Provider binding or Secret source.

## 4. Readiness states

The closed readiness states are:

- `RUNTIME_NOT_CONFIGURED`;
- `OBSERVATION_READY_ADVISORY_ONLY`;
- `ACTION_REQUIRED`;
- `INCIDENT_BLOCKED`.

`OBSERVATION_READY_ADVISORY_ONLY` does not authorize production mutation or controlled automation. It only means the current read-only evidence does not contain a critical incident signal.

## 5. Incident signals

P6-F recognizes only the following closed signals:

- runtime not configured;
- runtime profile drift;
- Kill Switch admission disabled;
- cost policy not current;
- Secret version not current;
- Circuit Breaker OPEN;
- Circuit Breaker HALF_OPEN;
- tenant rate window saturated;
- global rate window saturated;
- durable history empty;
- durable history version drift;
- retention tombstone due.

Critical control or rate signals result in `INCIDENT_BLOCKED`.

Kill Switch admission disabled, durable version drift or retention action due result in at least `ACTION_REQUIRED` when no more severe signal exists.

An empty durable-history window is recorded as an informational signal and does not by itself manufacture an incident.

## 6. Manual incident response only

P6-F returns a closed list of manual operator step codes, including:

- verify the read-only governance snapshot;
- verify control health;
- verify tenant usage;
- review durable history;
- do not automatically retry;
- review the non-executable rollback plan when required;
- escalate to the release owner when required;
- review version history or retention tombstones when required;
- continue read-only monitoring only when the posture permits it.

The exact P6-B rollback operator steps are also retained:

- disabled Runtime: no action required;
- configured Runtime: disable the existing runtime flag, redeploy through the established release process and verify the read-only governance snapshot.

These are review/runbook codes. No API applies them.

## 7. Evidence model

The incident-readiness evidence hash length-frames and binds:

- view version and observation/window times;
- Runtime, readiness, control, usage, history and rollback posture;
- P6-A Snapshot hash;
- P6-C Control Health hash;
- P6-D Usage hash;
- P6-E History hash;
- P6-B Rollback Plan hash;
- all incident signals;
- all manual operator and rollback step codes;
- all blocker codes;
- Action Whitelist and P5 decision.

The response does not copy individual V49 Evidence identifiers or hashes, raw Provider input/output, Prompt text or Secret material.

## 8. Permanent authority boundary

P6-F fixes the following values:

- durable evidence available: `true`;
- usage is process-local only: `true`;
- incident mutation available: `false`;
- Provider invocation available: `false`;
- rollback execution available: `false`;
- command execution authorized: `false`;
- automatic retry authorized: `false`;
- notification automation available: `false`;
- raw Secret exposed: `false`.

The Action Whitelist remains:

```text
EMPTY_PENDING_EXISTING_COMMAND_AUDIT
```

P5-A remains:

```text
P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND
```

`AI_IS_NOT_AN_OPERATOR` remains unchanged.

## 9. Honest limitations

P6-F does not add:

- durable control-health or Circuit Breaker time-series;
- actual Provider token/cost reconciliation;
- durable P6-D cost-upper-bound history;
- automated retention tombstoning;
- automatic incident notification or escalation;
- an executable rollback or release action;
- production reauthentication;
- any controlled-automation command.

## 10. Repository boundary

P6-F adds no Flyway migration. The highest migration remains `V50`.

P6-F adds no workflow. The only automatic PR/main workflow remains:

```text
.github/workflows/approval-platform-validation.yml
```

P6-F adds no PC or Mobile mutation surface.

## 11. Validation requirements

The permanent workflow must prove:

- Core readiness derivation and fail-closed source mismatch behavior;
- Server Controller canonical input, tenant READ and GET-only behavior;
- composition-root single-snapshot behavior;
- architecture prohibition of Provider Binding, Provider invocation, commands, mutation, scheduling and notification automation;
- complete Maven, PostgreSQL, Web, Mobile and repository-hygiene regression;
- all four permanent artifacts independently match GitHub size and SHA-256 metadata.

## 12. Stop boundary

Completion of P6-F does not authorize P7, P8, Ready, auto-merge, merge or Issue closure.
