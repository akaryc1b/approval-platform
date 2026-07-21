# M4 Authorization and Responsibility Governance

Status: accepted for M4-B on 2026-07-21.

This record is created once after the M4-B code head passed the permanent repository workflow. It is not a substitute for executable tests and must not be revised after creation. Later M4 phases may reference this record without changing it.

## 1. Stage boundary

M4-B started from the accepted M4-A document head:

- M4-A document head: `31c1fb1be119cae067e90bbfd275f0632b6517a6`;
- M4-A record: `docs/M4_IDENTITY_AND_TENANT_GOVERNANCE.md`;
- M4 branch: `agent/m4-production-readiness-and-enterprise-governance`;
- Draft PR: `#55`, base `main`;
- `main` and M3 baseline at the stage boundary: `c3e45a137315a6d258312fa5bbd75375239777f6`;
- highest Flyway migration: V27;
- Issues `#13` and `#14`: open and unchanged.

M4-B did not add a migration, modify an existing V1–V27 migration, modify an M3 governance document, create another branch or PR, enable auto-merge, mark the PR ready, or merge it.

## 2. M4-B code heads

M4-B consists of three purposeful commits:

1. `ee17972aac27081087f5acc0d4165e4320adc552` — `feat: resolve enterprise management responsibilities`;
2. `29c855875a3038a057a3a25bb5987c57bfcfe5f4` — `feat: audit governed high-risk management access`;
3. `9008ee36a9bdc1ff180051f152a81683e1707127` — `ci: enforce M4 authorization governance`.

The accepted M4-B code head is `9008ee36a9bdc1ff180051f152a81683e1707127`.

## 3. Unified enterprise responsibility model

The platform now has a closed server-side responsibility model rather than per-controller or per-table role logic.

### 3.1 Enterprise roles

`ApprovalEnterpriseRole` defines exactly:

- platform administrator;
- tenant administrator;
- process designer;
- process publisher;
- auditor;
- operations personnel;
- department approval administrator;
- data archive administrator;
- connector administrator;
- approval participant.

These roles are authentication and management-authorization evidence. They are not task assignment, delegation, handover, add-sign, vote, or other approval participation evidence.

### 3.2 Responsibility sources

`ApprovalResponsibilitySourceType` supports trusted assignments originating from:

- person;
- department;
- position;
- role;
- user group.

`ApprovalResponsibilityAssignment` preserves the source identifier, role, resource scope, effective start, effective end and version. Assignments that are not effective at the decision instant are ignored.

The principal accepts a bounded immutable responsibility set. A principal can expose enterprise roles separately from raw authorities; no enterprise role is converted into a task-processing authority.

## 4. Closed capability matrix

`DefaultApprovalResponsibilityResolver` owns the single closed role-to-capability matrix.

- platform administrators and tenant administrators can satisfy the current management capability set within their resource boundary;
- process designers receive read and design;
- process publishers receive read, publish, deploy and activate;
- auditors receive read, audit read, audit export and audit verification;
- operations personnel receive read, consistency read/run and operational failure read/replay;
- department approval administrators receive read and transfer, but only for an explicitly declared matching department resource;
- data archive administrators and connector administrators receive read until their later M4 capabilities are introduced;
- participants receive no management capability.

Every enterprise role is present exactly once in the matrix. Capability values are immutable and duplicate-free. Existing exact direct capabilities and the explicit management-admin authority remain compatible, but they do not create business-task participation.

## 5. Resource scope and department isolation

`ApprovalResource` and `ApprovalResourceScope` establish server-owned platform, tenant and department boundaries.

`@ApprovalManagementPermission` now declares:

- the required management capability;
- tenant or department resource scope;
- the Spring URI-template variable that contains the department identifier when department scope is used.

The management interceptor obtains department identifiers from Spring's resolved URI-template variables. It does not accept an arbitrary department header or query value as trusted scope evidence.

A department administrator:

- can act on a matching declared department resource;
- cannot act on another department;
- cannot use a department assignment for a tenant-wide endpoint;
- cannot cross tenants;
- cannot infer that a resource exists outside the authorized scope.

An invalid or missing department resource declaration fails closed.

`ApprovalManagementEndpointContractTest` scans every management handler and fails when:

- the handler has no explicit management capability;
- a department-scoped declaration omits its path variable;
- a tenant-scoped declaration incorrectly carries a department path variable.

The runtime interceptor also denies an undeclared management handler even when the configurable capability boundary is disabled for a controlled test environment.

## 6. Deterministic decisions and cache governance

`ApprovalAuthorizationDecision` returns only the requested capability decision, matched role and matched scope. It does not return the principal's unrelated authorities or responsibility assignments.

`CachingApprovalResponsibilityResolver` provides:

- a maximum 30-second cache lifetime;
- a maximum of 4,096 decision entries;
- cache keys including tenant, operator, authority fingerprint, responsibility fingerprint, requested capability and resource;
- expiry at an earlier assignment effective-start or effective-end boundary;
- explicit invalidation by tenant/operator;
- full clear support;
- fail-safe bounded eviction by clearing when a new entry would exceed the configured limit.

A changed responsibility fingerprint cannot reuse a previous decision. An assignment expiration cannot remain authorized until the general TTL when its validity boundary occurs sooner.

## 7. High-risk operation governance

The following management capabilities are classified as high risk:

- publish;
- deploy;
- activate;
- transfer;
- audit export;
- audit verification;
- consistency execution;
- operational failure replay.

Before a high-risk controller handler executes, the interceptor requires:

- `X-Approval-Operation-Reason`;
- `Idempotency-Key`;
- the trusted requestId and traceId established by M4-A;
- an allowed server-side authorization decision.

The reason is normalized with Unicode NFKC, must contain 8–512 Unicode code points, and rejects control, line-separator, paragraph-separator and surrogate content. The idempotency key is length- and character-constrained.

The reason is not used as a metric tag and is not written into the management denial log template. It is stored only in governed audit evidence.

## 8. Idempotent authorization audit evidence

`DefaultApprovalManagementGovernanceRecorder` uses the existing platform `IdempotencyGuard` and primary notification-aware audit sink. It appends one versioned audit event before the high-risk handler runs:

- action: `MANAGEMENT_HIGH_RISK_AUTHORIZED`;
- schema: `approval.management-security`;
- schema version: 1;
- aggregate type: `MANAGEMENT_AUTHORIZATION`;
- tenant and operator: authenticated principal values;
- requestId and traceId: trusted M4-A context values;
- attributes: requirement, reason, resource scope, authorization decision, matched role and department identifier when applicable.

The idempotency request hash binds the capability, tenant, resource and sorted audit attributes. A replay with the same key and same facts does not append another authorization event. Reusing the key for different facts produces a stable conflict.

The audit contract requires every management-security attribute. Missing reason or other mandatory evidence cannot create a valid audit event.

High-risk authorization fails closed before the handler when:

- the reason is missing or invalid;
- the idempotency key is missing or invalid;
- the key conflicts with different facts;
- the audit evidence cannot be recorded.

An unavailable audit sink produces a retryable service-unavailable error rather than allowing an unaudited high-risk operation.

## 9. Stable errors and non-disclosure

The management security advice has highest precedence and returns stable structured errors with requestId and retryable status.

Relevant codes include:

| Code | Status | Retryable | Boundary |
| --- | ---: | --- | --- |
| `APPROVAL_AUTHENTICATION_REQUIRED` | 401 | false | no trusted authenticated principal |
| `APPROVAL_MANAGEMENT_PERMISSION_DENIED` | 403 | false | capability or resource scope denied |
| `APPROVAL_OPERATION_REASON_REQUIRED` | 400 | false | high-risk reason absent |
| `APPROVAL_OPERATION_REASON_INVALID` | 400 | false | high-risk reason malformed |
| `APPROVAL_IDEMPOTENCY_KEY_REQUIRED` | 400 | false | high-risk idempotency key absent |
| `APPROVAL_IDEMPOTENCY_KEY_INVALID` | 400 | false | high-risk idempotency key malformed |
| `APPROVAL_IDEMPOTENCY_CONFLICT` | 409 | false | key reused for different governance evidence |
| `APPROVAL_MANAGEMENT_AUDIT_UNAVAILABLE` | 503 | true | authorization evidence could not be appended |

The permission-denied message is generic and does not disclose the caller's other authority, role or group memberships.

## 10. Low-cardinality observability

Management authorization counters use only closed low-cardinality tags:

- requirement;
- outcome;
- decision;
- role;
- resource scope.

Request duration uses requirement and outcome. The following values are not authorization metric tags:

- tenantId;
- operatorId or userId;
- requestId;
- traceId;
- instanceId;
- taskId;
- departmentId;
- reason text.

Denial logs sanitize bounded tenant, operator, requestId and path values. They do not log the submitted high-risk reason or the principal's complete authority/responsibility set.

## 11. Participant and management separation

M4-B preserves these boundaries:

- a platform, tenant or department administrator is not automatically a pending-task assignee;
- a process publisher cannot approve a task merely because it can publish a process version;
- an auditor cannot read private comments merely because it has audit capabilities; existing visibility and audit-query rules still apply;
- a normal participant receives no management capability from the participant role;
- route authority remains a client experience feature and is not a server security boundary;
- existing task, delegation, handover, collaboration and comment access continue to use platform-owned business evidence.

## 12. Permanent static governance

The existing permanent workflow `.github/workflows/approval-platform-validation.yml` continues to be the only validation workflow.

The expanded `scripts/tests/m4-identity-boundary.test.mjs` now has nine permanent checks covering:

1. production principal mode and explicit local/test identity profiles;
2. trusted tenant/operator/permission/correlation wrapping;
3. browser and mobile trusted-permission forgery prevention;
4. exact closed enterprise roles and responsibility source types;
5. management capability and resource declaration scanning;
6. high-risk reason, idempotency and audit evidence;
7. low-cardinality authorization metrics;
8. management-role and task-participation separation;
9. production Flowable internal-table dependency prohibition.

The static checks run in the existing Repository hygiene job. No temporary workflow or patch payload was introduced.

## 13. Automated test evidence

Focused M4-B tests on the accepted code head include:

- `DefaultApprovalResponsibilityResolverTest`: 4 passed;
- `CachingApprovalResponsibilityResolverTest`: 2 passed;
- `ApprovalManagementPermissionInterceptorTest`: 8 passed;
- `ApprovalManagementEndpointContractTest`: 1 passed;
- `ApprovalManagementPermissionCoverageTest`: 1 passed;
- `ApprovalManagementRequirementMatrixTest`: 1 passed;
- `DefaultApprovalManagementGovernanceRecorderTest`: 2 passed;
- `ApprovalManagementSecurityApiExceptionHandlerTest`: 2 passed;
- `ApprovalIdentityContextFilterTest`: 5 passed;
- `ApprovalIdentitySecurityConfigurationTest`: 2 passed;
- `PostgreSqlContainerTest`: 1 passed;
- `AuditEventTest`: 2 passed.

The tests prove exact capability mapping, department isolation, tenant isolation, management/participant separation, assignment expiry, cache invalidation, direct-capability compatibility, reason and idempotency validation, idempotent audit append, conflict handling, audit-contract requirements and stable non-leaking errors.

## 14. Permanent CI evidence for the code head

Accepted M4-B code head: `9008ee36a9bdc1ff180051f152a81683e1707127`.

Permanent workflow run: `29807483781`, run number 272, conclusion `success`.

Jobs:

- Repository hygiene: job `88560958133`, success;
- Java 21 / Maven / PostgreSQL: job `88560958062`, success;
- Vben TypeScript / production build: job `88560958072`, success;
- UniApp TypeScript / H5 / WeChat: job `88560958103`, success.

All job and step states were inspected. The three build artifacts were downloaded and expanded for log verification:

| Artifact | ID | Digest |
| --- | ---: | --- |
| `approval-maven-29807483781` | `8486095811` | `sha256:8a2ac0f346a057d32f76a5463e93ef8f7c12e476bc06811da50436f6f6fee238` |
| `approval-vben-29807483781` | `8486067904` | `sha256:6a18568eaa27a3a8803b6ccd843dd789dd5f16f06f441a1b34c87f48a2b165a4` |
| `approval-mobile-29807483781` | `8486052632` | `sha256:e3daa455a9a88d1e32c37755a334377f744bcb03118fe4dd9eeef9a74837cc02` |

Artifact log verification:

- 16-module Maven reactor: `BUILD SUCCESS`, total time 02:56;
- approval application: 62 tests, 0 failures, 0 errors, 0 skipped;
- `approval-persistence-jdbc`: 147 tests, 0 failures, 0 errors, 0 skipped;
- host SDK: 7 tests, 0 failures, 0 errors, 0 skipped;
- architecture tests: 9 tests, 0 failures, 0 errors, 0 skipped;
- `approval-server`: 38 tests, 0 failures, 0 errors, 0 skipped;
- Vben client boundary: 7 tests passed;
- Vben form renderer: 3 tests passed;
- Vben form designer: 2 tests passed;
- Vben production build: 11 of 11 tasks successful;
- UniApp type-check: passed;
- H5 production build: `DONE Build complete`;
- WeChat Mini Program production build: `DONE Build complete`.

## 15. Accepted M4-B invariants

M4-B is accepted with these invariants:

- management capability and approval participation remain independent;
- all management handlers explicitly declare a capability and a valid server-resolved resource scope;
- the role/capability matrix is centralized, closed and duplicate-free;
- people, departments, positions, roles and user groups can be represented by one responsibility model;
- department administrators cannot cross their department or use a department assignment for tenant-wide authority;
- responsibility validity and cache invalidation cannot leave stale authorization indefinitely;
- high-risk operations require a safe reason and idempotency key;
- high-risk authorization evidence is appended before execution and cannot be silently skipped;
- authorization audit failure prevents the high-risk handler from running;
- authorization metrics use only low-cardinality tags;
- denial errors do not expose unrelated authority or responsibility evidence;
- browser and mobile route authority cannot bypass the server decision;
- production code and migrations remain free of Flowable internal-table dependencies;
- M3 baseline tests, PostgreSQL integration tests and all clients remain green.

M4-B does not mark PR `#55` ready and does not authorize merge. The PR remains open and Draft for subsequent M4 stages.
