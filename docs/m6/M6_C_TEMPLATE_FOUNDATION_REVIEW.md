# M6-C Template Foundation Acceptance Review

Status: `READY_FOR_FORMAL_ACCEPTANCE_REVIEW_EVIDENCE_IMPLEMENTED`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #65
- Draft PR: #69
- branch: `agent/m6-c-template-component-ecosystem`
- target branch: `main`
- synchronized main: `d769722cf7dd5418739a91ad4c45ca1a1c147502`

## Purpose

This sixth safe slice adds deterministic technical review evidence for the M6-C template and
component foundation. It does not grant formal acceptance, production enablement, marketplace
availability, publication, deployment, activation, automatic activation or dynamic loading.

The evaluator consumes only:

- one validated Process Template Package;
- one server-generated governed preview;
- one optional governed DRAFT creation result.

It has no resolver, store, network, marketplace, worker, publication, deployment, activation or
component-loader port.

## Review protocol

`ProcessTemplateFoundationAcceptanceEvaluator` classifies evidence using the closed statuses:

- `READY_FOR_FORMAL_ACCEPTANCE_REVIEW`;
- `PACKAGE_INVALID`;
- `PACKAGE_EVIDENCE_MISMATCH`;
- `IMPORT_PLAN_NOT_READY`;
- `FORM_PACKAGE_EVIDENCE_MISMATCH`;
- `GOVERNED_PREVIEW_MISMATCH`;
- `DRAFT_EVIDENCE_INCOMPLETE`;
- `DRAFT_EVIDENCE_MISMATCH`.

A ready result requires all of the following:

- package validation succeeds against the declared byte count;
- the governed preview references the exact reviewed package hash;
- the import plan is importable and has no unresolved required rebinding;
- Form Package evidence matches the target tenant, definition key and immutable package version;
- Form Package evidence recomputes to its declared content hash;
- the governed preview hash recomputes from package, registry, plan and Form Package evidence;
- one exact tenant-local `DRAFT` result matches the governed preview;
- the DRAFT result matches package hash, registry evidence, plan hash, definition identity,
  Form Package version and one included source artifact reference.

The deterministic review hash uses the length-prefixed protocol:

`process-template-foundation-review-v1`

It covers the review status, package and plan evidence, registry evidence, governed preview,
Form Package evidence, DRAFT evidence, sorted findings and the complete blocked-capability list.

## Permanent authority boundary

Every `FoundationAcceptanceEvidence` result permanently reports:

- `formalAcceptanceGranted() == false`;
- `productionEnabled() == false`;
- `marketplaceEnabled() == false`;
- `publicationEnabled() == false`;
- `deploymentEnabled() == false`;
- `activationEnabled() == false`;
- `automaticActivationEnabled() == false`;
- `dynamicLoadingEnabled() == false`;
- `requiresExplicitFormalAcceptance() == true`.

`READY_FOR_FORMAL_ACCEPTANCE_REVIEW` means only that the deterministic technical evidence is
complete enough for a later explicit human acceptance decision. It is not formal acceptance and
cannot be used as an execution or activation capability.

## Blocked capabilities

The review evidence always retains explicit blocks for:

- marketplace persistence;
- remote package download;
- remote registry lookup;
- dynamic component loading;
- production enablement;
- publication;
- deployment;
- activation;
- automatic activation.

## Tests

The sixth-slice tests cover:

- complete evidence becoming review-ready without granting authority;
- deterministic review hashes;
- missing DRAFT evidence;
- non-importable plans;
- reviewed-package mismatch;
- tampered Form Package evidence;
- tampered governed preview hashes;
- inconsistent DRAFT evidence;
- distinct review outcomes producing distinct hashes;
- absence of stores, resolvers, network, runtime mutation and release operations;
- a public evaluator surface limited to `evaluate`.

The existing package, registry, Form Package, governed-preview, draft-only, migration, workflow
and M5 isolation boundaries remain unchanged.

## Still blocked

- formal M6-C acceptance;
- marking PR #69 Ready;
- auto-merge or merge;
- production Spring wiring;
- management HTTP endpoints;
- marketplace/catalog persistence or UI;
- remote package or registry access;
- dynamic or remote component loading;
- publication, deployment, activation or automatic activation;
- Flyway `V33`;
- changes to M5 PR #58;
- a second automatic workflow.
