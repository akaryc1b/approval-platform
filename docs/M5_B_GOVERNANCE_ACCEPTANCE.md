# M5-B Governance Acceptance

M5-B governance decision: `ACCEPTED`

Acceptance evidence status: `PERMANENTLY_VALIDATED`

M5-C stage authorization: `AUTHORIZED_TO_BEGIN`

Production migration execution authorization: `NOT_AUTHORIZED`

After the prior status identified the M5-B governance decision as the only permitted next action, the
user explicitly instructed continued progression. This document records that decision. It does not
implement M5-C and does not authorize M5-D.

PR #58 remains Open + Draft. Issues #13, #14 and #56 remain Open.

## Decision scope

Issue #56 defines M5-B as the governed migration domain model and persistence protocol. The accepted
scope is limited to platform-owned domain and PostgreSQL persistence evidence for intents, attempts,
verification and reconciliation.

The decision accepts:

- closed intent, attempt, verification and reconciliation vocabularies;
- tenant-scoped identities, composite lineage and tenant-isolated reads;
- current-row revision CAS and matching append-only event chains;
- exact idempotent replay and bounded changed-evidence conflicts;
- one active attempt owner per tenant-scoped approval instance;
- strict retry lineage using a new attempt identity and the immediate retryable parent;
- immutable, gap-free verification and reconciliation evidence;
- explicit lease actors, renewal, expiry takeover and stale-owner fencing;
- independently durable engine request, failure and UNKNOWN evidence;
- reconciliation evidence gates before UNKNOWN progression and closure;
- Flyway continuity through V37 with fresh, historical and heavy upgrade validation.

## Criteria and evidence

### Domain and persistence completeness

The six tenant-scoped protocol tables are complete for M5-B:

- `ap_process_migration_intent`;
- `ap_process_migration_intent_event`;
- `ap_process_migration_attempt`;
- `ap_process_migration_attempt_event`;
- `ap_process_migration_verification`;
- `ap_process_migration_reconciliation`.

Flyway is continuous through V37. No V38 or later migration exists.

### Concurrency and conflict closure

Permanent PostgreSQL/Testcontainers evidence closes exact replay, changed-evidence conflict,
one-plan/one-intent ownership, one-active-attempt ownership, evidence sequence races, terminal
reconciliation races and concurrent expired-lease takeover. No accepted test permits an arbitrary
exception set or an unbounded final state.

### Tenant, lineage and tamper resistance

Permanent evidence proves tenant isolation, immediate retryable-parent lineage, current-row/event
atomicity, stable payload immutability, append-only evidence, terminal closure and rejection of direct
revision, sequence, lease and UNKNOWN tampering.

### Lease and UNKNOWN safety

Claims and transitions require explicit durable lease authority. Revision CAS is the fencing token.
UNKNOWN preserves the exact engine request reference, engine outcome, failure class and bounded error
evidence. UNKNOWN is never automatically retried. Reconciliation evidence is mandatory before
progression and terminal closure.

### Permanent validation basis

The final pre-acceptance implementation evidence is:

- workflow: `Approval Platform Validation`;
- Run ID: `30089736069`;
- run number: `#527`;
- head: `86951bed70f54c25981f69da32b2cfdacf7afb22`;
- result: `success`;
- Maven aggregate: `540` tests, zero failures, zero errors and zero skipped;
- persistence-jdbc: `227/227`;
- M5-B domain and JDBC protocol: `37/37`;
- M5-B4 lease/UNKNOWN PostgreSQL scenarios: `8/8`;
- M5 permanent Node boundaries: `29/29`;
- all four raw job logs were read;
- all four downloaded artifact ZIP SHA-256 values matched the GitHub digests.

The final Run #527 artifact digests are:

- Maven: `6eb97232ea99f16a870484482b0f9e09aff41183367e7679acefcadab1de905c`;
- Vben: `76db210bc4e2605f9b77e4a2e648fb2f267b06f883ca860229d8e0597690931f`;
- Mobile: `a585a507fc305595d2d3b7e8f73f23f8cc45ce7fc51d03535b355700e814668f`;
- Hygiene: `6ef034dc57192e0e930e2e6bb5b9642b58c60f55c412d3b2aa271d99d0f3b606`.

Failed Runs #512, #513, #514, #517 and #525 remain retained and explained. None was hidden or
blindly rerun.

### Acceptance evidence permanent validation

The M5-B governance acceptance package was permanently validated without changing production Java,
Flyway migrations or execution authority:

- workflow: `Approval Platform Validation`;
- Run ID: `30094448784`;
- run number: `#530`;
- head: `3bcc679af0c573ef972bf303fd079c0f7ab65653`;
- result: `success`;
- Maven aggregate: `540` tests, zero failures, zero errors and zero skipped;
- persistence-jdbc: `227/227`;
- M5-B domain and JDBC protocol: `37/37`;
- M5-B4 lease/UNKNOWN PostgreSQL scenarios: `8/8`;
- M5 permanent Node boundaries: `31/31`;
- all four jobs succeeded;
- all four raw job logs were read;
- all four downloaded artifact ZIP SHA-256 values matched the GitHub digests.

The acceptance Run #530 artifacts are:

| Artifact | ID | GitHub digest and downloaded ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30094448784` | `8597131037` | `b9d005ecbcc9b22eae37ae835e1cfb499117c664f1199568b94f2439b6cf3241` — exact match |
| `approval-vben-30094448784` | `8597035914` | `c54f3b6c210becb7cafdefe20a73cb8c9abfa1333440081a11af4ff79ce67eac` — exact match |
| `approval-mobile-30094448784` | `8597008964` | `95bce1572a2d4a0f2dbc9485f8b5e8b99ccd9cc395cf91b252d1f997fddb58a0` — exact match |
| `approval-hygiene-30094448784` | `8596987460` | `5984ee8d2c43ab05ff9ba79879465f18a414cd7c9c4ff2fdcf73d687368234ff` — exact match |

This validation authorizes beginning M5-C as a plan-only stage. It does not begin M5-C in this
commit, authorize M5-D, or authorize production migration execution.

## Accepted limitations

The M5-A technical decision remains `SUPPORTED_WITH_LIMITATIONS`. M5-B acceptance does not expand
Flowable capability beyond the bounded shapes proven in M5-A.

M5-B does not provide or authorize:

- immutable migration-plan creation, approval, sealing or consumption;
- a production migration executor, worker, poller or scheduler;
- a production Flowable migration invocation;
- runtime-binding mutation;
- execute, force, rollback or reconciliation endpoints;
- Web or Mobile execution controls;
- automatic migration, lease renewal, takeover or UNKNOWN retry;
- direct Flowable `ACT_*` table access;
- fake rollback or atomicity between Flowable and the platform database;
- M5-D, M5-E, M5-F or M5-G implementation.

## Governance result

M5-B is accepted for its defined domain-and-persistence scope. This is not production migration
approval and does not mark PR #58 Ready.

The acceptance package is permanently validated. The only authorized next stage is
M5-C — Immutable Migration Plans and Approval Gates. M5-C must remain plan-only and cannot invoke
Flowable, create an executor, mutate runtime bindings, expose execution controls or authorize M5-D.

PR #58 must remain Open + Draft. No auto-merge, merge or issue closure is authorized.
