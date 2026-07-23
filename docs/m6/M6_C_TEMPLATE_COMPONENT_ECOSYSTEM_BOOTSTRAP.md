# M6-C Template and Component Ecosystem Bootstrap

Status: `BOOTSTRAP_ONLY`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #65
- branch: `agent/m6-c-template-component-ecosystem`
- target branch: `main`
- starting main: `906a4fcf784f22a329102a423fe9b4ab0ba1bdc4`

## Parallel milestone boundary

M6-C is allowed to develop in parallel with M5 and remains independent from Issue #56 and Draft PR #58. Template and component protocols cannot weaken existing release, tenant, form or client-security boundaries.

## First safe slice

The first implementation slice may add:

- deterministic template package contracts and hashes;
- category, version, compatibility and dependency metadata;
- preview, validation and import-plan contracts;
- tenant-local rebinding rules;
- component registry descriptors and version whitelists;
- supported property schemas and read-only fallback semantics;
- package size, depth, element and string bounds;
- deterministic fixtures, validators and permanent boundary tests.

## Blocked until a later gate

- marketplace persistence or remote package download;
- any `V33` migration;
- scripts, expressions, remote modules or dynamic imports;
- embedded credentials, tenant, operator, permission or engine identity;
- direct publication or activation during import;
- unrestricted third-party component execution;
- changes to M5 migration source or PR #58;
- a second permanent GitHub Actions workflow.

## Shared-core coordination

Changes to Approval DSL, Form Schema, Release Package, import/export or component-rendering contracts require explicit compatibility review and merge-order coordination. Later `main` changes are incorporated with merge commits only; no rebase or force push.

## Bootstrap acceptance

This bootstrap commit creates scope and safety boundaries only. It introduces no marketplace, remote loader, persistence, workflow change or M5 modification.