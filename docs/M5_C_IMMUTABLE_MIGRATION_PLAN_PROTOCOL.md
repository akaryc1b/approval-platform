# M5-C Immutable Migration Plan and Exact Approval Protocol

M5-C stage status: `ACCEPTED_PENDING_ACCEPTANCE_EVIDENCE_VALIDATION`

M5-C governance decision: `ACCEPTED`

M5-C1 status: `PERMANENTLY_VALIDATED`

M5-C acceptance evidence status: `IMPLEMENTED_AWAITING_PERMANENT_VALIDATION`

M5-D stage authorization: `AUTHORIZED_AFTER_ACCEPTANCE_EVIDENCE_VALIDATION`

Production migration execution authorization: `NOT_AUTHORIZED`

M5-B remains `ACCEPTED` with `PERMANENTLY_VALIDATED` evidence. The user explicitly accepted M5-C
after the immutable-plan slice reached permanent validation. This protocol remains plan-only and does
not itself implement M5-D or authorize production execution.

## 1. Accepted scope

M5-C accepts a server-owned, tenant-scoped protocol for:

- creating one immutable plan from one complete, current `READY`, detect-only assessment;
- selecting an explicit bounded set of 1–1000 instances that are individually `ELIGIBLE` and
  `RUNNING`;
- freezing expected instance status, canonical active-task keys and runtime-binding evidence hash;
- binding exact source and target Release Package identities;
- freezing target deployment record, engine deployment ID, engine definition ID and engine version;
- hashing assessment, requester, reason, expiry, selections and target deployment evidence into one
  deterministic `planHash`;
- requiring a fail-closed server-side authorization gate for the exact plan identity;
- separating requester and authorizer identities;
- persisting append-only authorization and lifecycle-event evidence;
- allowing only the exact `PROPOSED` revision 1 to `AUTHORIZED` revision 2 transition;
- opening an unexpired read-only authorized-plan lookup gate.

A `READY` assessment can create a proposed plan. It cannot create an M5-B intent, consume a plan or
invoke Flowable.

## 2. Plan admission and canonical evidence

`ApprovalMigrationPlanService.createPlan` fails closed unless the assessment:

- belongs to the authenticated tenant;
- is detect-only, complete, begins at offset zero and has no remaining page;
- has status `READY` and is within the configured maximum age;
- has internally consistent running, eligible, blocked and terminal counts;
- has no global blocker;
- still matches source and target release lifecycle/package identities;
- includes every selected UUID as `ELIGIBLE` and `RUNNING` evidence;
- provides nonempty canonical task keys and a valid binding evidence hash for every selection.

Selection identities and task keys are sorted before hashing and persistence. Duplicate selection
identities, blank or noncanonical task keys and delimiter-collision-prone evidence fail closed.

The deterministic plan hash covers tenant, assessment ID/report hash, source/target release packages,
target deployment identity, requester, normalized reason, assessment time, expiry and every selected
instance snapshot.

## 3. Exact approval binding

`ApprovalMigrationPlanService.authorizePlan` accepts only one current `PROPOSED` plan revision. The
server-side `ApprovalMigrationPlanAuthorizationGate` receives the authenticated context and exact plan
ID/hash, selection count, release identities and target deployment identity.

The caller cannot supply authoritative policy, policy-version or authorization-evidence hashes. The
service and database fail closed on tenant mismatch, stale revision, expiry, package/lifecycle drift,
target deployment drift, requester/authorizer equality, malformed gate evidence or mismatched
authorization identity.

Authorization evidence is append-only. The current plan, authorization row and matching event are
written in one short database transaction. `findAuthorizedPlan` is read-only and requires tenant,
plan ID, exact plan hash, `AUTHORIZED` state and unexpired plan/authorization evidence.

## 4. Flyway V38

`db.migration.V38__Create_immutable_process_migration_plans` is a checksum-bearing Flyway Java
migration that deterministically assembles seven immutable classpath SQL fragments.

It creates:

1. `ap_process_migration_plan`;
2. `ap_process_migration_plan_instance`;
3. `ap_process_migration_plan_authorization`;
4. `ap_process_migration_plan_event`.

V38 enforces immutable plan content, exact Release Package references, current target deployment
identity, canonical selection evidence, selected-row count equality, requester/authorizer separation,
revision CAS, current/event atomicity, append-only evidence, tenant isolation and direct tamper
rejection. V38 does not alter M5-B intent, attempt, verification or reconciliation semantics.

Flyway is continuous through V38. No V39 or later migration exists.

## 5. Permanent evidence

The accepted M5-C implementation is permanently backed by:

- M5-C1 domain tests: `4/4`;
- M5-C1 application tests: `7/7`;
- M5-C1 PostgreSQL plan scenarios: `9/9`;
- M5-C1 domain/application/JDBC total: `20/20`;
- exact replay and changed-evidence conflict tests;
- tenant coexistence and isolation tests;
- concurrent authorization with one revision owner;
- target deployment drift rejection at service and database boundaries;
- direct plan, selection, authorization and event tamper rejection;
- fresh, historical, V37-to-V38 and 5,000-instance/task upgrade paths.

Final pre-acceptance Run #535:

- Run ID: `30137372365`;
- head: `6732134b0fe330862635b7c8afc78cf8747718f6`;
- result: `success`;
- Maven aggregate: `560` tests, zero failures, zero errors and zero skipped;
- approval-persistence-jdbc: `236/236`;
- M5 permanent Node boundaries: `35/35`;
- all four jobs and downloaded artifact digest checks succeeded.

Failed Runs #532 and #533 remain retained as import-hygiene evidence and were not rerun.

## 6. Accepted limitations

M5-C does not provide or authorize:

- V39 or later migration;
- plan consumption or a `CONSUMED` transition;
- automatic M5-B intent creation;
- an executor, worker, poller, scheduler or automatic claim;
- production Flowable migration invocation or direct `ACT_*` access;
- runtime-binding mutation;
- execute, force, rollback or reconciliation endpoints;
- Web or Mobile execution controls;
- automatic migration, lease renewal, takeover or `UNKNOWN` retry;
- fake rollback or cross-system atomicity;
- M5-E, M5-F or M5-G implementation;
- PR Ready, auto-merge, merge or issue closure.

## 7. Acceptance and next gate

M5-C is accepted pending permanent validation of this governance package. After that validation, M5-D
is the only authorized next stage and may begin controlled server-side executor, verification,
`UNKNOWN` handling and reconciliation implementation.

M5-D authorization to begin does not authorize production migration execution. Production execution
remains `NOT_AUTHORIZED` until a separate M5-D governance decision and permanent evidence package.
PR #58 remains Open + Draft and Issues #13, #14 and #56 remain Open.
