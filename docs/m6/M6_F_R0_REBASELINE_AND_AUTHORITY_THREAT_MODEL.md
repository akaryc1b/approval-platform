# M6-F R0 — Rebaseline and Authority Threat Model

Status: `R0_BASELINE_RECORDED / DRAFT_PR_REQUIRED / P0_NOT_STARTED`

Permanent conclusion: `AI_IS_NOT_AN_OPERATOR`

## 1. Exact current-main baseline

This R0 record was created from the real-time repository state on 2026-08-05.

- repository: `akaryc1b/approval-platform`
- default branch: `main`
- exact baseline commit: `492a428627d3be707d5723350506302ca04841b0`
- commit subject: `Merge pull request #83 from akaryc1b/agent/m6-e-governed-ai-approval-assistance`
- no commit newer than that Merge Commit was present on `main` when the M6-F branch was created
- M6-F branch: `agent/m6-f-controlled-automation-and-ai-governance`
- branch creation source: exact current `main`, never the M6-E branch
- M6-F branch or equivalent branch before creation: absent
- M6-F Pull Request before creation: absent
- other Open Pull Requests at rebaseline: none

No rebase, squash, force push, direct `main` commit or history rewrite is authorized.

## 2. M6-E closure inherited as verified input

M6-E remains closed and must not be modified.

- Issue #80: `Closed / Completed`
- PR #83: `Merged / Closed`
- accepted M6-E Head: `e5c5eb6fef3c715f37b3ae3664eb371f2e96f2ca`
- ordinary Merge Commit: `492a428627d3be707d5723350506302ca04841b0`
- natural post-main Run: `30971078402`
- exact checkout: `492a428627d3be707d5723350506302ca04841b0`

All nine physical jobs remain completed successfully:

| Job | Job ID | Conclusion |
| --- | ---: | --- |
| Java 21 / Maven core | `92195322163` | success |
| Persistence JDBC / shard 0 | `92195322267` | success |
| Persistence JDBC / shard 1 | `92195322251` | success |
| Persistence JDBC / shard 2 | `92195322228` | success |
| Persistence JDBC / shard 3 | `92195322194` | success |
| Java 21 / Maven / PostgreSQL aggregate | `92195776807` | success |
| Vben TypeScript / production build | `92195322206` | success |
| UniApp TypeScript / H5 / WeChat | `92195322214` | success |
| Repository hygiene | `92195322196` | success |

Rebuilt accepted counts remain:

- Maven core: `1273 / 0 / 0 / 0`
- Persistence JDBC: `295 / 0 / 0 / 0`
- aggregate: `1568 / 0 / 0 / 0`
- AI SPI: `12 / 12`
- AI Core: `160 / 160`
- OpenAI: `62 / 62`
- application: `233 / 233`
- architecture: `139 / 139`
- server: `180 / 180`
- JDBC selected classes: `73`
- JDBC unique classes: `73`
- duplicate shard assignments: `0`
- permanent M6 transport/P7 boundary: `79 / 79`

The four post-main artifacts remain present and unexpired through `2026-11-03T03:02:22Z`:

| Artifact | ID | Size | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `8916557285` | `328115` | `012275729f5493d79c19ba7b52108b58f08edc1ec6aa4b9f6f56b39726468585` |
| Vben | `8916529581` | `18787` | `2bbb36b251a7363db976d0522ce40cfd7fc1d5317edf3a9b9ff173905abdecbb` |
| Mobile | `8916521731` | `9797` | `3051fc22008a93c1a528dc3e136d5b5f7b717b539ec4cfabe2a1b316614d78b2` |
| Hygiene | `8916505753` | `12065` | `b477ccccefe21d02541fce279fd1eacc89fe0c0671813b14603a5d2baf2bf9c0` |

All PR #83 review threads were resolved. No `REQUEST_CHANGES` review and no PR reaction blocker remained at rebaseline.

## 3. Issue and roadmap state

- Issue #81 — `[M6-F] Controlled Automation and AI Governance`: Open
- Issue #82 — `[M6-G] M6 Overall Formal Acceptance and Production Readiness`: Open and blocked by Issue #81 post-main formal closure
- parent Issue #62: Open
- Issue #13: Open
- Issue #14: Open

This branch must not start M6-G or close Issues #81, #82, #62, #13 or #14.

## 4. Migration and workflow invariants

- exact highest Flyway migration: `V49__create_ai_approval_assistance_durable_evidence.sql`
- no `V50+` migration exists at R0
- R0 and P0 must add no migration
- the only workflow with automatic `pull_request` or `push` execution remains `.github/workflows/approval-platform-validation.yml`
- repository workflow permissions remain read-only for contents

No second automatic workflow is authorized.

## 5. Current action-whitelist decision

The exact current decision is:

`EMPTY_PENDING_EXISTING_COMMAND_AUDIT`

Consequences:

- no application command is authorized for AI Controlled Automation;
- M6-E advisory output is not a command;
- no Action may be invented merely to claim M6-F completion;
- P5-A is prohibited unless P0 proves exactly one existing, valuable, low-risk command meets every gate;
- P1-P4 may establish non-executing governance foundations even if P5-A remains blocked.

## 6. Permanent authority chain

The only authorized direction is:

`AI advisory`
→ `typed non-executable proposal`
→ `fresh server policy and precondition evaluation`
→ `fresh authorization preview`
→ `explicit human confirmation`
→ `existing application command service`
→ `immutable audited result`

The following path is permanently prohibited:

`Provider -> direct command`

The Proposal is not a command, credential, permission token, reusable confirmation token, audit identity or operator identity.

## 7. Server-owned authority

The server, never the Provider or browser, owns:

- tenant identity;
- authenticated operator identity;
- roles and permissions;
- resource-level authorization;
- current resource state and version;
- Action Whitelist and whitelist version;
- policy version and feature/kill-switch state;
- typed parameter schema and mapping;
- source-evidence verification;
- separation-of-duties evaluation;
- expiry and stale-state decisions;
- command admission and final immutable outcome classification.

AI output may be evidence for a suggestion only. It grants no authority.

## 8. Zero-side-effect boundaries

The following operations must remain free of business side effects:

- creating a Proposal;
- reading a Proposal;
- loading or refreshing PC/Mobile views;
- switching tabs;
- querying Eligibility or Authorization Preview;
- displaying risks, parameters or side effects;
- cancelling before command admission.

No page load, advisory read, listener, polling loop, webhook, Provider callback, Queue, Worker or Scheduler may create, confirm or execute a Proposal.

## 9. Permanently prohibited commands and bypasses

Without a separate high-risk gate, M6-F excludes:

- approve;
- reject or return;
- transfer;
- withdraw;
- terminate;
- migrate;
- process-state advancement;
- template publish, deploy or activate;
- Provider activation;
- permission modification;
- Secret modification;
- arbitrary HTTP;
- arbitrary SQL;
- arbitrary script or executable expression;
- direct connector command;
- direct Flowable command or `ACT_*` table access;
- dynamic module, Java class, remote code or URL-selected execution.

Provider output cannot become an action type, command name, URL, HTTP method/body, SQL, script, connector operation or executable payload.

## 10. R0 scope and exclusions

R0 is limited to:

1. real-time baseline capture;
2. independent branch establishment;
3. permanent authority-boundary tests;
4. creation of a Draft PR targeting `main`.

R0 does not:

- qualify an Action;
- create a Proposal contract;
- evaluate eligibility;
- create confirmation authority;
- add durable M6-F persistence;
- bind an application command;
- add a Provider, model, Prompt, RAG, attachment extraction or vector store;
- add Queue, Worker, Scheduler, listener or polling;
- create a Flyway migration;
- mark Ready, merge or close an Issue.

P0 may begin only after the R0 Draft PR exists and the exact R0 Head completes a natural permanent `pull_request` workflow with complete artifact and review evidence.

`AI_IS_NOT_AN_OPERATOR`
