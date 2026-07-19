# Approval Artifact Transfer Security Model

## Scope

D7 defines two application-owned JSON transfer formats:

- `APPROVAL_DSL_EXPORT_V1`
- `APPROVAL_RELEASE_PACKAGE_EXPORT_V1`

The envelope is portable across tenants. It is not a database backup and never carries source tenant, operator, publication, deployment, effective-release or runtime-instance identity.

## Deterministic identity

The server recomputes the Approval DSL hash and, for Release Packages, the BPMN, optional DMN, compiled artifact, deployment metadata and complete Release Package hashes. It then recomputes `payloadHash` and `envelopeHash`. `exportedAt` is excluded from content identity, so repeated exports of unchanged content retain comparable payload and envelope hashes.

Client-declared hashes are assertions only. Any mismatch rejects export or import. JSON property order does not participate in the typed canonical hash material.

## Excluded data

Exports exclude `tenantId`, `createdBy`, `updatedBy`, `publishedBy`, `sourceDraftId`, deployment record IDs, Flowable deployment/definition IDs and versions, effective-release identity, activation history and runtime instance data.

## Parsing and size limits

The import endpoint accepts one uncompressed JSON request. ZIP or other compressed uploads are not supported.

- request and normalized JSON: 2 MiB maximum;
- JSON depth: 64;
- JSON elements: 30,000;
- JSON string: 64 KiB;
- JSON number token: 100 characters, with exact 32-bit checks for protocol integers;
- Approval DSL nodes: 500;
- condition routes and parallel branches: 2,000 each;
- BPMN: 1 MiB;
- DMN: 512 KiB.

Strict parsing rejects duplicate keys, unknown request/envelope/payload fields, null payloads, non-finite or malformed numbers, overflow, invalid Unicode and unsupported format/version combinations.

## XML safety

BPMN and DMN use namespace-aware parsers with DOCTYPE disabled, external general and parameter entities disabled, XInclude disabled, entity expansion disabled and external DTD/schema access set to empty. BPMN must contain exactly one process whose ID matches the source definition key. Resource names allow only bounded ASCII letters, digits, dot, underscore and hyphen, forbid `..`, slashes and backslashes, and require the `.bpmn20.xml` suffix.

## Import transaction

Import uses `RequestContext`, `Idempotency-Key` and a stable request hash. The JDBC idempotency row, draft and audit event are committed in one transaction. A replay returns the original result; reuse of the key with different content returns conflict.

The server rewrites the DSL to the requested target key, version and name, resolves the target tenant's exact local Form Package/Form Schema/UI Schema, validates and compiles against those local artifacts, then creates only:

- a new UUID draft;
- revision `1`;
- status `DRAFT`;
- one `APPROVAL_DESIGN_DRAFT_IMPORTED` audit event.

Import never creates an immutable Approval Definition Version, compiled artifact, Release Package, deployment, effective release, activation history or runtime instance. It never publishes, deploys or activates automatically.

## Audit and error handling

Audit attributes contain only transfer type/version, source keys and versions, source payload/envelope hashes, target key/version/Form Package version and draft revision. Full DSL, BPMN, DMN and Form data are not logged.

Stable client errors distinguish invalid format, unsupported version, size limits, hash mismatch, artifact integrity, missing source, target Form Package incompatibility, validation failure and import conflict. Internal stacks, SQL errors and full sensitive artifacts are not returned.

## Compatibility

Version 1 is closed at the request, envelope and payload levels: unknown fields and unknown format versions are rejected rather than silently ignored. Future versions require an explicit deterministic migration followed by complete server-side revalidation and rehashing.
