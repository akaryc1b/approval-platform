# M6-E Governed AI Approval Assistance Threat Model

Status: `M6_E_P0_THREAT_MODEL_ESTABLISHED_PENDING_PERMANENT_VALIDATION`

Date: `2026-07-31`

Baseline: `main@fcf031da9e6e04b15a1255044021a7fdd6637421`

Tracking: Issue #80 under parent Issue #62 and roadmap Issue #78.

## 1. Security objective

M6-E may help a human understand authorized approval material, but it must never create or amplify
approval authority. The system must fail closed before Provider invocation when tenant,
authorization, field visibility, state freshness, data policy or Provider policy is uncertain.

The protected authority chain is:

```text
server identity and authorization
  -> server-owned approval projection
  -> masking and minimization
  -> bounded Provider invocation
  -> unverified advisory result
  -> human review
```

The prohibited chain is:

```text
Provider -> application command
```

## 2. Protected assets

| Asset | Protection objective |
| --- | --- |
| Tenant boundary | no cross-tenant read, inference, routing, evidence or response |
| Operator identity | cannot be client- or Provider-manufactured |
| Authorization | evaluated by the server from current trusted state |
| Approval material | only visible, minimized fields may leave the application boundary |
| Process/task state | exact state/version used to detect stale assistance |
| Provider credentials | never exposed to browser, logs, artifacts or Provider output |
| Prompt/model/policy/schema versions | exact and auditable, not client-selected |
| Advisory result | bounded, structurally validated and never authoritative |
| Evidence chain | tamper-resistant linkage without raw sensitive payloads |
| Cost/availability budget | bounded against intentional or accidental exhaustion |
| Human decision | remains independent of Provider output and outside AI authority |

## 3. Actors

- authenticated approval participant;
- management or operations reader with explicit permissions;
- malicious or compromised tenant user;
- compromised browser or mobile client;
- malicious form field or attachment metadata author;
- compromised or faulty AI Provider;
- malicious network endpoint or DNS responder;
- privileged operator attempting to bypass separation of duties;
- internal developer accidentally weakening an authority boundary.

The AI Provider is always treated as an untrusted external processor. It is not an operator,
reviewer, approver, policy authority, audit authority or identity source.

## 4. Trust boundaries

### Boundary A — client to application

Untrusted:

- tenant, operator, role, permission or Provider claims from request bodies;
- client-supplied audit identities;
- client-selected model or trusted Prompt version;
- client assertions about visible fields or process state.

Required control:

- reuse authenticated principal and server-owned request context;
- reject tenant mismatch without disclosing another tenant's resource;
- derive authorization and field visibility on the server.

### Boundary B — approval domain to assistance projection

Untrusted:

- complete persistence entities;
- stale task or instance snapshots;
- fields outside the current operator's permission;
- attachment bodies or external links.

Required control:

- create a purpose-specific projection;
- bind exact definition, form, schema, task/instance and policy versions;
- remove unauthorized fields before Provider mapping;
- default-deny attachment extraction.

### Boundary C — application to Provider adapter

Untrusted:

- Provider endpoint behavior;
- Provider output;
- redirects, DNS changes, content type and payload size;
- Provider-declared token/cost data without local validation.

Required control:

- exact server-owned Provider/model/endpoint allowlists;
- TLS, DNS, redirect and SSRF protections;
- bounded request/response and timeout;
- at most one Provider;
- no unsafe retry or post-invocation fallback;
- circuit breaker and kill switch.

### Boundary D — Provider output to application response

Untrusted:

- factual claims;
- citations or policy references;
- instructions, commands or tool requests;
- identity, permission or decision claims.

Required control:

- strict output schema;
- result classification `ADVISORY` and `UNVERIFIED_ADVISORY`;
- `needsHumanReview = true`;
- provenance and limitations;
- command-like fields rejected, not ignored;
- malformed or oversized output fails closed.

### Boundary E — advisory result to human interface

Risk:

- users may mistake fluent text for an approved decision;
- UI may visually imply authority;
- suggested text may be copied into a command without independent review.

Required control:

- conspicuous AI/unverified/human-review labels;
- no AI-proxy approve/reject/transfer/withdraw/terminate/migrate action;
- no automatic command-field population;
- evidence, limitations and exact versions displayed;
- stale results visibly invalidated.

## 5. Threat register

| ID | Threat | Impact | Required control | Gate |
| --- | --- | --- | --- | --- |
| T01 | forged tenant or operator | cross-tenant disclosure or false audit | authenticated server-owned identity; tenant mismatch fail-closed | P1 |
| T02 | forged authorization | unauthorized assistance | fresh server permission evaluation; Provider claims ignored | P1/P3 |
| T03 | unauthorized field leakage | sensitive-data disclosure | field projection, masking and minimization before mapping | P1 |
| T04 | complete-object serialization | excessive disclosure | purpose-specific DTO/projection only; bounded fields | P1 |
| T05 | prompt injection in form data | policy bypass or exfiltration | treat content as data; fixed system policy; no tool authority | P2/P3/P7 |
| T06 | tool or command injection | unauthorized side effect | output schema has no command; direct command dependencies prohibited | P2/P3/P7 |
| T07 | Provider fabricates evidence | false confidence | exact server evidence references; unknown citation rejected or marked unsupported | P2/P7 |
| T08 | Provider returns approval decision | authority confusion | reject decision/command semantics; advisory labels are mandatory | P2/P5/P7 |
| T09 | cross-tenant Provider route | disclosure or billing confusion | tenant-scoped server routing and exact policy binding | P3/P6 |
| T10 | oversized/deep input | memory or cost exhaustion | field, character, collection, depth and request-byte limits | P1/P3/P7 |
| T11 | malformed/oversized output | parser abuse or UI compromise | strict schema, content type, byte/depth/string limits | P2/P3/P6/P7 |
| T12 | timeout or partial network result | duplicate billing or inconsistent evidence | explicit timeout/cancellation; no unsafe retry or fallback | P3/P6/P7 |
| T13 | stale circuit completion | availability/control corruption | preserve M6-D generation-aware circuit behavior | P3/P7 |
| T14 | stale approval state | misleading assistance | bind expected state/version; invalidate before display/use | P1/P5/P7 |
| T15 | replayed request/result | stale or duplicated evidence | request/evidence hashes, expiry, idempotency and CAS where persisted | P4/P7 |
| T16 | Provider/model/Prompt drift | unreviewed behavior change | exact version inventory, activation gate and drift detection | P3/P6/P7 |
| T17 | secret leakage | credential compromise | server-owned Secret source; redaction; no raw logs/artifacts | P6/P7 |
| T18 | SSRF/DNS rebinding/redirect | internal-network access | endpoint allowlist, DNS/TLS validation, no redirects by default | P6/P7 |
| T19 | cost/rate exhaustion | denial of service or spend | per-request and tenant budgets, rate limits, bounded tokens | P3/P6/P7 |
| T20 | feedback poisoning | Prompt/model corruption | feedback stored separately; no automatic training or Prompt mutation | P4/P7 |
| T21 | raw evidence persistence | durable sensitive-data leak | hash-only/bounded metadata, retention/delete and tenant isolation | P4/P7 |
| T22 | UI authority confusion | improper human decision | unverified labels, limitations, no command proxy | P5/P7 |
| T23 | background autonomous execution | uncontrolled repeated processing | M6-E synchronous only; no Queue/Worker/Scheduler | all |
| T24 | direct Flowable/database mutation | bypass application governance | no direct engine/ACT table/arbitrary SQL path | all |
| T25 | Provider compromise | malicious output/exfiltration | minimization, strict adapter, kill switch, circuit and incident runbook | P6/P7 |
| T26 | audit hash ambiguity | evidence collision/rebinding | preserve length-framed/domain-separated hashing | P3/P4/P7 |
| T27 | confused deputy | server executes Provider intent | Provider output cannot supply authority or executable action | all |
| T28 | unsafe attachment retrieval | exfiltration/malware | attachment extraction remains deferred and default-denied | all |

## 6. Prompt-injection model

Form values, comments, filenames, attachment metadata and imported template labels are all
untrusted content. Instructions embedded in them do not alter server policy.

Required behavior:

1. content is represented as data, not concatenated into an executable instruction channel;
2. the server selects exact Prompt metadata and output schema;
3. Provider output requesting tools, URLs, credentials or commands is rejected;
4. evidence references are resolved only from server-authorized sources;
5. the Provider sees no field that the operator cannot see;
6. no response can cause a second Provider or connector call.

## 7. Confused-deputy and command boundary

The Provider cannot provide or override:

- tenant;
- operator;
- roles or permissions;
- process/task/resource identity;
- expected state/version;
- Provider routing authority;
- audit identity;
- application command type;
- credential or Secret reference.

No M6-E component may import or directly call Flowable runtime/task/migration services or an
approval mutation command service. M6-E ends at an advisory result. Any later proposal-to-command
path belongs to M6-F and requires a separate server-policy and human-confirmation gate.

## 8. Failure semantics

Failures must be deterministic and non-authoritative.

Examples:

- authorization failure: no Provider call;
- missing field policy: no Provider call;
- stale state: no Provider call or result invalidation;
- no eligible Provider: unavailable advisory result;
- circuit open: unavailable advisory result;
- timeout: failed advisory result with no retry after partial invocation;
- malformed output: failed advisory result, no partial display;
- budget exceeded: rejected before invocation where possible;
- persistence failure: no claim of durable evidence completion.

A failure never falls back to an approval decision, default command or weaker Provider policy.

## 9. Observability and evidence

Allowed low-cardinality signals include:

- use-case classification;
- Provider/model policy identifiers from bounded inventories;
- outcome/failure classification;
- latency bucket;
- token/cost bucket;
- circuit and kill-switch state;
- policy/schema version.

Forbidden metric/log dimensions include raw tenant, operator, request, trace, task, instance,
field content, Prompt body, Provider response, Secret or token.

High-cardinality linkage belongs in protected hash-only evidence, not metric tags.

## 10. Incident and rollback model

P0 has no runtime activation. Later runtime controls must support:

- immediate Provider kill switch;
- activation state that defaults disabled;
- exact affected Provider/model/Prompt/policy versions;
- evidence-preserving circuit and failure state;
- rollback to a previously accepted configuration without output reinterpretation;
- retention/deletion response for affected evidence;
- no automatic retry of uncertain Provider invocations;
- no alternate direct-command path during outage.

## 11. Residual risks after P0

P0 documents and permanently tests authority boundaries but does not yet implement:

- approval context projection;
- advisory output contracts specific to approval assistance;
- executable application wiring;
- durable evidence;
- API/UI;
- real Provider and operational controls.

Therefore M6-E remains incomplete and non-production after P0.

## 12. Permanent invariants

- AI is never an operator.
- AI does not manufacture authority.
- AI output is unverified advisory material.
- field authorization occurs before Provider mapping.
- Provider invocation is bounded and at most one.
- M6-E is synchronous and has no autonomous background execution.
- Provider output cannot directly or indirectly execute a command.
- no direct Flowable `ACT_*` access exists.
- no raw Secret, Prompt or customer content is written to logs or artifacts.
- Issue #62 remains open until M6-G proves the complete production path.

`ADVISORY_NOT_AUTHORITY`

`HUMAN_REVIEW_REQUIRED`

`PROVIDER_TO_DIRECT_COMMAND_PROHIBITED`
