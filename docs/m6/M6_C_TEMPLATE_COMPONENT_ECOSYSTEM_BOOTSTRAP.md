# M6-C Template and Component Ecosystem Bootstrap

Status: `SECOND_SAFE_SLICE_IMPLEMENTED`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #65
- Draft PR: #69
- branch: `agent/m6-c-template-component-ecosystem`
- target branch: `main`
- synchronized main: `d769722cf7dd5418739a91ad4c45ca1a1c147502`

## Parallel milestone boundary

M6-C develops in parallel with M5 and remains independent from Issue #56 and Draft PR #58. The implementation does not modify migration source, runtime binding semantics, M5 plans or M5 verification/reconciliation behavior.

## Implemented first safe slice

The first implementation slice establishes deterministic, tenant-safe contracts in `ProcessTemplateContracts`, `ProcessTemplatePackageValidator`, `ProcessTemplateCanonicalHasher`, `ProcessTemplateImportPreviewService` and the strict server JSON codec:

- process template package manifest identity, version, title, summary, category and compatibility range;
- producer and capability metadata that cannot carry tenant, operator, permission, credential or engine deployment authority;
- dependency declarations for form fields, components, connector capabilities, business reference types, organization/identity placeholders and minimum platform protocols;
- included Approval DSL, Release Package, Form Schema and UI Schema artifact references with safe resource names and SHA-256 content hashes;
- deterministic package hashing independent of dependency, artifact, capability and rendering-support order;
- import preview findings, missing dependencies, compatibility results, target draft identities, tenant-local rebinding requirements and deterministic plan hashes;
- target-tenant-only Form Package, connector, organization/identity, business reference and component implementation selection;
- third-party component descriptors with field-type support, property-schema whitelist, rendering support, readonly fallback and unsupported-version behavior;
- unknown-component readonly fallback without dynamic component loading;
- strict JSON duplicate-key detection, unknown-field rejection and bounded decoding.

The preview service is intentionally side-effect free. It has no store, marketplace, publish, deployment, activation or engine port and creates no release or runtime state.

## Implemented second safe slice

The second implementation slice adds `ProcessTemplateDraftCreationService` as a narrow server-side gate from an immutable preview plan to one existing Approval design draft:

- recomputes package validation, dependency resolution, compatibility and tenant-local bindings at execution time;
- requires the caller-supplied expected plan hash to match the recomputed deterministic plan hash;
- rejects stale plans before any draft importer invocation;
- requires the write `RequestContext` tenant to equal the preview target tenant;
- requires exactly one immutable tenant-local Form Package binding;
- requires the target Form Package key to match the target Approval DSL definition key and to declare a positive version;
- requires exactly one matching Approval DSL or Release Package artifact reference;
- binds the package artifact reference to the supplied secure Artifact Transfer envelope hash;
- delegates DSL/release integrity verification, target Form Package validation, compilation, idempotency, audit and design-draft persistence to the existing `ApprovalArtifactTransferService`;
- accepts only a returned `DRAFT` with the exact target definition identity, Form Package version, payload hash and envelope hash.

This slice creates only an editable tenant-local design draft. It exposes no publish, deploy, activate, marketplace, remote download or component-loader operation. A failed or stale plan never invokes the draft importer.

## Security limits and rejection behavior

The package and decoder enforce:

- maximum package bytes: 2 MiB;
- maximum JSON depth: 64;
- maximum JSON element count: 30,000;
- maximum string length: 64 KiB;
- maximum dependencies: 500;
- maximum component properties: 50;
- maximum included artifact references: 100;
- duplicate JSON key rejection;
- duplicate dependency, artifact, component and property-schema rejection;
- unknown JSON field rejection;
- malformed Unicode rejection;
- path traversal and unsafe resource-name rejection;
- content-hash recomputation and tamper rejection;
- JavaScript, script tags, expressions, remote modules, dynamic imports, executable URLs and HTML data URL rejection;
- cross-tenant binding rejection.

## Fixtures and tests

Fixtures cover:

- valid template package;
- missing dependency preview;
- incompatible platform version;
- tampered content hash;
- duplicate dependency;
- cross-tenant binding attempt;
- script/dynamic import rejection;
- unknown component readonly fallback;
- path traversal;
- duplicate JSON keys;
- unknown tenant field;
- malformed Unicode.

Application and API tests cover deterministic package and plan hashes, compatibility, dependency resolution, tenant rebinding, safe fallback, strict JSON and preview-only behavior. Draft-creation tests cover stale-plan rejection, non-importable plans, cross-tenant write contexts, artifact-envelope mismatch, missing source artifacts, exact Form Package selection, immutable target versions, idempotency-context propagation and DRAFT-only results. The boundary test verifies that no `V33` migration or second permanent workflow exists and that the slice has no marketplace/download/class-loader/release-mutation dependency.

## Blocked until a later gate

- marketplace persistence;
- remote package download;
- dynamic component loading or remote modules;
- trusted package-supplied target bindings;
- direct publication, deployment or activation during import;
- database persistence or any `V33` migration;
- changes to M5 migration source or PR #58;
- a second permanent GitHub Actions workflow.

## Shared-core coordination

Future changes to Approval DSL, Form Schema, Release Package export/import or renderer protocols require explicit compatibility review and merge-order coordination. Later `main` changes must be incorporated by merge commit only; rebase and force push remain prohibited.
