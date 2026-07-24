# M6-C Template and Component Ecosystem Bootstrap

Status: `FOURTH_SAFE_SLICE_IMPLEMENTED`

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

## Implemented third safe slice

The third implementation slice adds `ProcessTemplateImportCoordinator` and the server-owned `ProcessTemplateTenantRegistryResolver` port:

- preview and draft-creation commands no longer accept `TenantRegistrySnapshot` from a caller;
- the coordinator resolves the current target-tenant registry only through the server-owned resolver;
- the resolver receives the immutable package requirements and preview target but cannot be selected by package content;
- preview resolves one current registry snapshot and remains side-effect free;
- draft creation resolves the registry again immediately before plan revalidation and draft creation;
- relevant registry drift changes the deterministic plan and is rejected as `StalePlan` before the draft importer;
- a resolver-returned null snapshot or infrastructure failure is converted to a safe `RegistryResolutionFailed` error;
- a resolver-returned snapshot for another tenant is rejected as cross-tenant authority;
- a cross-tenant write `RequestContext` is rejected before registry resolution;
- coordinator public commands contain no trusted registry field and expose only `preview` and `createDraft` operations.

This slice establishes a server-authoritative capability-resolution boundary without introducing a marketplace registry, database persistence, remote lookup or dynamic component loading.

## Implemented fourth safe slice

The fourth implementation slice binds every preview and draft-creation decision to deterministic tenant-registry evidence:

- validates server-resolved form-field, connector, business-reference, organization, identity and registered-component keys before dependency resolution;
- rejects unsafe registry values, duplicate registered-component identities, invalid component support whitelists and excessive registry/component counts;
- computes a length-prefixed SHA-256 registry hash using protocol `process-template-tenant-registry-v1`;
- sorts all registry sets and registered-component descriptors so semantically equivalent snapshots produce identical evidence;
- records target tenant, platform protocol version and registry content hash in immutable `RegistryEvidence`;
- includes `RegistryEvidence` in the `process-template-import-plan-v2` hash protocol;
- changes the plan hash when any registry capability or component contract changes, even when the changed capability is not referenced by the current package;
- returns the revalidated registry evidence with a successful draft-creation result for audit correlation.

An old plan therefore cannot be reused across any server-authoritative registry drift. No registry snapshot is persisted, downloaded remotely or supplied by package content.

## Security limits and rejection behavior

The package and decoder enforce:

- maximum package bytes: 2 MiB;
- maximum JSON depth: 64;
- maximum JSON element count: 30,000;
- maximum string length: 64 KiB;
- maximum dependencies: 500;
- maximum component properties: 50;
- maximum included artifact references: 100;
- maximum top-level registry values: 10,000;
- maximum registered components: 1,000;
- maximum properties per registered component: 100;
- duplicate JSON key rejection;
- duplicate dependency, artifact, component, property-schema and registered-component rejection;
- unknown JSON field rejection;
- malformed Unicode rejection;
- path traversal and unsafe resource-name rejection;
- content-hash recomputation and tamper rejection;
- JavaScript, script tags, expressions, remote modules, dynamic imports, executable URLs and HTML data URL rejection;
- cross-tenant binding and registry-authority rejection.

## Fixtures and tests

Fixtures cover valid template packages, missing dependencies, incompatible versions, tampered hashes, duplicate dependencies, cross-tenant binding attempts, script/dynamic-import rejection, unknown-component fallback, path traversal, duplicate JSON keys, unknown tenant fields and malformed Unicode.

Application and API tests cover deterministic package, registry and plan hashes, compatibility, dependency resolution, tenant rebinding, safe fallback, strict JSON and preview-only behavior. Draft-creation tests cover stale-plan rejection, non-importable plans, cross-tenant write contexts, artifact-envelope mismatch, missing source artifacts, exact Form Package selection, immutable target versions, idempotency-context propagation and DRAFT-only results. Coordinator tests cover server-only registry resolution, independent re-resolution for draft creation, registry drift, cross-tenant resolver output, cross-tenant write contexts, null snapshots, resolver-error redaction and command-contract exclusion of caller-supplied registries. Registry-evidence tests cover deterministic ordering, evidence fields, unreferenced capability drift, registered-component contract drift, unsafe values and duplicate component identities. The boundary test verifies that no `V33` migration or second permanent workflow exists and that the slices have no marketplace/download/class-loader/release-mutation dependency.

## Blocked until a later gate

- marketplace persistence;
- remote package download or remote registry lookup;
- dynamic component loading or remote modules;
- trusted package-supplied target bindings or registry snapshots;
- direct publication, deployment or activation during import;
- production registry resolver adapter wiring and management HTTP endpoints;
- database persistence or any `V33` migration;
- changes to M5 migration source or PR #58;
- a second permanent GitHub Actions workflow.

## Shared-core coordination

Future changes to Approval DSL, Form Schema, Release Package export/import or renderer protocols require explicit compatibility review and merge-order coordination. Later `main` changes must be incorporated by merge commit only; rebase and force push remain prohibited.
