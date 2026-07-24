# M6-C Template and Component Foundation Formal Acceptance

Status: `FORMALLY_ACCEPTED_TEMPLATE_FOUNDATION`

Decision date: `2026-07-24`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #65
- pull request: PR #69
- branch: `agent/m6-c-template-component-ecosystem`
- target branch: `main`
- reviewed implementation head: `f7cda8d765c0ef4592f04bde6ccf33f0622885ad`
- verified main baseline: `d769722cf7dd5418739a91ad4c45ca1a1c147502`

## Decision

The M6-C Template and Component Foundation is formally accepted.

The accepted scope is the deterministic package, strict codec, side-effect-free preview,
server-authoritative tenant registry, exact tenant-local Form Package evidence, governed DRAFT
creation, deterministic foundation-review evidence, permanent tests, and documentation implemented
through the reviewed head above.

This human governance decision does not make runtime review evidence an acceptance token.
`FoundationAcceptanceEvidence.formalAcceptanceGranted()` remains `false` by design.

PR #69 remains Open + Draft after this decision.

## Accepted foundation scope

The formal acceptance covers:

1. bounded Process Template Package identity, metadata, dependency and artifact contracts;
2. deterministic package, registry, plan, Form Package and governed-preview hashes;
3. strict duplicate-key, unknown-field, Unicode, path and executable-content rejection;
4. side-effect-free compatibility and tenant-local rebinding preview;
5. host-owned component descriptors, version whitelist and readonly fallback;
6. server-only tenant registry resolution with no caller-supplied trusted snapshot;
7. exact immutable Form Package, Form Schema and UI Schema evidence;
8. create-draft revalidation immediately before one tenant-local DRAFT import;
9. reuse of existing Artifact Transfer integrity, compilation, idempotency and audit boundaries;
10. deterministic foundation review evidence that cannot grant production authority;
11. permanent marketplace, remote-loading, release-mutation, migration and workflow boundaries.

## Formal review evidence

The decision reviewed:

- workflow: `Approval Platform Validation`;
- successful run: `30062377962` / run #489;
- successful head: `f7cda8d765c0ef4592f04bde6ccf33f0622885ad`;
- Maven artifact: `approval-maven-30062377962`;
- Maven artifact ID: `8585050001`;
- artifact digest:
  `247228e10492d68010b746c7f29393a8b6947524d54743fe32dc32209c5f014d`;
- Maven reactor: 548 tests, 0 failures, 0 errors, 0 skipped;
- focused M6-C tests: 71;
- Vben type-check and production build: passed;
- UniApp type-check, H5 build and WeChat Mini Program build: passed;
- Repository hygiene: passed;
- branch relation at review: ahead 22, behind 0;
- Flyway: V1 through V32;
- only automatic PR/main workflow:
  `.github/workflows/approval-platform-validation.yml`.

## Production Wiring and Management Import API gate

This acceptance explicitly opens one scoped implementation gate:

- local-only Spring wiring for the accepted preview and DRAFT-import contracts;
- server-owned tenant registry configuration and immutable local Form Package resolution;
- management preview API protected by the existing `DESIGN` capability;
- management create-draft API protected by the existing high-risk `TRANSFER` capability;
- strict bounded request decoding;
- server-owned tenant, operator, request, trace, permission, registry and evidence authority;
- stable redacted API errors;
- endpoint, configuration, permission and boundary tests.

This gate may create only an editable tenant-local DRAFT. It does not open publication,
deployment, activation, automatic activation, marketplace or remote loading.

## Blocked scope after acceptance

The following remain blocked:

- marking PR #69 Ready, auto-merge or merge;
- template marketplace persistence or catalog UI;
- remote package download or remote registry lookup;
- dynamic or remote component loading;
- package-supplied tenant, operator, permission, credential or engine authority;
- executable scripts or expressions;
- direct publish, deploy or activate during import;
- automatic activation after draft creation;
- database persistence for marketplace, import plans, registry snapshots or acceptance evidence;
- Flyway V33 or another M6-C migration;
- changes to M5 migration source or PR #58;
- a second automatic PR/main workflow;
- closing Issues #62, #65, #13 or #14.

## Repository state required after acceptance

- PR #69 remains Open + Draft;
- M5 PR #58 remains independent and Open + Draft;
- Issues #62, #65, #13 and #14 remain Open;
- no rebase, force push, squash, amend or history rewrite is permitted;
- later main changes may be incorporated only through a merge commit;
- the permanent validation workflow remains unchanged.
