# M6-E P7 Final Ready Review Correction Acceptance

## Decision

This append-only record captures the actionable Codex Review triggered by the Ready transition of PR #83 at exact reviewed Head `01cd530dc08a11d220fd0c04d0423bb81d05e2cc`.

The PR was immediately returned to Draft. Merge remains blocked until the corrected documented Head completes a fresh natural permanent workflow, all final artifacts are independently SHA-256 verified, both Review threads receive exact evidence replies and are resolved, and every Ready/merge gate is rechecked.

## Exact Review evidence

- Review submission: `PRR_kwDOTbeZ188AAAABIVludg`
- reviewed commit: `01cd530dc08a11d220fd0c04d0423bb81d05e2cc`
- response verbosity thread: `PRRT_kwDOTbeZ186WUnxy`
- response verbosity comment: `PRRC_kwDOTbeZ187dSSHe`
- post-Provider task revalidation thread: `PRRT_kwDOTbeZ186WUnx4`
- post-Provider task revalidation comment: `PRRC_kwDOTbeZ187dSSHm`
- both findings are actionable and remain unresolved pending exact evidence.

## Finding 1 — exact GPT-5 response verbosity

The selected Responses profile leaves text verbosity at its provider default. A live completed Responses object may therefore contain:

```json
{
  "text": {
    "format": { "type": "json_schema" },
    "verbosity": "medium"
  }
}
```

The strict decoder previously allowed only `text.format`, so the provider-default `text.verbosity` field was rejected as `UNKNOWN_PROPERTY` before an otherwise valid advisory could be decoded.

The correction preserves strictness:

- `text` permits only `format` and `verbosity`;
- `verbosity` may be absent for compatible fixtures/responses;
- when present, `verbosity` must be a string exactly equal to `medium`;
- `low`, `high`, non-string values, null and any other value fail closed as `SCHEMA_MISMATCH`;
- unknown sibling fields still fail closed as `UNKNOWN_PROPERTY`;
- structured-output schema, model snapshot, tools, storage, background, conversation and request-ID checks are unchanged.

No request setting, model selection, endpoint, streaming mode or Provider capability is added.

## Finding 2 — task revalidation after Provider execution

The service previously performed two pending-task queries before Provider execution but none after the external call. Because the call can consume the bounded Provider timeout, a task could be completed, transferred or updated during the call and an obsolete result could still be converted into P4 evidence and returned.

The corrected server-owned order is:

1. trusted pending-task identity query;
2. projection from the initial immutable task snapshot;
3. pre-Provider pending-task equality revalidation;
4. exact production runtime binding and one Provider orchestration attempt;
5. post-Provider pending-task equality revalidation;
6. P4 durable evidence construction;
7. one P4 store attempt;
8. advisory response only after the durable result is accepted.

The post-Provider query uses the same trusted `PendingTaskIdentity`. If it is absent or any `PendingTaskDetails` field differs from the initial snapshot, the service returns `STALE_TASK` and performs no P4 evidence write.

The correction does not retry the Provider, compensate, repeat the external request, queue work or expose a stale advisory.

## Permanent regression expectations

The corrected tests must prove:

- a response containing exact `text.verbosity=medium` decodes successfully;
- a compatible response omitting verbosity still decodes;
- `low`, `high` and non-string verbosity fail closed;
- a task changed after the Provider attempt returns `STALE_TASK`;
- a task no longer pending after the Provider attempt returns `STALE_TASK`;
- both post-Provider stale cases perform exactly one Provider attempt and zero evidence writes;
- unchanged tasks perform three exact pending-task reads before an evidence conflict/unavailable result;
- evidence-store failure/conflict never causes a second Provider attempt.

## Scope and authority boundary

This correction adds no:

- Provider, model, Prompt, endpoint or Secret source;
- automatic retry, fallback, redirect following or streaming;
- second external attempt;
- Queue, Worker, Scheduler, listener, polling or autonomous continuation;
- automation proposal or executable action;
- approval, reject, return, transfer, withdraw, terminate, migration, publish or activation command;
- direct Flowable mutation authority;
- migration or second automatic workflow;
- live paid/customer Provider request in CI.

Unique V49 remains the highest migration. V50+ remains absent. M6-F remains gated.

## Required evidence before acceptance

The correction is not accepted by this document alone. The final documented Head must receive:

- a natural `pull_request` permanent workflow;
- Maven core success;
- all four Persistence JDBC shards success;
- Maven/PostgreSQL aggregation success;
- Web production build success;
- Mobile type-check, H5 and WeChat build success;
- Repository hygiene success;
- four final artifacts with independently verified SHA-256 equality;
- zero unresolved actionable threads;
- zero blocking PR reactions;
- exact `main`, behind zero and mergeable status;
- a fresh Ready transition and immediate post-Ready recheck before ordinary Merge Commit.

`P7_FINAL_READY_REVIEW_CORRECTION_PENDING_EXACT_VALIDATION`

`FINAL_READY_REVIEW_THREADS_REMAIN_UNRESOLVED`

`PR_REMAINS_DRAFT`

`M6_F_REMAINS_GATED`

`AI_IS_NOT_AN_OPERATOR`
