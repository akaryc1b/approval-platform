# M5-F1 — Fault, Security and Observability Acceptance Foundation

## Governance result

- M5-F1 implementation: `COMPLETE / PERMANENTLY_VALIDATED`
- Scope: bounded fault, security and observability acceptance foundation only
- M5-E2 executable Operations commands: `NOT_IMPLEMENTED / BLOCKED`
- M5-F2 production-grade fault exercise: `NOT_IMPLEMENTED / BLOCKED`
- M5-G merge readiness: `NOT_IMPLEMENTED / BLOCKED`
- Production migration execution: `NOT_AUTHORIZED`

PR #58 remains Open + Draft. Issues #13, #14 and #56 remain Open. No M6 branch or PR was modified.

## Validated implementation head

- Head: `0d22ac99af6c262d9bd9d1d6c82e21759582cda6`
- Workflow: `Approval Platform Validation`
- Run ID: `30321385842`
- Run number: `#797`
- Result: `success`
- Repository hygiene: success
- Java 21 / Maven / PostgreSQL: success
- Vben TypeScript / production build: success
- UniApp TypeScript / H5 / WeChat: success

## Fault acceptance boundary

F1 freezes the existing governed fault semantics rather than adding another execution mechanism:

- one D3 engine request remains outside every platform transaction;
- ambiguous engine exceptions and unexpected runtime failures become durable `AMBIGUOUS_UNKNOWN`;
- the original request/outcome lineage is preserved;
- `UNKNOWN` never regains automatic dispatch authority;
- stale ownership, audit or evidence finalization failure propagates and cannot trigger a second outcome write;
- execution and worker one-shot runners fail closed unless both are explicitly enabled;
- no retry loop, scheduler, resident worker, cross-tenant scan or background migration command was added.

These assertions reuse the permanent D3-D8 tests and are additionally frozen by the F1 Node boundary.

## Security acceptance boundary

The E1 Operations surface remains strictly read-only:

- exactly four tenant-scoped `GET` handlers;
- management and Mobile use separate explicit read prefixes;
- dedicated `MIGRATION_OPERATIONS_READ` capability with tenant resource scope;
- no request body and no `POST`, `PUT`, `PATCH` or `DELETE` mapping;
- no execute, retry, force-success, rollback, cancel, reconcile or Kill Switch mutation route;
- no client-supplied trusted permission authority;
- no direct Flowable `ACT_*` access;
- no JDBC mutation in the Operations query;
- no raw payload JSON, operation-reason text, credentials, tokens or Flowable internal data in the read model.

Security failures remain server-derived. The observability layer classifies HTTP failures into a closed vocabulary and never turns client identity or resource identity into metric tags.

## Structured Operations observability

`ApprovalMigrationOperationsObservabilityAdvice` adds bounded response evidence and one low-cardinality counter:

```text
approval.migration.operations.read
```

Closed tags:

- `operation`: `summary`, `plan_list`, `plan_detail`, `instance_list`;
- `result`: `success`, `failure`;
- `failure_class`: `none`, `invalid_request`, `unauthenticated`, `forbidden`, `not_found`, `conflict`, `rate_limited`, `internal`.

The counter never uses tenant, operator, definition, plan, intent, Attempt, instance, request, trace or reason identity tags.

Known Operations errors are normalized into bounded structured evidence:

- stable error code;
- sanitized message limited to 512 Unicode code points;
- non-retryable read result;
- request ID and trace ID limited to 128 Unicode code points and resolved from server MDC first;
- server clock timestamp;
- closed `failureClass` detail;
- `X-Request-Id` and `X-Trace-Id` response headers.

Unknown security response bodies are counted but are not rewritten into authoritative application evidence.

## Default-disabled safety gauges

`ApprovalMigrationSafetyMetricsConfiguration` exposes one binary gauge family:

```text
approval.migration.safety.feature.enabled
```

The only tag is the closed `feature` value:

- `execution`;
- `worker`;
- `orchestration`;
- `aggregation`;
- `automatic_reconciliation`;
- `kill_switch`.

Each gauge is exactly `0` or `1`. The base configuration keeps all six values `false`. The gauges report configuration state only; they do not authorize execution and contain no resource identities.

## Tests

Maven aggregate:

- tests: `680`;
- failures: `0`;
- errors: `0`;
- skipped: `0`.

Focused F1 Java tests:

- `ApprovalMigrationOperationsObservabilityAdviceTest`: `3 / 3`;
- `ApprovalMigrationSafetyMetricsConfigurationTest`: `2 / 2`;
- focused total: `5 / 5`.

Focused F1 permanent Node boundary:

- default-disabled feature authority: pass;
- durable `UNKNOWN` and no-second-write semantics: pass;
- structured low-cardinality Operations observability: pass;
- read-only and redacted security boundary: pass;
- no F2, production, V49 or second-workflow expansion: pass;
- focused total: `5 / 5`.

All M5 Node groups:

- tests: `96`;
- pass: `96`;
- fail: `0`.

The full PostgreSQL, domain, application, Flowable adapter, Server, Web and Mobile regression suites also completed successfully.

## Artifact integrity

Every downloaded ZIP SHA-256 exactly matched the GitHub artifact digest.

| Artifact | ID | GitHub digest / downloaded ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30321385842` | `8674255893` | `2d0c90ac8c7a341db531caf354d33b76aabe7e44a8b0a84d2fa534a27520ab8d` — exact match |
| `approval-vben-30321385842` | `8674149654` | `8a1cc14688c51a63062df9240c8a68dff536a3f88b7dd0d2be9643b60292f728` — exact match |
| `approval-mobile-30321385842` | `8674134447` | `d5844671877768567688fd5377d3d0da5eac1c7a1230dde3d1d5e0fa514289c8` — exact match |
| `approval-hygiene-30321385842` | `8674125086` | `620a5c175ce991d61792118f44aba71ccd6d0581f7ad48d7bfc747c0dd5356c7` — exact match |

## Repository and migration ownership

- Flyway remains continuous through `V48`;
- F1 adds no Flyway migration and claims no `V49`;
- V1 through V47 remain unchanged;
- exactly one automatic PR/main workflow remains;
- no M6 file, branch or PR was modified;
- no production execution configuration was enabled.

## Retained validation lineage

All failed and concurrency-cancelled development Runs remain retained. None was rerun, hidden, deleted or treated as successful evidence. Run #797 is the first F1 implementation Head with all four permanent jobs successful.

## Explicitly blocked

F1 does not provide or authorize:

- executable Operations commands;
- migration execute, retry, force, rollback, cancel or reconcile endpoints;
- browser or Mobile execution controls;
- automatic retry or second dispatch of `UNKNOWN`;
- production fault injection or destructive chaos exercise;
- production migration execution;
- V49 or M6 Flyway ownership;
- M5-G merge readiness;
- Ready-for-review, auto-merge, merge or issue closure.

## Stop condition

M5-D1 through D8, M5-E1 and M5-F1 have reached their permanent validation gates. M5 remains `IN_PROGRESS` because M5-E2, M5-F2 and M5-G remain blocked.

This evidence does not authorize further executable or production work. The final documented Head must pass the same four permanent workflow jobs before PR and Issue status are updated.
