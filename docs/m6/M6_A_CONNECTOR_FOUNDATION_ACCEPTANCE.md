# M6-A Connector Foundation Formal Acceptance

Status: `FORMALLY_ACCEPTED_CONTRACT_FOUNDATION`

Decision date: `2026-07-24`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #63
- pull request: PR #67
- branch: `agent/m6-a-connector-foundation`
- target branch: `main`
- reviewed implementation head: `aa181216aa087b2726829247cacdfd41963a861f`
- verified main baseline: `d769722cf7dd5418739a91ad4c45ca1a1c147502`

## Decision

The M6-A Connector Foundation contract scope is formally accepted.

This acceptance covers only the provider-neutral contracts, deterministic evidence models,
network-free test adapters, compatibility and failure classification, server-owned planning and
admission boundaries, permanent tests, and documentation implemented in PR #67.

This acceptance does not authorize production enablement, real provider execution, PR readiness,
auto-merge, merge, issue closure, or any capability listed in the blocked scope below.

PR #67 remains Open + Draft after this acceptance.

## Accepted contract scope

The following foundation capabilities are accepted:

1. credential-free provider descriptors and the closed connector operation model;
2. typed request, result, error, provider-result and idempotency evidence;
3. trusted server execution context with opaque credential references;
4. timestamp, nonce, signing, validity-window, replay and canonical payload evidence;
5. deterministic secret redaction and bounded metadata contracts;
6. deterministic network-free mock behavior and typed test adapters;
7. immutable typed provider registration and exact request/response resolution;
8. callback-scoped credential access with deterministic test-scope zeroization;
9. explicit uncertain-result reconciliation with no blind automatic retry;
10. closed provider-neutral payloads for directory, identity, messaging, external to-do and
    business callback operations;
11. explicit allowlist-based deterministic provider selection with no implicit fallback;
12. immutable provider compatibility classification and deterministic evidence;
13. server-owned authorization evidence bounded to one exact request and selection decision;
14. no-network orchestration planning that carries hashes and identifiers only;
15. current-state execution-admission revalidation before any later explicit invocation gate;
16. deterministic foundation review evidence and permanent client/server boundary tests.

## Formal review evidence

The formal review considered the full five-slice M6-A implementation and the following permanent
validation evidence:

- workflow: `Approval Platform Validation`;
- final successful run: `30058999846` / run #477;
- successful head: `aa181216aa087b2726829247cacdfd41963a861f`;
- Maven artifact: `approval-maven-30058999846`;
- Maven artifact ID: `8583893853`;
- GitHub digest and downloaded SHA-256:
  `1e67d2d007180602beab5ed2f38d9dc1399c75f9396f475595c55671e6c7d573`;
- Maven reactor: 564 tests, 0 failures, 0 errors, 0 skipped;
- accumulated focused M6-A tests: 87;
- connector contract tests: 68;
- permanent boundary tests: 19;
- Vben type-check and production build: passed;
- UniApp type-check, H5 build and WeChat Mini Program build: passed;
- Repository hygiene: passed.

The review also confirmed that the branch is ahead of `main` with no missing `main` commit at the
review point, Flyway remains continuous through V32, and the only automatic PR/main workflow
remains `.github/workflows/approval-platform-validation.yml`.

## Historical failure retained

Run `30058739417` / run #474 remains part of the evidence history.

It failed the Java job because the new admission contract test used two prohibited star imports.
Repository hygiene passed. The minimum corrective commit replaced the imports and aligned the
anonymous test reconciliation implementation with the existing SPI signature. The failed run was
not hidden or rerun; the corrected head produced successful run #477.

## Governance interpretation

`ConnectorFoundationAcceptanceEvidence.formalAcceptanceGranted()` remains `false` by design.
That runtime contract records deterministic readiness evidence and cannot grant a governance
approval. This document is the explicit human-authorized governance decision for the accepted
contract scope.

The same distinction applies to production enablement and execution. No contract, selector,
compatibility report, orchestration plan, admission result or foundation review result is an
execution capability or production-enablement token.

## Safety conclusions

The accepted foundation preserves these conclusions:

- `TIMEOUT` and `UNKNOWN` require reconciliation before any retry decision;
- reconciliation never directly authorizes replay of an uncertain side effect;
- provider selection never uses registration order, randomness, health or network discovery;
- an ineligible preferred provider never falls back implicitly;
- planning and admission never invoke `ConnectorExecutionPort.execute`;
- planning and admission contain no executable provider binding;
- credential material is not present in public request, result, plan or admission evidence;
- browser and mobile code cannot manufacture trusted connector context or authorization evidence;
- identity-resolution results do not establish trusted platform identity;
- connectors cannot directly mutate approval process state;
- no M5 migration model or runtime-binding semantic is modified.

## Blocked scope after acceptance

All of the following remain blocked and require separate explicit gates:

- real DingTalk, Feishu or other provider adapters and network transport;
- production endpoints, customer domains or private addresses;
- production credentials, tokens, secrets, secret-store or key-management integration;
- connector-owned persistence or schema ownership;
- any V33 or later Flyway migration owned by M6-A;
- provider tenant-routing configuration;
- connector workers, leases, schedulers, recovery or production execution orchestration;
- automatic execution;
- automatic retry, including retry of uncertain outcomes;
- health-based routing, fallback, weighted routing or load balancing;
- browser or mobile execution controls;
- direct Flowable access;
- connector-driven approve, reject, transfer, withdraw, terminate or migrate actions;
- direct approval process-state modification;
- marking PR #67 Ready;
- enabling auto-merge;
- merging or closing PR #67;
- closing Issues #62, #63, #13 or #14.

## Repository state required after acceptance

After recording this decision:

- PR #67 must remain Open + Draft;
- M5 PR #58 must remain independent and Open + Draft;
- Issues #62, #63, #13 and #14 must remain Open;
- no rebase, force push, squash, amend or history rewrite is permitted;
- later `main` changes may be incorporated only through a merge commit;
- `.github/workflows/approval-platform-validation.yml` remains the only automatic PR/main
  validation workflow.

## Next gate

The M6-A contract foundation is formally accepted, but no production implementation stage is
opened by this decision.

Any next M6-A stage must be separately scoped and explicitly authorized. It must define ownership
for persistence, credentials, transport, execution, retry, recovery, routing and audit integration
before adding production behavior.
