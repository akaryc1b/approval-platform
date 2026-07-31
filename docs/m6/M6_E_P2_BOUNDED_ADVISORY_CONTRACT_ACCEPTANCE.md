# M6-E P2 Bounded Approval-Assistance Contract Acceptance

Status: `M6_E_P2_FORMALLY_ACCEPTED_PENDING_DOCUMENTED_HEAD_VALIDATION`

Date: `2026-07-31`

Tracking:

- parent milestone: Issue #62;
- roadmap rebaseline: Issue #78, closed / completed;
- workstream: Issue #80;
- Pull Request: #83;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- target: `main`;
- exact base: `fcf031da9e6e04b15a1255044021a7fdd6637421`.

This record accepts only M6-E P2. It does not authorize P3 orchestration, Provider invocation,
production Prompt material, customer knowledge, runtime Secret material, network egress,
persistence, Flyway migration, an API/UI, automation, an executable action or any approval
command.

## 1. P1 entry gate

P2 began only after P1 completed its independent documented-Head acceptance.

Accepted P1 evidence:

- exact documented Head: `110ca393b8c111e429209a4e4d96ec5ed5c298e7`;
- permanent Run: `30624413955` / run number `985`;
- event: `pull_request`;
- attempt: `1`;
- conclusion: `success`;
- all four permanent jobs: `success`;
- all four artifacts: independently downloaded and SHA-256 matched;
- Maven aggregate: `1418 / 0 / 0 / 0`;
- AI Core: `114 / 114`;
- P1 focused Java: `22 / 22`;
- M6-E P0/P1 architecture boundary: `7 / 7`;
- no actionable Review finding;
- PR #83 remained Open + Draft.

At P2 acceptance, current `main` remains
`fcf031da9e6e04b15a1255044021a7fdd6637421`. The M6-E branch is ahead of main and behind zero.
Issues #80, #62, #13 and #14 remain open.

## 2. P2 accepted scope

P2 adds one internal bounded contract in the existing AI Core:

- `server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/ApprovalAssistanceAdvisoryContract.java`.

It reuses:

- the P1 `ApprovalAssistanceContextProjection`;
- M6-D `AiAdvisoryResult`;
- M6-D `AiCapability`;
- M6-D `AiVersionReferences`.

Accepted permanent Java test:

- `ApprovalAssistanceAdvisoryContractTest`.

Accepted permanent architecture boundary:

- the P2 section in `scripts/tests/m6-e-approval-assistance-boundary.test.mjs`;
- loaded through the existing `scripts/tests/m3-repository-hygiene.test.mjs` gate;
- no second automatic workflow.

P2 adds no Spring bean, controller, application service, Provider adapter, database store,
configuration binding, browser source or mobile source.

## 3. Closed P2 use-case vocabulary

P2 permits exactly three single-capability advisory use cases:

| Use case | Exact capability | Exact Prompt template ID |
| --- | --- | --- |
| `SUMMARY` | `APPROVAL_SUMMARY` | `approval-summary` |
| `MATERIAL_COMPLETENESS` | `MATERIAL_COMPLETENESS` | `approval-material-completeness` |
| `RISK_REVIEW` | `RISK_SIGNALS` | `approval-risk-review` |

Every request must contain exactly the one capability bound to its selected use case. Multiple
capabilities and cross-use-case Prompt template substitution fail closed.

The exact output Schema ID is:

`approval-assistance`

A generic advisory Schema or another output Schema cannot satisfy the P2 contract.

The following existing AI capabilities are intentionally not accepted by P2:

- `SIMILAR_CASES`;
- `APPROVAL_OPINION_SUGGESTION`.

P2 does not expand these capabilities or reinterpret them as approval-assistance authority.

## 4. Exact request contract

A P2 request contains:

1. one accepted P1 context projection;
2. one closed P2 use case;
3. exact expected Provider/model/Prompt/knowledge/policy/output-Schema versions;
4. bounded result limits;
5. exact projection provenance;
6. request time.

### 4.1 Projection provenance

The request provenance binds:

- resource state version;
- resource observation time;
- Form Schema content hash;
- UI Schema hash;
- submission revision;
- data-policy version.

The provenance must equal the exact P1 projection. Request time must not precede the observed
resource state.

### 4.2 Version binding

- expected policy must equal the P1 projection policy;
- expected Prompt template ID must equal the selected use case;
- expected output Schema ID must equal `approval-assistance`;
- result versions must equal the complete expected request versions;
- no partial version comparison is accepted.

### 4.3 No knowledge source in P2

P2 requires:

`KnowledgeSourceVersion.none()`

Customer history, similar cases, RAG, embeddings, vector stores and general knowledge sources are
not accepted by this slice.

## 5. Immutable result limits

The request may choose lower limits but cannot exceed these P2 maximums:

| Result category | Maximum |
| --- | ---: |
| observations | `25` |
| risk signals | `25` |
| missing materials | `25` |
| recommendations | `25` |
| evidence references | `64` |
| limitations | `12` |

All limits must be positive. Direct construction cannot raise the P2 maximums.

## 6. Advisory-only result invariants

Every accepted result must retain:

- `needsHumanReview = true`;
- `authority = ADVISORY`;
- `assertionStatus = UNVERIFIED_ADVISORY`.

P2 adds no decision, task outcome, command, action credential or executable instruction.

The result must also satisfy:

- complete request/result version equality;
- per-category request limits;
- globally unique item IDs across observations, risk signals, missing materials and
  recommendations;
- unique limitations;
- confidence score/band consistency.

Confidence thresholds are fixed as:

| Score | Required band |
| --- | --- |
| `< 0.50` | `LOW` |
| `>= 0.50` and `< 0.80` | `MEDIUM` |
| `>= 0.80` | `HIGH` |

## 7. Evidence closure

P2 requires Provider-safe evidence references.

Permanent invariants:

- evidence IDs are unique;
- every evidence field key exists in the exact P1 Provider-safe field projection;
- observations, risk signals and recommendations each contain at least one evidence reference;
- every item reference resolves to a declared evidence reference;
- one item cannot repeat the same evidence ID;
- every declared evidence reference supports at least one advisory item;
- unused, unresolved and unauthorized evidence fails closed.

Evidence references do not grant approval authority and do not contain attachment bodies or
extracted attachment content.

## 8. Retained append-only hardening sequence

P2 used append-only commits. Natural Runs at intermediate exact Heads were retained and cancelled
only when later commits superseded those Heads. They were not rerun, hidden or used as final
acceptance evidence.

| Exact Head | Run | Run number | Conclusion | Superseding reason |
| --- | ---: | ---: | --- | --- |
| `7e0fdc68785447cb519e770b7eaa48527118c725` | `30625197553` | `986` | cancelled | focused contract regressions were still required |
| `eaf1030deb965508622e18b113c405ea0a6c5f88` | `30625268256` | `987` | cancelled | strict result, evidence and request-time bounds were still required |
| `bef5dccaea228d0e0651138dd711c23f47763440` | `30625593547` | `990` | cancelled | Prompt template and output Schema use-case binding were still required |
| `cf321dce824c6fe56b0e729a3a07c9727f676ec8` | `30625693998` | `991` | cancelled | exact Prompt/Schema regressions were still required |
| `12edca6c7775447be21146be97845c835af33872` | `30625785308` | `992` | cancelled | permanent architecture assertions needed alignment with the final contract |

The hardening findings were:

1. change advisory maximums from configurable broad bounds to immutable P2 bounds;
2. reject request time earlier than resource observation time;
3. require non-empty evidence for observations, risk signals and recommendations;
4. reject unused declared evidence;
5. bind each use case to one exact Prompt template ID;
6. bind every P2 request to the `approval-assistance` output Schema;
7. permanently prohibit similar-case and opinion-suggestion capability expansion in P2;
8. freeze all P2 invariants in the existing permanent architecture gate.

No hardening commit introduced Provider invocation, runtime wiring, persistence, API/UI or command
authority.

## 9. Successful P2 implementation verification

Exact implementation Head:

`eab102dda7f4c1ff95571dd879db25083b53056c`

Permanent workflow:

- Run ID: `30625855481`;
- run number: `993`;
- event: `pull_request`;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- attempt: `1`;
- conclusion: `success`.

Jobs:

| Job | Job ID | Result |
| --- | ---: | --- |
| Java 21 / Maven / PostgreSQL | `91140874065` | success |
| Vben TypeScript / production build | `91140874060` | success |
| UniApp TypeScript / H5 / WeChat | `91140874014` | success |
| Repository hygiene | `91140874107` | success |

Maven evidence recalculated from `maven-verify.log`:

- aggregate: `1438 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `134 / 134`;
- architecture module: `138 / 138`;
- ArchUnit `ModuleBoundariesTest`: `10 / 10`;
- P1 focused Java tests: `22 / 22`;
- P2 focused `ApprovalAssistanceAdvisoryContractTest`: `20 / 20`;
- `BUILD SUCCESS`: present;
- full reactor result: every module `SUCCESS`;
- total Maven time: `08:57 min`.

Permanent governance evidence from the Repository hygiene job:

- M6-E P0/P1/P2 authority boundary: `8 / 8`;
- combined repository-hygiene entrypoint: `13 / 13`;
- existing M6-D foundation boundary: `10 / 10`;
- existing M6-D activation boundary: `6 / 6`;
- existing M6-D transport boundary: `7 / 7`.

## 10. P2 implementation artifact verification

Every ZIP was independently downloaded and locally SHA-256 hashed. Each local SHA-256 exactly
matches the GitHub artifact digest.

| Artifact | ID | Size | GitHub/local SHA-256 | Match |
| --- | ---: | ---: | --- | --- |
| Maven | `8791520116` | `26989` | `7629bac77af47573eb55132e2c6197c391dbe76d5dc183cb074eb04d7a82d49b` | exact |
| Vben | `8791347540` | `18874` | `9f45af202f6b9215a0a434b59f1422588618a1c200afe5cb36de8fec9c362b4e` | exact |
| Mobile | `8791331410` | `9793` | `a3cdfe5bb76a47634eb07b596b0f252f7f6c24b4997d78b5f8c056d5cd9d3dcc` | exact |
| Hygiene | `8791312129` | `9258` | `3cbdbbb0231e502d637a6994d702c832307b57a58886d7f04c66a97e8019d57c` | exact |

All four artifacts are unexpired and expire at `2026-10-29T11:06:23Z`.

## 11. Review state before this record

Before this acceptance record was committed:

- PR #83 remained Open + Draft;
- mergeable: `true`;
- branch compare: ahead `36`, behind `0`;
- changed files: `14`;
- submitted reviews: none;
- requested reviewers: none;
- unresolved review threads: none;
- top-level PR comments: P0 and P1 acceptance evidence only, with no actionable finding;
- reactions: none;
- auto-merge was not enabled.

The documented Head created by this record must receive a new permanent workflow and four new
independently matched artifacts. Run `30625855481` cannot substitute for that documented-Head
validation.

## 12. Explicit absence of P3-P7 capability

P2 introduces no:

- application orchestration or Provider request mapping;
- Provider invocation or production Provider adapter;
- production Prompt content or customer knowledge;
- runtime Secret material or network egress;
- persistence, retention implementation or Flyway migration;
- read-only approval-assistance endpoint;
- PC or Mobile AI experience;
- AI Queue, Worker, Scheduler, listener, polling or autonomous retry;
- automation proposal or executable action;
- approve, reject, return, transfer, withdraw, terminate, migrate, publish or activate command;
- M6-F capability.

## 13. P2 formal decision

M6-E P2 is accepted as a bounded, evidence-backed, human-reviewed advisory contract, subject to
the new documented-Head permanent validation.

P3 may begin only after:

1. the exact documented Head Run succeeds;
2. all four documented-Head artifacts are independently SHA-256 matched;
3. Maven aggregate, AI Core and P1/P2 focused evidence are recalculated;
4. Review, thread, comment and reaction checks contain no actionable finding;
5. PR #83 remains Draft;
6. current main is unchanged or is merged into the branch through an ordinary Merge Commit and
   fully revalidated.

P3 may add bounded internal orchestration only. It must not bind a production endpoint, add
persistence, introduce a real Provider call or create any approval command.

`M6_E_P2_ACCEPTED_NOT_PRODUCTION_ENABLED`

`ADVISORY_NOT_AUTHORITY`

`HUMAN_REVIEW_REQUIRED`

`AI_IS_NOT_AN_OPERATOR`

`PROVIDER_TO_DIRECT_COMMAND_PROHIBITED`
