# M5-G1 Release Rehearsal and Production Readiness

## Governance result

- G1 status: `REHEARSAL_CANDIDATE / PERMANENT_VALIDATION_REQUIRED`
- Release rehearsal environment: local/CI isolated PostgreSQL and embedded test Flowable only
- Real production migration: `NOT_PERFORMED`
- Production migration execution: `NOT_AUTHORIZED`
- Production credentials/endpoints: `ABSENT`
- Production alerting connection: `NOT_CONFIGURED`

This runbook prepares an operator-reviewed release in which all executable migration capabilities remain disabled. It does not authorize a production canary, worker, orchestration run, aggregation run, reconciliation run or instance migration.

## 1. Release preconditions

Before deploying an M5 build, the release owner must verify all of the following:

1. the artifact was built from the exact accepted Git commit and the commit is recorded;
2. the unique permanent GitHub Actions workflow succeeded for that commit;
3. all artifact GitHub digests match independently downloaded ZIP SHA-256 values;
4. PR #58 has no unresolved requested changes or review threads;
5. the target `main` SHA is rechecked immediately before merge/deployment;
6. Flyway migration files are continuous through V48 and no V49 exists;
7. fresh-database and historical-upgrade tests reach V48 without execution side effects;
8. no production credential, production Flowable endpoint, production database endpoint or production message endpoint exists in the repository;
9. all migration execution feature flags use their safe defaults;
10. tenant authentication and `MIGRATION_OPERATIONS_READ` authorization tests are green;
11. E1/E2 Operations APIs remain GET-only, bounded, redacted and tenant-scoped;
12. Web and Mobile management views remain read-only and command-free;
13. fault-injection, security-negative and observability-cardinality suites are green;
14. UNKNOWN remains durable and automatic retry remains absent;
15. direct Flowable `ACT_*` access scans are green;
16. there is exactly one automatic PR/main workflow;
17. rollback, stop-the-line and incident contacts have been reviewed by named humans outside this repository;
18. production execution remains explicitly unauthorized in the release record.

Failure of any precondition is a stop-the-line condition.

## 2. Database migration checks

### 2.1 Required checks

- Run Flyway validation on a clean PostgreSQL database and confirm current version `48`.
- Run historical upgrade cases from V1, V13, V23, V31 and every M5-owned migration boundary V36–V47.
- Run the V27 heavy upgrade fixture containing 5,000 instances/tasks.
- Confirm platform projection evidence is unchanged by upgrade.
- Confirm migration plan consumption, intent, engine request/outcome, verification, completion, reconciliation, orchestration and aggregation tables remain empty after schema-only upgrade.
- Confirm repeated application startup does not add migration rows or repeat a migration command.
- Confirm there is no file or Java migration whose version is V49 or higher.

### 2.2 Forbidden database actions

- Do not point rehearsal configuration at a production database.
- Do not seed production process-instance data.
- Do not query or modify Flowable `ACT_*` tables.
- Do not use manual SQL to fabricate plan, Attempt, outcome, verification, reconciliation, binding or completion evidence.
- Do not downgrade or repair Flyway history to conceal a failure.

## 3. Safe configuration baseline

The base configuration must resolve to:

```text
approval.migration.execution.enabled=false
approval.migration.worker.enabled=false
approval.migration.orchestration.enabled=false
approval.migration.aggregation.enabled=false
approval.migration.reconciliation.automatic.enabled=false
approval.migration.kill-switch.enabled=false
```

The Kill Switch configuration value is an observable state, not an execution capability. Before any future separately authorized production exercise, an operator must verify the expected switch revision and choose the safe switch state under a distinct approval. G1 itself does not change the switch.

### 3.1 Startup verification

After starting the application in the isolated environment:

- confirm no migration runner is invoked automatically;
- confirm no migration scheduler, polling loop or resident migration worker exists;
- confirm no cross-tenant scanner starts;
- confirm no automatic reconciliation starts;
- confirm no automatic plan aggregation starts;
- confirm no Flowable migration request exists;
- confirm the safety feature gauge reports the configured values only;
- confirm a second startup leaves all M5 execution evidence tables unchanged.

## 4. Canary and bounded-orchestration parameters

Future authorized use must remain bounded by the accepted protocol:

- deterministic Canary algorithm: `CANONICAL_FIRST_V1`;
- Canary claim count: exactly `1`;
- bounded orchestration request limit: `1–100`;
- one-shot invocation only;
- exact expected orchestration revision required;
- exact expected Kill Switch revision required;
- dispatch rechecks the Kill Switch before every Attempt;
- the first non-exact result stops the current bounded loop;
- UNKNOWN, reconciliation, binding conflict, stale authority and terminal failure prevent wider dispatch;
- no scheduler or automatic next batch exists.

G1 validates these boundaries through tests and documentation only. It does not invoke a Canary or orchestration run against production or real production data.

## 5. Read-only Operations verification

### 5.1 API

Verify both prefixes:

```text
/api/approval/management/process-instance-operations
/api/approval/mobile/process-instance-operations
```

Required checks:

- unauthenticated calls fail with bounded authentication errors;
- authenticated calls without the dedicated permission fail with bounded forbidden errors;
- cross-tenant plan/instance enumeration does not reveal resource existence;
- only GET mappings exist;
- page/page-size, sort, status, failure-class, reconciliation-state and time-range inputs remain closed and bounded;
- duplicate, unknown, polluted and overlong parameters are rejected;
- responses are `no-store` and contain no SQL, stack, raw owner identity, token, Cookie or request body;
- API reads do not call Flowable, worker, reconciliation or mutation services.

### 5.2 Web and Mobile

Verify:

- the Web page renders summary, plan diagnostics, UNKNOWN instances, reconciliation evidence and lifecycle timeline;
- the Mobile page uses cards suitable for small screens rather than a compressed desktop table;
- loading, empty, error and no-permission states do not expose sensitive data;
- neither client stores diagnostic results in local/session/Mobile storage;
- neither client contains execute, retry, rollback, force-success, reconciliation-start, Kill Switch mutation, feature-flag mutation or bulk-operation controls;
- both clients use governed GET transports only.

## 6. Observability and redaction verification

Confirm the metric catalog and runtime meters contain only:

- `approval.migration.operations.read` with closed `operation`, `result`, `failure_class` labels;
- `approval.migration.operations.read.latency` with the same closed labels;
- `approval.migration.safety.event` with one closed `event` label;
- `approval.migration.safety.feature.enabled` with one closed `feature` label.

Confirm tenant, operator, definition, release, plan, intent, Attempt, instance, engine, request, trace, owner, message, exception and free text are absent from metric labels.

Logs and traces may contain only bounded request/trace correlation evidence. They must not contain Authorization, Cookie, token, credential, complete request body, SQL, stack trace, raw lease/fence owner or user privacy data. If the observability registry/export pipeline fails, migration safety semantics and read API bodies remain unchanged.

## 7. UNKNOWN and reconciliation operator procedure

1. Stop wider execution for the affected plan; do not retry the instance.
2. Read the tenant-scoped E2 plan diagnostic and locate the durable UNKNOWN/AMBIGUOUS_UNKNOWN evidence.
3. Record plan, instance, Attempt and evidence hashes in the incident record; do not copy secrets or raw payloads.
4. Confirm ownership, lease and fencing evidence before any manual action.
5. Use the separately gated one-shot reconciliation procedure only after explicit authorization; G1 grants no such authorization.
6. Reconciliation performs one bounded public readback and does not call the migration port.
7. If source state is confirmed, preserve no-retry evidence.
8. If target state is confirmed, require the governed runtime-binding CAS path.
9. If evidence remains ambiguous, retain `MANUAL_REVIEW_REQUIRED` and escalate.
10. Never force success, delete UNKNOWN, fabricate completion evidence or execute a second migration call.

## 8. Rollback and stop-the-line

### 8.1 Stop-the-line conditions

Stop deployment or disable future authorized dispatch immediately when any of these occurs:

- any execution flag is unexpectedly enabled;
- any startup creates M5 execution evidence;
- Flyway does not validate exactly through V48;
- an unauthorized V49 appears;
- an Operations write mapping or client command appears;
- tenant isolation or redaction test fails;
- UNKNOWN is retried automatically;
- stale ownership/fencing writes an outcome;
- completion exists without exact verification and binding CAS;
- a second automatic workflow exists;
- direct `ACT_*` access appears;
- artifact digest comparison fails;
- production credentials/endpoints are detected;
- permanent CI is not fully successful.

### 8.2 Application rollback

- Stop new application traffic through deployment tooling.
- Preserve the database and all M5 evidence; do not delete migration rows.
- Roll back application binaries only to a schema-compatible, previously accepted release.
- Keep all migration execution flags disabled.
- Verify read-only diagnostics still function against retained evidence.
- Do not run a Flyway downgrade.
- Record the exact rollback artifact SHA-256 and application commit.

### 8.3 Incident escalation

Escalate to the designated release owner, database owner, Flowable owner, security owner and incident commander. Contact identities and channels belong in the deployment environment’s private incident system, not this public repository.

## 9. Post-release observation window

For an ordinary disabled-by-default deployment, operators must observe startup, health, Flyway state, read APIs, authentication failures, 5xx, latency, metric registration and log redaction. No migration execution signal is expected. Any migration request/outcome, UNKNOWN, reconciliation, orchestration or aggregation evidence created without a separately authorized exercise is an incident.

Permanent evidence must retain:

- release commit and `main` SHA;
- workflow Run ID/number and job conclusions;
- artifact IDs/names/digests;
- downloaded ZIP SHA-256 comparison;
- configuration snapshot containing no secret values;
- Flyway version/validation result;
- stop-the-line and rollback decisions;
- operator sign-off stored outside the repository where required.

## 10. Dry-run release rehearsal matrix

The following rehearsal is non-production and performs no real process-instance migration.

| # | Rehearsal case | Verification | Expected result |
| --- | --- | --- | --- |
| 1 | All execution switches use defaults | inspect base configuration and startup context | all executable features are false |
| 2 | Service startup | start isolated application with clean V48 database | no instance migration is created or called |
| 3 | Worker startup boundary | inspect beans and run default-disabled one-shot tests | no resident migration worker starts |
| 4 | Cross-tenant scanner boundary | static/runtime architecture tests | no scanner or tenant enumeration loop exists |
| 5 | Scheduler boundary | scan M5 production sources | no migration scheduler or polling loop exists |
| 6 | Read-only Operations success | authorized tenant-scoped GET | bounded redacted evidence is returned |
| 7 | Unauthorized Operations access | unauthenticated/forbidden GET | bounded 401/403 without resource leak |
| 8 | Kill Switch observability | inspect closed feature gauge and E2 evidence | state is observable; no mutation control exists |
| 9 | Canary boundary | run deterministic service/store unit tests only | exactly one canonical Canary is permitted |
| 10 | Bounded orchestration boundary | run one-shot service/store tests only | accepted limit and first unsafe result stop are enforced |
| 11 | UNKNOWN diagnostics | query seeded isolated platform evidence | UNKNOWN is read-only visible and not retried |
| 12 | Reconciliation boundary | run read-only reconciliation tests only | no migration redispatch exists |
| 13 | Restart safety | restart isolated service twice | no new M5 execution evidence appears |
| 14 | Upgrade to V48 | execute historical Flyway upgrade cases | all reach V48 without execution side effects |
| 15 | Clean database migration | migrate an empty isolated database | V1–V48 validate successfully |
| 16 | Repeated startup/migration | repeat startup after V48 | no migration command or duplicate evidence is created |
| 17 | Rollback/stop procedure tabletop | walk the documented steps without changing production | operators can preserve evidence and stop safely |
| 18 | Observability outage | run throwing-registry/telemetry tests | safety state and API result remain unchanged |

## 11. Production readiness checklist

This checklist establishes readiness evidence only; it does not grant production execution authority.

- [ ] `code complete` — M5-D1–D8, E1/E2 and F1/F2 accepted.
- [ ] `tests complete` — focused, Maven, Node, Web and Mobile suites successful.
- [ ] `security complete` — 24-case negative matrix successful.
- [ ] `observability complete` — catalog, cardinality and outage tests successful.
- [ ] `documentation complete` — protocols, runbook, matrices and evidence indexed.
- [ ] `migration continuity complete` — Flyway exactly V1–V48; no V49.
- [ ] `configuration defaults safe` — all executable migration flags false.
- [ ] `no production credentials` — repository and artifacts contain none.
- [ ] `no production endpoints` — no production Flowable/database/message endpoint committed.
- [ ] `no auto execution` — startup creates no migration request/outcome.
- [ ] `no auto retry UNKNOWN` — UNKNOWN remains durable and manual-governed.
- [ ] `no cross-tenant access` — tenant isolation tests successful.
- [ ] `no direct ACT_* access` — forbidden-pattern scan successful.
- [ ] `no hidden write API` — E1/E2 remain GET-only.
- [ ] `no extra permanent workflow` — one automatic workflow only.
- [ ] `Web read-only` — no execution controls or persistent sensitive storage.
- [ ] `Mobile read-only` — no execution controls or persistent sensitive storage.
- [ ] `PR evidence complete` — base/head/reviews/threads/status documented.
- [ ] `permanent Run success` — all four permanent jobs successful.
- [ ] `artifacts retained` — all expected artifacts exist and are unexpired.
- [ ] `artifact SHA-256 verified` — downloaded ZIPs match GitHub digests.
- [ ] `rollback and kill switch runbook complete` — stop, preserve and rollback steps reviewed.
- [ ] `production execution NOT_AUTHORIZED` — explicit final statement retained.

## 12. Operator scenarios

| # | Scenario | Operator response | Safety outcome |
| --- | --- | --- | --- |
| 1 | Normal read-only diagnosis | authenticate, select tenant-owned plan, inspect E2 evidence | no state mutation |
| 2 | Canary reaches limit | confirm exactly one canonical instance and stop before wider execution | bounded Canary preserved |
| 3 | Kill Switch enabled | confirm new dispatch is blocked and evidence remains readable | no new execution |
| 4 | UNKNOWN appears | stop wider work, inspect durable evidence, never retry | UNKNOWN remains durable |
| 5 | Reconciliation finds target after lost response | require exact verification and governed binding CAS authorization | no second migration call |
| 6 | Reconciliation remains ambiguous | retain manual review and escalate | no forced outcome |
| 7 | Stale worker submits result | reject by ownership/lease/fence and inspect prevention event | no second outcome |
| 8 | Runtime-binding CAS conflicts | preserve conflict evidence and route to governance review | no binding overwrite |
| 9 | Plan aggregation pauses/stalls | inspect one-shot aggregate evidence and configuration; do not execute instances | aggregation remains non-executable |
| 10 | Tenant admin requests another tenant’s data | return non-leaking not-found/forbidden response | tenant isolation preserved |
| 11 | User lacks Operations permission | return bounded forbidden response | no evidence disclosure |
| 12 | Observability system is unavailable | use retained evidence/logs and keep safety controls unchanged | no semantic change |
| 13 | Immediate post-release stop required | stop traffic/new dispatch, keep flags disabled, preserve database/evidence | safe stop |
| 14 | Application rollback while retaining evidence | deploy schema-compatible prior binary; do not downgrade/delete evidence | evidence preserved |

## 13. G1 acceptance decision

G1 may be marked complete only after:

- this document is present on the M5 branch;
- the permanent G1 boundary test validates all runbook/checklist invariants;
- the unique permanent workflow succeeds for the G1 Head;
- artifacts are retained and digest-verified;
- PR #58 remains Draft until G2 final acceptance;
- production execution remains `NOT_AUTHORIZED`.
