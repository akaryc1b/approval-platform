# M6-E P6-F OpenAI Production Incident Runbook

Status: `M6_E_P6_F_IMPLEMENTATION_EVIDENCE`

Date: `2026-08-04`

Tracking:

- parent milestone: Issue #62;
- roadmap rebaseline: Issue #78, Closed / Completed;
- workstream: Issue #80;
- Pull Request: #83;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- accepted P6-E documented Head: `ecc811261324498bf7c70eaa147249f9bfe26dd6`;
- accepted P6-E Run: `30892287318` / #1156, success.

This runbook is the P6-F operational closure for the one accepted OpenAI Responses production path.
It adds no Provider capability, endpoint, model, Prompt, retry, fallback, automation or command.

## 1. Permanent operational boundary

The only governed production path remains:

`POST /api/approval/tasks/{taskId}/assistance/generations`

The accepted P5 GET remains zero-egress availability/read infrastructure.

Every result remains:

- `ADVISORY`;
- `UNVERIFIED_ADVISORY`;
- `needsHumanReview=true`;
- `commandAvailable=false`;
- `providerSelectable=false`;
- `retryAttempted=false`;
- `fallbackAttempted=false`.

AI is never an operator. Incident handling cannot approve, reject, return, transfer, withdraw,
terminate, migrate, publish, activate, modify permission/Secret material or manufacture identity.

## 2. Incident authority and separation of duties

The incident commander may order disablement and evidence preservation. The deployment owner may
change only server-owned deployment configuration and Secret references. The database owner may
inspect only platform P4 evidence and migration health. The security reviewer independently verifies
recovery gates.

No browser/mobile user, Provider response, model output or AI component may authorize enablement,
Secret rotation, rollback, replay or recovery.

Recovery requires two independent human approvals recorded outside Provider output. One person must
be the deployment or service owner; the second must be a security, platform or release reviewer.

## 3. Safe evidence that may be retained

Retain only:

- incident identifier and UTC timestamps;
- exact application commit, deployment revision and environment name;
- exact non-secret `OPENAI_API_KEY_VERSION`;
- kill-switch generation and policy revision;
- cost-policy version and validity interval;
- stable platform status/error code;
- closed outcome classification;
- request/evidence/route/DNS/TLS hashes already produced by accepted code;
- P4 evidence ID and canonical evidence hash when present;
- workflow Run, job, artifact ID, size and SHA-256;
- bounded aggregate counts and low-cardinality metrics.

Never retain:

- `OPENAI_API_KEY` or any Secret bytes;
- Authorization header value;
- raw request or response body;
- Prompt text or Provider-safe field values;
- raw Provider request/response ID;
- tenant, operator, task, instance or business identifiers in metrics;
- arbitrary exception messages;
- DNS/TLS certificate material beyond accepted hashes;
- screenshots or copied payloads containing customer data.

## 4. Severity classes

### SEV-1 — suspected Secret compromise or unsafe network trust

Examples:

- API key disclosure or unexpected use;
- endpoint, DNS, connected-address or TLS evidence drift;
- private/local/special-purpose address admission;
- hostname or certificate-chain validation failure;
- request profile or endpoint allowlist bypass suspicion.

Required action: disable immediately, rotate Secret material, preserve hash-only evidence and do not
re-enable until the full recovery gate passes.

### SEV-2 — Provider, policy or persistence integrity failure

Examples:

- repeated 401, 403, 429 or 5xx responses;
- refusal, malformed JSON, unknown fields, Schema/model/version mismatch or oversized response;
- stale cost policy, rate-limit saturation or open circuit;
- P4 evidence conflict or evidence-store unavailability;
- post-dispatch ambiguous `UNKNOWN` outcome.

Required action: keep zero automatic retry/fallback, disable when threshold or integrity risk is
reached, and preserve bounded evidence.

### SEV-3 — isolated bounded failure

Examples:

- one timeout or cancellation;
- one stale task snapshot;
- one client-invalid closed request;
- one explicit user request while runtime is disabled.

Required action: return the stable bounded failure, do not retry automatically and monitor closed
low-cardinality metrics.

## 5. Emergency disable procedure

Emergency disablement is server-owned and fail closed.

1. Set `APPROVAL_AI_OPENAI_ENABLED=false` in the deployment-owned configuration source.
2. Roll or restart every approval server instance so no instance retains a previously constructed
   production runtime.
3. Confirm the deployed revision and instance set are complete; a partial rollout is not accepted.
4. Verify an explicit generation request returns HTTP 503 with `AI_ASSISTANCE_DISABLED`.
5. Verify disabled handling occurs before task query, DNS, TLS, Secret lease and P4 store access.
6. Verify the accepted P5 GET remains zero-egress and does not trigger Provider access.
7. Confirm there is no automatic retry, fallback, polling, Queue, Worker or Scheduler.
8. Record only the safe evidence listed in this runbook.

A request already dispatched before disablement cannot be recalled by this slice. Its outcome remains
success, bounded failure or `UNKNOWN` according to accepted evidence. Never send a second Provider
request automatically to resolve ambiguity.

## 6. Secret compromise and rotation

1. Execute the emergency disable procedure first.
2. Revoke or rotate the external OpenAI credential through the deployment Secret backend.
3. Do not paste the old or new key into GitHub, logs, chat, tickets, YAML, database or API requests.
4. Change `OPENAI_API_KEY_VERSION` to a new exact non-secret version reference.
5. Set a new exact Secret effective interval and Secret policy revision.
6. Increase the kill-switch generation and update its policy revision.
7. Roll all server instances while `APPROVAL_AI_OPENAI_ENABLED=false`.
8. Verify the old version reference is absent from the active deployment configuration.
9. Complete the recovery gate before enabling.

Secret rotation without a changed exact version reference is not accepted. The application must not
read the raw key during configuration or preflight.

## 7. Provider HTTP outage, rate limit and refusal

The deterministic fault matrix includes 401, 403, 429, 500 and 503 outcomes.

- 401/403: treat as Provider unavailable, disable and rotate/repair server-owned credential binding;
- 429: preserve one-attempt evidence, do not retry, inspect tenant/global rate and external limits;
- 5xx: treat as Provider unavailable, allow the circuit policy to open, never fail over;
- refusal: treat as policy blocked and expose no refusal body;
- timeout/cancellation: return bounded timeout/unknown behavior with no second call.

No incident procedure may introduce exponential retry, alternate Provider, alternate endpoint,
streaming, background response continuation or redirect following.

## 8. Malformed, unsafe or drifted output

Disable and investigate when any response has:

- invalid UTF-8 or malformed JSON;
- duplicate or unknown properties;
- incomplete/queued/in-progress status;
- Provider error object or refusal;
- multiple advisory payloads;
- model, Schema or version mismatch;
- invalid usage counts;
- unauthorized evidence reference;
- confidence/result invariant failure;
- response-size or nesting/collection/string limit failure.

The response body must not be copied into incident evidence. Preserve only the stable failure code,
closed classification and accepted hashes. The client receives no partial advisory or internal body.

## 9. DNS, TLS and SSRF incident

1. Disable immediately.
2. Preserve endpoint, DNS, connected-address and TLS evidence hashes only.
3. Confirm the configured endpoint remains exactly
   `https://api.openai.com:443/v1/responses`.
4. Reject requests to add an endpoint override, alternate host, proxy bypass, trust-all manager,
   hostname-verifier bypass or plaintext fallback.
5. Confirm the admitted address set contains only public addresses and matches connection evidence.
6. Confirm certificate validity, trusted chain, SNI and hostname verification.
7. Do not re-enable on the basis of string allowlisting alone.

## 10. Cost, rate, circuit and kill-switch incident

- stale or future cost policy: keep runtime disabled and publish a new exact policy version/window;
- request-cost ceiling exceeded: return policy blocked and do not split or retry the request;
- tenant/global rate limit exceeded: return policy blocked and do not bypass the limiter;
- circuit open: do not force acquisition or create a second Provider route;
- kill-switch disabled/drifted: stop before Secret lease and dispatch;
- generation/policy mismatch: roll all instances with one exact configuration revision.

A recovery cannot reuse expired pricing evidence or lower safety limits solely to clear an incident.

## 11. P4 evidence conflict or store outage

A Provider attempt may complete before the P4 store reports conflict or unavailability. In that case:

- the public request fails and exposes no advisory or evidence ID;
- no automatic second Provider call is permitted;
- no partial raw output may be persisted elsewhere;
- preserve the stable store outcome and accepted hashes;
- restore database availability or investigate the exact canonical conflict;
- do not mutate an existing evidence row to force success;
- do not delete V49 evidence to clear the incident.

A later human may submit a new explicit request only after the incident is resolved. It is a new
request, not an automatic replay or continuation of the ambiguous attempt.

## 12. Rollback procedure

1. Disable first with `APPROVAL_AI_OPENAI_ENABLED=false` and roll every instance.
2. Preserve the exact failing and target commit SHAs and deployment revisions.
3. Roll application code back only to a version compatible with the already-applied V49 schema.
4. Do not run a V49 down migration, drop the P4 table or delete retained evidence.
5. Do not roll back by enabling a floating model, alternate endpoint or fallback Provider.
6. Keep generation disabled until deterministic tests and the recovery gate pass.
7. Record rollback evidence without Secret or payload data.

V49 is additive durable evidence and remains in place during application rollback.

## 13. Recovery gate

Re-enable only after all conditions are true:

- incident root cause and affected time range are recorded;
- two independent human reviewers approve recovery;
- exact application and deployment revisions are known;
- exact endpoint/model/Prompt/policy/schema versions remain accepted;
- Secret version is new/current when rotation was required;
- Secret and cost-policy intervals contain the server clock instant;
- kill-switch generation and policy revision are current and consistent;
- tenant/global rate, cost ceiling and circuit settings are positive and coherent;
- deterministic fault/security tests pass with zero external network;
- permanent workflow is green and artifacts are SHA-256 exact;
- no unresolved actionable Review/thread/reaction exists;
- deployment first starts with `APPROVAL_AI_OPENAI_ENABLED=false`;
- only after verification is the flag changed to exact `true` and all instances rolled;
- first validation is one explicit human-triggered advisory request, never an automated probe;
- returned content is treated as unverified advisory material.

## 14. Deterministic incident drills

The following drills are mandatory and zero-egress:

| Drill | Expected result | Provider sends |
| --- | --- | ---: |
| emergency disabled flag | no runtime, `AI_ASSISTANCE_DISABLED` | 0 |
| cancellation before encoding | bounded cancelled/unknown result | 0 |
| kill-switch disabled or drifted | policy/disabled result before Secret | 0 |
| stale cost policy | policy blocked | 0 |
| tenant/global rate limit | policy blocked | 0 |
| unsafe or drifted DNS | Provider unavailable before Secret | 0 |
| TLS/connection evidence drift | Provider unavailable before Secret | 0 |
| missing/malformed Secret | Provider unavailable before dispatch | 0 |
| 401/403/429/500/503 | stable Provider-unavailable result | 1 each |
| timeout or I/O ambiguity | stable timeout/unknown result | 1 |
| malformed/unknown/oversized output | invalid output | 1 |
| refusal | policy blocked | 1 |
| P4 conflict or outage | stable evidence failure, no second call | 1 |

CI must not read `OPENAI_API_KEY` and must not call `api.openai.com`.

## 15. Final exclusions

This runbook does not authorize:

- live paid/customer Provider calls in CI;
- a second Provider, endpoint or model;
- retry, fallback, redirect follow, stream or background mode;
- previous-response/conversation state;
- tools, function calling, RAG, embeddings or vector storage;
- attachment-content extraction;
- approval comment population;
- approval, migration, publication, permission or Secret command;
- automation proposal or executable action;
- Queue, Worker, Scheduler, listener or polling;
- V50+ migration or second automatic workflow;
- PR Ready, merge or Issue #80 closure;
- milestone M6-F capability.

`P6_F_FAULT_SECURITY_INCIDENT_ONLY`

`ZERO_EGRESS_DETERMINISTIC_DRILLS`

`V49_EVIDENCE_PRESERVED_ON_ROLLBACK`

`AI_IS_NOT_AN_OPERATOR`
