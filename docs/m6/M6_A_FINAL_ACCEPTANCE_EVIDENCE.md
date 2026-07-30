# M6-A Final Acceptance Evidence

## Decision

- formal acceptance: `M6_A_FORMALLY_ACCEPTED`
- implementation validation: `PERMANENTLY_VALIDATED`
- documented-Head validation: `PENDING_THIS_COMMIT_NATURAL_RUN`
- merge readiness: `BLOCKED_UNTIL_DOCUMENTED_HEAD_VALIDATED`
- production connector execution: `PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED`
- approval-state mutation: `APPROVAL_STATE_MUTATION_NOT_AUTHORIZED`

This record is evidence for M6-A Formal Acceptance and conditional merge readiness only. It does not enable a connector, grant runtime authority, resolve a production blocker or authorize customer configuration.

## Formal Acceptance implementation

- implementation commit: `d46cb95ae19e0f84574d382881a75c125d4baf6c`;
- message: `docs(m6-a): add final acceptance`;
- parent: `4ef20330a48288520a66956a7e26072f5d8d39bc`;
- branch update: ordinary fast-forward with `force=false`;
- added file: `docs/m6/M6_A_FINAL_ACCEPTANCE.md`;
- product behavior changed: none;
- Flyway migration added: none;
- workflow added or changed: none.

No rebase, force push, squash, amend, reset or history rewrite was used.

## G1 audited repository baseline

Before the Formal Acceptance implementation commit, GitHub was queried again and confirmed:

- `main`: `1d425581d0548c6b15487d58ce47774b29f1073a`;
- P9 documented Head: `4ef20330a48288520a66956a7e26072f5d8d39bc`;
- relation: ahead `94`, behind `0`;
- PR #67: Open, Draft, unmerged and mergeable;
- requested reviewers, submitted reviews and unresolved review threads: none;
- repository auto-merge: disabled;
- Issues #62, #63, #13 and #14: Open;
- PR #68 Head: `330dbdd035e436459ffdedf0d2b0c8e07dac7e6c`;
- PR #69 Head: `72acb3ba18602c09c28bfe08b58f8b91e6efe6e4`;
- PR #70 Head: `9d588215e869c8f1332c0bc1a2809fbd235c2efa`;
- Flyway: continuous through `V48`, with no `V49` or higher migration;
- only automatic PR/main workflow: `.github/workflows/approval-platform-validation.yml`.

The branch remained behind `0`, so no merge-main rebaseline was required.

## Scope and file-integrity audit

The large PR history was not accepted from filename metadata alone.

- the V48 migration blob is identical on `main` and the M6-A branch;
- the `ApprovalMigrationPlan` domain blob is identical on `main` and the M6-A branch;
- the mobile process-instance migration operations API blob is identical on `main` and the M6-A branch;
- the automatic workflow blob is identical on `main` and the M6-A branch;
- representative connector files reported with zero compare-line statistics were fetched directly and were non-empty, correctly mapped source files;
- the complete Maven reactor compiled and tested all 23 modules successfully;
- Vben and Mobile production builds succeeded;
- repository hygiene succeeded.

The current acceptance tests and successful workflow prove:

- no usable connector Secret literal or Bearer literal in production connector sources;
- no `.pem`, `.key`, `.p12`, `.pfx`, `.jks`, `.keystore` or `.env` artifact in the scanned connector scope;
- diagnostics remain default disabled, GET-only and `no-store`;
- no connector operations POST, PUT, PATCH or DELETE endpoint;
- no durable connector persistence, JDBC transaction, Worker, Scheduler or automatic retry/replay/recovery implementation in P8/P9 scope;
- Flyway highest version is exactly V48;
- exactly one automatic PR/main workflow exists;
- production execution and approval-state mutation remain false in every P9 fault scenario.

## Natural Formal Acceptance implementation workflow

Workflow: `Approval Platform Validation`

- Run ID: `30506195514`;
- run number: `#891`;
- Head: `d46cb95ae19e0f84574d382881a75c125d4baf6c`;
- trigger: natural pull-request branch update;
- status: `completed`;
- conclusion: `success`;
- no manual rerun, cancellation, deletion or hiding.

| Job | Job ID | Result |
| --- | ---: | --- |
| Repository hygiene | `90756383131` | success |
| Java 21 / Maven / PostgreSQL | `90756383170` | success |
| Vben TypeScript / production build | `90756383178` | success |
| UniApp TypeScript / H5 / WeChat | `90756383181` | success |

## Maven evidence

The downloaded Maven log was parsed from the implementation artifact rather than inferred from P9:

- Maven aggregate: `1132 / 0 / 0 / 0`;
- `M6AConnectorFaultAcceptanceTest`: `46 / 0 / 0 / 0`;
- `M6AConnectorSecurityAcceptanceTest`: `6 / 0 / 0 / 0`;
- P9 focused total: `52 / 0 / 0 / 0`;
- reactor modules: `23 / 23` successful;
- reactor: `BUILD SUCCESS`.

## Implementation artifacts and exact local SHA-256

All four artifacts were unexpired. Each ZIP was downloaded from GitHub Actions and hashed locally. Every local SHA-256 exactly matched the GitHub digest.

| Artifact | ID | GitHub digest / downloaded ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30506195514` | `8745547424` | `4efae522b8f03081f58aa2f503d3039e67459449776d47f653d29cc13be60b01` — exact match |
| `approval-vben-30506195514` | `8745424617` | `0b8cb6979497d077cd7ba87a4107ff471f0c29baa2070be03fbf580fd8a3ce9c` — exact match |
| `approval-mobile-30506195514` | `8745409742` | `733808351b373e968ed40520aa7533ec50c305305422ad73f3b51eb67143cad3` — exact match |
| `approval-hygiene-30506195514` | `8745397209` | `d44a7a9ec6bbd0cda448cb0240ea1a70d5975dbae2c819ae81bfc812b94730fb` — exact match |

## P5-P9 accepted lineage

| Gate | Final documented Run | Aggregate | Focused |
| --- | --- | --- | --- |
| P5 | `30372656382` / #878 | `967 / 0 / 0 / 0` | `21 / 0 / 0 / 0` |
| P6 | `30414994006` / #880 | `993 / 0 / 0 / 0` | `26 / 0 / 0 / 0` |
| P7 | `30426801370` / #882 | `1032 / 0 / 0 / 0` | `39 / 0 / 0 / 0` |
| P8 | `30445509949` / #887 | `1080 / 0 / 0 / 0` | `48 / 0 / 0 / 0` |
| P9 | `30449344853` / #890 | `1132 / 0 / 0 / 0` | `52 / 0 / 0 / 0` |

## Retained failed evidence

The acceptance lineage continues to retain all original failures and their bounded corrections:

- R0 Run `30356987426` / #873;
- P5 Run `30370207896` / #876;
- P8 Run `30443390978` / #885;
- P9 Run `30447754692` / #888.

No failure was deleted, hidden, cancelled or directly rerun. Each replacement validation was triggered naturally by a new minimal commit. The P9 PEM test-source fix preserved the same runtime scan marker and did not weaken Secret scanning.

## Permanent non-production boundary

The following remain explicitly unauthorized or absent:

- production connector execution;
- approval-state mutation;
- background connector execution;
- Worker, Queue, Scheduler, listener or polling loop;
- durable diagnostics, durable invocation audit or connector persistence;
- automatic retry, replay, recovery or reconciliation;
- real DingTalk AppKey, AppSecret, Token or customer endpoint;
- production customer tenant configuration;
- concrete production Secret Backend;
- public connector management mutation API;
- production egress, operated Kill Switch, on-call and disaster-recovery authority.

All B01-B20 production blockers remain `BLOCKED`.

## Documented-Head gate

This evidence commit must now trigger a second natural full workflow. M6-A is not merge-ready until:

1. this exact documented Head remains the PR Head;
2. all four permanent jobs complete successfully;
3. its four new artifacts are downloaded;
4. every local ZIP SHA-256 exactly matches GitHub;
5. the Maven aggregate and P9 focused counts are extracted from the new Maven artifact;
6. `main` remains unchanged and behind remains zero;
7. reviews, issues, frozen PR Heads, V48 and the single-workflow boundary remain acceptable.

Only after those conditions may PR #67 be marked Ready and merged with a Merge Commit.

`FORMAL_ACCEPTANCE_DOES_NOT_EQUAL_PRODUCTION_ENABLEMENT`

`PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED`

`APPROVAL_STATE_MUTATION_NOT_AUTHORIZED`
