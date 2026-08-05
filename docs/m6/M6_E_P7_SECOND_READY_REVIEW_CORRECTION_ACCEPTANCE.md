# M6-E P7 Second Ready Review Correction Acceptance

Status: `P7_SECOND_READY_REVIEW_CORRECTION_PENDING_EXACT_VALIDATION`

Date: `2026-08-04`

Tracking:

- workstream: Issue #80;
- parent milestone: Issue #62;
- Pull Request: #83;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- target: `main`;
- exact current `main`: `b20b5cca68bb6b77e7a51233bc2aee3387b21993`;
- exact pre-review documented Head: `e4e4f64c48f89e0067a9114da0575824fdafdde3`;
- exact pre-review permanent Run: `30903214702` / #1195, success;
- controlled current-main rebaseline Merge Commit: `783dbf30be49c69d7e694ec7ff87f3f7b4af0d85`.

PR #83 was marked Ready only after the prior exact gate. The Ready transition triggered a new Codex
Review with two additional actionable P2 findings. PR #83 was immediately returned to Draft. No merge
was performed. Both new threads remain unresolved until the exact correction Head receives complete
permanent workflow and artifact evidence.

## 1. Exact second Ready Review evidence

Review:

- review ID: `PRR_kwDOTbeZ188AAAABIUyBcQ`;
- state: `COMMENTED`;
- reviewer: `chatgpt-codex-connector`;
- submitted at: `2026-08-04T11:20:51Z`;
- reviewed commit: `e4e4f64c48f89e0067a9114da0575824fdafdde3`.

### Finding A — residual 120-character tenant limits

- thread ID: `PRRT_kwDOTbeZ186WSxPZ`;
- comment ID: `PRRC_kwDOTbeZ187dPieQ`;
- severity: `P2`.

The first Review correction raised `AiServerRequestContext.tenantId` to the platform maximum of 128,
but downstream trusted tenant carriers still retained 120-character limits. A valid 121–128 character
tenant could therefore fail while creating the authorized resource, resource-state snapshot, Provider
request or production runtime binding.

Correction:

1. `AiServerRequestContext.tenantId` remains 128;
2. `AiAuthorizedResource.tenantId` is 128;
3. `ApprovalAssistanceContextProjection.ResourceStateSnapshot.tenantId` is 128;
4. `AiProviderRequest.AuthorizedContext.tenantId` is 128;
5. `AiProviderRequest.AuthorizedResource.tenantId` is 128;
6. `OpenAiResponsesProductionRuntimeFactory.bind` accepts 128 and rejects 129;
7. permanent Java tests exercise every trusted carrier at exact 128 and 129;
8. tenant equality, authorization and credential binding remain exact and server owned.

The correction does not truncate, hash-substitute or manufacture a tenant identity. It preserves the
same exact trusted tenant through projection, Provider-safe request construction and production binding.

### Finding B — stale P5 Provider-unavailable read status

- thread ID: `PRRT_kwDOTbeZ186WSxPd`;
- comment ID: `PRRC_kwDOTbeZ187dPieX`;
- severity: `P2`.

The P5 GET contract continued to return `PROVIDER_NOT_CONFIGURED`,
`AI_ASSISTANCE_P6_PROVIDER_REQUIRED` and `NO_ADVISORY_RESULT_AVAILABLE` after P6-E production runtime
activation. PC and Mobile therefore rendered stale limitations even when explicit generation was
available and could display them below a successfully generated advisory.

Correction:

1. `ApprovalAssistanceGenerationService` implements a read-only
   `ApprovalAssistanceRuntimeAvailability` contract;
2. runtime availability is `runtimeFactory.isPresent()`, which was already validated during application
   startup by the P6-E production configuration;
3. the GET controller reads only that boolean and never calls `generate`, `bind`, Secret material,
   DNS, TLS or network code;
4. the read contract exposes the closed states `AVAILABLE` and `PROVIDER_NOT_CONFIGURED`;
5. available runtime uses `AI_ASSISTANCE_AVAILABLE` with
   `EXPLICIT_GENERATION_REQUIRED` and `HUMAN_REVIEW_REQUIRED`;
6. absent runtime uses `AI_ASSISTANCE_PROVIDER_REQUIRED` with
   `PRODUCTION_PROVIDER_NOT_CONFIGURED`, `EXPLICIT_GENERATION_UNAVAILABLE` and
   `HUMAN_REVIEW_REQUIRED`;
7. the stale `NO_ADVISORY_RESULT_AVAILABLE` and `AI_ASSISTANCE_P6_PROVIDER_REQUIRED` values are removed
   from current production/client contracts;
8. GET remains no-store, task-scoped, zero-egress and never returns or manufactures an advisory result;
9. Web and Mobile expose the same closed union and suppress generation unless availability is exact
   `AVAILABLE`.

A read response with `AVAILABLE` means only that the validated server runtime can accept a separate,
explicit POST. It does not claim that a result already exists, does not invoke the Provider and does not
authorize any command.

## 2. Scope closure

This correction changes only:

- trusted tenant length compatibility across existing AI carrier records and runtime binding;
- deterministic 128/129 boundary tests;
- one read-only runtime-availability interface implemented by the existing generation service;
- the GET read contract, controller and tests;
- Web/Mobile closed read types and explicit-generation guards;
- permanent P5 and P7 Review boundaries;
- this append-only acceptance record.

It adds no Provider, model, Prompt, endpoint, Secret source, retry, fallback, redirect, streaming,
Queue, Worker, Scheduler, listener, polling, automation proposal or executable action.

It adds no migration. M6-E continues to own exactly
`V49__create_ai_approval_assistance_durable_evidence.sql`; no V50 or later migration exists.

It adds no workflow. The only automatic PR/main workflow remains
`.github/workflows/approval-platform-validation.yml`.

## 3. Required exact correction gate

Before either second Ready Review thread may be answered and resolved, the final commit containing all
code, tests, clients, document and permanent boundary changes requires:

1. one natural pull-request workflow, attempt 1;
2. successful Maven core, all four Persistence JDBC shards and Maven aggregation;
3. successful Web, Mobile and Repository hygiene;
4. four final artifacts tied to the exact Head and Run;
5. independent local size and SHA-256 equality for every final artifact;
6. recalculated aggregate, AI Core, OpenAI, server and permanent-boundary statistics;
7. current `main` unchanged, or an ordinary Merge Commit rebaseline followed by a new exact Run;
8. PR #83 Open, Draft, mergeable and behind zero;
9. no additional actionable Review, requested change, unresolved thread or disallowed reaction;
10. Issues #80, #62, #13 and #14 Open and Issue #78 Closed / Completed.

After exact evidence, each finding may receive an evidence-backed reply and only its corresponding
thread may be resolved. A complete real-time gate remains mandatory before marking Ready again.

## 4. Permanent authority boundary

The correction preserves:

- `AI_IS_NOT_AN_OPERATOR`;
- every output remains `ADVISORY`, `UNVERIFIED_ADVISORY` and human-reviewed;
- no Provider-to-command, approval mutation or Flowable mutation path;
- no client-selected Provider, model, Prompt, endpoint, policy or Secret;
- one Provider attempt maximum, no retry or fallback;
- no background continuation or milestone M6-F capability;
- no live paid/customer Provider request in CI.

Current decision:

`P7_SECOND_READY_REVIEW_CORRECTION_PENDING_EXACT_VALIDATION`

`PR_83_REMAINS_DRAFT`

`SECOND_READY_REVIEW_THREADS_REMAIN_UNRESOLVED`

`M6_F_REMAINS_GATED`

`AI_IS_NOT_AN_OPERATOR`
