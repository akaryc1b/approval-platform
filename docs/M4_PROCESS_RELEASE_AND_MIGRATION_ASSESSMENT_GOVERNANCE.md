# M4 Process Release and Migration Assessment Governance

## Acceptance status

This document records the formal M4-E/F governance acceptance of process release lifecycle management, immutable runtime binding, and detect-only release migration assessment.

- Accepted code head: `11bb380c92451b7f7ccd577287034f93d8759309`
- Permanent workflow: `.github/workflows/approval-platform-validation.yml`
- Accepted code-head workflow run: `29985539220`
- Pull request: `#55`
- Source branch: `agent/m4-production-readiness-and-enterprise-governance`
- Target branch: `main`
- Target branch commit at acceptance: `72b1f051312e8e4994311e480b55e326df36648d`
- Pull request state required after acceptance: **Open + Draft**
- Highest Flyway migration: `V32`

The accepted capability is limited to release lifecycle governance, exact release-bound instance behavior, and non-mutating migration assessment. Process-instance migration execution is not available.

## 1. Scope

The accepted scope includes:

- tenant-scoped process release lifecycle evidence;
- immutable Approval Release Packages;
- immutable release deployment evidence;
- tenant-scoped effective release selection;
- exact runtime binding for release-bound instances;
- release-bound start enforcement;
- replay validation and fail-closed runtime binding behavior;
- detect-only migration assessment for running and terminal instances;
- deterministic assessment report evidence;
- independent Web governance reporting for migration dry-run;
- PostgreSQL transaction and non-mutation proofs;
- structured release-governance errors;
- low-cardinality lifecycle, assessment, and runtime-binding metrics;
- permanent client, server, migration, Flowable, and governance boundaries;
- a design-only future migration execution protocol that remains unavailable.

The accepted scope does not include:

- an instance migration execution endpoint;
- force migration;
- migration rollback;
- browser-selected engine commands;
- automatic migration retries;
- a bulk migration worker;
- direct Flowable internal table access;
- mutation of release package or deployment evidence;
- modification of an assessment report into an execution plan.

## 2. Governance invariants

The following invariants are mandatory:

1. Tenant and operator identity originate from the authenticated server principal.
2. Browser and Mobile clients cannot nominate trusted tenant, operator, permission, authority, audit reference, worker identity, lease owner, or engine identity.
3. All release lifecycle mutation commands require a bounded operation reason and governed idempotency evidence.
4. Operation reasons are NFKC-normalized, contain 8–512 Unicode code points, and reject control, line-separator, paragraph-separator, and surrogate characters.
5. Audit evidence must succeed before a governed lifecycle or assessment operation can complete.
6. Cross-tenant resource access returns non-disclosing not-found behavior.
7. Release packages, deployment evidence, lifecycle history, runtime bindings, and completed audit evidence remain immutable according to their database and domain constraints.
8. A tenant and definition can have at most one `ACTIVE` process release.
9. New release-bound instances can start only from the tenant's exact `ACTIVE` effective release.
10. Existing instances retain the release evidence to which they were originally bound.
11. A release-bound instance with missing or conflicting runtime binding evidence fails closed.
12. Migration assessment produces evidence only and cannot modify process, task, collaboration, binding, lifecycle, deployment, effective-release, or engine runtime state.
13. Production source code cannot read or write Flowable `ACT_*` tables.
14. Metrics use only closed, low-cardinality tag sets.
15. Real migration execution remains unavailable, disabled, fail-closed, and not exposed to browser or Mobile clients.

## 3. Process release lifecycle

### 3.1 Lifecycle states

The lifecycle is a closed state machine:

- `DRAFT`
- `PUBLISHED`
- `ACTIVE`
- `DEPRECATED`
- `RETIRED`

`DRAFT` is represented before immutable release publication. Persisted lifecycle evidence begins with the governed `DRAFT -> PUBLISHED` transition.

### 3.2 Permitted transitions

Normal forward progression is:

- `DRAFT -> PUBLISHED`
- `PUBLISHED -> ACTIVE`
- `ACTIVE -> DEPRECATED`
- `DEPRECATED -> RETIRED`

Governed alternatives are:

- `PUBLISHED -> RETIRED` for a published release that is intentionally retired without activation;
- `DEPRECATED -> ACTIVE` only through the explicit governed rollback/reactivation path for a previously active release.

`RETIRED` is terminal.

The model rejects, among others:

- `DRAFT -> ACTIVE`;
- `DRAFT -> RETIRED`;
- `ACTIVE -> RETIRED` without deprecation;
- `RETIRED -> ACTIVE`;
- same-state mutation presented as a new transition;
- mismatched tenant, definition, release version, package hash, revision, or transition time evidence.

### 3.3 Single active release

PostgreSQL partial uniqueness on tenant and definition prevents multiple `ACTIVE` lifecycle rows. Application services also serialize lifecycle changes with tenant-definition-scoped advisory locking and revision checks.

Activation switches the current release atomically within the platform database transaction:

1. append governed audit evidence;
2. deprecate the prior active lifecycle when present;
3. activate the target lifecycle;
4. update exact effective-release projection and activation history;
5. complete command idempotency evidence.

Failure in deployment validation, lifecycle compare-and-set, effective-release update, audit persistence, or idempotency completion rolls back platform-side changes.

## 4. Immutable release package evidence

An Approval Release Package binds exact versions and hashes for:

- process definition;
- form package;
- form schema;
- UI schema;
- compiler;
- compiled artifact;
- BPMN artifact;
- optional DMN artifact;
- deployment metadata;
- source draft;
- publishing operator and timestamp.

The package hash is a lowercase SHA-256 value over canonical release content. Once published, package content is immutable.

Lifecycle state does not rewrite package content. `PUBLISHED`, `ACTIVE`, `DEPRECATED`, and `RETIRED` all retain the original package identity.

Package hash possession does not grant cross-tenant access. All lookups include tenant, definition key, and release version.

## 5. Deployment evidence

Release deployment is represented by a platform-owned tenant-scoped projection containing:

- immutable release package identity;
- deployment status;
- bounded attempt count;
- exact engine deployment identity;
- exact engine definition identity;
- exact engine version;
- requester and timestamps;
- bounded failure evidence when deployment fails.

Only successfully deployed evidence with a package hash and release identity matching the immutable Release Package is eligible for activation or a `READY` migration assessment.

Deployment identity does not grant cross-tenant visibility. Deployment evidence cannot be modified to impersonate a different tenant or package.

## 6. Effective release

The effective release is the tenant-scoped mutable selection used for ordinary new starts. Its activation history is immutable.

The effective projection binds:

- exact release version and package hash;
- definition, form package, form schema, and UI schema versions and hashes;
- compiler and artifact hashes;
- engine deployment, definition, and version identity;
- prior release version;
- revision;
- authenticated operator, reason, request, trace, and timestamp evidence.

Only an `ACTIVE` lifecycle release may be selected as effective for ordinary starts.

Switching the effective release does not modify existing instance bindings.

## 7. Runtime binding

### 7.1 Binding model

Each release-bound approval instance has immutable tenant-scoped runtime binding evidence containing:

- approval instance and business key;
- engine instance identity;
- definition and release identity;
- release package hash;
- definition, form package, form, and UI schema versions and hashes;
- compiler and artifact hashes;
- deployment and engine definition identity;
- deterministic binding evidence hash;
- authenticated binding operator, request, trace, audit reference, and timestamp.

The binding is created from authoritative server-side release and deployment evidence. The browser cannot provide it.

### 7.2 Transaction boundary

For a release-bound start, runtime binding, approval instance, tasks, start audit, and idempotency command evidence participate in the same platform database transaction.

PostgreSQL integration tests prove both complete commit and complete rollback. A failure after engine invocation cannot be represented as an engine rollback; platform records instead preserve the explicit external-engine outcome boundary.

### 7.3 Start enforcement

Ordinary new starts resolve the tenant's current effective release and validate that:

- lifecycle is `ACTIVE`;
- package and deployment evidence match;
- engine definition identity is exact;
- all authoritative release metadata is available;
- runtime binding can be committed with instance, task, audit, and idempotency evidence.

`DEPRECATED` and `RETIRED` releases do not accept ordinary new starts.

### 7.4 Replay and read enforcement

Existing release-bound instances retain their original binding when the effective release changes.

Replay and platform reads validate the binding against the platform instance projection. Missing binding or any identity/hash/version mismatch raises a fail-closed projection conflict.

Legacy instances without release identity remain readable for compatibility, but cannot fabricate release package or binding evidence.

### 7.5 Runtime binding metric

Runtime binding validation records:

`approval.runtime.binding.validation`

Allowed tags are only:

- `result`: `success`, `failure`, or `not_required`;
- `failure_class`: `none`, `missing_binding`, or `evidence_mismatch`.

Tenant, operator, definition, release version, instance, task, package hash, binding hash, request, trace, and reason are forbidden metric tags.

## 8. Detect-only migration assessment

### 8.1 Endpoint

The only callable migration-related server operation is:

`POST /api/approval/version-management/{definitionKey}/releases/{sourceReleaseVersion}/migration-dry-run`

The request body is a closed object containing only:

- `targetReleaseVersion`;
- optional `limit`;
- optional `offset`.

The operation reason is carried only in `X-Approval-Operation-Reason` through governed command headers.

The client cannot submit tenant, operator, user, authority, audit-chain reference, package hash, source tenant, engine identity, or migration command fields.

### 8.2 Assessment statuses

Report status is one of:

- `READY`;
- `BLOCKED`;
- `NO_IN_FLIGHT`;
- `PARTIAL`.

Per-instance decision is one of:

- `ELIGIBLE`;
- `BLOCKED`;
- `TERMINAL_SKIPPED`.

### 8.3 Evidence evaluated

Assessment reads only public application ports and platform-owned records for:

- source and target lifecycle;
- source and target immutable packages;
- target deployment readiness;
- source and target published definition topology;
- source-bound runtime bindings;
- tenant-scoped instance projections;
- active task projections.

It does not inspect Flowable internal database tables.

### 8.4 Fail-closed findings

Blocking evidence includes, among other conditions:

- missing source or target release/package evidence;
- target deployment unavailable or package mismatch;
- ineligible target lifecycle state;
- missing runtime binding;
- source binding mismatch;
- missing or mismatched platform instance projection;
- active task transition in progress;
- active task definition key absent from the target definition;
- incomplete paging when a complete report is required.

Terminal instances are returned as `TERMINAL_SKIPPED` unless their evidence is itself inconsistent.

### 8.5 Deterministic report evidence

The report hash covers canonical authoritative state including:

- tenant and definition scope;
- source and target release identities and package hashes;
- lifecycle states;
- total binding count and paging;
- structural high-impact count;
- canonical global findings;
- canonical per-instance decisions, active task keys, findings, and binding evidence.

The assessment identifier and assessment time are not included in the deterministic report hash. Identical authoritative state produces the same report hash; authoritative state changes produce a different hash.

A report hash is evidence of observed state, not authorization or an executable migration plan.

## 9. Web governance report

The Web client provides a separate governance page:

`/approval/versions/migration-dry-run`

The route requires the host authority hint:

`approval:definition:design`

The page explicitly states:

> 仅生成评估证据，不执行实例迁移

It provides only report-generation, reset, report-hash copy, and assessment-summary copy/export behavior.

It does not provide execute, confirm, force, apply, skip-blocker, instance mutation, or rebinding actions.

The report displays:

- detect-only and completeness evidence;
- summary counts;
- source and target release/package/lifecycle evidence;
- global findings;
- per-instance decisions, active task keys, findings, and binding evidence hash;
- assessment identifier, full copyable report hash, assessed time, and paging evidence.

The API client uses the shared governed approval transport and structured error tracing. Definition keys are URL-encoded and all numeric and reason inputs are validated before request creation.

## 10. Non-mutating proof

### 10.1 Application proof

Application tests cover:

- blank definition key;
- invalid source/target versions;
- equal source and target versions;
- invalid limit and offset;
- short, long, control-character, and NFKC-normalized reasons;
- cross-tenant lookup;
- missing release/package/deployment evidence;
- `NO_IN_FLIGHT`, `READY`, `BLOCKED`, and `PARTIAL` results;
- terminal skipped instances;
- missing and mismatched runtime binding;
- missing target task nodes;
- task transitions in progress;
- mixed multi-instance results;
- exact counts and high-impact changes;
- deterministic report hash and authoritative-state change detection.

### 10.2 PostgreSQL proof

PostgreSQL integration tests take ordered snapshots before and after assessment for business and governance tables, including:

- approval instance;
- task;
- collaboration;
- runtime binding;
- release package;
- deployment evidence;
- lifecycle and lifecycle history;
- effective release and activation history.

A successful assessment leaves those rows and key fields unchanged. Only the explicitly allowed audit-chain and idempotency evidence is committed.

When audit persistence fails, assessment audit and idempotency evidence roll back and business state remains unchanged.

This proof is stronger than merely asserting that no migration method was called.

## 11. Lifecycle transaction and concurrency proof

PostgreSQL integration tests prove:

- lifecycle switch, effective release, activation history, audit, and nested idempotency evidence commit together;
- missing deployment evidence rolls back lifecycle, effective-release, audit, and idempotency changes;
- concurrent activation of competing target releases yields one governed winner;
- the losing transaction produces no partial lifecycle, activation, audit, or idempotency evidence;
- exactly one `ACTIVE` lifecycle and one effective release remain;
- deprecation commits lifecycle transition and effective projection removal together;
- failed effective-release clear rolls back lifecycle, audit, and idempotency evidence;
- retirement retains immutable package and runtime-binding evidence.

Application tests additionally cover duplicate replay, conflicting idempotency payload, semantic replay, stale revision, invalid transition, tenant boundary, and audit failure behavior.

## 12. Tenant isolation

Tenant is present in all authoritative release, deployment, lifecycle, effective-release, runtime-binding, assessment, audit, and idempotency lookups.

The accepted proofs establish:

- different tenants may use the same definition key;
- release version uniqueness is tenant-scoped;
- package hash does not grant cross-tenant visibility;
- deployment identity does not grant cross-tenant visibility;
- runtime binding lookup outside the tenant returns non-disclosing absence;
- assessment cannot count or expose another tenant's instance or hash evidence;
- effective-release foreign keys reject cross-tenant release identity;
- clients cannot manufacture trusted tenant or operator headers.

## 13. Audit evidence

Lifecycle and assessment audit evidence contains, as applicable:

- authenticated tenant and operator;
- operation;
- bounded normalized reason;
- requestId and traceId;
- source and target release identity;
- package hashes;
- prior/current lifecycle evidence;
- result status and counts;
- report hash for migration assessment;
- occurredAt.

The browser cannot provide `auditChainReference`.

Audit failure is fail-closed and participates in the command transaction.

## 14. Structured errors

Release lifecycle and migration assessment responses are normalized to:

- `errorCode`;
- bounded `message`;
- `retryable`;
- `requestId`;
- `traceId`;
- `timestamp`;
- bounded non-sensitive `details`.

Request and trace evidence is taken from trusted server request context when available and is bounded before response use.

Errors do not expose SQL, Java stack traces, Flowable internal table names, deployment secrets, package storage paths, or another tenant's resource identity.

## 15. Metrics

### 15.1 Lifecycle operation

`approval.release.lifecycle.operation`

Allowed tags:

- `operation`: `publish`, `activate`, `rollback`, `deprecate`, `retire`;
- `result`: `success`, `failure`;
- `failure_class`: closed bounded classes or `none`.

### 15.2 Migration assessment

`approval.release.migration.assessment`

Allowed tags:

- `status`: closed assessment status or `unknown` on failure;
- `completeness`: `complete`, `partial`, or `unknown`;
- `result`: `success`, `failure`.

### 15.3 Runtime binding validation

`approval.runtime.binding.validation`

Allowed tags:

- `result`;
- `failure_class`.

The permanent boundary prohibits high-cardinality tenant, operator, definition, release, instance, task, hash, request, trace, worker, or reason tags.

## 16. Future migration execution protocol

The design-only protocol is recorded in:

`docs/M4_RELEASE_MIGRATION_EXECUTION_PROTOCOL_DRAFT.md`

Its status is explicitly:

- unavailable;
- disabled;
- fail closed;
- not exposed to browser.

Any future implementation must separate:

1. assessment;
2. immutable migration plan;
3. approval and authorization;
4. execution intent;
5. per-instance attempt;
6. verification;
7. reconciliation;
8. immutable completion evidence.

Execution must revalidate report expiry, release and package identity, deployment evidence, instance state, active task keys, runtime-binding evidence hash, authorization, plan hash, intent, and lease immediately before mutation.

Any stale evidence requires a new assessment.

External engine calls must use an official public runtime/service API or platform public port. They cannot be represented as part of a database rollback. Unknown outcomes enter reconciliation.

No execution port, endpoint, worker, retry process, or UI action exists in the accepted code.

## 17. Permanent validation matrix

Accepted code-head run `29985539220` executed the only permanent workflow and completed all four jobs successfully.

### 17.1 Java 21 / Maven / PostgreSQL

- 16-module reactor: `BUILD SUCCESS`
- Aggregate tests: `477`
- Failures: `0`
- Errors: `0`
- Skips: `0`
- Approval application: `125`
- Approval persistence JDBC: `199`
- Approval server: `64`
- Migration assessment application: `6/6`
- Migration assessment PostgreSQL: `2/2`
- Lifecycle activation PostgreSQL transaction/concurrency: `3/3`
- Runtime binding enforcement and metric outcomes: `4/4`
- Runtime binding production configuration: `1/1`
- Migration upgrade: `6/6`

### 17.2 Vben

- Client governance boundary: `10/10`
- TypeScript/Vue type-check: success
- Production pipeline tasks: `11/11`
- Production bundle: success

### 17.3 Mobile

- UniApp type-check: success
- H5 build: success
- WeChat Mini Program build: success
- No management migration execution endpoint: confirmed by permanent boundary

### 17.4 Hygiene and permanent boundaries

- M4 release governance boundary: `4/4`
- Detect-only migration route only: confirmed
- Low-cardinality metric tags: confirmed
- Future migration protocol remains design-only and fail-closed: confirmed
- M4 identity and Flowable boundary: success
- M4 SLA/calendar boundary: success
- Frozen governance document hashes: success
- Production Flowable internal table references: absent

## 18. Accepted artifact evidence

Artifacts were downloaded, SHA-256 checked against GitHub artifact digests, unpacked, and their original logs read.

| Job | Artifact ID | SHA-256 |
| --- | ---: | --- |
| Java 21 / Maven / PostgreSQL | `8554936049` | `263294a37fe71bc970140812f49bde712a7b80140b8e9817c8f917d61902e171` |
| Vben TypeScript / production build | `8554862415` | `164b5e5759d7383a6d376c1197ce8a7e4a289a6f190244dd3278439b0d55ae47` |
| UniApp TypeScript / H5 / WeChat | `8554851847` | `581030e11e46c16d2664f6561c968a7c8b9def3395e6e685037914d73323e830` |
| Repository hygiene | `8554837987` | `2095c85929bea06b2911b73c7fa654b1f9d31a802aaf3aa87d014e975992b0f1` |

## 19. Accepted implementation lineage

Key commits after the accepted M4-E/F baseline include:

- `d7afb7ba08c54989522fd85984c42d037475dfed` — detect-only Web migration assessment;
- `1dad930db59cff8f11cb033dcff0adf1a9b852e1` — assessment result matrix and PostgreSQL non-mutation proof;
- `2327dda424e06c026de4498914354051a5c0b003` — lifecycle PostgreSQL transaction and concurrency proof;
- `9bdc352733f6676d6c48aed0b7a98f2de0206582` — exact publish-time fixture correction;
- `c51a35df36258c9e20ee412c4570ae9bb2cdf579` — release governance structured errors and metrics;
- `0a72989032cdba23583e682edf77a34bf09a95df` — response-header test flush correction;
- `5739c7f3f20a56df9209c8a2b5c2af05c56c9cd9` — disabled migration execution protocol draft;
- `066e75e13426ca609ef8d24b17736df21bf88b93` — HTTP migration path boundary correction;
- `11bb380c92451b7f7ccd577287034f93d8759309` — runtime binding validation metric.

All branch ref updates were non-force fast-forwards.

## 20. Frozen prior governance lineage

The following previously accepted documents remain frozen:

- `docs/M3_FINAL_ACCEPTANCE.md` — `459c684027e4a08f08655bff3e31721912dc35bc`;
- `docs/M4_IDENTITY_AND_TENANT_GOVERNANCE.md` — `716ecf6503aeaea7a6dbfa5980964a5c4b983619`;
- `docs/M4_AUTHORIZATION_AND_RESPONSIBILITY_GOVERNANCE.md` — `888f07df905726cfb3507d2ae495db3247d6c4fe`;
- `docs/M4_SLA_AND_CALENDAR_GOVERNANCE.md` — `beb098bc6b4ee68c6ca11da0678a76780b72a049`;
- `docs/M4_SLA_EXECUTION_AND_REPLAY_GOVERNANCE.md` — `dc687d073e0352e0b88d96bd8df0f4ee36775b6e`.

Flyway migrations V28, V29, V30, V31, and V32 were not modified during this acceptance phase.

## 21. Known limitations

- Migration assessment reports are generated on demand and are not executable plans.
- A report can become stale immediately after assessment; future execution must re-assess and revalidate.
- No process-instance migration execution capability exists.
- No migration plan, execution intent, attempt, verification, reconciliation, or completion persistence model exists.
- No engine-specific public migration API has been selected or proven.
- External engine calls cannot share the platform database transaction.
- Legacy instances may remain without release binding, but cannot claim release-bound evidence.
- The Web page is an administrative evidence report, not an operational migration console.
- The protocol draft is not production authorization.

## 22. Future implementation prerequisites

A future migration implementation requires explicit approval and a separate implementation task. Before any production code is introduced, it must establish:

1. immutable migration plan and authorization evidence models;
2. assessment and plan expiry policy;
3. exact stale-state revalidation rules;
4. official engine public migration API semantics;
5. bounded per-instance intent, attempt, lease, and compare-and-set behavior;
6. append-only verification and reconciliation evidence;
7. independent handling of unknown external engine outcomes;
8. capped retry policy, if retries are approved;
9. tenant isolation and non-disclosing failure tests;
10. audit-failure rollback proof;
11. low-cardinality metrics and structured error boundaries;
12. permanent browser and Mobile tests that reject trusted identity and engine commands;
13. a separate UI design that cannot bypass authorization or blockers;
14. a new migration and upgrade test matrix;
15. formal governance acceptance before enabling any endpoint or worker.

Until those prerequisites are met, migration execution remains unavailable and fail-closed.

## 23. Final acceptance statement

At accepted code head `11bb380c92451b7f7ccd577287034f93d8759309`:

- process release lifecycle governance is tenant-scoped and evidence-producing;
- release packages and deployment evidence are immutable;
- effective release switching is transactional and concurrency-safe;
- release-bound starts and replay are exact and fail closed;
- migration assessment is detect-only and has PostgreSQL non-mutation proof;
- Web reporting is independent and contains no execution action;
- audit failure remains fail closed;
- structured errors preserve bounded request and trace evidence;
- lifecycle, assessment, and runtime-binding metrics remain low-cardinality;
- no production Flowable internal-table dependency exists;
- real migration execution is not exposed or implemented;
- the permanent CI matrix is fully green;
- prior frozen governance documents and V28–V32 remain unchanged;
- PR #55 must remain Open + Draft until an explicit later instruction changes that state.
