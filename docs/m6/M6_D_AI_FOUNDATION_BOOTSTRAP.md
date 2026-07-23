# M6-D AI Foundation — First Safe Slice

Status: `FIRST_SAFE_SLICE_IMPLEMENTED`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #66
- branch: `agent/m6-d-ai-foundation`
- Draft PR: #70
- target branch: `main`
- implementation baseline `main`: `d769722cf7dd5418739a91ad4c45ca1a1c147502`
- Flyway remains V1–V32
- automatic PR/main validation remains `.github/workflows/approval-platform-validation.yml`

## Repository analysis

The implementation follows the existing repository boundaries:

- Domain and SPI contracts remain framework neutral;
- application-style orchestration depends only on exact contracts and server-owned evidence;
- `ApprovalPrincipal` and request context remain the authority for tenant, operator, requestId and traceId;
- Form Schema `FieldType` and UI Schema `FieldAccess` are reused instead of creating parallel permission semantics;
- `HIDDEN` and invisible fields are omitted before provider invocation;
- sensitive `READONLY` fields are omitted unless an explicit policy masks them;
- metrics use only closed low-cardinality dimensions;
- audit is defined as a contract only, with no persistence table in this slice.

## Implemented contracts

### Provider-neutral SPI

`approval-ai-spi` defines:

- provider identifier, provider type and exact provider version;
- closed capability descriptors and authorized model versions;
- structured provider request and cancellation boundary;
- structured advisory result;
- exact failure classification;
- audit linkage contract.

No concrete production provider adapter exists. The only provider implementation is the deterministic test fixture under `src/test`.

### Exact version evidence

One advisory attempt carries separate typed references for:

- provider version;
- model version;
- prompt-template version and content hash;
- knowledge-source version and content hash;
- input/masking policy version and content hash;
- output-schema version.

These values cannot be collapsed into one opaque version string. Customer knowledge data is explicitly blocked by the first-slice execution preflight.

### Trusted request construction

`approval-ai-core` builds provider requests from:

- server-owned tenant and operator context;
- server-owned requestId and traceId;
- an already authorized task or instance resource;
- the server-resolved allowed field set;
- the requested advisory capability;
- exact policy and version evidence;
- a bounded timeout.

The untrusted intent contract contains only capability and resource identity. It has no tenant, operator, authority, audit identity, credential, model authorization or knowledge authorization field.

### Data minimization and masking

Before provider invocation the core:

- rejects cross-tenant or forged resource identity;
- intersects inputs with the authorized field set;
- removes `HIDDEN`, invisible and null fields;
- omits sensitive `READONLY` values by default;
- masks sensitive editable values by default;
- applies versioned include, mask and omit rules;
- blocks prompt-injection markers;
- limits field count, text length, total text, collection size and nesting depth;
- rejects binary data and unsupported object types;
- permits attachment metadata only and rejects raw attachment content.

### Advisory-only output

The result contract separates:

- summary;
- observations;
- risk signals;
- missing materials;
- recommendations;
- evidence references;
- confidence;
- limitations;
- human-review requirement;
- complete version evidence.

Every result is typed as `ADVISORY` and `UNVERIFIED_ADVISORY`. The result has no decision, command or authoritative approval-status field. Validation rejects command language, missing or unauthorized evidence, version mismatch, malformed output and attempts to remove human review.

### Failure, audit and metrics boundaries

The closed provider outcome model is:

- `SUCCESS`;
- `DISABLED`;
- `UNSUPPORTED`;
- `REJECTED`;
- `TIMEOUT`;
- `PROVIDER_UNAVAILABLE`;
- `INVALID_OUTPUT`;
- `POLICY_BLOCKED`;
- `LOW_CONFIDENCE`;
- `UNKNOWN`.

The execution service performs no automatic retry and returns a structured outcome. It is not connected to task completion or any approval command path.

The audit contract links requestId/traceId, tenant, operator, resource, capability, policy version, all provider/model/prompt/knowledge/output versions, result classification and an optional later human-decision reference.

Metrics expose only:

- capability;
- result;
- failure class;
- provider type;
- policy result.

Tenant, user, task, instance, request, trace, prompt, response and arbitrary error content are not metric dimensions.

## Deterministic evaluation coverage

The test-only provider:

- performs no network access;
- consumes no key or credential;
- uses no production prompt or customer knowledge data;
- deterministically produces structured summary, observation, risk and recommendation output;
- deterministically simulates low confidence, disabled, unsupported, rejected, timeout, provider unavailable, invalid output, policy blocked, unknown and exception outcomes;
- can fail a test if a forbidden field reaches the provider boundary.

Tests cover hidden-field leakage, cross-tenant resources, forged resource identity, prompt injection, oversized input, raw attachment content, low confidence, disabled provider, timeout fallback, provider exception, malformed output, unknown model version, customer-knowledge blocking and prohibited approval-command output.

## Permanent boundary

The permanent Node boundary test and ArchUnit rule prove that this slice contains:

- no production provider implementation or network client;
- no production credential loading;
- no production prompt asset;
- no customer knowledge data;
- no JDBC, SQL, Flyway or persistence dependency;
- no `V33`;
- no approval, rejection, return, transfer, withdrawal, termination or migration command call;
- no production deterministic mock;
- no second automatic workflow;
- no M5 migration, runtime-binding or frozen M3/M4 governance change on the M6-D branch.

## Still blocked

This slice does not add:

- real model network calls;
- production provider configuration or credentials;
- production prompts;
- retrieval from customer knowledge sources;
- attachment-content extraction;
- database persistence;
- retries, circuit-breaker implementation or provider routing;
- participant or management API endpoints;
- Web or Mobile AI controls;
- approval-state automation;
- M6-E or M6-F behavior.

Any future controlled automation requires a separate accepted gate with explicit human confirmation, server-side reauthorization, bounded reason, idempotency, audit and risk validation.
