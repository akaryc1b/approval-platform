# M6-E R0 Production Invocation Rebaseline Acceptance

Status: `M6_E_R0_PENDING_DOCUMENTED_HEAD_VALIDATION`

Date: `2026-08-04`

Tracking:

- parent milestone: Issue #62;
- roadmap rebaseline: Issue #78, closed / completed;
- workstream: Issue #80;
- Pull Request: #83;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- target: `main`;
- exact source main: `fcf031da9e6e04b15a1255044021a7fdd6637421`;
- exact pre-R0 Head: `f4c830ff260301b55b658e94443d631748e0f6db`.

This append-only record performs only M6-E R0. It does not implement or authorize P6-E, create a
runtime Provider bean, enable production invocation, add a generation API, bind P4 persistence,
start M6-F, grant AI command authority, mark PR #83 Ready or authorize merge.

## 1. Exact source and branch state

The connected GitHub repository was queried again before this record was created.

Verified state:

- `main`: `fcf031da9e6e04b15a1255044021a7fdd6637421`;
- PR #83: `open`, `draft`, `mergeable=true`, not merged;
- PR base: `main@fcf031da9e6e04b15a1255044021a7fdd6637421`;
- branch Head before this record: `f4c830ff260301b55b658e94443d631748e0f6db`;
- compare: ahead `165`, behind `0`;
- commits: `165`;
- changed files: `99`;
- additions/deletions: `23638 / 104`;
- requested reviewers: none;
- auto-merge: not enabled;
- Ready transition: not performed.

No Merge Commit from a newer `main` was required because the branch remained behind zero.

## 2. Issue and roadmap gate

Verified issue state:

- Issue #80: open;
- Issue #62: open;
- Issue #13: open;
- Issue #14: open;
- Issue #78: closed with reason `completed`.

No issue was closed, reopened or relabelled by R0.

## 3. Review, discussion and reaction audit

Verified PR #83 discussion state before this record:

- submitted Reviews: two `COMMENTED` P6-A evidence-only records;
- the second record explicitly states that the records contain no actionable finding;
- requested reviewers: none;
- unresolved review threads: zero;
- PR reactions: zero;
- no `REQUEST_CHANGES` review exists;
- no new actionable security, correctness, compatibility or authority finding was identified.

Existing top-level comments remain append-only evidence for accepted P0 through P6-D slices.

## 4. Accepted P6-D permanent evidence revalidation

The exact pre-R0 Head still maps to natural pull-request workflow Run `30879942788` / number `1122`.

Run state:

- workflow: `Approval Platform Validation`;
- event: `pull_request`;
- attempt: `1`;
- conclusion: `success`;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- Head: `f4c830ff260301b55b658e94443d631748e0f6db`.

Jobs:

| Job | Job ID | Result |
| --- | ---: | --- |
| Java 21 / Maven / PostgreSQL | `91898926646` | success |
| Vben TypeScript / production build | `91898926627` | success |
| UniApp TypeScript / H5 / WeChat | `91898926644` | success |
| Repository hygiene | `91898926611` | success |

The four artifacts remain unexpired through `2026-11-02T05:11:51Z`. Every ZIP was downloaded again
in the R0 execution environment and independently SHA-256 hashed. Each local digest exactly matched
the GitHub artifact digest.

| Artifact | ID | Size | GitHub/local SHA-256 | Match |
| --- | ---: | ---: | --- | --- |
| Maven | `8881052937` | `27492` | `afe58852b6b9d08e58046cbedb1713b96735cd119f9b0390e31a2f7f2cce7117` | exact |
| Vben | `8880921532` | `18935` | `85999e23b4fb532ff7de4a808a1baf5ed16590f71af4529c7888dcaefb9f1ce6` | exact |
| Mobile | `8880905949` | `9812` | `cbbd0c644db161508d5e04bf34d4a4a10f914a1b59c9c21466ba1590cd9b7392` | exact |
| Hygiene | `8880891428` | `9237` | `1a338df530af8725972b823e791ac1e7ce4ef2398e3742b13a252b837cf08b2b` | exact |

Revalidated P6-D evidence remains:

- Maven aggregate: `1528 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `156 / 156`;
- OpenAI module: `50 / 50`;
- approval-application: `233 / 233`;
- persistence JDBC: `295 / 295`;
- architecture module: `139 / 139`;
- approval-server: `156 / 156`;
- repository hygiene: `29 / 29`;
- all 26 Maven reactor projects: success;
- Vben type-check and production build: success;
- UniApp type-check, H5 and WeChat builds: success.

These are revalidated historical P6-D facts. They are not represented as a new R0 test Run.

## 5. Migration and workflow inventory

The branch still contains the governed M6-E migration:

`server-modules/approval-persistence-jdbc/src/main/resources/db/migration/V49__create_ai_approval_assistance_durable_evidence.sql`

The migration remains hash-only and explicitly prohibits raw Provider request/response, Prompt,
Secret, network material, approval command and autonomous processing. The migration-upgrade
assertions still register only the V49 P4 evidence tables and indexes. A fetch for a V50 migration
path returned `404`, and the PR compare contains no V50-or-higher migration.

The retained automatic PR/main workflow is:

`.github/workflows/approval-platform-validation.yml`

It continues to provide the four permanent jobs and triggers for `pull_request -> main` and
`push -> main`. R0 creates no second workflow and does not modify the existing workflow.

## 6. Other repository activity and stale-assumption audit

A separate Dependabot PR #84 is open against the same source `main` and proposes only a
`flatten-maven-plugin` version update. It is not merged and therefore has not changed current main,
PR #83's merge base or the behind count.

No other observed repository activity changed the accepted P0 through P6-D contracts. If PR #84 or
another change reaches main before a later gate, PR #83 must stop and merge the new main through an
ordinary Merge Commit, never rebase or force push, followed by complete permanent revalidation.

## 7. Execution-environment worktree statement

The active execution container did not contain a pre-existing local checkout and could not resolve
`github.com` through its local DNS path. This record does not falsely claim a local `git status` or
an authenticated `gh` session.

All R0 reads and this append-only write are instead performed through the connected GitHub App,
explicitly pinned to repository `akaryc1b/approval-platform` and branch
`agent/m6-e-governed-ai-approval-assistance`. The write is applied directly to the exact remote ref;
there is therefore no uncommitted local worktree state mixed into this commit. The new natural
pull-request workflow is the authoritative clean-checkout validation of the resulting documented
Head.

This substitution is recorded as an execution-environment fact, not concealed as a normal local
checkout.

## 8. Revalidated permanent boundaries

R0 retains without reinterpretation:

- AI is never an operator;
- every result remains `ADVISORY`, `UNVERIFIED_ADVISORY` and human-reviewed;
- no Provider-to-command path;
- no approve, reject, return, transfer, withdraw, terminate, migrate, publish or activate command;
- P5 GET remains zero-egress and cannot generate;
- exact Provider ID `openai-responses` and protocol `OPENAI_RESPONSES_V1`;
- exact endpoint `https://api.openai.com:443/v1/responses`;
- exact model snapshot `gpt-5-mini-2025-08-07`;
- `store=false`, `background=false`, `stream=false`, tools empty, `tool_choice=none`;
- exactly one HTTP attempt, zero retry, zero fallback and zero redirect;
- server-owned `OPENAI_API_KEY` and `OPENAI_API_KEY_VERSION` callback-scoped lease;
- admission before DNS and Secret access;
- bounded DNS/TCP/TLS and total Provider deadlines;
- private/local/special-purpose address rejection and admitted-address connection binding;
- dispatch ambiguity recorded as `UNKNOWN` without retry;
- no Queue, Worker, Scheduler, listener, polling, automation proposal or executable action;
- no M6-F capability.

## 9. Documented-Head gate

This record creates a new exact documented Head. R0 is not formally accepted until:

1. a new natural pull-request workflow for that exact Head completes successfully;
2. all four jobs are successful;
3. four new artifacts are present, unexpired and tied to that exact Run and Head;
4. every artifact is independently downloaded and SHA-256 matched;
5. Maven, Web, Mobile and hygiene evidence are recalculated;
6. PR #83 remains Open + Draft + mergeable and behind zero;
7. Reviews, threads, comments and reactions contain no actionable finding;
8. Issues #80, #62, #13 and #14 remain open and Issue #78 remains completed;
9. current main remains exact or is merged normally and fully revalidated.

P6-E may begin only after an append-only PR evidence record freezes those successful results.

`M6_E_R0_NOT_YET_FORMALLY_ACCEPTED`

`P6_E_REMAINS_GATED`

`AI_IS_NOT_AN_OPERATOR`
