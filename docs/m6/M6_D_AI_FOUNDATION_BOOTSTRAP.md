# M6-D AI Foundation — Rebaselined Safe-Slice Overview

Status: `SEVENTH_SAFE_SLICE_IMPLEMENTED_REBASELINED`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #66
- branch: `agent/m6-d-ai-foundation`
- Draft PR: #70
- target branch: `main`
- original implementation baseline `main`: `d769722cf7dd5418739a91ad4c45ca1a1c147502`
- original implementation head: `9d588215e869c8f1332c0bc1a2809fbd235c2efa`
- formal rebaseline `main`: `735e41526371ea481b31af377e3410d085160f7e`
- controlled synchronization Merge Commit: `c0078be9669c4936edb73f3c195f75fc0f6bc9e8`
- current-main Flyway set: `V2` through `V37`, plus `V39` through `V48`
- M6-D adds no Flyway migration relative to the formal rebaseline
- the only workflow with automatic `pull_request` or `push` triggers remains `.github/workflows/approval-platform-validation.yml`

This document consolidates the seven previously implemented M6-D safe slices against the current main baseline. The original implementation commits and permanent Runs remain historical evidence; they are not reused as formal-acceptance evidence after rebaseline.

## Repository boundary

M6-D remains provider-neutral, advisory-only, tenant-safe, zero-call for all deployment and activation review paths, and independent from M5 execution. The AI modules are not wired to participant endpoints, management endpoints, task completion, process migration, persistence or a production Provider adapter.

Every structured result remains:

- `ADVISORY`;
- `UNVERIFIED_ADVISORY`;
- `needsHumanReview = true`.

No M6-D type represents a verified approval decision, grants execution authority or produces an approval/process command.

## Safe slice 1 — Provider-neutral advisory SPI

The SPI defines closed Provider, model, capability, request, outcome, cancellation, version and usage contracts without Spring, Flowable, HTTP, JDBC or Provider-specific dependencies.

The production tree contains no real OpenAI, Anthropic, Azure OpenAI, Gemini or other Provider adapter. Deterministic Provider behavior remains test-only.

Provider outcomes are advisory evidence only. Failure, timeout, cancellation, low confidence and unknown results fail closed and require human review.

## Safe slice 2 — Server-owned identity and data minimization

Tenant, operator, permission, request and trace identity comes only from server-owned context. Client request data cannot manufacture or override identity, authority, audit, worker, lease or engine identity.

Field permission and tenant isolation execute before Provider mapping. Unauthorized data cannot enter a Provider payload.

Data minimization and masking execute before mapping. Confidential or restricted fields must be rejected or redacted. Prompt injection controls, bounded field/character limits and stable classifications remain mandatory.

Audit and metrics evidence stores only bounded classifications, stable codes, hashes, versions and low-cardinality counters. It stores no raw Prompt, business input, Provider request/response, header value, token, API key or credential.

## Safe slice 3 — Provenance, artifact registry and offline evaluation

Advisory evidence binds exact:

- Provider identity, type and capability;
- model version;
- Prompt metadata version and hash;
- knowledge-source metadata version and hash;
- policy version and hash;
- output-schema version;
- request/envelope and canonical payload hashes;
- audit evidence hash.

`AiPromptTemplateDescriptor` contains metadata only and no Prompt body, message list or instructions.

`AiKnowledgeSourceDescriptor` rejects customer data and retrieval enablement. No RAG, embeddings, vector store or customer knowledge source exists.

`AiPolicyDescriptor` requires human review, rejects authoritative decisions and prohibits post-invocation retry. `AiOutputSchemaDescriptor` requires advisory-only output.

`AiEvaluationRunner` consumes already-produced fixture observations. It performs no Provider invocation and cannot authorize production or approval automation.

## Safe slice 4 — Deterministic routing and immutable configuration

Provider routing is deterministic and server-owned. Invocation budgets, timeout and cancellation semantics are bounded.

At most one Provider may be invoked for a request. Pre-invocation candidate selection is distinct from fallback after invocation. Post-invocation fallback and unsafe retry are prohibited.

Circuit Breaker state is operational evidence only and never an authority source.

`AiAdvisoryConfigurationSnapshot` is immutable and hash-bound. It contains no endpoint, credential, Secret, API key, Prompt body or customer data. Its stage remains `DRY_RUN_ONLY`.

Startup preflight validates exact configuration, policy, Provider and artifact metadata without invoking a Provider. Dry-run assembly is zero-call and non-authorizing.

## Safe slice 5 — External references and offline deployment readiness

`AiExternalSecretReference` stores external reference metadata only. It contains no Secret material and cannot authorize runtime Secret resolution or Provider invocation.

Endpoint and egress descriptors are metadata-only. They require exact HTTPS/public-DNS/port/path constraints, reject redirects and private-address authority, and do not perform DNS, TLS or network operations.

Protocol-validation SPIs are structural and zero-call. No production validator implementation exists.

`AiProviderDeploymentSnapshot` is immutable, hash-bound and permanently `FAULT_DRILL_ONLY`. Deployment readiness consumes metadata and fixture evidence only. `READY_FOR_FAULT_DRILL` does not mean production-ready or active.

Failure drills compare precomputed observations. Deployment change sets are non-applying and require human review for changes.

## Safe slice 6 — Runtime trust and non-executable activation review

`AiExternalSecretResolver` exposes metadata inspection rather than Secret retrieval. The deterministic resolver remains test-only.

DNS and TLS evidence is precomputed. No lookup, handshake, socket, certificate material or raw address is produced by M6-D.

Kill Switch states remain `DISABLED` or `FAULT_DRILL_ONLY`. Activation lease states cannot grant authority.

Two-person review requires distinct approved reviewers and roles. `REVIEW_COMPLETE` is review evidence only.

`AiProviderActivationPlan` remains permanently `NON_EXECUTABLE_REVIEW_ONLY`. It cannot grant a lease, resolve a Secret, access a network, invoke a Provider, apply a change, enable production or authorize approval automation.

See `M6_D_RUNTIME_TRUST_ACTIVATION_REVIEW.md` for the detailed contract inventory.

## Safe slice 7 — Offline Provider transport review

Transport mapping contracts contain exact Provider/capability/endpoint/profile identity, hashes, byte/field counts, timeout and cancellation metadata only. They contain no raw request body, response body, header value or Secret material.

Canonical payload evidence stores JSON pointer, value type, SHA-256 hash, byte count, classification and redaction evidence. It stores no raw field value.

Signing-input evidence stores hashes and safe header names only. It computes no signature and rejects sensitive header names such as `authorization`, `cookie`, `proxy-authorization` and `x-api-key`.

Transport lifecycle evaluation consumes precomputed fixture observations and fails closed for cancellation, timeout, mapping rejection, malformed JSON, schema drift, unknown fields, oversized/empty body evidence, connection error and unknown response.

Transport audit evidence is hash-only and redaction-safe. The acceptance checklist remains permanently `NON_EXECUTABLE_TRANSPORT_ACCEPTANCE`.

See `M6_D_PROVIDER_TRANSPORT_REVIEW.md` for the detailed contract inventory.

## Deterministic evidence and reproducibility

Configuration, deployment, route, payload, request, envelope, lifecycle, audit, review and checklist evidence uses canonical, deterministic hashing over bounded metadata.

Equivalent input and version metadata must produce identical evidence. Hash mismatch, identity mismatch, missing registration, unknown classification or authority escalation fails closed.

The permanent Node boundaries continue to prove:

- production AI code has no network client, credentials, Provider adapter or Prompt asset;
- AI code has no persistence dependency;
- AI contracts cannot execute approval or migration commands;
- routing invokes at most one Provider and prohibits post-invocation fallback;
- artifact and evaluation metadata cannot contain Prompt content or authorize production;
- preflight and dry-run remain zero-call;
- deployment, external-reference and failure-drill paths remain zero-call;
- activation review and transport review remain non-executable;
- deterministic implementations remain test-only;
- only the established permanent validation workflow is automatic;
- M6-D adds or modifies no Flyway migration relative to current main;
- M5 migration, runtime-binding and frozen governance boundaries remain untouched.

## Historical validation retained

The pre-rebaseline implementation head `9d588215e869c8f1332c0bc1a2809fbd235c2efa` was permanently validated by Run `30067321892` / #511. That Run remains historical evidence only and is not accepted for the rebaselined or documented Head.

Retained historical failures include the Checkstyle, endpoint-trust hash and workflow-directory assumption failures recorded in PR #70. They remain visible and were corrected with append-only commits; no Run or history was deleted.

## Permanent safety boundary

The combined M6-D work contains:

- no real Provider adapter, transport client or network call;
- no real request/response serialization or parsing;
- no production protocol validator, transport mapper or Secret resolver;
- no runtime Secret material, signature computation, DNS lookup or TLS handshake;
- no production credential, API key, Prompt content or customer knowledge;
- no attachment extraction, RAG, vector database or embeddings;
- no database persistence, durable AI state, Outbox, Queue, Worker or Scheduler;
- no Flyway migration added or modified by M6-D relative to current main;
- no retry or post-invocation Provider fallback;
- no granted activation lease, executable activation plan or executable transport acceptance;
- no participant/management AI endpoint or Web/Mobile AI page;
- no approve, reject, return, transfer, withdraw, terminate or migrate command path;
- no second automatic workflow;
- no modification to M5 migration/runtime binding, M6-A connector invocation, M6-B event delivery or M6-C Draft-only template/component semantics;
- no M6-E or M6-F behavior.

## Still blocked / not implemented

M6-D does not implement:

- concrete production Provider/model adapters;
- runtime request/response serialization or malformed-response handling against a real Provider;
- production Secret resolution, signing or credential rotation;
- real DNS/TLS/egress enforcement;
- production Prompt or customer knowledge registration;
- durable Provider activation, lease, idempotency or audit persistence;
- AI summary, risk recommendation or material-check product entry points;
- participant or management AI APIs;
- Web/Mobile AI controls;
- controlled automation or AI-driven approval-state changes.

A production Provider gate remains a separate, explicitly reviewed future scope. Controlled automation remains a later independent gate requiring human confirmation, server-side reauthorization, bounded reason, idempotency, audit and risk acceptance. Neither M6-E nor M6-F is started by this rebaseline.
