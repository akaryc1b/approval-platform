# M6-E P3 Synchronous Orchestration Acceptance

Status: `M6_E_P3_FORMALLY_ACCEPTED_PENDING_DOCUMENTED_HEAD_VALIDATION`

Date: `2026-07-31`

Tracking:

- parent milestone: Issue #62;
- roadmap rebaseline: Issue #78, closed / completed;
- workstream: Issue #80;
- Pull Request: #83;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- target: `main`;
- exact base: `fcf031da9e6e04b15a1255044021a7fdd6637421`.

This record accepts only M6-E P3. It does not authorize P4 persistence, a real production
Provider, production Prompt/customer knowledge, runtime Secret material, network egress, a public
API/UI, automation, an executable action or any approval command.

## 1. P2 entry gate

P3 began only after P2 completed its independent documented-Head acceptance.

Accepted P2 evidence:

- exact documented Head: `fa18b27df6286d5170f9b139b6ec0ec1806d03ed`;
- permanent Run: `30626680112` / run number `994`;
- event: `pull_request`;
- attempt: `1`;
- conclusion: `success`;
- all four permanent jobs: `success`;
- all four artifacts: independently downloaded and SHA-256 matched;
- Maven aggregate: `1438 / 0 / 0 / 0`;
- AI Core: `134 / 134`;
- P1 focused Java: `22 / 22`;
- P2 focused Java: `20 / 20`;
- M6-E P0-P2 architecture boundary: `8 / 8`;
- no actionable Review finding;
- PR #83 remained Open + Draft.

At P3 acceptance, current `main` remains
`fcf031da9e6e04b15a1255044021a7fdd6637421`. The M6-E branch is ahead of main and behind zero.
Issues #80, #62, #13 and #14 remain open.

## 2. P3 accepted scope

P3 adds one internal synchronous orchestrator in the existing AI Core:

- `server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/ApprovalAssistanceSynchronousOrchestrator.java`.

It consumes only:

- the accepted P1 Provider-safe `ApprovalAssistanceContextProjection`;
- the accepted P2 bounded `ApprovalAssistanceAdvisoryContract.Request`;
- existing M6-D Provider registry, routing, budget, circuit, kill-switch, advisory service and
  execution-evidence contracts.

Accepted permanent Java tests:

- `ApprovalAssistanceSynchronousOrchestratorTest`;
- `ApprovalAssistanceSynchronousOrchestratorServiceBoundaryTest`.

Accepted permanent architecture boundary:

- the P3 section in `scripts/tests/m6-e-approval-assistance-boundary.test.mjs`;
- loaded through the existing `scripts/tests/m3-repository-hygiene.test.mjs` gate;
- no second automatic workflow.

P3 adds no Spring bean, controller, application-service binding, database store, migration,
configuration activation, browser source or mobile source.

## 3. Invocation authority remains closed

P3 has two orchestration modes only:

- `DISABLED`;
- `DETERMINISTIC_TEST_ONLY`.

A disabled control permits zero Provider attempts. An enabled P3 control permits exactly one
Provider attempt and only in `DETERMINISTIC_TEST_ONLY` mode.

The only Provider type accepted by P3 is:

`AiProviderType.DETERMINISTIC_MOCK`

Production Provider types remain blocked until the independent P6 gate. P3 creates no real network
client, endpoint configuration, Secret source or production Provider registration.

The existing M6-D kill switch remains a blocking control and never becomes authorization. P3 also
requires an independent server-owned orchestration control before any deterministic test attempt.

## 4. Exact single-route orchestration

Before a Provider attempt can start, P3 requires:

1. orchestration control enabled for one deterministic test attempt;
2. routing policy enabled;
3. both pre-invocation and post-invocation fallback disabled;
4. exactly one route candidate;
5. the route capability equals the exact P2 use-case capability;
6. route Provider/model/Prompt/knowledge/policy/output-Schema versions equal the complete P2
   expected versions;
7. kill-switch Provider version, generation and SHA-256 evidence hash equal the server control;
8. kill switch in review/fault-drill-only state;
9. route timeout no greater than the P3 control maximum;
10. exact registered Provider and route descriptor match;
11. Provider capability enabled and its collection/depth limits cover the P1 projection;
12. Provider-safe field count and calculated character count fit the route budget;
13. circuit-breaker permit available.

P3 has exactly one production `advisoryService.advise(...)` call site. It contains no Provider-route
loop, retry loop, sleep, polling or direct `provider.advise(...)` call.

## 5. Provider request construction

P3 does not reconstruct raw Form values and does not invoke `AiDataMinimizer` again.

The Provider request is mapped directly from accepted P1 evidence:

- authenticated tenant/operator/request/trace;
- exact authorized resource type, ID and authorization reference;
- exact P2 use-case capability;
- exact Provider-safe field keys;
- the immutable P1 Provider-safe field list;
- complete P2 expected versions;
- exact selected-route timeout.

The request allowed-field set is derived from the final P1 Provider-safe fields, not from browser
input or a broader Form Schema set.

## 6. No retry and no fallback

P3 permanently enforces:

- maximum Provider attempts: `1`;
- retry attempted: `false`;
- pre-invocation candidate fallback: prohibited;
- post-invocation fallback: prohibited;
- Provider outcomes including timeout, unknown and invalid output are not retried;
- partial invocation cannot select another Provider.

An open circuit starts zero Provider attempts.

## 7. P2 result revalidation

A Provider success is not accepted directly. P3 creates a new P2 `Result` from the exact request and
returned advisory result.

If P2 validation fails, P3 converts the Provider success into:

- classification: `INVALID_OUTPUT`;
- failure code: `AI_ASSISTANCE_CONTRACT_INVALID`;
- accepted result: absent;
- no retry or fallback.

Therefore Provider output cannot bypass the P2 authority, version, Prompt/Schema, confidence,
result-limit or evidence-reference invariants.

## 8. Service-boundary failure semantics

If the existing advisory-service boundary throws a runtime exception after the Provider attempt may
have started, P3 does not propagate the exception and does not retry.

It returns:

- classification: `UNKNOWN`;
- failure code: `AI_ASSISTANCE_SERVICE_BOUNDARY_EXCEPTION`;
- Provider attempts: `1`;
- Provider invocation started: `true`;
- accepted result: absent;
- post-invocation fallback: `false`.

The circuit receives the final `UNKNOWN` classification and final execution evidence is recorded.

## 9. Execution evidence

Every blocked or attempted outcome is sent through the existing
`AiAdvisoryExecutionEvidenceSink` using existing hash-only/redaction-safe M6-D evidence creation.

P3 outcome invariants require:

- Provider attempts equal invocation-started evidence;
- attempts are zero or one;
- retry is always false;
- selected-route versions equal the exact P2 request versions;
- accepted result exists exactly when the final coordinated outcome contains a valid advisory
  result;
- accepted result binds the exact P2 request;
- any started invocation uses deterministic test mode.

A rejected candidate whose versions do not match the P2 request is never labeled as the selected
route in final evidence.

## 10. Retained failed Run and correction

P3 retained the failed natural Run and applied an append-only minimal correction.

Failed exact Head:

`2252943d64fccf64ecba4cd4cd01981e0fc28f58`

Failed permanent workflow:

- Run ID: `30628520177`;
- run number: `1000`;
- event: `pull_request`;
- attempt: `1`;
- conclusion: `failure`;
- Repository hygiene: `success`;
- Maven: `failure`.

Failure:

- `routeVersionMismatchFailsClosedBeforeInvocation` correctly produced a rejected zero-call
  outcome;
- the shared blocked-result path still retained the mismatched candidate as `selectedRoute`;
- the P3 `Outcome` invariant then correctly rejected that inconsistent evidence.

Minimal correction:

`3e02eae4b243e6b55c55b438218311294aa86904`

The correction changes only blocked evidence normalization:

- a candidate route is retained only when its versions equal the exact P2 request versions;
- otherwise `selectedRoute` is `null`;
- classification, zero-call behavior, routing result, budget, circuit and kill-switch semantics are
  unchanged.

Run #1000 was not rerun, deleted, hidden or used as acceptance evidence.

## 11. Successful P3 implementation verification

Exact implementation Head:

`3e02eae4b243e6b55c55b438218311294aa86904`

Permanent workflow:

- Run ID: `30628693710`;
- run number: `1001`;
- event: `pull_request`;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- attempt: `1`;
- conclusion: `success`.

Jobs:

| Job | Job ID | Result |
| --- | ---: | --- |
| Java 21 / Maven / PostgreSQL | `91149804108` | success |
| Vben TypeScript / production build | `91149804149` | success |
| UniApp TypeScript / H5 / WeChat | `91149804551` | success |
| Repository hygiene | `91149804000` | success |

Maven evidence recalculated from `maven-verify.log`:

- aggregate: `1452 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `148 / 148`;
- architecture module: `138 / 138`;
- ArchUnit `ModuleBoundariesTest`: `10 / 10`;
- P1 focused Java tests: `22 / 22`;
- P2 focused Java tests: `20 / 20`;
- P3 focused Java tests: `14 / 14`;
- `ApprovalAssistanceSynchronousOrchestratorTest`: `13 / 13`;
- `ApprovalAssistanceSynchronousOrchestratorServiceBoundaryTest`: `1 / 1`;
- `BUILD SUCCESS`: present;
- full reactor result: every module `SUCCESS`;
- total Maven time: `08:21 min`.

Permanent governance evidence from the Repository hygiene job:

- M6-E P0-P3 authority boundary: `9 / 9`;
- combined repository-hygiene entrypoint: `14 / 14`;
- existing M6-D foundation boundary: `10 / 10`;
- existing M6-D activation boundary: `6 / 6`;
- existing M6-D transport boundary: `7 / 7`.

## 12. P3 implementation artifact verification

Every ZIP was independently downloaded and locally SHA-256 hashed. Each local SHA-256 exactly
matches the GitHub artifact digest.

| Artifact | ID | Size | GitHub/local SHA-256 | Match |
| --- | ---: | ---: | --- | --- |
| Maven | `8792630031` | `26956` | `5e12c8cc97483b2bbdc55de5982d8de9a03af68f360a2d7a69fb23776fd9703d` | exact |
| Vben | `8792465504` | `18827` | `a73e3f8abdfabb22234ca2132a1f0568aa15cef777bf5fb03827bb8ab62509a8` | exact |
| Mobile | `8792452605` | `9791` | `e569054cc4c7af03b27b86c00bb6216e7d697f3b16361ee204063253be57d9ee` | exact |
| Hygiene | `8792433435` | `9234` | `9033bb41f94167fe711e1312bce000463363ae2bf5ee277da650ff770d14dc73` | exact |

All four artifacts are unexpired and expire at `2026-10-29T11:54:49Z`.

## 13. Review state before this record

Before this acceptance record was committed:

- PR #83 remained Open + Draft;
- mergeable: `true`;
- branch compare: ahead `44`, behind `0`;
- changed files: `18`;
- submitted reviews: none;
- requested reviewers: none;
- unresolved review threads: none;
- top-level PR comments: P0, P1 and P2 acceptance evidence only, with no actionable finding;
- reactions: none;
- auto-merge was not enabled.

The documented Head created by this record must receive a new permanent workflow and four new
independently matched artifacts. Run `30628693710` cannot substitute for that documented-Head
validation.

## 14. Explicit absence of P4-P7 capability

P3 introduces no:

- durable assistance-evidence table, CAS store, retention/delete implementation or Flyway
  migration;
- real Provider or production Provider adapter;
- production Prompt content, customer knowledge, RAG, embedding or vector storage;
- runtime Secret material or network egress;
- public approval-assistance endpoint;
- PC or Mobile AI experience;
- AI Queue, Worker, Scheduler, listener, polling or autonomous retry;
- automation proposal or executable action;
- approve, reject, return, transfer, withdraw, terminate, migrate, publish or activate command;
- M6-F capability.

## 15. P3 formal decision

M6-E P3 is accepted as synchronous, exact-route, at-most-one-attempt internal orchestration for a
deterministic test Provider only, subject to the new documented-Head permanent validation.

P4 may begin only after:

1. the exact documented Head Run succeeds;
2. all four documented-Head artifacts are independently SHA-256 matched;
3. Maven aggregate, AI Core and P1-P3 focused evidence are recalculated;
4. Review, thread, comment and reaction checks contain no actionable finding;
5. PR #83 remains Draft;
6. current main is unchanged or is merged into the branch through an ordinary Merge Commit and
   fully revalidated.

P4 must be a separate durable-minimal-evidence migration slice with tenant isolation,
retention/delete rules, CAS and no raw sensitive input/output. It must not introduce a real Provider,
network call, public endpoint or approval command.

`M6_E_P3_ACCEPTED_NOT_PRODUCTION_ENABLED`

`DETERMINISTIC_PROVIDER_TEST_ONLY`

`NO_RETRY_NO_FALLBACK`

`ADVISORY_NOT_AUTHORITY`

`AI_IS_NOT_AN_OPERATOR`

`PROVIDER_TO_DIRECT_COMMAND_PROHIBITED`
