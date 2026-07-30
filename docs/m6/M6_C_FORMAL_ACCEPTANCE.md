# M6-C Template and Component Ecosystem Formal Acceptance

Status: `FORMALLY_ACCEPTED_M6_C_TEMPLATE_COMPONENT_ECOSYSTEM`

This record accepts only the bounded, local-only M6-C Template and Component Ecosystem described below. It does not grant runtime production authority, marketplace authority, remote-loading authority, publication, deployment, activation, migration, approval-state mutation, connector invocation, AI Provider transport or automation authority.

## 1. Formal baseline and controlled rebaseline

- Pull request: `#69`
- Base branch: `main`
- Formal main baseline: `5559fb54fd36208d57d3a3f3728755f022cc4b80`
- Original M6-C head before rebaseline: `72acb3ba18602c09c28bfe08b58f8b91e6efe6e4`
- Controlled main synchronization merge commit: `5dbce337e2bfd6a161fc6dfd70e196422bd4ad89`
- Synchronization parents:
  1. `72acb3ba18602c09c28bfe08b58f8b91e6efe6e4`
  2. `5559fb54fd36208d57d3a3f3728755f022cc4b80`
- Final implementation head: `1039a220b6d879ad38d3cba79c0f50892c040da6`
- Implementation diff against the formal main baseline:
  - ahead: `30`
  - behind: `0`
  - commits: `30`
  - changed files: `54`
  - additions: `7482`
  - deletions: `0`

The rebaseline used an ordinary two-parent Merge Commit. No rebase, squash, force push, amend or history rewrite was used.

## 2. Formally accepted scope

The accepted scope is limited to:

1. a bounded deterministic Process Template Package contract;
2. strict JSON decoding and canonical evidence hashing;
3. side-effect-free compatibility preview;
4. tenant-local rebinding preview;
5. governed creation of exactly one editable tenant-local `DRAFT`;
6. server-authoritative tenant registry resolution;
7. immutable tenant-local Form Package, Form Schema and UI Schema evidence binding;
8. a host-owned component descriptor whitelist;
9. deterministic registry, governed-preview and foundation-review evidence;
10. local-only Spring wiring;
11. management import endpoints:
    - `POST /api/approval/management/process-template-imports/previews`
    - `POST /api/approval/management/process-template-imports/drafts`
12. tests, bounded fixtures, architecture/boundary checks and governance documentation.

No additional product capability was introduced during R0 or G1. The only compatibility corrections after the merge were append-only test updates that rebaseline an obsolete migration boundary to the exact current-main migration set.

## 3. Explicitly excluded and prohibited scope

This acceptance does not implement, enable or imply:

- template marketplace persistence, catalog or marketplace UI;
- remote template catalog, remote package download or remote registry lookup;
- dynamic component loading, a remote UI bundle or package-provided implementation;
- Java class names, module paths, URLs, JavaScript, arbitrary HTML or executable expressions supplied by a package;
- package-provided tenant, operator, role, permission, audit, registry snapshot, Form Package evidence, credential, engine or deployment authority;
- publish, deploy, activate or automatic activation during or after import;
- a published, deployed or activated process definition;
- a production process instance or approval-state command;
- approval, reject, return, transfer, withdraw, terminate or migrate execution;
- M5 migration, runtime-binding or reconciliation mutation;
- production Worker, Queue, Scheduler or automatic delivery;
- production connector invocation or production Secret material;
- M6-D Provider transport, M6-E AI assistance, M6-F controlled automation or any M7 capability;
- a second automatic workflow;
- an unrelated Flyway migration.

## 4. Tenant, identity, permission and audit boundary

The client cannot authoritatively submit tenant, operator, role or permission evidence.

`ApprovalIdentityContextFilter` applies to `/api/approval/**`, validates the authenticated `ApprovalPrincipal`, rejects tenant mismatch without disclosing another tenant's resource, and wraps the request so that tenant, operator, request and trace headers are server-owned values. The configured local permission header is removed before the request reaches controllers.

The two accepted endpoints reuse existing management governance:

- preview requires existing `DESIGN` permission;
- create-DRAFT requires existing high-risk `TRANSFER` permission;
- high-risk execution continues to require the existing bounded operation reason, idempotency key and governance audit chain.

Every target and binding is overwritten with the trusted tenant before preview or creation. Cross-tenant requests fail closed and use redacted not-found behavior.

## 5. Package and strict JSON boundary

The accepted decoder and package validator enforce:

- maximum management request body: `4 MiB`;
- maximum package size: `2 MiB`;
- maximum JSON depth: `64`;
- maximum total JSON elements: `30000`;
- maximum string length: `65536` characters;
- maximum number length: `100` characters;
- strict duplicate-key rejection;
- strict unknown-field rejection;
- invalid Unicode rejection;
- path traversal rejection;
- exact content-hash verification;
- duplicate and missing dependency rejection;
- incompatible protocol/version rejection;
- unknown component handling without dynamic loading;
- script, dynamic import, remote module, URL, executable expression and unsafe HTML rejection.

Bounds are enforced before trusted domain contracts are created. Package content cannot supply executable authority.

## 6. Host-owned component whitelist boundary

The component registry exposes immutable data-only descriptors:

- component type;
- component version;
- supported field types;
- property-key whitelist;
- rendering support;
- read-only fallback.

It does not expose or accept a Java class name, package/module path, URL, JavaScript, HTML, expression, remote bundle, dynamic loader or package-provided implementation. Unknown or incompatible components cannot introduce executable behavior.

## 7. Form Package and deterministic registry evidence

Registry resolution is server authoritative and tenant local. It consumes:

- the tenant-local immutable Form Package/Form Schema/UI Schema stores;
- the host-owned component whitelist;
- closed server configuration for optional capability allowlists.

Missing, stale, inconsistent or cross-tenant Form Package evidence fails closed. The governed create path re-resolves registry and Form Package evidence and compares the expected governed-preview hash before import.

Runtime `FoundationAcceptanceEvidence.formalAcceptanceGranted()` intentionally remains `false`. Formal acceptance is a human governance decision recorded in this document, not a reusable runtime capability token.

## 8. DRAFT-only lifecycle boundary

The import path may create exactly one tenant-local editable `DRAFT`. The final result is accepted only when the existing Artifact Transfer service returns `ApprovalDesignDraft.Status.DRAFT` with exact definition, package, source-payload and source-envelope evidence.

There is no M6-C path to publish, deploy, activate, automatically activate, start a runtime process, mutate approval state, invoke M5 migration or mutate runtime binding/reconciliation semantics.

## 9. Compatibility with the formal main baseline

The complete rebaselined diff was audited against current main infrastructure, including:

- trusted identity filtering and management permission interception;
- reason, idempotency, audit and error models;
- Form Package and release lifecycle behavior;
- M5 migration/runtime-binding/reconciliation behavior;
- M6-A connector modules;
- M6-B SDK/event modules;
- Maven module graph and architecture tests;
- Vben/UniApp repository boundaries;
- the unique automatic workflow.

No test module was skipped, no assertion was removed to conceal a failure, and no product boundary was loosened.

## 10. Flyway and workflow boundary

M6-C adds no Flyway migration.

For the formal main baseline, the versioned migration files in the approval persistence migration directory are exactly:

- `V2` through `V37`;
- `V39` through `V48`.

The repository's clean and historical migration validation gates continue to validate to the exact current upper bound `V48`. This document does not inaccurately restate the obsolete `V1-V32` baseline and does not claim ownership of `V49`.

The only workflow with automatic `pull_request` or `push` triggers remains:

`.github/workflows/approval-platform-validation.yml`

## 11. Retained failed evidence and append-only corrections

No failed Run was hidden, deleted or replaced by rerun evidence.

Historical M6-C implementation failures remain retained:

- Run `30065815215` / #502: incorrect multi-path tree mapping omitted the resolver path; corrected by append-only commits including `24917a6309034d68a365dfcd8f92bbf244d2f7a3`;
- Run `30066075037` / #506: non-canonical configured protocol version; corrected by `adb8d97379a6ca2640eaeb980d8063ddf19ede43`;
- Run `30066423837` / #508: missing canonical-hasher constructor argument; corrected by `72acb3ba18602c09c28bfe08b58f8b91e6efe6e4`.

Rebaseline/G1 failures remain retained:

- Run `30522553026` / #903, head `5dbce337e2bfd6a161fc6dfd70e196422bd4ad89`:
  - Vben, UniApp and hygiene succeeded;
  - Java failed because an old M6-C boundary test still prohibited `V33` after main had legitimately advanced to `V48`;
  - corrected by append-only commit `b26b5934c01fa59a1b7e55d97b7d7f49a7fd28fd`.
- Run `30522942185` / #904, head `b26b5934c01fa59a1b7e55d97b7d7f49a7fd28fd`:
  - Vben, UniApp and hygiene succeeded;
  - Java failed because the first correction over-assumed an integer-contiguous `V1-V48` file set instead of preserving the exact current-main set;
  - corrected by append-only commit `1039a220b6d879ad38d3cba79c0f50892c040da6`.

## 12. Final implementation verification

Permanent workflow:

- name: `Approval Platform Validation`
- Run ID: `30523351640`
- run number: `905`
- branch: `agent/m6-c-template-component-ecosystem`
- exact head: `1039a220b6d879ad38d3cba79c0f50892c040da6`
- conclusion: `success`

Jobs:

- Java 21 / Maven / PostgreSQL: `success`;
- Vben TypeScript / production build: `success`;
- UniApp TypeScript / H5 / WeChat: `success`;
- Repository hygiene: `success`.

Maven evidence:

- aggregate: `1288 / 0 / 0 / 0`;
- M6-C focused: `88 / 88`;
- management/API/config/wiring: `12 / 12`;
- strict JSON codec: `15 / 15`;
- M6-C architecture/boundary: `8 / 8`;
- `BUILD SUCCESS` present.

Artifacts were independently downloaded and hashed:

| Artifact | ID | Size | GitHub digest | Downloaded ZIP SHA-256 | Match |
|---|---:|---:|---|---|---|
| `approval-maven-30523351640` | `8751855492` | `25846` | `sha256:620d87921542efdec79079d7a1c14611afb8b13d7702297c945ebb36c4231db3` | `620d87921542efdec79079d7a1c14611afb8b13d7702297c945ebb36c4231db3` | exact |
| `approval-vben-30523351640` | `8751706734` | `18891` | `sha256:79f1dcfbe59176c52ec250a9de83e6b3160468825d3c8a835e50a75b4f2227dc` | `79f1dcfbe59176c52ec250a9de83e6b3160468825d3c8a835e50a75b4f2227dc` | exact |
| `approval-mobile-30523351640` | `8751690745` | `9792` | `sha256:13c80af0219c60b3ff903f4f2616cf146ff32b921d391e6e2157747c199d28ea` | `13c80af0219c60b3ff903f4f2616cf146ff32b921d391e6e2157747c199d28ea` | exact |
| `approval-hygiene-30523351640` | `8751676648` | `7144` | `sha256:570607fb783411e3762bebd241a5de71c539f6fba80f35cdfe1a5aa0cb131fbf` | `570607fb783411e3762bebd241a5de71c539f6fba80f35cdfe1a5aa0cb131fbf` | exact |

## 13. Formal acceptance decision

The rebaselined M6-C implementation is formally accepted for merge-readiness evaluation within the exact bounded scope of this document.

This decision confirms:

- scope completeness for the accepted local-only template/component ecosystem;
- tenant, identity, permission, audit, JSON, package, component and lifecycle boundaries;
- compatibility with the formal main baseline;
- successful full-repository verification and independently matched artifacts;
- no authorization of any explicitly excluded capability.

This decision alone does not authorize marking PR #69 Ready or merging it. The exact documented Head created by this documentation commit must receive its own new natural permanent workflow and four independently verified artifacts.

## 14. Merge readiness conditions

PR #69 may be marked Ready only when all of the following are simultaneously true:

1. the exact documented Head workflow is successful;
2. all four jobs are successful;
3. Maven has zero failures, zero errors and zero skipped tests;
4. all four documented-Head artifacts exist, are unexpired and have downloaded ZIP SHA-256 values exactly matching GitHub digests;
5. the PR remains behind `0` and main has not drifted;
6. the PR contains no unexpected file and no unresolved actionable Review/comment/thread;
7. auto-merge remains disabled;
8. PR #70 remains Open + Draft at `9d588215e869c8f1332c0bc1a2809fbd235c2efa`;
9. Issue #65 remains Open.

Final merge must use an explicit Merge Commit with the verified documented Head as `expected_head_sha`. Squash, rebase merge, auto-merge and force merge are prohibited.

## 15. Required post-merge G4 verification

After merge, acceptance is incomplete until a natural `push -> main` run satisfies all of the following:

- workflow branch is `main`;
- exact workflow head and current main equal the PR #69 Merge Commit;
- the run is not a PR run, manual dispatch or rerun;
- all four jobs succeed;
- Maven aggregate, M6-C focused, management/API and boundary counts are extracted again from the main artifact;
- all four main-run artifacts are downloaded again and independently hashed;
- every local ZIP SHA-256 exactly matches its main-run GitHub digest;
- no new actionable Review, inline comment, security finding, correctness finding or post-merge regression exists.

If any actionable post-merge finding appears, Issue #65 remains Open and correction must occur in a new bounded Draft correction PR from the latest main. The merged PR #69 must not be modified.

Only after the natural main run, independent main artifact verification and post-merge Review check succeed may Issue #65 receive the final evidence comment and be closed with `state_reason = completed`.

Issues #62, #66, #13 and #14 must remain Open, and PR #70 must remain Open + Draft and unchanged.
