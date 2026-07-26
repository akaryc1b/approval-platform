# M5-D2 — Shared Command Fence and Lease-governed Claim Permanent Evidence

## Governance result

- M5-D overall state: `IN_PROGRESS`
- M5-D1 authorized-plan admission: `PERMANENTLY_VALIDATED`
- M5-D2 implementation slice: `COMPLETE_PENDING_EXPLICIT_ACCEPTANCE`
- Production migration execution: `NOT_AUTHORIZED`
- M5-D3 through M5-D8: not started
- PR #58 remains Open + Draft.
- Issues #13, #14 and #56 remain Open.

This evidence closes only the D2 server-owned bridge from one consumed sealed plan to exact initial attempts, a bounded claim batch, one shared tenant/instance command fence and durable lease ownership. It does not accept M5-D2 on behalf of the user and does not authorize a Flowable migration call, runtime-binding mutation, resident worker, public endpoint, Web control, Mobile control or production execution.

## Validated head and branch safety

- implementation head: `1eedb7ee75060b8c6e1d06bbf8504432ce782462`
- branch: `agent/m5-governed-process-instance-migration`
- base at validation: `d769722cf7dd5418739a91ad4c45ca1a1c147502`
- Flyway latest: `V40`
- V1 through V40 remain immutable.
- every M5 ref update used `force=false`.
- active M6 pull requests #67, #68, #69 and #70 contained no Flyway migration when each M5 ref was updated.
- the only permanent validation workflow remained `.github/workflows/approval-platform-validation.yml`.

## Delivered D2 scope

The permanent implementation provides:

- exact initial-attempt provisioning from one current `CONSUMED` plan and its exact selected-instance rows;
- current tenant, intent, plan, runtime-instance and runtime-binding validation before attempt creation;
- one initial `PENDING` attempt per selected approval instance, in canonical plan order;
- exact replay that returns authoritative existing attempts without duplicate events or audit;
- a tenant-prefixed, intent-prefixed, deterministic and bounded claim query;
- `ORDER BY created_at, attempt_id`, bounded `LIMIT` and `FOR UPDATE SKIP LOCKED`;
- exact empty claim-batch evidence when another worker wins the eligible work;
- one durable migration command fence per active tenant/approval-instance claim;
- one shared PostgreSQL transaction advisory-lock namespace for migration claims and business commands;
- same-owner renewal only before current expiry and only with a strict extension;
- new-owner takeover only at or after expiry;
- stale-owner rejection through current revision, lease actor, lease owner and lease-until evidence;
- append-only claim-batch and command-fence event evidence;
- audit failure rollback for provisioning, claim and lease transition transactions;
- one internal one-shot runner that remains disabled by default and has no scheduler.

D2 invokes no Flowable API and mutates no runtime binding.

## V40 persistence and database guards

Flyway V40 adds and protects:

- `ap_process_migration_claim_batch` — immutable bounded-claim replay evidence, including exact empty claims;
- `ap_approval_instance_command_fence` — current tenant/instance migration ownership and lease state;
- `ap_approval_instance_command_fence_event` — append-only contiguous fence revisions;
- `idx_process_migration_attempt_claim_v40` on `(tenant_id, intent_id, status, lease_until, created_at, attempt_id)`;
- a partial unique active-fence index on `(tenant_id, approval_instance_id)` where status is `ACTIVE`;
- deferred linkage from each claim batch to exact claimed attempts and fences;
- payload-to-column equality, immutable identity, revision, status, lease-owner, expiry and event-chain guards.

The database rejects:

- a fence that does not match the current `CLAIMED` attempt owner and lease;
- two active fences for one tenant/approval instance;
- same-owner renewal after expiry or without extending the lease;
- takeover before expiry;
- stale or skipped fence revisions;
- claim-batch arrays that do not match the durable attempts and fences;
- mutation or deletion of append-only evidence.

## Provisioning and claim transaction boundaries

### Short transaction A — provision initial attempts

1. lock the exact tenant-scoped intent;
2. require the linked plan to be current `CONSUMED` evidence;
3. read exact sealed selections in canonical order;
4. require each approval instance to remain `RUNNING`;
5. require current runtime binding to match source release, source engine definition and expected binding hash;
6. insert one initial attempt and event per exact selection;
7. append one bounded audit record;
8. commit.

### Short transaction B — claim and fence

1. lock the exact intent;
2. select at most the requested limit with tenant/intent prefix, deterministic order and `SKIP LOCKED`;
3. acquire the same tenant/instance advisory lock used by business commands;
4. transition each attempt to `CLAIMED` with server-owned worker and lease evidence;
5. create the active command fence and initial event;
6. append one immutable claim batch, including an exact empty result if no row was won;
7. append one bounded audit record;
8. commit before any future engine call.

### Short transaction C — renewal or expiry takeover

1. lock the current attempt and fence;
2. validate current revision and lease evidence;
3. renew the same owner only before expiry with a strict extension, or transfer ownership only after expiry;
4. update attempt and fence by one revision;
5. append both events and audit;
6. commit.

No Flowable call occurs in any D2 transaction.

## Shared business-command serialization

`CommandFencedApprovalProjectionStore` places complete, return/control, transfer, retrieve and withdraw projection mutations behind `ApprovalInstanceCommandFence`. The closed operation vocabulary also reserves approve, reject, terminate and migration.

`JdbcApprovalInstanceCommandFence` and `JdbcApprovalMigrationAttemptClaimStore` use the same key namespace:

```text
approval-instance-command:v1:<tenantId>:<approvalInstanceId>
```

Both sides acquire `pg_advisory_xact_lock(hashtextextended(...))`. A business command inside its platform transaction is rejected while an unexpired active migration fence exists and is admitted at exact lease expiry when no current active lease remains.

## Permanent implementation success run

- workflow: `.github/workflows/approval-platform-validation.yml`
- run ID: `30187205016`
- run number: `#563`
- head: `1eedb7ee75060b8c6e1d06bbf8504432ce782462`
- conclusion: `success`
- all four raw job logs were read.

| Job | Job ID | Conclusion |
| --- | ---: | --- |
| Repository hygiene | `89753894834` | success |
| UniApp TypeScript / H5 / WeChat | `89753894813` | success |
| Vben TypeScript / production build | `89753894857` | success |
| Java 21 / Maven / PostgreSQL | `89753894876` | success |

## Test, scale and build totals

- Maven aggregate: 586 tests, 0 failures, 0 errors, 0 skipped
- approval-domain: 35/35
- approval-engine-flowable: 40/40
- approval-application: 140/140
- approval-persistence-jdbc: 250/250
- approval-definition-compiler: 11/11
- approval-connector-spi: 3/3
- approval-integration-core: 12/12
- generic connector: 6/6
- approval-integration-jdbc: 4/4
- host SDK: 7/7
- architecture tests: 9/9
- server tests: 64/64
- example tests: 5/5
- D2 application claim tests: 3/3
- D2 provisioning PostgreSQL tests: 4/4
- D2 claim/fence PostgreSQL tests: 4/4
- D2 5,000-row claim-plan test: 1/1
- D1/D2 permanent Node governance boundaries: 34/34
- retained M5-A parallel/subprocess/timer/duplicate boundaries: 8/8
- retained M5-A history/concurrent-command boundaries: 2/2
- Vben type-check and production build: success
- UniApp type-check, H5 build and WeChat Mini Program build: success
- Maven reactor: `BUILD SUCCESS`

The scale test loaded 5,000 migration attempts and executed the production claim shape with `EXPLAIN (FORMAT JSON)`. The plan retained a bounded `Limit`, used `idx_process_migration_attempt_claim_v40`, retained tenant and intent prefix conditions, and contained no `Seq Scan`.

## Artifact integrity

Each Run #563 artifact ZIP was downloaded and its local SHA-256 exactly matched the GitHub artifact digest.

| Artifact | ID | GitHub digest / downloaded ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30187205016` | `8627434660` | `d1e772445838afd0e5215c86ab32def7cef3576004d76bfd449342a1029e09a3` — exact match |
| `approval-vben-30187205016` | `8627394734` | `e1da0047758b6c0376e4b25d073a45fef5fedab258ae6f713101e2c294c6e759` — exact match |
| `approval-mobile-30187205016` | `8627390472` | `1a308f7e5d714d1f841533ac1e6f373838666af724400b9ee853d1dff2b588e9` — exact match |
| `approval-hygiene-30187205016` | `8627384038` | `f3e1adf64a05f57e45a1e046bc725c04771ee9230dab3eaea54f1f53170cefc5` — exact match |

## Successful implementation lineage

- Run #556 / `30166354379` — V40 upgrade, wiring and retained-boundary baseline succeeded.
- Run #557 / `30166700060` — sealed selection to initial-attempt provisioning compiled and passed all retained tests.
- Run #558 / `30185560924` — claim concurrency, renewal, takeover, stale-owner, business fence and audit rollback tests succeeded.
- Run #559 / `30186044530` — production-store provisioning, concurrent replay, binding drift rejection and rollback tests succeeded.
- Run #563 / `30187205016` — complete D2 implementation, scale and permanent Node boundaries succeeded.

All four raw logs and all four downloaded artifact ZIP digests were verified for each success run used as permanent evidence.

## Retained failed-run lineage

No failed run was cancelled, hidden or rerun. Raw job logs were read and downloaded artifact ZIP hashes were checked against GitHub digests before each minimal fix.

- Run #550 / `30155701169`, head `36afad83e7fa8c6ba3a24f8471c9f0bb264fdd1e`: one extra parenthesis broke persistence Checkstyle parsing, and incomplete configuration wiring displaced accepted M4 metrics/audit wrappers. Fix: `4c243d2a20c2ff555acb79cb23dfc0ca06384ea7`.
- Run #552 / `30165203748`, head `4c243d2a20c2ff555acb79cb23dfc0ca06384ea7`: one unused `Instant` import remained after syntax repair. Fix: `57ed32a3da06e865fbdc955198620ae568ff80b0`.
- Run #554 / `30165498640`, head `57ed32a3da06e865fbdc955198620ae568ff80b0`: V40 migrated correctly, but three upgrade tests still asserted V39. Fix: `6d3915e432fb87ce96ad8f3ac1a27f4c9386b29b`.
- Run #555 / `30165985206`, head `6d3915e432fb87ce96ad8f3ac1a27f4c9386b29b`: one D1 Node boundary still asserted V39, and the new fence dependency changed an accepted M4 bean parameter position. Fix: `1461a83485df0d6e0f9105b76d515122526ffec9`.
- Run #560 / `30186493721`, head `b0b90093a42f5894bc57a50f3da07d4abe0dd143`: Java, PostgreSQL scale, Vben and Mobile succeeded; three new D2 static regular expressions did not match actual implementation spelling/layout. Fix: `5e9b4590a7f50a8183925ee00ac7df14b1da4ea8`.
- Run #562 / `30186890308`, head `5e9b4590a7f50a8183925ee00ac7df14b1da4ea8`: Java, Vben and Mobile succeeded; the final static test asserted a nonexistent error-message literal instead of the real actor-fenced transition call. Fix: `1eedb7ee75060b8c6e1d06bbf8504432ce782462`.

## Security and retained limitations

Permanent evidence proves for D2:

- production code does not read or write Flowable `ACT_*` tables;
- D2 invokes no Flowable migration API;
- no Flowable call runs in a platform database transaction;
- no definition-wide or batch migration exists;
- one attempt remains bound to one exact approval instance;
- no `UNKNOWN` automatic retry exists;
- no force-success or fake rollback exists;
- no runtime-binding mutation exists in D2;
- no public execution Controller, REST route or endpoint exists;
- no Web or Mobile execution control exists;
- no resident scheduler or always-on worker exists;
- execution, worker and automatic reconciliation remain disabled by default;
- audit failure fails closed;
- M5 has no dependency on or modification to M6 code;
- production execution remains disabled and not authorized.

## Decision and stop boundary

M5-D2 is `COMPLETE_PENDING_EXPLICIT_ACCEPTANCE`. This is not an acceptance decision and does not authorize production execution.

M5-D overall remains `IN_PROGRESS`. M5-D3 through M5-D8 are not started. Work stops here before the single-instance Flowable executor, verification, runtime-binding CAS, durable `UNKNOWN` reconciliation, canary runner, management API/UI, M5-F fault injection, M5-G merge readiness, Ready-for-review, auto-merge, merge or issue closure.