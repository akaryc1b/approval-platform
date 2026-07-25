# M5-C Governance Acceptance

M5-C governance decision: `ACCEPTED`

Acceptance evidence status: `IMPLEMENTED_AWAITING_PERMANENT_VALIDATION`

M5-D stage authorization: `AUTHORIZED_AFTER_ACCEPTANCE_EVIDENCE_VALIDATION`

Production migration execution authorization: `NOT_AUTHORIZED`

The user explicitly instructed `验收` after M5-C1 reached permanent validation. This decision accepts
M5-C for its defined immutable-plan and exact-approval-gate scope. No additional M5-C plan lifecycle
slice is required before stage acceptance.

This acceptance package changes governance evidence only. It does not add production Java, Flyway
V39, an executor, a worker, a scheduler, a Flowable migration call, runtime-binding mutation or an
execution endpoint.

PR #58 remains Open + Draft. Issues #13, #14 and #56 remain Open.

## Decision scope

Issue #56 defines M5-C as immutable migration plans and approval gates. The accepted scope is limited
to server-owned plan creation and exact approval evidence below production execution authority.

The decision accepts:

- creation of one immutable, content-addressed tenant-scoped plan from one complete, current `READY`,
  detect-only assessment;
- explicit bounded selection of 1–1000 assessment instances that are individually `ELIGIBLE` and
  `RUNNING`;
- frozen instance status, canonical active-task keys and runtime-binding evidence hash per selection;
- exact source and target Release Package identities;
- exact target deployment record, engine deployment ID, engine definition ID and engine version;
- deterministic `planHash` coverage of assessment, requester, reason, expiry, selection and target
  deployment evidence;
- fail-closed server-side `ApprovalMigrationPlanAuthorizationGate` decisions;
- requester and authorizer separation;
- exact `PROPOSED` revision 1 to `AUTHORIZED` revision 2 transition;
- append-only plan, selection, authorization and event evidence;
- tenant-isolated reads and direct tamper rejection;
- an unexpired, read-only authorized-plan gate that neither consumes a plan nor creates an M5-B intent;
- Flyway continuity through V38.

## Acceptance criteria and evidence

### Immutable plan completeness

The four tenant-scoped M5-C tables are complete for the accepted scope:

1. `ap_process_migration_plan`;
2. `ap_process_migration_plan_instance`;
3. `ap_process_migration_plan_authorization`;
4. `ap_process_migration_plan_event`.

`db.migration.V38__Create_immutable_process_migration_plans` is a checksum-bearing Flyway Java
migration that deterministically assembles seven immutable SQL fragments. No V39 or later migration
exists.

### Exact approval and separation of duties

The caller cannot manufacture authoritative policy, policy-version or authorization-evidence hashes.
The server-side gate receives the authenticated context and exact plan ID/hash, selected count,
source/target release identity and frozen target deployment identity. The requester cannot authorize
the same plan.

### Concurrency, tenancy and tamper closure

Permanent PostgreSQL/Testcontainers evidence proves exact create replay, bounded changed-evidence
conflicts, tenant coexistence and isolation, exact authorization replay, stale-revision rejection,
concurrent authorization with one revision owner, target deployment drift rejection, append-only
evidence and direct update/delete tamper rejection.

### Final pre-acceptance validation basis

The frozen implementation evidence is:

- workflow: `Approval Platform Validation`;
- Run ID: `30137372365`;
- run number: `#535`;
- head: `6732134b0fe330862635b7c8afc78cf8747718f6`;
- result: `success`;
- all four jobs succeeded;
- Maven aggregate: `560` tests, zero failures, zero errors and zero skipped;
- approval-domain: `31/31`;
- approval-engine-flowable: `40/40`;
- approval-application: `132/132`;
- approval-persistence-jdbc: `236/236`;
- M5-C1 domain/application/JDBC: `20/20`;
- M5 permanent Node boundaries: `35/35`;
- all four raw job logs were read;
- all four downloaded artifact ZIP SHA-256 values matched GitHub digests.

The final Run #535 artifacts are:

| Artifact | ID | GitHub digest and downloaded ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30137372365` | `8613344744` | `cae0b9c6f31d08e226ea185885cf009f3c1ef761e30d2e914a48e9c443d71a8c` — exact match |
| `approval-vben-30137372365` | `8613286546` | `e180db6bd666b513b4cad2445a18795760bb6c3f3b6f1083a6539489b963695d` — exact match |
| `approval-mobile-30137372365` | `8613276609` | `040a98fed0e3f0cfaac5c3b8e6a3e8f35cc68e6457696a1db99f345f9b135813` — exact match |
| `approval-hygiene-30137372365` | `8613265429` | `ae6440ff679235132c619e255f97a0b460ed9e026383269e75e12d9c7a36b3ce` — exact match |

Failed Runs #532 (`30113635674`) and #533 (`30136606769`) remain retained and explained.
Neither was hidden or blindly rerun. Both failed only on unused test imports before PostgreSQL semantic
execution; the minimal follow-up commits removed those imports without changing production or V38
semantics.

## Accepted limitations

M5-C acceptance does not provide or authorize:

- plan consumption or a `CONSUMED` transition;
- automatic creation of an M5-B intent from an authorized plan;
- a production executor, worker, poller, scheduler or automatic claim;
- production Flowable migration invocation;
- runtime-binding mutation;
- execute, force, rollback or reconciliation endpoints;
- Web or Mobile execution controls;
- automatic migration, lease renewal, takeover or `UNKNOWN` retry;
- direct Flowable `ACT_*` table access;
- fake rollback or atomicity between Flowable and the platform database;
- M5-E, M5-F or M5-G implementation;
- PR Ready, auto-merge, merge or issue closure.

## M5-D gate after acceptance validation

After this acceptance package is permanently validated, M5-D is the only authorized next stage.
M5-D may begin controlled server-side executor, verification, `UNKNOWN` handling and reconciliation
design and implementation, subject to all permanent Issue #56 invariants.

M5-D authorization to begin is not production migration execution approval. Production execution must
remain disabled by default and remains `NOT_AUTHORIZED` until a separate M5-D governance decision and
permanent evidence package establish its exact admission, engine-call, verification, failure and
reconciliation boundaries.

## Governance result

M5-C is accepted for its immutable-plan and exact-approval-gate scope. This does not mark PR #58 Ready.
The acceptance decision becomes the stage baseline only after this evidence package receives permanent
validation.

PR #58 must remain Open + Draft. No auto-merge, merge or issue closure is authorized.
