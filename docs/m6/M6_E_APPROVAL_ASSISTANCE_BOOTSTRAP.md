# M6-E Governed AI Approval Assistance Bootstrap

Status: `M6_E_P0_REBASELINE_AND_THREAT_MODEL_IN_PROGRESS`

Date: `2026-07-31`

Tracking:

- parent milestone: Issue #62;
- roadmap rebaseline: Issue #78, closed / completed;
- workstream: Issue #80;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- target: `main`;
- exact source main: `fcf031da9e6e04b15a1255044021a7fdd6637421`;
- source natural `push -> main` Run: `30612812090`.

This document establishes the P0 baseline and grants no Provider, Secret, network, persistence,
API, UI, automation or command authority.

## 1. Entry gate

M6-R1 completed all mandatory entry gates before this branch was created:

- PR #79 merged with an ordinary Merge Commit;
- Merge Commit `fcf031da9e6e04b15a1255044021a7fdd6637421` is current `main`;
- natural main Run `30612812090` is bound to that exact commit and succeeded;
- all four main jobs succeeded;
- all four main artifacts were independently downloaded and SHA-256 matched;
- Issue #78 is closed with `state_reason = completed`;
- Issue #80 remains open;
- Issues #62, #13 and #14 remain open.

Unrelated Dependabot pull requests may remain open. They are not a base, dependency or merge target
for M6-E and must not be modified by this workstream.

## 2. Current repository truth

At P0 start:

- Maven migration history has highest version `V48`;
- no `V49` migration was found;
- `.github/workflows/approval-platform-validation.yml` remains the only automatic PR/main workflow;
- `approval-ai-spi` and `approval-ai-core` exist in the reactor;
- the executable `apps/server` does not yet bind an AI approval-assistance product path;
- no participant or management AI endpoint exists;
- no PC or Mobile AI experience exists;
- no real AI Provider adapter, runtime Secret material source or production egress exists;
- no durable AI assistance evidence exists;
- no AI automation proposal or executable action exists.

M6-D remains an accepted, provider-neutral, advisory-only foundation. P0 must preserve all seven
post-merge M6-D corrections, including circuit generation safety, exact deployment metadata,
length-framed evidence hashing and invocation-time nesting enforcement.

## 3. M6-E product boundary

M6-E provides read-only approval assistance. It does not provide automatic approval.

Every successful result must remain:

- `ADVISORY`;
- `UNVERIFIED_ADVISORY`;
- `needsHumanReview = true`.

An assistance result may contain only bounded support such as:

- a summary of authorized approval material;
- a material-completeness checklist;
- missing-information questions;
- bounded risk or conflict signals;
- policy or evidence references;
- confidence, provenance and limitations.

It cannot contain or represent:

- an approval decision;
- an executable application command;
- trusted tenant, operator, permission or audit identity;
- a claim that unverified Provider output is confirmed fact;
- a credential, Secret, token or network authority;
- a tool invocation, arbitrary URL, SQL statement or script.

## 4. Server-owned data flow

The only authorized conceptual flow is:

```text
Authenticated request
  -> server identity context
  -> tenant and authorization enforcement
  -> current process/task/resource-state read
  -> field-permission projection
  -> masking and minimization
  -> exact Provider/model/Prompt/policy/schema selection
  -> bounded at-most-one Provider invocation
  -> structural and advisory-result validation
  -> advisory response plus exact provenance and limitations
  -> durable minimal evidence gate
  -> read-only PC/Mobile presentation
  -> human review outside AI authority
```

The following values are always server-owned and cannot be trusted from browser or Provider input:

| Category | Server-owned evidence |
| --- | --- |
| Identity | tenant, operator, roles, permissions, authorization reference |
| Request | request ID, trace ID, idempotency/replay evidence where applicable |
| Approval resource | process definition/version, form/schema version, instance/task identity and state version |
| Visibility | visible fields, field permissions, masking and minimization policy |
| AI governance | Provider, model, Prompt metadata, policy and output-schema versions |
| Audit | evidence hashes, timestamps, classifications and human-review requirement |

The browser may request an allowed assistance use case, but it cannot select a trusted Provider,
model, tenant, operator, permission set or audit identity.

## 5. Provider boundary

M6-R1 decided that one real production Provider gate is required for M6-E completion. P0 does not
select or implement that Provider.

The later P6 gate must independently establish:

- server-owned Secret material and credential rotation;
- exact endpoint and model allowlists;
- DNS, TLS, redirect and SSRF controls;
- request/response size and content-type validation;
- timeout, rate, budget, circuit and kill-switch controls;
- no paid or customer Provider calls in CI;
- deterministic mock/real-adapter contract parity;
- incident, rollback and deactivation runbooks.

No Provider can call an application command service, Flowable or a connector command.

## 6. Persistence decision

M6-R1 decided that durable minimal assistance evidence is required for M6-E completion. P0 adds no
migration and reserves no migration number.

The later P4 migration slice must rescan the actual migration set and then define:

- schema ownership;
- tenant isolation;
- retention and deletion;
- optimistic concurrency and replay protection;
- immutable audit linkage;
- hash-only or bounded metadata;
- prohibition on raw sensitive input/output;
- prohibition on automatic model training or feedback-to-Prompt mutation.

## 7. Synchronous-only execution

M6-E is synchronous and bounded. It introduces no AI Queue, Worker, Scheduler, listener, polling
loop, automatic retry worker or autonomous reconciliation.

Provider timeout, malformed output, circuit-open state, stale approval state, budget exhaustion or
policy failure must return deterministic non-authoritative failure evidence. No partial invocation
may trigger Provider fallback or an approval command.

## 8. Explicit deferrals

The following are not part of M6-E:

- attachment-content extraction;
- general RAG, embeddings or vector storage;
- a generic Agent platform;
- arbitrary tool execution;
- marketplace or dynamic code loading;
- AI-driven approval, reject, return, transfer, withdraw, terminate or migrate;
- M6-F automation proposal and confirmation;
- background AI execution.

## 9. Safe-slice order

M6-E must proceed in this exact order:

1. P0 — rebaseline, data flow, threat model and acceptance bootstrap;
2. P1 — server-owned approval context projection;
3. P2 — bounded approval-assistance contracts;
4. P3 — governed synchronous orchestration;
5. P4 — separate durable-evidence migration slice;
6. P5 — read-only API and PC/Mobile presentation;
7. P6 — production Provider gate;
8. P7 — adversarial acceptance, Formal Acceptance, Merge Commit and post-main closure.

Each slice uses append-only commits and a new natural permanent workflow. A failed Run is retained
and corrected by a new minimal commit; it is never hidden or substituted.

## 10. P0 Definition of Done

P0 is complete only when:

- this bootstrap and the threat model are committed;
- the unique permanent workflow contains the M6-E authority boundary test;
- the exact P0 Head completes all four jobs successfully;
- all four artifacts are downloaded and independently SHA-256 matched;
- Maven aggregate and focused evidence are recalculated from the P0 Run;
- reviews, inline threads, top-level comments and reactions contain no actionable finding;
- PR remains Draft;
- no P1 product capability is present.

P0 completion does not authorize P1 until its exact evidence is recorded.

## 11. Stop conditions

Further progress stops immediately if any of the following occurs:

- `main` identity or branch ancestry is unclear;
- migration ownership conflicts;
- a Provider or client can manufacture authority;
- unauthorized fields can reach Provider mapping;
- a result can be represented as an approval decision;
- a Provider can directly call a command or Flowable service;
- required evidence depends only on test mocks;
- any permanent job or artifact verification fails;
- an actionable security or correctness review remains open.

## 12. Rollback and incident posture

P0 is documentation and permanent-boundary validation only. Rollback is removal of the P0 branch
before merge; no runtime or data rollback exists because no runtime, network, Secret or schema
change is introduced.

Later runtime slices must remain default-disabled until their own activation and incident gates are
accepted. A kill switch disables Provider invocation but never grants an alternate authority path.

`AI_IS_NOT_AN_OPERATOR`

`M6_E_IS_READ_ONLY_ADVISORY_ASSISTANCE`

`PROVIDER_TO_DIRECT_COMMAND_IS_PROHIBITED`
