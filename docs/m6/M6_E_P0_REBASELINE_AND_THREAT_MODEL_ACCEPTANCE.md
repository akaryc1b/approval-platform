# M6-E P0 Rebaseline and Threat Model Acceptance

Status: `M6_E_P0_FORMALLY_ACCEPTED_PENDING_DOCUMENTED_HEAD_VALIDATION`

Date: `2026-07-31`

Tracking:

- parent milestone: Issue #62;
- roadmap rebaseline: Issue #78, closed / completed;
- workstream: Issue #80;
- Pull Request: #83;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- target: `main`;
- exact base: `fcf031da9e6e04b15a1255044021a7fdd6637421`.

This record accepts only M6-E P0. It does not authorize P1 implementation, production Provider
activation, persistence, an API/UI, automation or any approval command.

## 1. Source-main gate

M6-E was created only after M6-R1 completed its Merge Commit and post-main acceptance.

Source main evidence:

- Merge Commit: `fcf031da9e6e04b15a1255044021a7fdd6637421`;
- natural `push -> main` Run: `30612812090`;
- branch/head: `main@fcf031da9e6e04b15a1255044021a7fdd6637421`;
- all four permanent jobs: `success`;
- all four source-main artifacts: independently downloaded and SHA-256 matched;
- Issue #78: `closed / completed`.

At P0 acceptance, current `main` remains the same exact commit. PR #83 is ahead of main and behind
zero. Issues #80, #62, #13 and #14 remain open.

## 2. P0 accepted scope

P0 establishes:

1. an exact current-main and migration/workflow baseline;
2. the approval-assistance product boundary;
3. a server-owned conceptual data flow;
4. five explicit trust boundaries;
5. a 28-entry threat register;
6. Provider, Secret, network and persistence decisions inherited from M6-R1;
7. a synchronous-only execution boundary;
8. permanent authority tests loaded by the existing repository-hygiene job;
9. exact P0 stop conditions, rollback posture and safe-slice ordering.

Formal documents:

- `docs/m6/M6_E_APPROVAL_ASSISTANCE_BOOTSTRAP.md`;
- `docs/m6/M6_E_APPROVAL_ASSISTANCE_THREAT_MODEL.md`.

Permanent boundary files:

- `scripts/tests/m6-e-approval-assistance-boundary.test.mjs`;
- `scripts/tests/m3-repository-hygiene.test.mjs`.

The unique automatic workflow is unchanged. It already runs the repository-hygiene test, which now
imports the M6-E authority suite. No second automatic workflow was created.

## 3. Accepted authority model

The only accepted direction is:

```text
authenticated server identity
  -> fresh tenant and authorization evaluation
  -> server-owned approval context projection
  -> field permission, masking and minimization
  -> bounded at-most-one Provider invocation
  -> strictly validated unverified advisory result
  -> human review
```

The following direction is permanently prohibited:

```text
Provider -> application command
```

Every future successful assistance result must remain:

- `ADVISORY`;
- `UNVERIFIED_ADVISORY`;
- `needsHumanReview = true`.

AI is never the operator, approver, reviewer, policy authority, authorization authority or audit
identity source.

## 4. Retained initial failure

The initial P0 Head was:

`a32ae05c72f0af787de4b1fc6417f04070a74f70`

Natural permanent Run:

- Run ID: `30616762036`;
- run number: `961`;
- event: `pull_request`;
- attempt: `1`;
- conclusion: `failure`.

Job outcomes:

- Repository hygiene: `failure`;
- Vben TypeScript / production build: `success`;
- UniApp TypeScript / H5 / WeChat: `success`;
- Java 21 / Maven / PostgreSQL: `cancelled` after the new synchronization commit superseded the
  Head.

Failure cause:

- five of six M6-E P0 authority tests passed;
- the synchronous-execution test compared an unnecessarily exact phrase instead of the documented
  equivalent rule that no partial invocation may trigger Provider fallback or an approval command;
- no product, security or authority boundary was missing.

No failed Run was rerun, deleted, hidden or used as acceptance evidence.

Minimal append-only correction:

- commit: `6bfa4006ec69b245d16f23174c8a9c2bf92a32c3`;
- change: replace only the overly exact phrase assertion with a semantic multiline match;
- no production code, document policy, workflow, migration or runtime configuration changed.

## 5. Successful P0 implementation verification

Exact implementation Head:

`6bfa4006ec69b245d16f23174c8a9c2bf92a32c3`

Permanent workflow:

- Run ID: `30616905114`;
- run number: `962`;
- event: `pull_request`;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- attempt: `1`;
- conclusion: `success`.

Jobs:

| Job | Job ID | Result |
| --- | ---: | --- |
| Java 21 / Maven / PostgreSQL | `91112120462` | success |
| Vben TypeScript / production build | `91112120519` | success |
| UniApp TypeScript / H5 / WeChat | `91112120532` | success |
| Repository hygiene | `91112120589` | success |

Maven evidence recalculated from `maven-verify.log`:

- aggregate: `1396 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `92 / 92`;
- architecture module: `138 / 138`;
- ArchUnit `ModuleBoundariesTest`: `10 / 10`;
- `BUILD SUCCESS`: present.

Focused permanent evidence from the Repository hygiene job:

- M6-E P0 authority boundary: `6 / 6`;
- combined repository-hygiene entrypoint: `11 / 11`;
- existing M6-D foundation boundary: `10 / 10`;
- existing M6-D activation boundary: `6 / 6`;
- existing M6-D transport boundary: `7 / 7`.

## 6. P0 implementation artifact verification

Every ZIP was independently downloaded and locally hashed. Each local SHA-256 exactly matches the
GitHub artifact digest.

| Artifact | ID | Size | GitHub/local SHA-256 | Match |
| --- | ---: | ---: | --- | --- |
| Maven | `8787925785` | `26886` | `e088a1704c9048e8fce5274d01846bac7feb7dddf8273fdc8d7cbb0e37b47736` | exact |
| Vben | `8787774794` | `18855` | `2fca7fd9c412d04ec67ff930513c07f707ca885b6cee993266870dadb321840e` | exact |
| Mobile | `8787755137` | `9792` | `7531a0a0a3f004d1f3c6851e019763fb3c293047b44cf4137b982d740c152942` | exact |
| Hygiene | `8787739690` | `9233` | `c1677334416b7de9f1b3cdd73dd0dc56da981429935590c7b9062ade31cac39f` | exact |

All four artifacts are unexpired and expire at `2026-10-29T08:36:29Z`.

## 7. Review state before this record

Before this acceptance record was committed:

- PR #83 remained Open + Draft;
- mergeable: `true`;
- submitted reviews: none;
- requested reviewers: none;
- unresolved review threads: none;
- top-level PR comments: none;
- no actionable security, correctness or authority finding existed;
- auto-merge was not enabled.

The documented Head created by this record must receive a new permanent workflow and four new
independently matched artifacts. The implementation Run above cannot substitute for that
validation.

## 8. Explicit absence of P1-P7 capability

P0 introduces no:

- server-owned approval context projection implementation;
- approval-assistance-specific advisory contracts;
- executable AI application wiring;
- real Provider adapter, production Prompt or customer knowledge;
- runtime Secret material or network egress;
- persistence or Flyway migration;
- read-only assistance API;
- PC or Mobile AI experience;
- AI Queue, Worker, Scheduler, listener or polling loop;
- automation proposal or executable action;
- approve, reject, return, transfer, withdraw, terminate, migrate, publish or activate command;
- M6-F capability.

## 9. P0 formal decision

M6-E P0 is accepted at the documentation and permanent-authority-boundary level, subject to the
new documented-Head permanent validation.

P1 may begin only after:

1. the exact documented Head Run succeeds;
2. all four documented-Head artifacts are independently SHA-256 matched;
3. Maven and focused evidence are recalculated;
4. Review, thread, comment and reaction checks contain no actionable finding;
5. PR #83 remains Draft;
6. current main is unchanged or is merged into the branch through an ordinary Merge Commit and
   fully revalidated.

P1 must create a purpose-specific, server-owned approval context projection. It must not pass a
complete persistence entity, unauthorized field, attachment body or client-supplied trusted
identity to an AI Provider.

`M6_E_P0_ACCEPTED_NOT_PRODUCTION_ENABLED`

`AI_IS_NOT_AN_OPERATOR`

`PROVIDER_TO_DIRECT_COMMAND_PROHIBITED`
