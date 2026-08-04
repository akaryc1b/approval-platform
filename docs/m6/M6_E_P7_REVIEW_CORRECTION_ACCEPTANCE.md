# M6-E P7 Actionable Review Correction Acceptance

Status: `P7_REVIEW_CORRECTION_PENDING_EXACT_VALIDATION`

Date: `2026-08-04`

Tracking:

- workstream: Issue #80;
- parent milestone: Issue #62;
- Pull Request: #83;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- target: `main`;
- exact pre-review candidate Head: `c762c2161d1ee63e4db323c21396950968dcda71`;
- exact pre-review permanent Run: `30900381660` / #1179, all four jobs success;
- current `main` at review discovery: `ff736dee3b02c6a9f087d92b2a176d9af2724886`.

This correction exists because the Ready transition triggered a Codex review that produced two new,
actionable and unresolved findings. PR #83 was already Draft when the findings were processed. No merge
was performed, Issue #80 remains Open, and milestone M6-F remains gated.

## 1. Exact actionable review evidence

Review submission:

- review ID: `PRR_kwDOTbeZ188AAAABIUMkpg`;
- state: `COMMENTED`;
- reviewer: `chatgpt-codex-connector`;
- submitted at: `2026-08-04T10:07:46Z`.

### Finding A — client and Provider request IDs were conflated

- thread ID: `PRRT_kwDOTbeZ186WReSu`;
- comment ID: `PRRC_kwDOTbeZ187dNq-g`;
- severity: `P1`;
- file: `OpenAiResponsesResponseDecoder.java`.

The Provider-generated `x-request-id` and the client-generated `X-Client-Request-Id` are independent
correlation identifiers. Hashing the Provider ID and comparing it with the admitted client-request-ID
hash caused valid live responses to fail as `REQUEST_ID_MISMATCH`.

Correction:

1. the Provider `x-request-id` remains mandatory, bounded and independently hashed;
2. the Provider request-ID hash remains the `DecodedResponse.requestIdHash` evidence;
3. the client request-ID hash is read only from verified transport evidence;
4. only the client transport hash is compared with the server-owned admitted client hash;
5. a different Provider request ID is accepted when the client transport hash remains exact;
6. client transport hash drift still fails closed as `REQUEST_ID_MISMATCH`.

The HTTP codec continues to derive the client hash from the exact `X-Client-Request-Id` written to the
request, and `TransportEvidence` continues to bind that hash into its recomputed evidence hash.

### Finding B — AI tenant limit was narrower than the platform contract

- thread ID: `PRRT_kwDOTbeZ186WReS2`;
- comment ID: `PRRC_kwDOTbeZ187dNq-p`;
- severity: `P2`;
- file: `ApprovalAssistanceGenerationService.java` through `AiServerRequestContext`.

The platform tenant identity contract permits 128 characters, while `AiServerRequestContext` accepted
only 120. Because context projection occurs before the guarded Provider path, a valid 121–128 character
tenant could throw instead of returning a stable generation outcome.

Correction:

1. `AiServerRequestContext.tenantId` now accepts the full platform maximum of 128 characters;
2. an exact 128-character tenant is permanently tested as accepted;
3. a 129-character tenant is permanently tested as rejected;
4. operator, request and trace limits remain unchanged;
5. no tenant identity is manufactured or accepted beyond the platform contract.

## 2. Scope closure

This correction changes only:

- the OpenAI response correlation-ID validation;
- the exact decoder regression test;
- the trusted AI tenant-ID maximum and its unit test;
- this append-only acceptance record;
- one permanent repository review-correction boundary and its aggregator import.

It adds no Provider, model, Prompt, endpoint, Secret source, retry, fallback, redirect, streaming,
Queue, Worker, Scheduler, listener, polling, automation proposal or executable action.

It adds no migration. M6-E continues to own only
`V49__create_ai_approval_assistance_durable_evidence.sql`; no V50 or later migration exists.

It adds no workflow. The only automatic PR/main workflow remains
`.github/workflows/approval-platform-validation.yml`.

## 3. Required correction gate

Before either review thread may be answered and resolved, this exact correction requires:

1. one final exact branch Head containing all code, tests, document and permanent-boundary changes;
2. one natural pull-request workflow, attempt 1, with all four jobs successful;
3. four artifacts tied to that exact Head and Run;
4. independent local size and SHA-256 equality for every artifact;
5. recalculated Maven, OpenAI, AI Core, server and permanent-boundary statistics;
6. current `main` unchanged, or an ordinary Merge Commit rebaseline followed by a new exact Run;
7. PR #83 Open, Draft, mergeable and behind zero;
8. no additional requested change, actionable comment, unresolved thread or disallowed reaction;
9. Issues #80, #62, #13 and #14 Open and Issue #78 Completed.

After exact validation, the two comments may receive evidence-backed replies and the two corresponding
threads may be resolved. Resolution itself is not Ready or merge authorization. A new complete gate
check remains mandatory before marking Ready again.

## 4. Permanent authority boundary

The correction preserves:

- `AI_IS_NOT_AN_OPERATOR`;
- every result remains `ADVISORY`, `UNVERIFIED_ADVISORY` and human-reviewed;
- no Provider-to-command or Flowable/approval mutation path;
- no client-selected Provider, model, Prompt, endpoint, policy or Secret;
- one Provider attempt maximum with no retry or fallback;
- no autonomous continuation or milestone M6-F capability;
- no live paid/customer Provider request in CI.

Current decision:

`P7_REVIEW_CORRECTION_PENDING_EXACT_VALIDATION`

`PR_83_REMAINS_DRAFT`

`REVIEW_THREADS_REMAIN_UNRESOLVED_UNTIL_EXACT_EVIDENCE`

`M6_F_REMAINS_GATED`

`AI_IS_NOT_AN_OPERATOR`
