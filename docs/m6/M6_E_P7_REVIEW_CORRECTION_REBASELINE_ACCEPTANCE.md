# M6-E P7 Review Correction Current-Main Rebaseline Acceptance

Status: `P7_REVIEW_CORRECTION_REBASELINE_PENDING_EXACT_VALIDATION`

Date: `2026-08-04`

Tracking:

- workstream: Issue #80;
- parent milestone: Issue #62;
- Pull Request: #83;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- target: `main`;
- exact current `main`: `b20b5cca68bb6b77e7a51233bc2aee3387b21993`;
- PR #86: merged current-main Persistence JDBC CI sharding;
- exact review-correction implementation Head: `75470f61d4c363be03fdf490b47abe73f45cf804`;
- exact controlled rebaseline Merge Commit: `783dbf30be49c69d7e694ec7ff87f3f7b4af0d85`;
- controlled synchronization PR: #87, merged with ordinary Merge Commit.

This record freezes the final current-main rebaseline for the two actionable P7 review corrections.
It adds no product capability and is not Ready or merge authorization by itself.

## 1. Retained review corrections

The rebaseline retains both exact actionable corrections from
`M6_E_P7_REVIEW_CORRECTION_ACCEPTANCE.md`:

1. Provider `x-request-id` is independently required and hashed, while the client
   `X-Client-Request-Id` is verified through transport evidence; the two identifiers are never
   required to be equal.
2. `AiServerRequestContext.tenantId` accepts the platform maximum of 128 characters and rejects
   129 characters.

The original actionable review evidence remains:

- review `PRR_kwDOTbeZ188AAAABIUMkpg`;
- thread `PRRT_kwDOTbeZ186WReSu`, comment `PRRC_kwDOTbeZ187dNq-g`;
- thread `PRRT_kwDOTbeZ186WReS2`, comment `PRRC_kwDOTbeZ187dNq-p`.

Both threads remain unresolved until this documented Head receives exact permanent evidence.

## 2. Pre-rebaseline implementation evidence

Run `30902240640` / #1191 at Head
`75470f61d4c363be03fdf490b47abe73f45cf804` succeeded under the current-main sharded workflow.

The final Maven evidence aggregated Maven core plus four deterministic Persistence JDBC shards:

- Maven aggregate: `1553 / 0 / 0 / 0`;
- AI Core: `158 / 158`;
- OpenAI: `57 / 57`;
- approval-application: `233 / 233`;
- Persistence JDBC: `295 / 295`;
- architecture: `139 / 139`;
- approval-server: `172 / 172`;
- Persistence JDBC selected classes: `73`;
- Surefire report classes: `72` plus one abstract class;
- duplicate shard assignments: zero;
- skipped, failures and errors: zero.

Four final artifacts were downloaded and independently SHA-256 exact:

| Artifact | ID | Size | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `8889609306` | `327860` | `e45911f1748baf95fef0c0c7cb7226d4e79a1c8e9a9eae780f1d5957dfa95a8a` |
| Vben | `8889571946` | `18553` | `28e95ecadc75e01fb2ab03ec021238cc37c44d6b81602c2fc65c23030e599df8` |
| Mobile | `8889565208` | `9817` | `fe3e3101305073ea74c905e994fc4d34eb00b536a6b46dc65d53a637a7ebc090` |
| Hygiene | `8889542179` | `10759` | `ad94b20dc564e555ec9c91604e4d9f1c697f14e45a743d382789d0bbdbb1b938` |

This Run proved the corrected code against the merge result of implementation Head and current
`main`, but final acceptance still requires a new natural workflow for the documented rebaselined Head.

## 3. Controlled Merge Commit rebaseline

PR #87 merged exact `main` into the M6-E branch with ordinary Merge Commit:

`783dbf30be49c69d7e694ec7ff87f3f7b4af0d85`

The merge retained both sides without rebase, squash or force push:

- the M6-E `approval-ai-core` dependency in `approval-persistence-jdbc`;
- `approval.persistence.test.fork-count=4`;
- `approval.persistence.tests.skip=false`;
- Surefire `skipTests=${approval.persistence.tests.skip}`;
- reusable forks and JaCoCo append mode;
- `.github/workflows/approval-platform-validation.yml` sharded Maven architecture;
- `scripts/ci/select-persistence-jdbc-tests.sh`;
- `scripts/ci/verify-persistence-jdbc-shards.py`;
- every M6-E production, test, document and permanent-boundary change.

After the merge, branch compare is ahead `219`, behind `0`, and PR #83 remains Open, Draft,
mergeable and unmerged.

## 4. Sharded permanent workflow contract

The repository still has one automatic PR/main workflow only:

`.github/workflows/approval-platform-validation.yml`

The backend now uses these physical jobs:

- Java 21 / Maven core;
- Persistence JDBC shard 0;
- Persistence JDBC shard 1;
- Persistence JDBC shard 2;
- Persistence JDBC shard 3;
- Java 21 / Maven / PostgreSQL aggregation.

The formal release evidence remains four final artifact groups:

- Maven/PostgreSQL aggregate;
- Vben Web;
- UniApp Mobile;
- Repository hygiene.

Helper artifacts are merged and deleted by the workflow. Acceptance uses only the final four artifacts.

## 5. Final documented-Head gate

Before the two actionable review threads may be answered and resolved, the commit containing this
record and its permanent boundary must receive:

1. one natural pull-request workflow, attempt 1;
2. successful Maven core, all four Persistence JDBC shards and Maven aggregation;
3. successful Web, Mobile and Repository hygiene;
4. four final artifacts tied to the exact Head and Run;
5. independent size and SHA-256 equality for every final artifact;
6. recalculated aggregate and module statistics;
7. current `main` unchanged and branch behind zero;
8. PR #83 Open, Draft, mergeable and unmerged;
9. no new actionable review, requested change, unresolved thread or disallowed reaction;
10. Issues #80, #62, #13 and #14 Open and Issue #78 Closed / Completed.

After exact evidence, replies may be posted and the two corresponding threads may be resolved.
A complete gate recheck remains mandatory before Ready and ordinary Merge Commit to `main`.

## 6. Permanent exclusions

This rebaseline adds no Provider, model, Prompt, endpoint, Secret, retry, fallback, redirect,
streaming, Queue, Worker, Scheduler, automation proposal or executable action.

It adds no migration. M6-E owns only
`V49__create_ai_approval_assistance_durable_evidence.sql`; no V50 or later migration exists.

It preserves:

- `AI_IS_NOT_AN_OPERATOR`;
- advisory, unverified and human-reviewed output only;
- no approval or Flowable command authority;
- no milestone M6-F capability;
- no live paid/customer Provider request in CI.

Current decision:

`P7_REVIEW_CORRECTION_REBASELINE_PENDING_EXACT_VALIDATION`

`PR_83_REMAINS_DRAFT`

`REVIEW_THREADS_REMAIN_UNRESOLVED_UNTIL_DOCUMENTED_HEAD_EVIDENCE`

`M6_F_REMAINS_GATED`

`AI_IS_NOT_AN_OPERATOR`
