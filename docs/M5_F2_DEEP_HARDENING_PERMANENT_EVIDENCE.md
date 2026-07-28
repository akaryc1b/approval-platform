# M5-F2 Deep Fault, Security and Observability Hardening — Permanent Evidence

## Governance result

- M5-F2 implementation: `COMPLETE / PERMANENTLY_VALIDATED`
- Scope: fault injection, security negative testing, redaction, low-cardinality observability and example alert/SLO baselines only
- Production migration execution: `NOT_AUTHORIZED`
- Real production alerting connection: `NOT_CONFIGURED`
- Destructive production chaos exercise: `NOT_PERFORMED`
- PR #58 remains Open + Draft for the later M5-G gates
- Issues #13, #14 and #56 remain Open
- M6 PRs #67–#70 remain independent and were not modified

M5-F2 adds no migration command, scheduler, resident worker, automatic UNKNOWN retry, automatic migration redispatch, cross-tenant scanner, direct Flowable `ACT_*` access, Flyway V49 or second permanent workflow.

## Permanently validated implementation Head

- Head: `cd3e2a3d22bba7c3580da3edc1d3499fc9a7a446`
- Workflow: `Approval Platform Validation`
- Run ID: `30335096759`
- Run number: `#855`
- Run URL: `https://github.com/akaryc1b/approval-platform/actions/runs/30335096759`
- Conclusion: `success`

All permanent jobs succeeded:

- Repository hygiene: success
- Java 21 / Maven / PostgreSQL: success
- Vben TypeScript / production build: success
- UniApp TypeScript / H5 / WeChat: success

## Fault hardening

The accepted fault matrix contains 24 controlled cases and binds each case to executable Java, PostgreSQL or permanent Node evidence. The matrix covers:

- failures before, during and after the Flowable migration call;
- response loss and ambiguous engine outcomes;
- exact-verification mismatch and read failure;
- ownership, lease and fencing loss;
- runtime-binding CAS and completion-evidence failure;
- audit/transaction uncertainty;
- reconciliation read/finalization failure;
- competing workers and stale-worker return;
- Kill Switch, Canary and bounded-orchestration boundaries;
- aggregation delay/replay;
- durable UNKNOWN across restart;
- database and observability outage behavior.

Permanent invariants remain:

- any call that may have occurred without authoritative completion evidence becomes one durable UNKNOWN;
- UNKNOWN never initiates another migration call;
- reconciliation performs one public read-only observation and never redispatches migration;
- a stale owner or fence cannot persist an outcome;
- no success exists without exact verification, runtime-binding CAS and completion evidence;
- observability failure is non-authoritative.

## Security hardening

The accepted security matrix contains 24 negative cases. It verifies:

- authenticated tenant-scoped `MIGRATION_OPERATIONS_READ` authorization;
- bounded unauthenticated/forbidden/not-found behavior without cross-tenant existence leaks;
- strict duplicate-rejecting query decoding;
- page, page-size, string and time-window bounds;
- server-owned sort mapping with no dynamic SQL field injection;
- GET-only E1/E2 surfaces with no request body or write mapping;
- no UI command, hidden write endpoint, wildcard CORS or write-capable OpenAPI surface;
- `no-store` browser/proxy caching policy;
- no browser or Mobile persistent diagnostic storage;
- bounded redacted error responses and logs;
- restricted actuator exposure;
- gateway-owned rate limiting with closed 429 observability.

## Observability hardening

### Read API metrics

`approval.migration.operations.read` and `approval.migration.operations.read.latency` use only:

- `operation`: seven closed E1/E2 operations;
- `result`: `success` or `failure`;
- `failure_class`: nine closed HTTP result classes.

The latency Timer declares an expected 1 ms–30 s range and does not enable application-side percentile histograms.

### Safety-event metrics

`approval.migration.safety.event` pre-registers exactly 12 identity-free event values:

- `unknown_entered`;
- `reconciliation_observation_recorded`;
- `reconciliation_manual_review_required`;
- `canary_limit_reached`;
- `orchestration_bounded_stop`;
- `kill_switch_blocked`;
- `plan_aggregation_completed`;
- `stale_ownership_rejected`;
- `duplicate_outcome_prevented`;
- `verification_mismatch`;
- `runtime_binding_cas_failed`;
- `completion_evidence_failed`.

Safety events are recorded only after the corresponding durable state or as evidence that an unsafe write was rejected. A telemetry exception is swallowed and cannot change migration state, retry behavior or API responses.

### Feature-state gauge

`approval.migration.safety.feature.enabled` uses one closed `feature` label:

- `execution`;
- `worker`;
- `orchestration`;
- `aggregation`;
- `automatic_reconciliation`;
- `kill_switch`.

The gauge reports configuration state only and grants no execution authority.

No metric includes tenant, operator, definition, release, plan, intent, Attempt, instance, engine, request, trace, owner, message, exception, SQL or free-text labels.

## Alerting baseline

`docs/examples/m5-migration-alerting-baseline.yml` defines 14 example-only alerts for UNKNOWN growth, manual reconciliation pressure, stale ownership, duplicate-outcome prevention, Kill Switch state, verification mismatch, runtime-binding CAS failure, Operations 5xx/auth/rate-limit growth, Canary boundary, orchestration stop, aggregation stall and observability-pipeline degradation.

Every threshold and window is an environment placeholder. No production alert destination, credential, endpoint or hard-coded production threshold is committed.

## Test evidence

Maven reactor aggregate:

- tests: `704`
- failures: `0`
- errors: `0`
- skipped: `0`

Module aggregates:

| Module | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Approval Domain | 61 | 0 | 0 | 0 |
| Definition Compiler | 11 | 0 | 0 | 0 |
| Connector SPI | 3 | 0 | 0 | 0 |
| Integration Core | 12 | 0 | 0 | 0 |
| Generic Connector | 6 | 0 | 0 | 0 |
| Integration JDBC | 4 | 0 | 0 | 0 |
| Engine Flowable | 48 | 0 | 0 | 0 |
| Approval Application | 166 | 0 | 0 | 0 |
| Persistence JDBC / PostgreSQL | 281 | 0 | 0 | 0 |
| Host SDK | 7 | 0 | 0 | 0 |
| Architecture Tests | 9 | 0 | 0 | 0 |
| Approval Server | 91 | 0 | 0 | 0 |
| Generic Host Example | 5 | 0 | 0 | 0 |

Focused F2 Java evidence includes:

- safety telemetry port: `1 / 1`;
- Micrometer safety telemetry: `2 / 2`;
- Operations telemetry classifier: `3 / 3`;
- Operations observability advice: `6 / 6`;
- Operations telemetry/filter/cache policy: `3 / 3`;
- single-instance executor fault/telemetry suite: `8 / 8`;
- exact verification: `4 / 4`;
- reconciliation: `4 / 4`;
- runtime-binding CAS: `2 / 2`;
- bounded orchestration: `4 / 4`;
- plan aggregation service: `3 / 3`.

Permanent M5 Node boundaries:

- groups: `13`
- tests: `125`
- pass: `125`
- fail: `0`
- F2 deep-hardening boundary: `7 / 7`

Web and Mobile typecheck/build jobs succeeded.

## Artifact integrity

Every artifact ZIP was downloaded and hashed locally. The local SHA-256 exactly matched GitHub's recorded digest.

| Artifact | Artifact ID | GitHub digest / downloaded ZIP SHA-256 | Result |
| --- | ---: | --- | --- |
| `approval-maven-30335096759` | `8678973441` | `ff2532198d7308be8b2cf6df8ebd7533e4697b13f1e30801b60f72e3bf5fefd5` | exact match |
| `approval-vben-30335096759` | `8678836958` | `20798451afbf338236e0a1a328ad35905dd7b52234fd1f81bfbc7c3be1aa4da5` | exact match |
| `approval-mobile-30335096759` | `8678828800` | `1c0a693ccb684e8f7f8f6c39a3b586e31a062aeafb900d2c090f162fb17be8cd` | exact match |
| `approval-hygiene-30335096759` | `8678812979` | `f0c7bf7baec00e6001cf827d630ac60c6615b37a874f4cc9c05d626c88e1c8cb` | exact match |

## Repository and migration ownership

- Flyway remains continuous through V48;
- no V49 exists;
- only `.github/workflows/approval-platform-validation.yml` is the automatic PR/main validation path;
- all executable migration flags remain default false;
- no M6 file, branch or Pull Request was modified;
- production execution remains `NOT_AUTHORIZED`.

## Retained failed and cancelled evidence

No failed or cancelled Run was deleted, hidden or treated as success.

Retained F2 development lineage includes:

- Run `30334116314` / #839: the F1 static boundary still expected the read metric literal inside the Advice after F2 moved it into a shared closed classifier;
- Run `30334415341` / #843: cancelled by a later non-forced fast-forward update;
- Run `30334558687` / #846: the D6 static boundary matched one-line formatting rather than the preserved prepare → readback → finalize call order;
- Run `30334689350` / #848 and Run `30334730694` / #849: automatically cancelled by later fast-forward documentation/boundary corrections.

The fixes made the tests validate the real security semantics and call ordering; they did not delete faults, weaken state assertions or introduce retry.

## Formal records

- `docs/M5_F2_FAULT_SECURITY_OBSERVABILITY_HARDENING.md`
- `docs/M5_F2_OBSERVABILITY_METRIC_CATALOG.md`
- `docs/examples/m5-migration-alerting-baseline.yml`
- `scripts/tests/m5-f2-deep-hardening-boundary.test.mjs`

## Stop condition

M5-F2 is complete and permanently validated. M5 remains in progress until M5-G1 and M5-G2 complete. PR #58 must remain Open + Draft at this point, and production migration execution remains unauthorized.
