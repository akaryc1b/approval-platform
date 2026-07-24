# M6-D AI Foundation — Runtime Trust and Non-Executable Activation Review

Status: `SIXTH_SAFE_SLICE_IMPLEMENTED`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #66
- branch: `agent/m6-d-ai-foundation`
- Draft PR: #70
- target branch: `main`
- implementation baseline `main`: `d769722cf7dd5418739a91ad4c45ca1a1c147502`
- Flyway remains V1–V32
- automatic PR/main validation remains `.github/workflows/approval-platform-validation.yml`

## Purpose

The sixth M6-D safe slice defines review-only runtime-trust contracts before any real Provider adapter gate. It does not read a secret, resolve DNS, perform a TLS handshake, open a network connection, grant a lease, invoke a Provider or authorize production.

The first five safe slices remain authoritative: advisory output is unverified and human-reviewed; masking and minimization happen before invocation; exact versions are mandatory; routing permits at most one Provider invocation; post-invocation retry/fallback is prohibited; configuration and deployment metadata remain immutable, hashed and non-authorizing.

## Metadata-only external secret resolver SPI

The framework-neutral SPI adds:

- `AiExternalSecretResolver`;
- `AiExternalSecretResolutionRequest`;
- `AiExternalSecretResolutionResult`.

The resolver method is `inspectReference`, not a byte-returning secret API. Requests contain only exact Provider/reference identity, a closed purpose, a SHA-256 evidence hash, and false authority flags. Results permanently require:

- `secretMaterialReturned = false`;
- `secretResolutionPerformed = false`;
- `networkCallAttempted = false`;
- `providerInvocationAuthorized = false`;
- `productionEnablementAuthorized = false`.

The production tree contains no resolver implementation. `DeterministicExternalSecretResolver` exists under test source only.

## Precomputed DNS and TLS evidence

`AiDnsResolutionEvidence` records exact endpoint/host identity, SHA-256 address-evidence hashes, a closed resolution status, bounded TTL and an evidence hash. It stores no raw IP address and performs no lookup. Statuses distinguish public pinned-set match, private/local address, rebinding, empty result and unknown evidence.

`AiTlsPeerEvidence` records exact endpoint/host identity, optional certificate SPKI SHA-256, closed hostname/chain/expiry/pin/redirect status and an evidence hash. It performs no handshake and stores no certificate, key or session material.

## Endpoint trust policy and assessment

`AiEndpointTrustPolicy` binds exact endpoint/host identity, allowed address-evidence hashes, allowed certificate SPKI hashes and maximum DNS TTL. It rejects redirect, rebinding, private-address, network-access and production-enablement authority.

`AiEndpointTrustAssessment` consumes only precomputed evidence and blocks identity mismatch, private/local resolution, rebinding, empty/unknown DNS, address pin mismatch, excessive TTL, TLS hostname/chain/expiry/pin failures, redirects and unknown TLS evidence. Success is only `TRUSTED_FOR_REVIEW`; it is not network or production authorization.

## Kill switch and non-granting lease

`AiProviderKillSwitch` has only `DISABLED` and `FAULT_DRILL_ONLY`. There is no enabled or active state.

`AiProviderActivationLease` has only `NOT_GRANTED`, `DENIED` and `EXPIRED`. There is no granted or active lease state. It permanently rejects Provider invocation, secret resolution, network access, apply authority and production enablement.

## Two-person activation review

`AiProviderActivationReviewBundle` binds exact hashes for deployment, readiness, fault drill, change set, endpoint trust, secret-reference evidence and kill-switch evidence.

`REVIEW_COMPLETE` requires at least two distinct approved reviewers in at least two distinct roles from security, platform and operations. Duplicate reviewers, a single role or any rejection produces `BLOCKED`. Completion remains non-authorizing.

## Non-executable activation plan

`AiProviderActivationPlan` is permanently:

- `NON_EXECUTABLE_REVIEW_ONLY`;
- `leaseGranted = false`;
- `secretResolutionAuthorized = false`;
- `networkAccessAuthorized = false`;
- `providerInvocationAuthorized = false`;
- `applyAuthorized = false`;
- `productionEnablementAuthorized = false`;
- `approvalAutomationAuthorized = false`.

A plan can be `REVIEW_READY` only when the two-person review is complete, identities/hashes match, the kill switch is `FAULT_DRILL_ONLY`, the lease remains `NOT_GRANTED`, and endpoint trust is `TRUSTED_FOR_REVIEW`. `REVIEW_READY` creates no command, lease, secret resolution, network operation or Provider call.

## Deterministic tests

The sixth slice adds 15 JUnit tests covering secret authority rejection, metadata-only resolution, endpoint trust, rebinding/private-address/redirect/pin failures, deterministic hashing, distinct reviewer/role requirements, review rejection, non-executable planning, disabled kill-switch, denied lease and blocked endpoint trust.

A separate permanent Node boundary adds six checks proving the resolver cannot return material, DNS/TLS evidence is precomputed, kill switch and lease never grant execution, two-person review remains non-authorizing, activation plans remain non-executable and deterministic implementations remain test-only.

## Permanent safety boundary

The combined M6-D slices still contain:

- no real Provider network call or network client;
- no production Provider, protocol-validator or secret-resolver implementation;
- no runtime secret material or actual secret resolution;
- no DNS lookup, TLS handshake, endpoint connection or network egress;
- no production credential, API key, Prompt content or customer knowledge;
- no database persistence or durable AI state;
- no Flyway `V33`;
- no approval-state command, retry or post-invocation fallback;
- no Provider activation lease or executable activation plan;
- no second automatic workflow;
- no M5 migration/runtime-binding/frozen-governance modification.

## Still blocked

A later production adapter gate still requires explicit acceptance for real secret management, DNS/TLS and egress enforcement, request/response mapping, an authoritative kill switch, durable activation lease/idempotent command, persisted review/audit evidence, production Prompt registration, API/UI exposure and any M6-E/M6-F behavior.

No contract in this slice authorizes those capabilities.
