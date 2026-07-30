# M5-F2 Fault, Security and Observability Hardening

## 1. Scope and non-authorization

M5-F2 hardens and verifies the governance boundaries already delivered by M5-D1 through D8 and M5-E1/E2. It does not add a new migration command, scheduler, retry loop, production connection, cross-tenant scanner or production enablement path.

The following remain true:

- production execution is `NOT_AUTHORIZED`;
- execution, worker, orchestration, aggregation and automatic reconciliation are disabled by default;
- UNKNOWN is durable and is never automatically retried;
- reconciliation reads evidence and never redispatches migration;
- Web and Mobile remain read-only;
- Flyway remains V1–V48 and no V49 is introduced;
- Flowable `ACT_*` tables are not queried directly;
- no second permanent automatic workflow is created.

## 2. Fault-injection matrix

The matrix is executed through the existing Java unit/integration suites plus the M5-F2 boundary suite. “Safe expected result” is the required invariant; a green test must not be obtained by deleting or weakening the fault.

| # | Injected fault | Injection boundary | Safe expected result | Executable evidence |
| --- | --- | --- | --- | --- |
| 1 | Failure before Flowable migration call | pre-dispatch validation/authority | engine is not called; terminal or authority rejection is persisted as applicable | `FlowableGovernedSingleInstanceMigrationAdapterTest`; `ApprovalMigrationSingleInstanceExecutorTest#recordsPreparationAuthorityConflictBeforeAnyEngineCall` |
| 2 | Timeout while Flowable call may be in progress | engine port ambiguous exception | one durable UNKNOWN; no automatic second call | `ApprovalMigrationSingleInstanceExecutorTest#persistsDurableUnknownOnceAndNeverRetriesAmbiguousDispatch`; Flowable unknown-outcome capability tests |
| 3 | Engine returns an ambiguous result | engine port | durable UNKNOWN / `AMBIGUOUS_UNKNOWN` evidence | executor and D6 reconciliation tests |
| 4 | Migration may have succeeded but response is lost | engine port after dispatch | UNKNOWN is finalized once and operator reconciliation is required | `ApprovalMigrationSingleInstanceExecutorTest#recordsUnknownOnlyAfterDurableFinalization` |
| 5 | Migration call returns but exact verification fails | D4 verification | attempt moves to governed reconciliation; no runtime binding success | `ApprovalMigrationExactVerificationServiceTest`; `JdbcApprovalMigrationExactVerificationGuardIntegrationTest` |
| 6 | Runtime binding CAS fails | D5 CAS | conflict evidence is durable; binding is not overwritten | `ApprovalMigrationRuntimeBindingCasServiceTest`; `JdbcApprovalMigrationRuntimeBindingCasStoreIntegrationTest` |
| 7 | Ownership is lost during execution | D3 prepare/finalize authority | stale authority is rejected; no second authoritative outcome | executor authority tests; JDBC engine-dispatch guard tests |
| 8 | Lease expires | claim/lease guard | stale worker cannot continue or finalize | `JdbcApprovalMigrationLeaseUnknownIntegrationTest`; claim-store integration tests |
| 9 | Fencing token is stale | command fence | engine dispatch/finalization is rejected | `JdbcApprovalMigrationEngineDispatchGuardIntegrationTest` |
| 10 | Completion evidence persistence fails | D5 completion transaction | no fabricated success; failure propagates and exact completion is absent | runtime-binding CAS service/store tests; pipeline completion-failure telemetry path |
| 11 | Audit persistence fails | transactional evidence write | authoritative state and audit do not partially diverge; failure propagates | JDBC guarded store integration tests; executor finalization-conflict test |
| 12 | Transaction commit result is uncertain | PostgreSQL serialized wrapper | caller receives conflict/unknown authority; no blind duplicate write | PostgreSQL serialized CAS/orchestration/aggregation integration tests |
| 13 | Reconciliation engine read fails | read-only verification port | bounded read-failure observation; manual review, no migration call | `ApprovalMigrationReconciliationServiceTest` |
| 14 | Reconciliation evidence write fails | reconciliation finalization | error propagates; no migration redispatch | `JdbcApprovalMigrationReconciliationExecutionStoreIntegrationTest` |
| 15 | Two workers compete for one instance | claim/fence transaction | one owner wins; loser is stale/conflicted | `JdbcApprovalMigrationProtocolConcurrencyIntegrationTest`; claim-store tests |
| 16 | Kill switch changes at a dispatch boundary | D7 pre-dispatch snapshot | new dispatch is stopped; existing durable evidence remains | `ApprovalMigrationBoundedOrchestrationServiceTest`; orchestration-store tests |
| 17 | Canary limit is reached | D7 deterministic canary | exactly one canonical canary is processed | bounded orchestration service/store tests and `canary_limit_reached` event |
| 18 | Orchestration batch limit is reached | D7 bounded loop | processing stops at accepted limit; no unbounded scan/loop | bounded orchestration tests and `orchestration_bounded_stop` event |
| 19 | Plan aggregation is delayed or not invoked | D8 default-disabled one-shot runner | aggregation cannot trigger instance execution; plan remains diagnosable | `ApprovalMigrationPlanAggregationServiceTest`; D8 boundary tests |
| 20 | Duplicate aggregation request/event | D8 serialized store | idempotent replay or conflict; no duplicate authoritative completion | plan-aggregation store/serialization integration tests |
| 21 | Stale worker returns after losing ownership | D3 finalization | result write is rejected; no second outcome | executor finalization conflict and JDBC engine-dispatch guard tests |
| 22 | Service restarts with durable UNKNOWN rows | V45/V46 persistence | UNKNOWN remains durable and available to read-only reconciliation | lease/unknown, upgrade and reconciliation integration tests |
| 23 | Database is transiently unavailable | any transactional store boundary | store exception propagates; no engine retry and no fabricated success | executor preparation/finalization conflict injections; serialized JDBC tests |
| 24 | Metrics/observability registry is unavailable | counters, timers, safety telemetry | response/state outcome is unchanged; fixed redacted warning only | `ApprovalMigrationOperationsObservabilityAdviceTest#observabilityFailureCannotChangeTheResponseBody`; telemetry filter/adapter/port tests; executor telemetry-outage test |

### 2.1 Core fault invariants

- A call that may have occurred but lacks authoritative completion evidence becomes durable UNKNOWN.
- UNKNOWN does not initiate another migration call.
- Reconciliation performs one read-only observation and never calls the migration port.
- Lost ownership or stale fencing prevents outcome persistence.
- Completion success is impossible without exact verification, binding CAS and completion evidence.
- Observability failure is non-authoritative.

## 3. Security negative-test matrix

| # | Negative case | Required response/boundary | Evidence |
| --- | --- | --- | --- |
| 1 | No authentication | bounded 401 without resource details | management permission/security tests; closed telemetry classification |
| 2 | Authenticated but missing Operations permission | bounded 403 | responsibility and endpoint contract tests |
| 3 | Guessing plan IDs | 404-style not-found semantics without cross-tenant existence leak | diagnostics query/controller tests |
| 4 | Cross-tenant plan or instance query | no data returned; tenant predicate is mandatory | JDBC E1/E2 integration tests |
| 5 | SQL injection in sort/filter | rejected before SQL; sort selected by server switch | E2 parameter and Node boundary tests |
| 6 | Unknown sort field | bounded invalid request | parameter tests |
| 7 | Parameter pollution | unsupported parameter rejected | parameter tests |
| 8 | Repeated query parameter | duplicate rejected | parameter tests |
| 9 | Overlong query value | rejected at 128 code units | parameter tests |
| 10 | Excessive page/pageSize | page and pageSize bounded; maximum pageSize 100 | query-contract tests |
| 11 | Sensitive exception content | stable error body; no stack, SQL or internal type | observability advice and exception-handler tests |
| 12 | Token/cookie/header/privacy leakage in logs | forbidden by static boundary; logs contain fixed messages only | F1/F2 Node boundary tests |
| 13 | High-cardinality metric label injection | identities and free text never become labels | classifier/filter/metric adapter tests |
| 14 | High-cardinality trace attribute injection | request/trace IDs only bounded response/log evidence, not metrics | advice tests and redaction policy |
| 15 | Unsupported HTTP method | no write mapping; framework rejects the method | E1/E2 endpoint contract tests |
| 16 | CSRF against Operations UI/API | GET-only surface has no state-changing action | controller and client boundary tests |
| 17 | Browser/proxy caching of diagnostics | `Cache-Control: no-store, max-age=0`, `Pragma: no-cache`, expired response | telemetry-filter tests |
| 18 | Browser sensitive-state residue | diagnostics remain in component memory; no local/session storage write | Web static boundary test |
| 19 | Mobile sensitive-state persistence | card data remains in page memory; no storage API write | Mobile static boundary test |
| 20 | Calling application services to bypass API auth | migration services are internal beans; no public HTTP command controller exists | forbidden endpoint scan and configuration boundary |
| 21 | Hidden UI button but callable write endpoint | no POST/PUT/PATCH/DELETE Operations mapping exists | endpoint and OpenAPI/static scans |
| 22 | Unexpected actuator/debug exposure | application exposure is restricted to health/info/metrics/prometheus; debug/env/configprops/heapdump/loggers/mappings/shutdown are rejected by boundary scan | application configuration and F2 Node test |
| 23 | Wildcard CORS | no Operations controller wildcard annotation or credentials wildcard | F2 Node scan; deployment CORS remains environment policy |
| 24 | OpenAPI accidentally advertises write capability | Operations controllers contain only `@GetMapping`; clients contain no non-GET method | endpoint and client boundary tests |

### 3.1 Rate limiting boundary

M5 does not add an in-process per-tenant map or scanner because that would create unbounded identity state and inconsistent multi-node enforcement. The deployment gateway remains responsible for request-rate enforcement. The application:

- bounds page size, page number, parameter length and time range;
- classifies any gateway/application 429 as the closed `rate_limited` failure class;
- records count and latency without identity labels;
- defines an environment-configured 429 growth alert;
- never responds to pressure by widening query bounds.

## 4. Data-redaction policy

Operations APIs and diagnostics must not return or log:

- SQL, stack traces or database driver messages;
- Authorization, Cookie, Set-Cookie or token values;
- complete request bodies or unrestricted query strings;
- Flowable internal table rows;
- raw lease/fence owner IDs;
- engine variables or user-entered free text;
- cross-tenant identifiers.

Allowed diagnostic evidence is limited to:

- closed server-owned enums;
- bounded counts and timestamps serialized with explicit offsets/UTC instants;
- UUIDs only for resources already authorized inside the current tenant;
- SHA-256 evidence hashes;
- irreversible `sha256:` short owner references;
- bounded stable error codes and redacted messages;
- bounded request/trace IDs in response headers and structured logs, never metric labels.

## 5. Cardinality policy

1. Every metric label is enumerated in `M5_F2_OBSERVABILITY_METRIC_CATALOG.md`.
2. Dynamic identity labels are prohibited even when hashed.
3. Exception class/message, SQL state and user input are prohibited labels.
4. Repeated identical requests must not increase meter registration count.
5. Safety-event counters are pre-registered from a 12-value enum.
6. Current tenant/plan UNKNOWN counts remain tenant-scoped API fields rather than labelled gauges.
7. No cross-tenant background metric scanner is permitted.

## 6. UNKNOWN handling evidence

- The executor catches only ambiguity at the engine boundary and builds one `AMBIGUOUS_UNKNOWN` finalization.
- `UNKNOWN_ENTERED` telemetry is recorded only after `finalizeOutcome` returns a durable UNKNOWN attempt.
- A telemetry exception is swallowed after the durable state is established and cannot change the outcome.
- Reconciliation is separately gated, one-shot and read-only; its automatic runner remains disabled by default.
- UI/API expose UNKNOWN and reconciliation evidence but contain no retry/reconcile-start command.

## 7. Duplicate-outcome prevention evidence

- Final outcome persistence is guarded by tenant, attempt revision, ownership lease and fencing revision.
- A finalization conflict propagates and records `duplicate_outcome_prevented`; it does not call `finalizeOutcome` again.
- Runtime binding is protected by PostgreSQL serialization plus binding revision CAS.
- Plan aggregation and orchestration are serialized and evidence-hashed.
- No retry annotation, scheduler or loop wraps engine finalization.

## 8. Tenant-isolation evidence

- Both E1 and E2 controllers require `X-Tenant-Id` and `MIGRATION_OPERATIONS_READ` with tenant resource scope.
- Every diagnostics SQL root predicate includes `tenant_id=:tenantId` and joins preserve tenant lineage.
- Cross-tenant plan enumeration returns no result or governed not-found.
- Tenant IDs do not become metric labels.

## 9. Kill-switch safety evidence

- D7 reads an immutable switch snapshot before preparation and again before each dispatch.
- A stale revision or enabled switch prevents new dispatch.
- Existing prepared/finalized evidence remains readable and is not deleted.
- The switch does not rewrite completed evidence.
- `kill_switch_blocked` is an identity-free event; the current configured state remains the closed `approval.migration.safety.feature.enabled{feature="kill_switch"}` gauge.
- Operations views display switch state but contain no switch mutation control.

## 10. Alerting and SLO baseline

`docs/examples/m5-migration-alerting-baseline.yml` defines example-only alerts for:

- UNKNOWN growth;
- manual reconciliation pressure;
- repeated stale ownership;
- duplicate outcome prevention;
- kill-switch state;
- verification mismatch;
- binding CAS failure;
- Operations 5xx, auth failures and rate limiting;
- canary boundary and orchestration stop;
- aggregation stall during an explicitly expected rehearsal;
- observability-pipeline degradation.

All thresholds/windows are environment placeholders. No production threshold, destination, credential or endpoint is committed.

## 11. Known operational limits

- Current UNKNOWN/backlog counts are confirmed through authorized tenant-scoped E2 diagnostics, not a global gauge.
- Rate limiting enforcement belongs to the deployment gateway; the application observes 429 and maintains bounded queries.
- The example alert file is not installed into a real alert manager.
- No production migration, canary, worker, orchestration, aggregation or reconciliation execution is authorized by this work.
