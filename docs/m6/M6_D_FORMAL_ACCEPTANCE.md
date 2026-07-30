# M6-D AI Foundation — Formal Acceptance

Status: `FORMAL_ACCEPTANCE_DOCUMENTED_PENDING_EXACT_HEAD_VALIDATION`

## 1. Acceptance identity

- repository: `akaryc1b/approval-platform`
- parent milestone: Issue #62
- workstream: Issue #66
- Pull Request: #70
- branch: `agent/m6-d-ai-foundation`
- target: `main`
- original implementation baseline: `d769722cf7dd5418739a91ad4c45ca1a1c147502`
- original implementation Head: `9d588215e869c8f1332c0bc1a2809fbd235c2efa`
- final formal-rebaseline `main`: `735e41526371ea481b31af377e3410d085160f7e`
- controlled rebaseline Merge Commit: `c0078be9669c4936edb73f3c195f75fc0f6bc9e8`
- R0 validated Head: `7f812ce0e08a5644f861b6d3c6bcd7b900af89f0`
- G1 corrected implementation Head: `66f4e3104bb0f34d636a6dea9f37bc4be833abfb`
- G1 audited Head: `a9e2fddfadf7414c50ba23dec17332da53d5db82`
- G1 audit document: `docs/m6/M6_D_COMPATIBILITY_AND_SECURITY_AUDIT.md`
- documented Head: the commit containing this document; its exact SHA is recorded in the permanent PR #70 G2 evidence comment after validation because a commit cannot truthfully contain its own SHA

Immediately before this document commit, PR #70 was ahead `46`, behind `0`, with `46` commits and `136` changed files. The exact post-document counts are recorded in the permanent G2 evidence comment.

## 2. Formal scope

M6-D establishes a provider-neutral, advisory-only, offline-validated AI foundation. It does not establish a production AI service, a real Provider connection, a production Prompt, customer-knowledge retrieval, AI persistence, an AI management surface or authority to change approval/process state.

Every accepted AI result remains:

- `ADVISORY`;
- `UNVERIFIED_ADVISORY`;
- `needsHumanReview = true`.

No accepted contract can express or execute approve, reject, return, transfer, withdraw, terminate, migrate or any other process command.

## 3. Seven accepted safe slices

### Slice 1 — Provider-neutral advisory SPI

- closed Provider, capability, model, version, request, cancellation, outcome and usage contracts;
- no Spring, Flowable, HTTP, database or Provider-specific production dependency;
- no real OpenAI, Anthropic, Azure OpenAI, Gemini or other production adapter;
- deterministic Provider remains test-only.

### Slice 2 — Server-owned identity, permission and minimization

- tenant, operator, request and trace identity comes from server-owned context;
- client input cannot create or overwrite authority, audit, lease, engine or worker identity;
- tenant isolation and field permission run before Provider mapping;
- masking and minimization run before Provider mapping;
- unauthorized, confidential or restricted fields are blocked or redacted;
- prompt-injection-like input and unbounded fields/characters/collections fail closed.

### Slice 3 — Advisory provenance and offline evaluation

- exact Provider, Provider type/capability, model, Prompt metadata, knowledge-source metadata, policy and output-schema versions are bound;
- exact content hashes and registry authorization are required;
- Prompt metadata contains no Prompt body or instructions;
- knowledge metadata rejects customer-data retrieval;
- offline evaluation consumes precomputed observations only and cannot invoke a Provider or authorize production.

### Slice 4 — Deterministic routing, bounded invocation and immutable configuration

- routing order is deterministic and server-owned;
- invocation field/character/timeout/confidence budgets are bounded;
- circuit state is an operational gate, never an authority source;
- at most one Provider can be invoked for one request;
- unsafe retry and post-invocation fallback are prohibited;
- timeout and cancellation semantics are explicit and fail closed;
- configuration snapshot is immutable and content-hash verified;
- startup preflight and dry-run assembly are zero-call and non-authorizing.

### Slice 5 — External reference metadata and offline deployment readiness

- external Secret reference stores metadata only and no Secret material;
- endpoint, HTTPS, public-DNS, port, path and egress constraints are metadata-only;
- protocol validation is structural/offline and has no production implementation;
- deployment snapshot is immutable, exact-version bound and `FAULT_DRILL_ONLY`;
- readiness and fault drills use precomputed observations and perform no Provider/network/Secret operation;
- deployment change evidence cannot apply a change.

### Slice 6 — Runtime trust and non-executable activation review

- Secret resolver inspection is metadata-only; deterministic resolver is test-only;
- DNS and TLS evidence is precomputed and performs no lookup or handshake;
- kill-switch states are limited to `DISABLED` and `FAULT_DRILL_ONLY`;
- activation lease states cannot grant execution authority;
- two-person review requires distinct reviewer identity hashes and distinct roles;
- `REVIEW_COMPLETE` is evidence only and does not enable production;
- activation plan remains permanently `NON_EXECUTABLE_REVIEW_ONLY`.

### Slice 7 — Offline Provider transport review

- transport mapping is structural and offline;
- no production transport mapper exists;
- canonical payload evidence stores pointer, type, SHA-256 hash, byte count, classification and redaction evidence, not raw values;
- signing-input evidence computes no signature and stores no Secret or sensitive header value;
- malformed response, schema drift, unknown fields, connection error, timeout, cancellation and unknown result fail closed;
- transport audit stores hashes, stable codes and bounded counts only;
- acceptance remains permanently `NON_EXECUTABLE_TRANSPORT_ACCEPTANCE`.

## 4. Advisory-only result acceptance

Accepted invariants:

- AI output is not an approval decision;
- AI output is not verified fact;
- AI output cannot set approval, task, process or runtime-binding state;
- AI output cannot generate a process command;
- authoritative/command language is rejected;
- provider failure, timeout, cancellation, low confidence, invalid output and unknown outcome require human review and fail closed;
- no contract provides execution authority.

## 5. Identity, permission and tenant acceptance

Accepted invariants:

- tenant/operator/request/trace context is server-owned;
- resource tenant must match server tenant;
- permission filtering precedes request construction;
- minimization and masking precede Provider mapping;
- unauthorized fields cannot enter a Provider request;
- client input cannot inject audit, authority, lease, worker or engine identity;
- raw high-cardinality request/subject/resource identities are not retained in audit/execution evidence after G1 hardening.

## 6. Masking, data minimization and Secret acceptance

Accepted invariants:

- confidential/restricted data is blocked or redacted before mapping;
- raw Prompt, business input and Provider response are not stored in audit evidence;
- raw request/response bodies and header values are not stored in transport evidence;
- API key, credential, token, password, private key and Secret material are prohibited from code, evidence, metrics and artifacts;
- external Secret reference does not mean a Secret is configured;
- external Secret reference does not authorize Secret retrieval or Provider invocation;
- deterministic resolver exists only in test source.

## 7. Provenance, version and reproducibility acceptance

Accepted evidence binds exact:

- Provider identity and version;
- Provider type and capability;
- model identity and version;
- Prompt-template metadata version and content hash;
- knowledge-source metadata version and content hash;
- policy version and content hash;
- output-schema identity and version;
- immutable configuration snapshot and hash;
- immutable deployment snapshot and hash;
- canonical payload hash;
- request and envelope hashes;
- audit, execution, lifecycle, review and checklist hashes.

Equivalent bounded metadata produces deterministic evidence. Identity, version or content-hash mismatch fails closed. Evidence reproducibility is a review property; it does not grant runtime dispatch or production authority.

## 8. Provider routing and execution acceptance

Accepted invariants:

- routing is deterministic and exact-version authorized;
- invocation budget is bounded;
- circuit breaker is not an authorization source;
- one request invokes at most one Provider;
- pre-invocation candidate selection is not post-invocation fallback;
- post-invocation fallback is prohibited;
- unsafe retry is prohibited;
- cancellation and timeout are bounded and fail closed;
- production source has no real Provider adapter, HTTP client, DNS lookup, TLS handshake, signing computation, network egress or runtime Secret retrieval.

## 9. Deployment snapshot, preflight and dry-run acceptance

Accepted invariants:

- configuration and deployment snapshots are immutable and hash-bound;
- configuration stage remains `DRY_RUN_ONLY`;
- deployment stage remains `FAULT_DRILL_ONLY`;
- startup preflight performs no Provider call;
- dry-run assembly performs no Provider call;
- deployment readiness is offline review evidence, not production activation;
- fault drills operate on precomputed observations;
- change sets cannot apply changes.

## 10. Activation, lease and kill-switch acceptance

Accepted invariants:

- kill switch has no enabled/active production state;
- activation lease has no granted/active authority state;
- reviewer identity is stored only as a controlled hash;
- two-person review cannot be satisfied by one actor or one role;
- review checklist and activation plan cannot apply a change;
- `REVIEW_COMPLETE` cannot be interpreted as production enabled;
- activation plan cannot resolve a Secret, access a network, invoke a Provider, apply a change, enable production or authorize approval automation.

## 11. Offline transport acceptance

Accepted invariants:

- transport mapping cannot send a request;
- lifecycle evaluator consumes precomputed fixture observations only;
- canonical payload stores no raw field value;
- signing-input evidence computes no signature;
- authorization, cookie, proxy-authorization and API-key header values cannot be stored;
- malformed/unknown/schema-drift/connection/cancellation/timeout evidence fails closed;
- transport audit is redaction-safe and hash-only;
- transport acceptance checklist cannot authorize invocation or production.

## 12. Redaction-safe audit and low-cardinality observability

G1 formally corrected advisory audit/execution evidence to retain:

- request evidence hash;
- subject evidence hash;
- resource evidence hash;
- optional route and human-decision evidence hashes;
- exact versions;
- capability and classification;
- usage/circuit evidence;
- bounded counters;
- deterministic aggregate evidence hash.

Raw tenant, operator, request, trace, resource, authorization, route and human-decision identifiers are not stored in those evidence records.

Activation reviewer identity is stored as `reviewerEvidenceHash` and is not emitted as a raw identifier.

Metrics use only closed, low-cardinality capability, outcome, Provider type, policy, circuit and routing dimensions. Tenant, operator, request, trace, instance, task and Provider-response data are not metric tags.

## 13. Mainline compatibility acceptance

M6-D does not modify:

- M4 identity/governance behavior;
- M5 migration plan, intent, attempt, verification or reconciliation;
- M5 runtime binding or process release lifecycle;
- M6-A production connector invocation or credential lifecycle;
- M6-B SDK/event delivery semantics;
- M6-C template import, component registry or Draft-only semantics;
- Web/Mobile product behavior;
- persistence migration files.

The current-main Maven reactor, connector modules, architecture dependencies and workflow checks were retained. M6-D adds only its two AI modules and three M6-D permanent Node boundary suites to the shared integration points.

## 14. Migration and workflow acceptance

Current-main migration files are `V2` through `V37`, plus `V39` through `V48`. M6-D adds or modifies no Flyway migration relative to current main and does not assume integer continuity.

The repository may contain manual or reusable workflows. The only workflow with automatic `pull_request` or `push` triggers remains:

`.github/workflows/approval-platform-validation.yml`

M6-D adds no second automatic workflow.

## 15. Audit findings and closure

Formal G1 findings are recorded in `M6_D_COMPATIBILITY_AND_SECURITY_AUDIT.md`:

1. raw high-cardinality advisory audit/execution identities — corrected to controlled evidence hashes;
2. raw activation reviewer identity — corrected to reviewer evidence hash;
3. stale test fixtures and contract assertions — corrected to the hash-only contracts.

All fixes were append-only. No production authority was added. No unresolved G1 code-level security or compatibility finding remained at G1 closure.

## 16. Audited Head evidence

G1 audited Head:

`a9e2fddfadf7414c50ba23dec17332da53d5db82`

Permanent workflow:

- Run ID: `30540479319`
- Run number: `934`
- Java 21 / Maven / PostgreSQL: `success`
- Vben TypeScript / production build: `success`
- UniApp TypeScript / H5 / WeChat: `success`
- Repository hygiene: `success`

Maven evidence:

- aggregate: `1389 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `85 / 85`;
- security/minimization: `6 / 6`;
- deterministic hash-only evidence: `2 / 2`;
- activation/runtime-trust: `15 / 15`;
- transport review: `21 / 21`;
- ArchUnit module boundaries: `10 / 10`;
- Node foundation / activation / transport: `10 / 6 / 7`;
- `BUILD SUCCESS`: present.

Audited Head artifacts:

| Artifact | ID | Size | GitHub digest | Independently downloaded ZIP SHA-256 |
|---|---:|---:|---|---|
| `approval-maven-30540479319` | `8758682811` | `26793` | `sha256:0c83f89c64f1c65852863233b861744cbbccb5a939c53b521340240c87295924` | `0c83f89c64f1c65852863233b861744cbbccb5a939c53b521340240c87295924` |
| `approval-vben-30540479319` | `8758512620` | `18907` | `sha256:f12626045c92920063c984a0417977ff99c55b28bfb4022f3a0aec11acdb931e` | `f12626045c92920063c984a0417977ff99c55b28bfb4022f3a0aec11acdb931e` |
| `approval-mobile-30540479319` | `8758501786` | `10047` | `sha256:46c01e4e761ba73bed30e077da8bdbe105a78cde72b88547f73c9c5de360f2d2` | `46c01e4e761ba73bed30e077da8bdbe105a78cde72b88547f73c9c5de360f2d2` |
| `approval-hygiene-30540479319` | `8758462854` | `9232` | `sha256:bc2a92cfe438a0fa275e3a2210ca603274939108e55646a826eb141dade2e34d` | `bc2a92cfe438a0fa275e3a2210ca603274939108e55646a826eb141dade2e34d` |

## 17. Documented Head evidence rule

This document does not reuse the R0 or G1 workflow as final G2 evidence.

The exact commit containing this document must receive a new complete permanent workflow. All four jobs must be `completed / success`; all four artifact ZIP files must be independently downloaded; each local ZIP SHA-256 must exactly match the GitHub artifact digest; Maven aggregate, focused counts and `BUILD SUCCESS` must be recalculated from that documented-Head artifact.

The exact documented Head SHA, Run ID/number, artifact IDs/sizes/digests/local hashes, Maven counts and final PR ahead/behind/files/commits are recorded in the permanent PR #70 G2 evidence comment after successful validation.

## 18. Retained failed evidence

No failed or cancelled Run was deleted.

Retained formal-acceptance evidence includes:

- historical Run `30067321892` / #511 as pre-rebaseline evidence only;
- R0 Runs #915 and #916;
- G1 Runs #926, #929, #930 and #931;
- all earlier Checkstyle, endpoint-trust and workflow-boundary failures already documented in PR #70.

Each correction used a new append-only commit and a new complete workflow. No amend, rebase, squash, force push or history rewrite was used.

## 19. Reviews, threads and comments

At document creation:

- requested reviewers: none;
- review submissions: none;
- requested changes: none;
- inline review threads: none;
- top-level PR comments contain permanent R0/G1 evidence and no blocker;
- PR #70 remains Open + Draft;
- auto-merge remains disabled.

Review state must be rechecked after documented-Head validation and again after Ready for Review. A later actionable finding invalidates merge readiness until fixed and revalidated.

## 20. Explicitly not implemented or accepted

M6-D formal acceptance does not implement or imply:

- real OpenAI, Anthropic, Azure OpenAI, Gemini or other Provider adapter;
- real HTTP Provider client;
- real DNS, TLS, endpoint connection, egress or signature;
- runtime Secret retrieval or Secret material;
- production API key, credential or Prompt;
- customer knowledge source, attachment extraction, RAG, embeddings or vector database;
- AI persistence, durable state, Outbox, Queue, Worker or Scheduler;
- participant or management AI endpoint;
- Web/Mobile AI page;
- AI summary, risk recommendation or material-check product entry point;
- AI-driven approval decision;
- approve, reject, return, transfer, withdraw, terminate or migrate command;
- controlled automation;
- production Provider activation;
- executable activation plan or transport acceptance;
- Flyway migration;
- second automatic workflow;
- M6-E, M6-F or M6-G.

## 21. Conditional merge readiness

PR #70 may be considered merge-ready only when all are observed simultaneously:

- this exact documented Head has a complete permanent success Run;
- all four documented-Head ZIP digests match independently computed SHA-256 values;
- Maven aggregate and focused tests are recalculated and green;
- current `main` remains the recorded rebaseline or is merged again by a true Merge Commit and revalidated;
- behind is `0` and mergeable is `true`;
- PR remains Open + Draft until the explicit Ready step;
- no requested changes, unresolved actionable review thread or top-level blocker exists;
- Issue #66 remains Open;
- Issues #62, #13 and #14 remain Open;
- Issue #65 remains Closed / completed;
- PR #69 and PR #75 remain Merged / Closed;
- auto-merge remains disabled.

Ready for Review is a separate G3 action. Ready must not be combined with merge. Repeated independent review checks are required after Ready.

## 22. Merge method and exact-head gate

If all G3 gates pass, PR #70 may be merged only with:

- GitHub Merge Commit method;
- exact expected documented Head SHA;
- no squash;
- no rebase;
- no auto-merge.

If `main` changes before merge, the new `main` must be merged into the branch using a true Merge Commit and the complete permanent validation/artifact process repeated.

Issue #66 must remain Open immediately after PR merge.

## 23. Post-merge verification and Issue closure

After the PR Merge Commit, completion requires the natural `push → main` workflow bound to the exact merge SHA. A PR Run or workflow-dispatch Run cannot substitute.

The main Run must have all four jobs `success`; all four main artifact ZIP files must independently match GitHub digests; Maven aggregate, M6-D focused counts and `BUILD SUCCESS` must be recalculated from the main artifact.

PR #70 reviews, threads and comments must be checked again for post-merge actionable findings. Any actionable post-merge finding requires a separate bounded correction branch and Draft PR from latest main; PR #70 history is not modified and Issue #66 stays Open until correction closure.

Only after final main validation, artifact verification, review closure and final-state checks may Issue #66 be closed with `state_reason=completed`. Issues #62, #13 and #14 remain Open; Issue #65 remains Closed; PR #69 and PR #75 remain Merged / Closed.

## 24. Acceptance disposition

At document creation:

- R0 is complete;
- G1 is complete;
- the audited Head is permanently validated with exact artifacts;
- this G2 document is committed independently from functional code;
- PR #70 remains Open + Draft;
- Issue #66 remains Open;
- M6-E and M6-F have not started.

G2 is complete only after this exact documented Head has its own full permanent workflow, four exact artifact matches, recalculated Maven/focused evidence, updated PR title/body and permanent G2 evidence comment. Until then, PR #70 must remain Draft and must not be merged.
