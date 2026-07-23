# M6-D AI Foundation — Artifact Authorization and Offline Evaluation

Status: `THIRD_SAFE_SLICE_IMPLEMENTED`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #66
- branch: `agent/m6-d-ai-foundation`
- Draft PR: #70
- target branch: `main`
- implementation baseline `main`: `d769722cf7dd5418739a91ad4c45ca1a1c147502`
- Flyway remains V1–V32
- automatic PR/main validation remains `.github/workflows/approval-platform-validation.yml`

## Repository boundary

M6-D remains independent from M5 Issue #56 / Draft PR #58. The AI modules remain framework neutral and are not wired to participant endpoints, management endpoints, task completion, process migration, persistence or a production provider adapter.

The first two safe slices remain authoritative:

- provider-neutral advisory SPI and exact failure classifications;
- server-owned tenant, operator, request, trace and authorized-resource evidence;
- Form/UI field permission, masking and bounded minimization;
- structured advisory-only output with complete version lineage;
- exact provider registry, deterministic server-owned routing, budgets and circuit breaker;
- no retry or provider fallback after one invocation starts;
- contract-only audit, usage and low-cardinality metrics evidence;
- deterministic test-only provider and permanent security boundaries.

Every result remains `ADVISORY`, `UNVERIFIED_ADVISORY` and `needsHumanReview = true`.

## Third safe slice

### Metadata-only Prompt registry

`AiPromptTemplateDescriptor` authorizes only:

- exact prompt-template ID;
- exact version;
- exact content hash;
- allowed advisory capabilities;
- metadata availability classification.

It contains no Prompt body, message list, instructions or template text. The repository still has no production Prompt resource directory.

Supported availability is limited to:

- `METADATA_ONLY`;
- `TEST_FIXTURE_METADATA`.

Registration of metadata is not equivalent to approval of Prompt content or production use.

### Knowledge-source metadata boundary

`AiKnowledgeSourceDescriptor` records only exact source identity/version/hash, allowed capabilities and a closed source kind.

The M6-D constructor rejects:

- `containsCustomerData = true`;
- `retrievalEnabled = true`;
- a `NONE` kind that does not match the exact `none` version;
- non-`NONE` metadata represented as the `none` version.

No retrieval implementation, embedding, document content, vector store or customer data is added.

### Policy and output-schema metadata

`AiPolicyDescriptor` requires:

- exact policy ID/version/hash;
- allowed advisory capabilities;
- `humanReviewRequired = true`;
- `authoritativeDecisionAllowed = false`;
- `postInvocationRetryAllowed = false`.

`AiOutputSchemaDescriptor` requires:

- exact output schema ID/version;
- allowed advisory capabilities;
- advisory-only output;
- mandatory human review;
- the foundation sections for summary, confidence, limitations, version provenance and human-review evidence.

Neither descriptor can authorize approval automation or authority escalation.

### Exact artifact authorization

`AiAdvisoryArtifactRegistry` indexes exact:

- prompt-template metadata;
- knowledge-source metadata;
- policy metadata;
- output-schema metadata.

It rejects duplicate exact registrations.

A route is authorized only when all four exact version references exist and each descriptor allows the requested capability. Missing or mismatched metadata fails closed before a route can match a Provider.

The public `AiProviderRegistry` constructor now requires an artifact registry. Its route match includes exact artifact authorization.

A package-private compatibility constructor exists only for deterministic test providers. It rejects every non-`DETERMINISTIC_MOCK` provider type. Permanent boundaries continue to prove that deterministic mocks do not exist in production source.

### Offline evaluation protocol

The third slice adds metadata-only evaluation contracts:

- `AiEvaluationCase`;
- `AiEvaluationSuite`;
- `AiEvaluationObservation`;
- `AiEvaluationReport`;
- `AiEvaluationRunner`.

Evaluation cases bind:

- bounded case ID;
- capability;
- exact Provider/model/Prompt/knowledge/policy/output versions;
- expected result classifications;
- criticality;
- whether one Provider invocation is required;
- minimum confidence;
- minimum evidence-reference count;
- maximum observed platform latency.

Observations contain only:

- case ID;
- bounded fixture hash;
- an already-produced coordinated outcome.

The evaluator is pure and offline. It has no Provider call site, no secret access and no network dependency.

### Evaluation gate semantics

A case fails when evidence shows:

- a missing observation;
- a post-invocation fallback attempt;
- an invocation/no-invocation mismatch;
- selected-route or result version mismatch;
- unexpected classification;
- platform latency over the case limit;
- non-advisory authority;
- missing human review;
- confidence below the gate;
- evidence references below the gate;
- missing structured result when one is required.

The report contains only bounded classifications and hashes, not Prompt or model-response content.

The report hash is deterministic over:

- suite ID/version and allowed failure count;
- sorted case expectations;
- exact version references;
- sorted case results;
- fixture hashes;
- classifications and failure codes.

### Non-authorizing report

Even a passing report can only state that the M6-D foundation evaluation passed.

`AiEvaluationReport` permanently requires:

- `productionEnablementAuthorized = false`;
- `approvalAutomationAuthorized = false`.

The constructor rejects either flag being true.

A passing offline suite does not authorize:

- production Provider configuration;
- network egress;
- production Prompt activation;
- customer knowledge retrieval;
- participant or management API exposure;
- an approval-state command;
- M6-E or M6-F behavior.

## Evaluation coverage

The third slice adds deterministic tests for:

- exact artifact-bundle authorization;
- unknown prompt version rejection;
- customer knowledge rejection;
- knowledge retrieval rejection;
- policy human-review enforcement;
- policy authority-escalation rejection;
- output-schema advisory enforcement;
- duplicate artifact registration rejection;
- Provider route matching with complete artifact metadata;
- Provider route mismatch with missing artifact metadata;
- passing foundation evaluation;
- missing critical observation;
- unexpected classification;
- deterministic report hash;
- fixture hash affecting the report hash;
- production and automation authorization remaining false.

The permanent Node boundary additionally proves:

- Prompt metadata classes contain no Prompt body or instructions field;
- knowledge retrieval and customer data remain prohibited;
- policy and output descriptors retain advisory/human-review constraints;
- public Provider registration includes artifact authorization;
- offline evaluation has no `.advise(...)` call;
- evaluation reports cannot authorize production or automation;
- no second automatic workflow, V33, M5 source or frozen governance change appears.

## Permanent safety boundary

The combined M6-D slices still contain:

- no real Provider network call or network client;
- no production Provider implementation or Spring wiring;
- no production credential or API key;
- no production Prompt content or Prompt resource;
- no customer knowledge data, retrieval or embeddings;
- no attachment-content extraction;
- no database persistence or durable circuit/evaluation state;
- no Flyway `V33`;
- no approval, rejection, return, transfer, withdrawal, termination or migration command;
- no retry or post-invocation Provider fallback;
- no production deterministic mock;
- no second automatic workflow;
- no M5 migration, runtime-binding or frozen M3/M4 governance modification.

## Still blocked

This slice does not add:

- concrete Provider/model network adapters;
- production secret management;
- production Prompt registration or content;
- customer knowledge retrieval;
- provider-reported token, latency or cost integration;
- durable AI audit, evaluation or circuit persistence;
- provider retry/background workers;
- participant or management AI endpoints;
- Web or Mobile AI controls;
- AI-driven approval-state changes;
- M6-E or M6-F behavior.

A production adapter requires a separate accepted gate covering secret management, network egress, exact model and artifact authorization, provider-specific structured validation, operational disablement, usage verification and failure drills. Controlled automation remains a later independent gate requiring human confirmation, server-side reauthorization, bounded reason, idempotency, audit and risk acceptance.
