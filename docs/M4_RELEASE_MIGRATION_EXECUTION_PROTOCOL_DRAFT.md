# M4 Release Migration Execution Protocol — Design Draft

> **Status: DESIGN ONLY — UNAVAILABLE, DISABLED, FAIL CLOSED, NOT EXPOSED TO BROWSER.**
>
> This document does not authorize or implement process-instance migration. The platform currently exposes only the detect-only migration assessment endpoint. No migration execution endpoint, worker, command adapter, database migration, user-interface action, automatic retry, or rollback operation is available.

## 1. Purpose

This draft defines the minimum enterprise-governance protocol that would be required before a future release-bound process-instance migration capability could be considered.

The protocol is intentionally separated from the current detect-only assessment capability. It establishes future invariants for immutable planning, authorization, bounded execution, verification, reconciliation, and completion evidence without introducing callable production behavior.

## 2. Current boundary

The only currently available migration capability is the non-mutating assessment flow:

1. an authenticated principal submits a governed assessment command;
2. the platform reads tenant-scoped release, deployment, runtime-binding, instance, and task projections through public application ports;
3. the platform produces a deterministic report hash and immutable audit evidence;
4. the platform does not modify instances, tasks, collaboration state, runtime bindings, release lifecycle state, effective release state, deployment evidence, or Flowable runtime state.

A `READY` assessment is evidence that the observed state was eligible at assessment time. It is not authorization to execute a migration and is not an execution plan.

## 3. Non-goals and prohibited implementation

This stage must not provide any of the following:

- an execute migration endpoint;
- a force migration endpoint;
- a rollback migration endpoint;
- a browser-selected internal engine command;
- a migration execution button or confirmation dialog;
- a bulk migration background worker;
- automatic migration retries;
- direct updates to Flowable `ACT_*` tables;
- reliance on Flowable database internals;
- an unbounded database transaction across multiple instances;
- an attempt to represent an external engine call as if it could be rolled back by a platform database transaction;
- mutation of immutable release packages, deployment evidence, assessment reports, runtime-binding source evidence, or historical audit records.

Any future component that is accidentally wired without all protocol gates must fail closed and report the capability as unavailable.

## 4. Canonical protocol phases

A future migration must be decomposed into eight explicit phases. No phase may be skipped or inferred from a browser request.

### 4.1 Assessment

The existing detect-only assessment evaluates source-bound instances against an exact target release. It produces a report hash over tenant-scoped authoritative state.

Assessment remains non-mutating and cannot create execution intent.

### 4.2 Immutable migration plan

A server-side planner would convert one unexpired assessment into an immutable migration plan. The plan would contain only instances that were explicitly `ELIGIBLE` in that assessment and would preserve their expected binding evidence hashes.

The plan must be content-addressed and immutable after creation. Any change to selected instances, release identity, reason, authorization scope, expiry, or expected evidence creates a different plan.

### 4.3 Approval and authorization

A separate enterprise authorization decision must approve the exact immutable plan hash. Assessment permission alone must never imply execution permission.

Authorization must be derived from the authenticated principal and server-side responsibility/capability evidence. The browser cannot provide trusted permissions, tenant identity, operator identity, audit references, or engine authority.

### 4.4 Execution intent

Execution begins only from a durable server-created intent bound to the authorized plan hash. The intent is a request to attempt bounded work; it is not proof that an engine migration occurred.

The intent must have an expiry, bounded batch size, idempotency evidence, and a closed lifecycle. A stale, expired, cancelled, or unauthorized intent must be rejected before any external engine call.

### 4.5 Per-instance migration attempt

Each selected approval instance receives an independent attempt identity. An attempt owns only one approval instance and one expected runtime-binding evidence hash.

No batch-wide transaction may encompass external engine calls. Each attempt uses compare-and-set ownership and a bounded lease so that a stale worker cannot commit an outcome.

### 4.6 Verification

After the engine operation returns, the platform must verify the authoritative engine result through an approved public engine/service API and compare it with the expected target release identity and active task topology.

A successful API response alone is insufficient. Verification evidence must establish the observed engine definition identity and platform runtime-binding state.

### 4.7 Reconciliation

External engine state and platform database state cannot share a single atomic transaction. Any ambiguous, timed-out, or partially persisted outcome enters reconciliation.

Reconciliation must re-read both sides, classify the discrepancy, and either complete the evidence transition or stop for governed operator review. It must never guess, overwrite immutable source evidence, or silently retry without a new bounded attempt.

### 4.8 Immutable completion evidence

Completion evidence records the final per-instance and plan-level result. It is append-only and cannot be used to rewrite the original assessment, plan, release package, deployment evidence, or source runtime binding.

A plan is complete only when every selected instance has a terminal verified or reconciled outcome.

## 5. Immutable migration plan model

A future plan must bind at least the following fields:

| Field | Governance requirement |
| --- | --- |
| `migrationPlanId` | Server-generated stable identity. |
| `tenantId` | Derived from the authenticated principal; never browser-trusted. |
| `definitionKey` | Exact tenant-scoped process definition identity. |
| `sourceReleaseVersion` | Positive release version assessed as the source. |
| `sourcePackageHash` | Immutable source release package SHA-256. |
| `targetReleaseVersion` | Positive release version assessed as the target. |
| `targetPackageHash` | Immutable target release package SHA-256. |
| `assessmentId` | Identity of the detect-only assessment evidence. |
| `assessmentReportHash` | Exact deterministic report hash used to create the plan. |
| `selectedInstanceIds` | Explicit bounded closed set; no query evaluated later. |
| `expectedBindingEvidenceHashes` | One expected immutable binding hash per selected instance. |
| `expectedInstanceStates` | State observed at planning time. |
| `expectedActiveTaskDefinitionKeys` | Canonical active task key set per instance. |
| `operationReason` | NFKC-normalized, bounded, control-character-free reason. |
| `requestedBy` | Authenticated requester identity. |
| `authorizationEvidenceHash` | Server-produced evidence for the exact plan hash. |
| `createdAt` | Server clock timestamp. |
| `expiresAt` | Mandatory bounded validity window. |
| `planHash` | Deterministic hash over the complete canonical plan content. |

The selected instance set must be bounded when the plan is created. A plan cannot mean “all current instances matching this query.”

## 6. Plan and intent lifecycle proposal

The following states are conceptual only and are not implemented:

### 6.1 Plan states

- `PROPOSED`: immutable plan created from an assessment but not authorized;
- `AUTHORIZED`: exact plan hash has valid authorization evidence;
- `EXPIRED`: plan validity window elapsed before execution intent;
- `CANCELLED`: governed cancellation occurred before any new attempt;
- `CONSUMED`: one execution intent was created for the authorized plan.

A consumed, expired, or cancelled plan cannot be reactivated. A new assessment and plan are required.

### 6.2 Execution intent states

- `PENDING`;
- `RUNNING`;
- `RECONCILING`;
- `COMPLETED`;
- `PARTIAL`;
- `FAILED`;
- `CANCELLED`.

Intent state is derived from append-only per-instance outcomes. It does not permit deletion or rewriting of an attempt.

### 6.3 Per-instance attempt states

- `PENDING`;
- `CLAIMED`;
- `ENGINE_REQUESTED`;
- `VERIFYING`;
- `RECONCILING`;
- `SUCCEEDED`;
- `BLOCKED_STALE`;
- `FAILED_RETRYABLE`;
- `FAILED_TERMINAL`.

Retries, if ever enabled, must create a new append-only attempt with an explicit parent attempt reference. They must be capped by policy and cannot be automatic in the current stage.

## 7. Mandatory stale-assessment validation

Immediately before creating an execution intent, and again immediately before each external engine request, the platform must re-read authoritative state and verify all of the following:

- the assessment report exists and is not expired;
- the immutable migration plan exists and its hash is valid;
- authorization applies to the exact plan hash and is not expired or revoked;
- source release identity and source package hash are unchanged;
- target release identity and target package hash are unchanged;
- target deployment evidence remains successfully deployed and matches the target package hash;
- target lifecycle state remains eligible under the future execution policy;
- the approval instance remains in the expected tenant and definition scope;
- instance status is unchanged and still eligible;
- active task definition keys exactly match the planned canonical set;
- runtime binding exists;
- runtime-binding source release and package hash still match the plan;
- runtime-binding evidence hash exactly matches the expected hash;
- no prior successful or in-progress attempt already owns the instance under another plan;
- the execution intent and worker lease are still current.

Any mismatch must stop that instance before an engine mutation and produce `BLOCKED_STALE` evidence. The platform must require a new detect-only assessment; it must not refresh or patch an existing plan in place.

## 8. Authorization invariants

A future execution capability requires a dedicated high-risk capability distinct from release design, lifecycle management, deployment, activation, and assessment.

Authorization must bind:

- authenticated tenant and operator;
- exact plan hash;
- exact selected instance count;
- source and target release identities;
- bounded operation reason;
- request and trace evidence;
- authorization policy/version;
- authorizer identity or governed approval evidence;
- decision timestamp and expiry.

Audit failure must fail closed before intent creation or external engine invocation.

## 9. Bounded execution semantics

A future executor must obey these invariants:

- batch size is bounded by server policy;
- each instance has an independent intent/attempt boundary;
- each claim is a short database transaction;
- external engine calls occur after claim commit;
- lease ownership is compare-and-set protected;
- stale workers cannot record an outcome;
- retry count and backoff are bounded;
- partial success is represented explicitly and is reconcilable;
- immutable source release and deployment evidence are never modified;
- existing instance history is preserved;
- runtime binding changes only after verified engine state and through a governed public platform port;
- no direct Flowable table read or write is permitted;
- no browser request may select an internal engine command or worker identity.

## 10. Engine boundary

Any future engine adapter must use an official supported public runtime/service API or a platform-owned public port backed by that API.

The adapter contract must distinguish:

- request accepted;
- request rejected before mutation;
- mutation confirmed;
- timeout with unknown outcome;
- transport failure with unknown outcome;
- verification mismatch.

Unknown outcomes must enter reconciliation. They must never be classified as rolled back merely because the platform database transaction did not commit.

## 11. Platform evidence and runtime-binding transition

A successful future migration requires two independently verifiable facts:

1. the engine instance is verified against the target engine definition and expected active task topology;
2. the platform runtime binding is transitioned through a governed compare-and-set operation from the exact expected source evidence to exact target evidence.

If engine verification succeeds but platform evidence cannot be committed, reconciliation must retain the verified engine result and retry only the evidence transition under a bounded governed attempt.

If platform evidence appears updated but engine verification fails, the state is inconsistent and must fail closed for reconciliation. The platform must not fabricate deployment or source history to make the records appear consistent.

## 12. Idempotency and duplicate suppression

Idempotency must exist at three levels:

- plan creation: same assessment hash and canonical selection may replay the same plan;
- execution intent: one authorized plan hash may create at most one active intent;
- per-instance external request: each attempt has a stable engine idempotency reference when the engine API supports it.

Reuse of an idempotency key with a different canonical payload must be a conflict. A repeated request cannot create a second engine mutation or duplicate completion evidence.

## 13. Verification and reconciliation evidence

Per-instance evidence must include at least:

- plan hash and attempt identity;
- expected source and target identities;
- expected and observed runtime-binding hashes;
- preflight instance status and task keys;
- engine request reference;
- engine response classification;
- observed engine definition identity;
- observed post-operation task keys;
- platform binding compare-and-set result;
- requestId and traceId;
- worker/lease evidence held only by the server;
- occurredAt timestamps for each phase;
- terminal outcome and bounded failure class.

Sensitive engine details, SQL, stack traces, internal storage paths, and other-tenant identities must never be returned to the browser.

## 14. Audit and metrics proposal

Future audit evidence must be append-only and include the authenticated requester, tenant, operation, bounded reason, request/trace evidence, plan hash, assessment report hash, source/target release identities, selected count, result, and occurredAt.

Metrics must use only closed low-cardinality tags. Candidate metrics are:

- `approval.release.migration.plan` with `operation`, `result`, and `failure_class`;
- `approval.release.migration.execution` with `phase`, `result`, and `failure_class`;
- `approval.release.migration.reconciliation` with `result` and `failure_class`.

Tenant, operator, definition key, release version, instance/task identity, package/report/plan hash, request/trace identity, worker identity, and reason are forbidden metric tags.

## 15. Failure model

Future failures must map to bounded classes such as:

- `INVALID_REQUEST`;
- `NOT_FOUND` without cross-tenant disclosure;
- `UNAUTHORIZED`;
- `PLAN_EXPIRED`;
- `STALE_ASSESSMENT`;
- `STALE_BINDING`;
- `STALE_INSTANCE_STATE`;
- `ENGINE_REJECTED`;
- `ENGINE_OUTCOME_UNKNOWN`;
- `VERIFICATION_MISMATCH`;
- `PLATFORM_EVIDENCE_CONFLICT`;
- `RECONCILIATION_REQUIRED`;
- `INTERNAL`.

Client errors must contain only an error code, bounded message, requestId, traceId, timestamp, retryability, and bounded non-sensitive details.

## 16. Future implementation gates

No production implementation may begin until all of these gates are explicitly approved:

1. immutable plan and authorization evidence models are reviewed;
2. expiry and stale-assessment policies are fixed;
3. the official engine migration API and its outcome semantics are proven;
4. per-instance intent, attempt, lease, and CAS rules are specified;
5. reconciliation ownership and operator procedures are approved;
6. database append-only and immutability constraints are designed;
7. tenant isolation and non-disclosing errors have tests;
8. idempotency and concurrency tests cover duplicate and competing plans;
9. audit failure rollback is proven;
10. low-cardinality metrics and structured errors are permanently bounded;
11. browser and mobile permanent tests prove there is no trusted identity or internal command input;
12. a separate implementation task explicitly authorizes production work.

Until every gate is satisfied, the capability remains **UNAVAILABLE**, **DISABLED**, **FAIL CLOSED**, and **NOT EXPOSED TO BROWSER**.

## 17. Known limitations of this draft

- No engine-specific migration API has been selected or validated.
- No migration plan, intent, attempt, verification, reconciliation, or completion table exists.
- No public or internal execution port exists.
- No retry policy is enabled.
- No execution authorization policy has been approved.
- No UI or API execution surface exists.
- The current platform remains detect-only.
