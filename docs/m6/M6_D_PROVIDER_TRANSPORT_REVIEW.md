# M6-D AI Foundation — Provider Transport Review Contracts

Status: `SEVENTH_SAFE_SLICE_IMPLEMENTED`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #66
- branch: `agent/m6-d-ai-foundation`
- Draft PR: #70
- target branch: `main`
- implementation baseline `main`: `d769722cf7dd5418739a91ad4c45ca1a1c147502`
- Flyway remains V1–V32
- automatic validation remains `.github/workflows/approval-platform-validation.yml`

## Purpose

The seventh M6-D safe slice defines hash-only Provider transport mapping and review contracts. It does not serialize a real request body, compute a real signature, read a Secret, resolve DNS, open a socket, invoke a Provider, retry a request or enable production.

The previous six safe slices remain authoritative. Advisory output remains unverified and human-reviewed; exact versions and artifacts remain mandatory; routing permits at most one Provider invocation; post-invocation retry/fallback remains prohibited; deployment and activation evidence remains non-executable.

## Provider transport mapping SPI

The framework-neutral SPI adds:

- `AiProviderTransportMapper`;
- `AiProviderTransportMappingRequest`;
- `AiProviderTransportMappingResult`;
- `AiProviderTransportFixtureObservation`.

Mapping requests contain exact Provider/capability/endpoint/profile identity, schema hashes, canonical payload hash, signing-input hash, byte/field counts, timeout and cancellation metadata. They contain no body, header values or Secret material and cannot authorize dispatch, Provider invocation or production.

Mapping results are closed as `MAPPED_FOR_OFFLINE_VALIDATION`, `REJECTED`, `UNSUPPORTED` or `UNKNOWN`. They contain only hashes and stable codes. They cannot store request/response bodies, access Secret material, attempt a network call, invoke a Provider, retry, fall back after invocation or authorize production/automation.

`DeterministicProviderTransportMapper` exists only under test source.

## Canonical payload evidence

`AiProviderPayloadCanonicalizationPolicy` bounds field count, canonical byte count and permitted data classifications. It requires redaction for confidential/restricted evidence and rejects raw-value storage, Secret material, signing authority, network access and production enablement.

`AiProviderCanonicalPayloadEvidence` stores only:

- exact policy/provider/capability/schema identity;
- JSON pointer;
- value type;
- SHA-256 value hash;
- canonical byte count;
- data classification;
- redaction state;
- deterministic payload hash.

It stores no raw field value. Duplicate pointers, unauthorized classifications, unredacted sensitive fields and budget excess fail closed. Equivalent field ordering produces the same hash.

## Signing-input evidence

`AiProviderSigningInputEvidence` creates deterministic signing-input metadata from exact endpoint/mapper/profile identity, request schema hash, canonical payload hash, nonce hash, timestamp and safe signed-header names.

It stores no header values, signature or Secret. Sensitive header names such as `authorization`, `cookie`, `proxy-authorization` and `x-api-key` are rejected. `signatureComputed`, `secretMaterialAccessed`, `networkCallAttempted` and production authority remain false.

## Offline lifecycle evaluation

`AiProviderTransportLifecycleEvaluator` consumes mapping results and precomputed fixture observations only. It never dispatches a request.

Closed outcomes cover:

- ready for offline assertion;
- cancelled before dispatch;
- timed out before dispatch;
- mapping rejection/unsupported/unknown;
- malformed JSON;
- schema drift or schema mismatch;
- unknown response fields;
- oversized or empty body evidence;
- connection-error fixture;
- unknown response.

`AiProviderTransportLifecycleReport` permanently rejects network dispatch, Provider invocation, Secret access, retry, post-invocation fallback, production enablement and approval automation.

## Redaction-safe audit

`AiProviderTransportAuditEvidence` binds Provider/capability/endpoint/mapper identity, canonical payload hash, signing-input hash, request/envelope/lifecycle hashes, bounded status and stable codes.

It permanently rejects raw request/response bodies, header values, Secret material and network payload storage. The audit hash is deterministic and does not contain business input or Provider output.

## Non-executable acceptance checklist

`AiProviderTransportAcceptanceChecklist` binds exact deployment, activation review, mapper, canonicalization, lifecycle, malformed-response, schema-drift, cancellation and audit evidence hashes.

Required gates cover deterministic canonicalization, Secret absence, prohibited dispatch, malformed/schema-drift fail-closed behavior, timeout/cancellation behavior, redaction audit and the prior two-person activation review. Passed evidence must cover Security, Platform and Operations.

Even `REVIEW_COMPLETE` remains `NON_EXECUTABLE_TRANSPORT_ACCEPTANCE` with apply, network, Provider invocation, production and automation authority permanently false.

## Deterministic validation

The seventh slice adds 21 behavior checks across transport SPI safety, canonicalization/signing, lifecycle failure classifications, redaction-safe audit and the non-executable acceptance checklist.

A separate permanent Node boundary adds seven checks proving transport mapping remains metadata-only, payload/signing evidence contains no raw values, lifecycle is zero-call and no-retry, audit is hash-only, acceptance is non-executable, deterministic mapping remains test-only and the existing workflow remains the only automatic workflow.

## Permanent safety boundary

The combined M6-D work still contains:

- no real Provider adapter or network client;
- no real request/response body mapping;
- no signature computation or Secret access;
- no DNS/TLS/network operation;
- no retry or post-invocation fallback;
- no production Prompt/customer knowledge;
- no persistence or V33;
- no executable activation lease/plan;
- no approval-state command or M6-E/M6-F behavior.

A production transport adapter requires a later explicit gate covering real serialization/parsing, runtime Secret/signature lifecycle, DNS/TLS/egress enforcement, durable audit/idempotency, authoritative kill switch/lease, operational drills and independent acceptance.
