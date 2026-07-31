# M6-R1 Formal Acceptance

Status: `FORMALLY_ACCEPTED_PENDING_DOCUMENTED_HEAD_PERMANENT_VALIDATION`

Date: `2026-07-31`

## 1. Scope and exact tracking

- parent milestone: Issue #62;
- M6-R1 issue: #78;
- Draft Pull Request: #79;
- branch: `agent/m6-r1-capability-gap-roadmap-rebaseline`;
- target: `main`;
- exact source main: `cefb2bf35701ca8af39ce6607e1b25c58ffb9a4e`;
- capability/roadmap document: `docs/m6/M6_CAPABILITY_GAP_AND_ROADMAP_REBASELINE.md`;
- rebaselined M6-E issue: #80;
- rebaselined M6-F issue: #81;
- rebaselined M6-G issue: #82.

A dated roadmap comment linking #78, #79, #80, #81 and #82 was added to parent Issue #62
without replacing or deleting its historical bootstrap description.

This acceptance covers only current-truth inventory, gap classification, roadmap decisions,
threat/rollback strategy and Definition of Done. It adds no M6-E or M6-F product capability.

## 2. Audited current truth

The audit re-read GitHub state, Merge Commits, Issues, reactor modules, executable application
dependencies, runtime configuration, APIs, migrations, workflow, tests and permanent artifacts.

Key findings:

1. M6-A has accepted contracts and substantial read-only connector implementation, but production
   connector execution remains blocked. The executable app does not bind the DingTalk contract/HTTP
   modules, constructs no real Secret material source, and defaults routing/token/invocation/
   diagnostics gates off.
2. M6-B provides accepted Java/TypeScript SDK and deterministic event/transport/evidence models,
   but no durable platform event delivery, Outbox, broker, Queue, Worker or Scheduler.
3. M6-C exposes a real tenant-governed local preview and DRAFT-only import path. It does not provide
   marketplace, remote loading, publication, deployment or activation.
4. M6-D provides an accepted advisory-only Provider-neutral SPI/core with deterministic test
   Provider and strong safety boundaries. The AI modules are absent from the executable app,
   no AI API/UI exists, and no real Provider, Secret, network or persistence path exists.
5. Current migration history ends at V48. No V49 or higher migration exists at this acceptance
   baseline, but M6-E must rescan immediately before owning a migration.
6. `.github/workflows/approval-platform-validation.yml` remains the only automatic PR/main
   validation workflow.

The audit therefore preserves the distinction:

```text
contract exists
!= executable runtime binding
!= production path
!= operated rollback/incident capability
!= whole-M6 completion
```

## 3. Formal roadmap decisions

The following decisions are accepted:

- `PRODUCTION_PROVIDER_REQUIRED_FOR_M6_E_COMPLETION`;
- `DURABLE_MINIMAL_ASSISTANCE_EVIDENCE_REQUIRED`;
- `ATTACHMENT_EXTRACTION_AND_GENERAL_RAG_DEFERRED`;
- `ACTION_WHITELIST_EMPTY_PENDING_EXISTING_COMMAND_AUDIT`;
- M6-E is synchronous and bounded, with no AI Queue, Worker or Scheduler;
- M6-F cannot be accepted with an empty or invented action whitelist;
- M6-G is a real cross-workstream production-readiness acceptance, not documentation-only closure;
- Issue #62 remains open until #80, #81 and #82 meet their complete post-main gates.

No executable action is authorized by M6-R1. Approve, reject/return, transfer, withdraw, terminate,
migrate, template/Provider activation, permission/Secret change, arbitrary SQL/script/HTTP and
direct connector commands remain prohibited without separate high-risk gates.

## 4. Implementation-head permanent verification

Implementation Head:

`eb8955e8b74a4d9c1108f8e7888c7e8e2e455af3`

Permanent workflow:

- Run ID: `30611565578`;
- run number: `958`;
- event: `pull_request`;
- branch: `agent/m6-r1-capability-gap-roadmap-rebaseline`;
- exact Head: `eb8955e8b74a4d9c1108f8e7888c7e8e2e455af3`;
- attempt: `1`;
- conclusion: `success`.

Jobs:

| Job | Job ID | Conclusion |
| --- | ---: | --- |
| Repository hygiene | `91095286831` | success |
| UniApp TypeScript / H5 / WeChat | `91095286860` | success |
| Java 21 / Maven / PostgreSQL | `91095286887` | success |
| Vben TypeScript / production build | `91095286918` | success |

Maven evidence recalculated from the downloaded `maven-verify.log`:

- aggregate: `1396 / 0 / 0 / 0`;
- AI SPI: `12 / 0 / 0 / 0`;
- AI Core: `92 / 0 / 0 / 0`;
- architecture test module: `138 / 0 / 0 / 0`;
- ArchUnit `ModuleBoundariesTest`: `10 / 10`;
- `BUILD SUCCESS`: present.

Repository-hygiene focused evidence recalculated from downloaded logs:

- M6 AI foundation boundary: `10 / 10`;
- M6 AI activation review boundary: `6 / 6`;
- M6 AI transport review boundary: `7 / 7`;
- single automatic workflow boundary: passed;
- deterministic Provider implementations remain test-only: passed;
- no direct Flowable table access or unauthorized V49: passed.

## 5. Implementation-head artifact verification

Every artifact was downloaded independently. Each local ZIP SHA-256 exactly matched the GitHub
digest.

| Artifact | ID | Size | Created at | Expires at | Expired | GitHub digest / local ZIP SHA-256 | Match |
| --- | ---: | ---: | --- | --- | --- | --- | --- |
| `approval-maven-30611565578` | `8785829121` | `26909` | `2026-07-31T07:12:17Z` | `2026-10-29T07:03:46Z` | false | `7f8b4758118042223845daef458f984baf54fe3db4e79d606b0d5a63090d6a5b` | exact |
| `approval-vben-30611565578` | `8785683164` | `18856` | `2026-07-31T07:05:41Z` | `2026-10-29T07:03:46Z` | false | `28e050f5d36edafbbf4988b3d41eb394fef78c0233f776cda29e50f22671b827` | exact |
| `approval-mobile-30611565578` | `8785663900` | `9800` | `2026-07-31T07:04:49Z` | `2026-10-29T07:03:46Z` | false | `517e5f446981caaa0032c78b99317f071b660f39b7fae2963b6152c6054fc2a1` | exact |
| `approval-hygiene-30611565578` | `8785644798` | `9244` | `2026-07-31T07:03:59Z` | `2026-10-29T07:03:46Z` | false | `cda2e5f4323e83d7924b6328f368ccd16d775b3d855c55f4d743221f7f6f5575` | exact |

## 6. Formal acceptance decision

The M6-R1 capability gap audit and roadmap rebaseline are formally accepted at the documented
scope.

This acceptance does not authorize:

- marking M6-E, M6-F, M6-G or parent Issue #62 complete;
- a real Provider or production Prompt;
- Secret or customer credential material;
- network egress;
- AI persistence or Flyway migration;
- AI API/UI;
- automation proposal or command;
- Queue, Worker or Scheduler;
- approval-state mutation.

AI remains advisory-only, non-executable and unable to manufacture tenant, operator, permission,
authority, audit, worker, lease or engine identity.

## 7. Remaining documented-head, Ready and post-main gates

The commit containing this record is the documented acceptance Head. It must complete a new natural
full permanent workflow because a Git commit cannot record its own SHA and resulting Run in its own
content.

PR #79 may be marked Ready only when:

1. that exact documented Head passes all four jobs;
2. its four artifacts are downloaded and independently SHA-256 matched;
3. Maven aggregate/focused evidence is recalculated again;
4. current main remains `cefb2bf35701ca8af39ce6607e1b25c58ffb9a4e` and PR behind is zero;
5. reviews, threads, comments and reactions contain no actionable finding;
6. PR scope remains documentation-only;
7. auto-merge remains disabled.

Ready and Merge are separate actions. Merge must use an ordinary Merge Commit with the exact
verified Head. After merge, Issue #78 remains open until the natural `push -> main` Run, all four
main artifacts, Maven/focused evidence and final Review state are independently verified.

M6-E must not begin before that post-main closure.
