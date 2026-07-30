# M5-D1 — Authorized Plan Admission Permanent Evidence

## Governance result

- M5-D overall state: `IN_PROGRESS`
- M5-D1 implementation slice: `PERMANENTLY_VALIDATED`
- Production migration execution: `NOT_AUTHORIZED`
- PR #58 remains Open + Draft.
- Issues #13, #14 and #56 remain Open.
- M5-E, M5-F and M5-G remain not started.

This evidence accepts only the server-side D1 bridge from one exact authorized immutable plan to one M5-B execution intent. It does not authorize an executor, worker, scheduler, Flowable production invocation, runtime-binding mutation, public endpoint, Web control, Mobile control or production migration.

## Validated head

- implementation head: `31b58e50b1b191f46f9ec8a7ffd05201f514df52`
- branch: `agent/m5-governed-process-instance-migration`
- base at validation: `d769722cf7dd5418739a91ad4c45ca1a1c147502`
- Flyway latest: `V39`
- active M6 pull requests #67, #68, #69 and #70 contained no Flyway migration when each M5 ref was updated.

## Validated D1 scope

The permanent implementation provides:

- exact tenant-scoped authorized-plan lookup by plan ID and plan hash;
- unexpired plan and authorization checks;
- current source/target release-package evidence checks;
- exact active target release and deployed engine identity checks;
- deterministic server-owned admission request hash;
- one atomic short PostgreSQL transaction for initial `PENDING` intent/event, immutable consumption evidence, `AUTHORIZED revision 2 -> CONSUMED revision 3`, plan event and audit;
- exact replay returning the authoritative existing plan, intent and consumption;
- changed-payload idempotency conflict;
- concurrent admission single winner and one authoritative replay;
- deferred bidirectional plan/intent/consumption linkage;
- append-only consumption evidence and direct tamper rejection;
- fresh, historical and explicit `V38 -> V39` upgrade paths;
- 5,000 approval-instance/task upgrade preservation;
- proof that upgrade creates no plan consumption or intent and changes no runtime binding.

D1 creates no attempt, claims no worker lease, invokes no Flowable API and changes no runtime binding.

## Permanent success run

- workflow: `.github/workflows/approval-platform-validation.yml`
- run ID: `30154773430`
- run number: `#542`
- head: `31b58e50b1b191f46f9ec8a7ffd05201f514df52`
- conclusion: `success`
- all four raw job logs were read.

| Job | Job ID | Conclusion |
| --- | ---: | --- |
| Repository hygiene | `89670912883` | success |
| UniApp TypeScript / H5 / WeChat | `89670912902` | success |
| Vben TypeScript / production build | `89670912906` | success |
| Java 21 / Maven / PostgreSQL | `89670912908` | success |

## Test and build totals

- Maven aggregate: 574 tests, 0 failures, 0 errors, 0 skipped
- approval-domain: 35/35
- approval-engine-flowable: 40/40
- approval-application: 137/137
- approval-persistence-jdbc: 241/241
- approval-definition-compiler: 11/11
- approval-connector-spi: 3/3
- approval-integration-core: 12/12
- generic connector: 6/6
- approval-integration-jdbc: 4/4
- host SDK: 7/7
- architecture tests: 9/9
- server tests: 64/64
- example tests: 5/5
- M5 permanent Node boundaries: 40/40
- M4 SLA/calendar boundaries: 13/13
- M4 release-governance boundaries: 5/5
- Vben type-check and production build: success
- UniApp type-check, H5 build and WeChat Mini Program build: success
- Maven reactor: `BUILD SUCCESS`

## Artifact integrity

Each artifact ZIP was downloaded and its local SHA-256 exactly matched the GitHub artifact digest.

| Artifact | ID | GitHub digest / downloaded ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30154773430` | `8618718273` | `78995bb084b1bf36f9e673e968464b900887df7fe52c904c12d0b3db2b5de302` — exact match |
| `approval-vben-30154773430` | `8618680962` | `7c046c19906efed36c234e59f9b6348bae8f329eddf3d0c5ba12b9f38a725808` — exact match |
| `approval-mobile-30154773430` | `8618675249` | `1bb26f2e8d84962d2c2bf85404fe4adfbe2d10ac95540f840066f8cd643fe1e6` — exact match |
| `approval-hygiene-30154773430` | `8618668014` | `777d54e6e541f78d4cddf2268cdce381998831258074379bf79afae3f12cf1c4` — exact match |

## Retained failed-run lineage

No failed run was cancelled, hidden or rerun. Every failed run's four jobs and raw logs were inspected, and all four artifact ZIPs were downloaded with exact digest matches.

### Run #539 — stale latest-version assertions

- run ID: `30154020939`
- head: `eb5574fb1299a9f403302e313e956ec7a260b68c`
- Java failed because three historical upgrade tests still asserted latest Flyway version `V38` after V39 was correctly applied.
- Hygiene, Vben and Mobile succeeded.
- fix: `f9b0fdcd084965a3426ad3e6871eeff0c80d5850` updated the upgrade matrix and added D1 evidence tests.

| Artifact | ID | GitHub digest / downloaded ZIP SHA-256 |
| --- | ---: | --- |
| Maven | `8618513242` | `ffd0aa74c2524856e83168a66974c9c6903b4158b35480ef18c47015fb956314` — exact match |
| Vben | `8618478189` | `3348069dec7c61c776678513ed7687c0849e2621c065f8df5244ef850a65ec68` — exact match |
| Mobile | `8618474416` | `7d52afae89e36913eaf51084aefcbd58b24a8f3270052a3be044dbd25e2f89ba` — exact match |
| Hygiene | `8618468678` | `e5c99a49ecbf9b6267970786dea4b4d5178a680341fa36de66968cb22645f0b4` — exact match |

### Run #540 — plan-event authorization evidence

- run ID: `30154264805`
- head: `f9b0fdcd084965a3426ad3e6871eeff0c80d5850`
- Java failed because the M5-C plan-event constructor retained authorization evidence only for `PROPOSED -> AUTHORIZED`, rejecting the already-reserved legal `AUTHORIZED -> CONSUMED` transition.
- Hygiene, Vben and Mobile succeeded.
- fix: `39b2c62ba513d9f7f5a214f8b96638aab7674268` preserved exact authorization evidence on the consumption transition and added domain tests.

| Artifact | ID | GitHub digest / downloaded ZIP SHA-256 |
| --- | ---: | --- |
| Maven | `8618538402` | `89a6ff067c6fdb33b5f5c05b7db2c2009e4754bd04626685512c451122cbf563` — exact match |
| Vben | `8618542833` | `c978bee87aff0931f3eaeaa917a8dbb98e1012015e4e6b84c53d00243d3904d6` — exact match |
| Mobile | `8618536029` | `08607869fac5776e89cb58729546a7c92c8a24e58ed6c8e3e2b500a6b19d9462` — exact match |
| Hygiene | `8618530978` | `e740f6dff3d782b4580f87e38b9b043b3a697da52ea5c0015458f2b2484ac4cd` — exact match |

### Run #541 — authoritative replay ordering

- run ID: `30154483465`
- head: `39b2c62ba513d9f7f5a214f8b96638aab7674268`
- Java failed because exact replay checked the current `AUTHORIZED` read gate before the admission store could return the authoritative already-`CONSUMED` result.
- Hygiene, Vben and Mobile succeeded.
- fix: `31b58e50b1b191f46f9ec8a7ffd05201f514df52` added an exact server-owned replay query before first-admission authorization validation. Initial consumption still requires current authorization and exact release/deployment evidence.

| Artifact | ID | GitHub digest / downloaded ZIP SHA-256 |
| --- | ---: | --- |
| Maven | `8618595954` | `4c2e482884f03d29176c434a6b1773e288e7dc952f561a060a13984176ff0ccc` — exact match |
| Vben | `8618599616` | `1b32ded17f4f55ca14e0647c2801ace754d562cc9c095b4e4deadbd5752786d1` — exact match |
| Mobile | `8618593350` | `45c0db68ebfb00e3a621d0ee3898da112c503387a9bcb6735fc7a2c032401400` — exact match |
| Hygiene | `8618587742` | `145eda7f2b4d23b524f57c92537a956235aa7c33dcff368915193a6f678244d8` — exact match |

## Security and execution boundary

Permanent evidence proves for D1:

- no `ACT_*` access;
- no Flowable migration invocation;
- no definition-wide or batch migration;
- no migration worker, scheduler or automatic claim;
- no `UNKNOWN` retry;
- no fake rollback;
- no runtime-binding mutation;
- no public execution Controller or endpoint;
- no Web or Mobile execution control;
- no M6 dependency or modification;
- production execution remains disabled and not authorized.

## Next slice

M5-D2 may add the shared tenant/instance command fence, bounded claim service, durable lease and stale-owner fencing. It must not invoke Flowable, add a public API, enable a resident scheduler, authorize production execution or enter M5-E/F/G.
