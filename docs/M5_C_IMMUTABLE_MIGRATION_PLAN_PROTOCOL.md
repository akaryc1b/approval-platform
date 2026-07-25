# M5-C Immutable Migration Plan and Exact Approval Protocol

M5-C stage status: `IN_PROGRESS`

M5-C1 status: `PERMANENTLY_VALIDATED`

M5-C1 evidence freeze status: `IMPLEMENTED_AWAITING_FINAL_VALIDATION`

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
Duplicate selection identities fail. Selected instances and task keys are sorted before hashing and
persistence. Task-key counts and each individual key are hashed as separate canonical values rather
than a delimiter-joined string.

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

`db.migration.V38__Create_immutable_process_migration_plans` is a checksum-bearing Flyway Java
migration. It deterministically concatenates seven immutable classpath SQL fragments and adds four
tenant-scoped tables:

1. `ap_process_migration_plan`;
2. `ap_process_migration_plan_instance`;
3. `ap_process_migration_plan_authorization`;
4. `ap_process_migration_plan_event`.

The assembled V38 SQL does not alter the accepted M5-B intent, attempt, verification or
reconciliation tables. There is no foreign key or application call that automatically converts an
authorized plan into an intent.

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
- target deployment identity drift rejection at service and database boundaries;
- direct plan, selection, authorization and event mutation/deletion rejection;
- cross-tenant authorization reference rejection;
- fresh, historical, V37-to-V38 and 5,000-instance/task upgrade paths.

## 6. Retained failed validation evidence

### Run #532 — application test import hygiene

- workflow Run ID: `30113635674`;
- run number: `#532`;
- head: `24ba20d5a8bf941450365caa4b41472dd87a97c6`;
- conclusion: `failure`;
- Repository hygiene, Vben and Mobile succeeded;
- Java stopped at application Checkstyle because two M5-C test files retained 30 unused imports;
- no application test, JDBC test, V38 migration or PostgreSQL semantic failure occurred;
- no rerun was used.

| Artifact | ID | GitHub digest and downloaded ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30113635674` | `8604560277` | `35ab49754a66d6a7c62f74e181e99a55213404d0a1b0b8af92f0281ad4290804` — exact match |
| `approval-vben-30113635674` | `8604564540` | `0e9515e62efb37b91509c75984d0142599fe15e95e8bc32d60e8b0eeeed3430b` — exact match |
| `approval-mobile-30113635674` | `8604544311` | `900713a8604085fc83432043a2f32153ebcdd418fec8287e3507c541cf77a8bb` — exact match |
| `approval-hygiene-30113635674` | `8604520545` | `af0d64d6e7447cf21e9cbed5613601e8f63a8824bced901ce9b913a295f738e6` — exact match |

The minimal follow-up commit was
`dd876e84fc9be709157a283af8bd6f4748a2603b` — `fix: clean migration plan test imports`.

### Run #533 — JDBC test import hygiene

- workflow Run ID: `30136606769`;
- run number: `#533`;
- head: `dd876e84fc9be709157a283af8bd6f4748a2603b`;
- conclusion: `failure`;
- Repository hygiene, Vben and Mobile succeeded;
- domain tests were `31/31`, Flowable tests were `40/40` and application tests were `132/132`;
- Java then stopped at persistence-jdbc Checkstyle because two M5-C JDBC tests retained four unused imports;
- no V38 migration or PostgreSQL semantic failure occurred;
- no rerun was used.

| Artifact | ID | GitHub digest and downloaded ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30136606769` | `8613016086` | `b9cf6d0c7bdd890ee9d88581fe69021c74e5dc30706ac1c3fffe8268071de44b` — exact match |
| `approval-vben-30136606769` | `8613019403` | `b508a2e3f781ff4e0ff88a628601cc5912035a9aa5d32b71e0a102da63b84914` — exact match |
| `approval-mobile-30136606769` | `8613010792` | `13fbaf0ba9648fdbfbd5225654355824861a5e7937f17b18a8b79a06a92078f0` — exact match |
| `approval-hygiene-30136606769` | `8612998465` | `0702494a695e9acd05ab284bed778825052dece79d5334160cca8ad23ba11d30` — exact match |

The minimal follow-up commit was
`502bb7462c2191cbef98f64e286d84a91ea8ed57` — `fix: clean migration plan jdbc test imports`.

## 7. Successful implementation validation

M5-C1 was permanently validated by the unique automatic workflow:

- workflow: `Approval Platform Validation`;
- Run ID: `30136814277`;
- run number: `#534`;
- head: `502bb7462c2191cbef98f64e286d84a91ea8ed57`;
- result: `success`;
- all four jobs succeeded;
- all four raw job logs were read;
- all four downloaded artifact ZIP SHA-256 values matched GitHub digests.

| Artifact | ID | GitHub digest and downloaded ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30136814277` | `8613145767` | `dd00cb2d11bf04ed947a292bbf59e9101b94e686306f600cdd2050268903ee37` — exact match |
| `approval-vben-30136814277` | `8613087604` | `a3bb4e3135d40d895bbe7e23e71b3f7841f0d4ecd7f005ac0d77a70faf24391e` — exact match |
| `approval-mobile-30136814277` | `8613079906` | `dedf3c9b35c995644658757e2506c15fed381f5fb76d5755fe7c751acf75b2ee` — exact match |
| `approval-hygiene-30136814277` | `8613068920` | `733eb9be61f5faafa4fb1271fe6fe72fe7fbf920f962e9df8395197ab6adb6b5` — exact match |

Final implementation evidence:

- Maven aggregate: `560` tests, zero failures, zero errors and zero skipped;
- approval-domain: `31/31`;
- approval-engine-flowable: `40/40`;
- approval-application: `132/132`;
- approval-persistence-jdbc: `236/236`;
- M5-C1 domain tests: `4/4`;
- M5-C1 application service tests: `7/7`;
- M5-C1 PostgreSQL plan scenarios: `9/9`;
- M5-C1 domain/application/JDBC total: `20/20`;
- M5 permanent Node boundaries: `35/35`;
- M4 SLA/calendar boundaries: `13/13`;
- M4 release governance boundaries: `5/5`;
- Vben client boundaries: `10/10`;
- Vben type-check and production build succeeded;
- UniApp type-check, H5 build and WeChat Mini Program build succeeded;
- fresh, historical, V37-to-V38 and 5,000-instance/task upgrade paths reached V38 and preserved evidence;
- Maven reactor completed all 16 modules with `BUILD SUCCESS`.

## 8. Explicitly absent

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

## 9. Next gate

M5-C remains `IN_PROGRESS` after this slice. M5-C1 is permanently validated, but this does not
accept the whole M5-C stage. The next permitted decision is whether additional M5-C plan
lifecycle/approval governance evidence is required before formal M5-C acceptance. M5-D and
production execution remain `NOT_AUTHORIZED`.
