# M6-A-R0 Post-M5 Rebaseline Permanent Evidence

Status: `REBASELINED_ON_POST_M5_MAIN / IMPLEMENTATION_PERMANENTLY_VALIDATED`

Production connector execution: `NOT_AUTHORIZED`

## Scope and governance

This record covers only M6-A-R0 for Draft PR #67 and branch
`agent/m6-a-connector-foundation`.

R0 merged the accepted M5 `main` history into M6-A with a normal merge commit.
It did not implement P5, acquire Secret material, acquire a Token, invoke a
connector, modify approval state, create a worker or scheduler, add persistence,
or authorize production execution.

The following operations were not used:

- rebase;
- squash;
- amend;
- reset or history rewrite;
- force push;
- auto-merge;
- Ready-for-review transition;
- PR merge.

## Pre-rebaseline verified state

- repository: `akaryc1b/approval-platform`;
- `main`: `1d425581d0548c6b15487d58ce47774b29f1073a`;
- M5 PR #58: Merged / Closed;
- M5 documented Head: `cff98b78aabe2b1892d98969b188c102cc1ed591`;
- M5 merge commit: `1d425581d0548c6b15487d58ce47774b29f1073a`;
- M6-A initial Head: `4f59b12dff8b9988c4509b54fbbcb61046069fc9`;
- initial relation to `main`: ahead `76`, behind `346`;
- PR #67: Open + Draft + mergeable;
- reviews, requested reviewers and unresolved review threads: none;
- repository auto-merge: disabled;
- merge commit: allowed;
- Flyway: continuous through `V48`;
- `V49` or higher: absent;
- only automatic PR/main workflow:
  `.github/workflows/approval-platform-validation.yml`.

M6-B, M6-C and M6-D remained isolated at their verified Heads:

- PR #68: `330dbdd035e436459ffdedf0d2b0c8e07dac7e6c`;
- PR #69: `72acb3ba18602c09c28bfe08b58f8b91e6efe6e4`;
- PR #70: `9d588215e869c8f1332c0bc1a2809fbd235c2efa`.

## Normal merge commit

R0 fixed the GitHub-generated, conflict-free two-parent PR merge commit onto the
M6-A branch with a non-forced fast-forward ref update:

- merge commit: `b44ff1dd4fbe3316a945524c43b6ad8ae2546885`;
- first parent: M6-A Head
  `4f59b12dff8b9988c4509b54fbbcb61046069fc9`;
- second parent: post-M5 `main`
  `1d425581d0548c6b15487d58ce47774b29f1073a`;
- branch ref update: `force=false`;
- merge conflicts: none;
- M5 `V33` through `V48`: unchanged by conflict resolution because no conflict
  resolution was required.

The resulting branch contained the complete accepted M5 history and the existing
M6-A P1-P4 history. It did not copy M5 implementation into M6-A-owned modules or
claim M6-A ownership of M5 migration semantics.

## Retained failed permanent workflow

The normal merge naturally triggered Approval Platform Validation Run
`30356987426` / #873 at Head
`b44ff1dd4fbe3316a945524c43b6ad8ae2546885`.

Job results:

- Repository hygiene: success;
- Vben TypeScript / production build: success;
- UniApp TypeScript / H5 / WeChat: success;
- Java 21 / Maven / PostgreSQL: failure.

The failed Run was not cancelled, deleted, hidden or rerun.

### Root cause

Four pre-M5 M6-A architecture assertions still encoded the old repository-wide
premises that Flyway could not exceed `V32` and that accepted M5 source files had
to be absent. After the authorized M5 merge, those premises were stale. Maven
reported four failures in the 65-test architecture module:

- `M6ConnectorFoundationBoundaryTest.connectorSliceAddsNoV33OrOtherFlywayMigration`;
- `M6ConnectorFoundationBoundaryTest.connectorSliceDoesNotCopyOrModifyM5MigrationSources`;
- `M6DingTalkProductionTransportBoundaryTest.p3AddsNoWorkflowMigrationM5OrExecutionCoordinator`;
- `M6CredentialBindingFoundationBoundaryTest.foundationAddsNoWorkflowMigrationM5OrApprovalMutation`.

This was a test-baseline defect. No M5 runtime, persistence or migration semantic
failed.

### Failed Run artifact chain

Every ZIP was downloaded and independently hashed. Each local SHA-256 exactly
matched the GitHub artifact digest.

| Artifact | ID | GitHub digest / downloaded ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30356987426` | `8687380480` | `3467783738a5ca5be71312c7e0d663b1b9a7c18425f52bc1fed57b02fd2691d8` — exact match |
| `approval-vben-30356987426` | `8687239897` | `199f03f43ebfd8bfadfe152d673636e79ac91157a78085cae095c09b8cd633b7` — exact match |
| `approval-mobile-30356987426` | `8687218425` | `44e230fcce1275ae0ca3a7ab81126a120372305cd7e77e596d62f13d11ddec5b` — exact match |
| `approval-hygiene-30356987426` | `8687199357` | `07c1aef54e8ede0eb91f9984eedd2cbbfbe0a548fde4c9d2f79469f721d35da7` — exact match |

## Minimal post-merge boundary fix

Commit:

`7783c615d339828366433fb38bc23f485c51a0e8`

Message:

`fix(m6-a): rebaseline post-M5 boundary assertions`

The commit changed only three M6-A architecture test files. It:

- recognizes accepted repository Flyway versions through `V48`;
- continues to reject every `V49` or higher migration;
- recognizes that accepted M5 source is now present through `main`;
- verifies that M6-A connector modules do not import or own M5 migration domain,
  engine or execution semantics;
- preserves the single automatic workflow assertion;
- preserves no approval-state mutation and no worker/retry/recovery boundaries.

The fix did not change M5 production source, M5 migrations, M6-A production
source, runtime configuration, database schema, Web or Mobile behavior.

The branch ref update for the fix used `force=false`.

## Successful implementation validation

Approval Platform Validation Run `30358593206` / #874 completed successfully at
Head `7783c615d339828366433fb38bc23f485c51a0e8`.

Job results:

- Repository hygiene: success;
- Java 21 / Maven / PostgreSQL: success;
- Vben TypeScript / production build: success;
- UniApp TypeScript / H5 / WeChat: success>

Maven evidence:

- aggregate: `946 / 0 / 0 / 0`;
- architecture module: `65 / 0 / 0 / 0`;
- R0-rebaselined focused boundaries: `24 / 0 / 0 / 0`;
  - M6 connector foundation boundary: `7`;
  - DingTalk production transport boundary: `9`;
  - credential-binding foundation boundary: `8`;
- reactor: `BUILD SUCCESS`;
- total time: `08:21 min`.

Node and client evidence:

- permanent M5 Node boundaries: `139 / 139`;
- M4 release-governance boundaries: `5 / 5`;
- M4 SLA/calendar boundaries: `13 / 13`;
- Vben client/form boundaries: `15 / 15`;
- Vben type-check and production build: success;
- UniApp type-check, H5 build and WeChat build: success.

### Successful Run artifact chain

Every ZIP was downloaded and independently hashed. Each local SHA-256 exactly
matched the GitHub artifact digest.

| Artifact | ID | GitHub digest / downloaded ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30358593206` | `8688058572` | `853ce3f02d4703a65b850be9c531c376682fa283f73e80febfdcde0820908a4e` — exact match |
| `approval-vben-30358593206` | `8687856339` | `d63c34d4bd86bb7e14b74baecf65adeb0cb810b82b4112ecfa2f9209c3343b2c` — exact match |
| `approval-mobile-30358593206` | `8687839067` | `ffdab4894ead0b14b376e0342db37f9bb74ca925eac9afbd7b5bc0b73f20667b` — exact match |
| `approval-hygiene-30358593206` | `8687819619` | `d8e2b284f0acd27bf052b87e896061341582611cb876665018906faf7d04e2dd` — exact match |

## Rebaseline invariants

After the fix Head:

- relation to `main`: ahead `78`, behind `0`;
- Flyway remains continuous through `V48`;
- no `V49` or higher migration exists;
- no second automatic workflow exists;
- no production Secret or Token was introduced;
- no Secret or Token persistence was introduced;
- no worker, scheduler, polling loop or scanner was introduced;
- no connector invocation was introduced;
- no approval-state mutation was introduced;
- M5 default-disabled and `NOT_AUTHORIZED` execution semantics remain intact;
- PR #67 remains Open + Draft and is not merged;
- auto-merge remains disabled.

## R0 acceptance

```text
M6-A R0:
  REBASELINED_ON_POST_M5_MAIN
  IMPLEMENTATION_PERMANENTLY_VALIDATED

Flyway:
  V48
  NO_V49

PR #67:
  OPEN
  DRAFT
  NOT_MERGED

Production connector execution:
  NOT_AUTHORIZED
```

A separate natural workflow at the evidence-document Head is required before R0
is considered finally documented and before P5 implementation may begin.
