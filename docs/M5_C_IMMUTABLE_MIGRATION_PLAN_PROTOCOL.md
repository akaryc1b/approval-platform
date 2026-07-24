# M5-C Immutable Migration Plan and Exact Approval Protocol

M5-C stage status: `IN_PROGRESS`

M5-C1 status: `IMPLEMENTED_PENDING_PERMANENT_VALIDATION`

M5-B governance decision remains `ACCEPTED` and its evidence remains
`PERMANENTLY_VALIDATED`.

This slice implements only immutable migration-plan creation and exact plan-hash approval evidence.
It does not create an execution intent, invoke Flowable, mutate runtime bindings, expose an execution
API, or authorize M5-D.

## 1. Authorized scope

M5-C1 adds a server-side, tenant-scoped protocol for:

- creating one content-addressed immutable plan from one complete `READY` detect-only assessment;
- freezing an explicit bounded set of assessment instances that were individually `ELIGIBLE`;
- freezing expected instance status, active task keys and runtime-binding evidence hash per instance;
- binding source and target release versions and immutable package hashes;
- freezing the exact target deployment record, engine deployment ID, engine definition ID and engine version;
- requiring a fail-closed server-side authorization gate for the exact `planHash`;
- recording the gate decision as independent append-only authorization evidence;
- enforcing separation between the plan requester and authorizer;
- opening a read-only authorized-plan gate only while both plan and authorization remain current;
- retaining append-only plan, selection, authorization and lifecycle-event evidence.

The assessment remains non-authoritative for execution. A `READY` assessment can create a proposed
plan, but it cannot create an M5-B intent and cannot invoke an engine operation.

## 2. Canonical plan creation

`ApprovalMigrationPlanService.createPlan` accepts a server-side `AssessmentResult`; no management,
Web or Mobile endpoint is introduced.

The service fails closed unless the assessment:

- belongs to the authenticated tenant;
- is detect-only;
- is complete, begins at offset zero and has no remaining page;
- has status `READY`;
- is not future-dated or older than the configured maximum age;
- has internally consistent total, running, eligible, blocked and terminal counts;
- has no global blocker finding;
- still matches the source and target release lifecycle/package identities;
- contains every selected UUID as `ELIGIBLE` and `RUNNING` evidence;
- provides a nonempty canonical active-task key set and binding evidence hash for every selection.

The selected set is explicit and bounded to 1–1000 UUIDs. It cannot be a query evaluated later.
Duplicate selection identities fail. Selected instances and task keys are sorted before hashing and persistence. Task-key counts and
each individual key are hashed as separate canonical values rather than a delimiter-joined string.

The deterministic plan hash covers:

- tenant and assessment identity/report hash;
- exact source and target release/package identity;
- exact target deployment record and engine deployment/definition/version identity;
- requester and normalized operation reason;
- assessment time and deterministic expiry;
- every canonical selected-instance snapshot, task-key set and binding evidence hash.

A different selection, release identity, reason, requester, expected evidence or expiry produces a
different plan hash. Reuse of an idempotency key with different evidence is a conflict. A distinct
idempotency key cannot create a second row for an existing plan hash.

## 3. Exact approval binding

`ApprovalMigrationPlanService.authorizePlan` authorizes only one current `PROPOSED` plan revision.
It sends the authenticated context and exact plan ID/hash, selection count and release identity to
`ApprovalMigrationPlanAuthorizationGate`. The caller cannot provide an authoritative policy name,
policy version or authorization evidence hash; those values must come from the server-side gate.
It fails closed when:

- the tenant-scoped plan does not exist;
- the revision compare-and-set is stale;
- the plan or authorization is expired;
- source or target immutable package identity or lifecycle eligibility changed;
- target deployment record, package binding or engine deployment/definition/version identity changed;
- the requester and authorizer are the same identity;
- the decision does not bind the exact plan hash, selected count, source/target release identity and frozen target deployment identity;
- the server-side gate denies the request or returns malformed/mismatched evidence;
- authorization policy, policy version, authorization evidence hash, reason or audit evidence is
  missing or malformed.

Authorization evidence is append-only and independently stores the authorizer, policy and version,
exact plan hash, selected count, source/target package hashes, decision time and expiry. Plan current
state advances from `PROPOSED` revision 1 to `AUTHORIZED` revision 2 in the same short database
transaction that writes the authorization and matching event.

`findAuthorizedPlan` is a read-only gate. It requires tenant, plan ID, exact plan hash, `AUTHORIZED`
status, unexpired plan and unexpired authorization. It does not consume a plan or create an intent.

## 4. Flyway V38

`db.migration.V38__Create_immutable_process_migration_plans` is a checksum-bearing Flyway Java migration. It deterministically concatenates seven immutable classpath SQL fragments and adds four tenant-scoped tables:

1. `ap_process_migration_plan`;
2. `ap_process_migration_plan_instance`;
3. `ap_process_migration_plan_authorization`;
4. `ap_process_migration_plan_event`.

The assembled V38 SQL does not alter the accepted M5-B intent, attempt, verification or reconciliation tables. There is
no foreign key or application call that automatically converts an authorized plan into an intent.

Database guards enforce:

- immutable plan identity and canonical content;
- exact source/target Release Package foreign keys;
- immutable target deployment record and engine deployment/definition/version evidence included in the plan hash;
- one plan hash and one creation idempotency key per tenant;
- canonical selection order, unique task keys and exact selection payload matching;
- deferred selected-row count equality at transaction commit;
- append-only selection, authorization and event evidence;
- requester/authorizer separation and exact authorization identity;
- plan revision compare-and-set and the C1-only `PROPOSED -> AUTHORIZED` transition;
- current-plan and event atomicity at transaction commit;
- current row, authorization row, event row and JSON payload/column consistency;
- exact current `DEPLOYED` target deployment identity on plan creation and authorization;
- rejection of update/delete tampering and cross-tenant binding.

Flyway is continuous through V38. No V39 or later migration is introduced by this slice.

## 5. Permanent test boundary

Permanent domain, application and PostgreSQL/Testcontainers tests cover:

- canonical plan invariants and requester/authorizer separation;
- exact server-side authorization-gate binding and caller-evidence rejection;
- complete/READY/current assessment admission;
- explicit eligible selection and deterministic plan hashing;
- exact create replay and changed-evidence conflicts;
- same stable identities in different tenants with tenant-scoped reads;
- exact authorization replay and authorized read gating;
- stale revision and mismatched authorization rejection;
- concurrent authorization with exactly one revision owner;
- direct plan, selection, authorization and event mutation/deletion rejection;
- fresh, historical, V37-to-V38 and 5,000-instance/task upgrade paths.

## 6. Explicitly absent

M5-C1 includes no:

- `V39`;
- plan consumption or `CONSUMED` transition implementation;
- M5-B intent creation from a plan;
- executor, worker, poller, scheduler or automatic claim;
- Flowable migration invocation or direct `ACT_*` access;
- runtime-binding mutation;
- execute, force, rollback or reconciliation endpoint;
- Web or Mobile execution control;
- automatic migration, lease renewal, takeover or `UNKNOWN` retry;
- M5-D, M5-E, M5-F or M5-G implementation;
- second automatic workflow;
- PR Ready, auto-merge, merge or issue closure.

## 7. Next gate

M5-C remains `IN_PROGRESS` after this slice. The next permitted decision is whether additional M5-C
plan lifecycle/approval governance evidence is required before formal M5-C acceptance. M5-D and
production execution remain `NOT_AUTHORIZED`.
