# M6-A Formal Acceptance

## Decision

M6-A is formally accepted at the implemented, default-disabled and non-production boundary recorded in this document.

- status: `M6_A_FORMALLY_ACCEPTED_PENDING_FINAL_EVIDENCE`
- production connector execution: `PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED`
- approval-state mutation: `APPROVAL_STATE_MUTATION_NOT_AUTHORIZED`
- production enablement: `NOT_GRANTED`
- production customer configuration: `NOT_PRESENT`

Formal Acceptance confirms that the reviewed M6-A scope is complete and eligible for the final permanent validation and conditional merge-readiness process. It is not a reusable runtime authority token and is not permission to enable a connector in production.

Tracking:

- parent milestone: Issue #62;
- workstream: Issue #63;
- pull request: PR #67;
- branch: `agent/m6-a-connector-foundation`;
- target: `main`.

## Audited baseline before this record

The repository was re-read from GitHub before this document was created:

- `main`: `1d425581d0548c6b15487d58ce47774b29f1073a`;
- accepted P9 documented Head: `4ef20330a48288520a66956a7e26072f5d8d39bc`;
- relation to `main`: ahead `94`, behind `0`;
- PR #67: Open, Draft, unmerged and mergeable;
- requested reviewers, submitted reviews and unresolved review threads: none;
- repository auto-merge: disabled;
- Issues #62, #63, #13 and #14: Open;
- frozen PR Heads: #68 `330dbdd035e436459ffdedf0d2b0c8e07dac7e6c`, #69 `72acb3ba18602c09c28bfe08b58f8b91e6efe6e4`, #70 `9d588215e869c8f1332c0bc1a2809fbd235c2efa`;
- Flyway: continuous through `V48`, with no `V49` or higher migration;
- only automatic `pull_request` / `push` workflow: `.github/workflows/approval-platform-validation.yml`.

Representative M5 files shown in the large PR history were compared by Git blob between `main` and the M6-A branch, including the V48 migration, the migration-plan domain model and the mobile migration operations API. Their blob identities were exact matches. They are inherited mainline history, not M6-A semantic modifications.

## Formally accepted scope

The accepted M6-A scope consists of the following governed foundations and safe slices:

1. provider-neutral Connector Foundation and closed capability contracts;
2. Production Integration Ownership Gate and explicit ownership evidence;
3. DingTalk captured-transport conformance;
4. server-owned credential binding and reference-only credential authority;
5. production transport boundary with fixed HTTPS policy and no production authorization;
6. exact server-owned tenant routing;
7. backend-neutral production Secret Material abstraction and bounded lease lifecycle;
8. DingTalk Token acquisition, refresh, rotation, cache and zeroization lifecycle;
9. governed synchronous read-only invocation with exact route and Token revalidation;
10. read-only, process-local connector operations diagnostics and low-cardinality observation;
11. fault, security and concurrency acceptance;
12. deterministic non-production release rehearsal, operations runbook and production blocker catalog.

The implementation may construct and exercise these capabilities only through the closed contracts, default-disabled Spring gates, deterministic fixtures and explicit server-owned dependencies established in the accepted evidence.

## Implemented but default disabled

The following application gates remain literal default disabled:

- production Secret Material source wiring;
- DingTalk Token lifecycle wiring;
- tenant connector routing;
- governed connector invocation;
- connector operations diagnostics.

Default startup does not create a concrete production Secret Backend, real DingTalk Token endpoint, production dispatch implementation, customer route, background executor or durable diagnostics backend. Enabling a gate without every required server-owned dependency fails closed.

## Explicitly not authorized or not implemented

Formal Acceptance does not authorize or provide:

- production connector execution;
- approval-state mutation or approve, reject, return, transfer, withdraw, terminate or migrate commands;
- background connector execution, Worker, Queue, Scheduler, listener or polling loop;
- durable connector diagnostics, durable invocation audit or connector persistence;
- automatic retry, replay, recovery or reconciliation;
- production customer tenant configuration;
- real DingTalk AppKey, AppSecret, Access Token, customer endpoint or credential provisioning;
- public connector management mutation API;
- POST, PUT, PATCH or DELETE connector operations endpoint;
- Provider fallback, weighted routing, health routing or automatic failover;
- a concrete Vault, KMS, Kubernetes, cloud, file, environment, database or HTTP Secret adapter;
- production egress ownership, operated Kill Switch, on-call ownership or disaster-recovery authority.

## Production blocker catalog

The authoritative blocker catalog remains `docs/m6/M6_A_PRODUCTION_BLOCKER_CATALOG.md`. All twenty entries B01-B20 remain `BLOCKED`, including real Secret Backend approval, customer endpoint approval, production egress, durable audit ownership, on-call and incident ownership, capacity evidence, retention and security approval, production Kill Switch, tenant allowlist, Token rotation operations, Provider policy, legal/privacy approval, release sign-off, observability backend, disaster recovery and approval-mutation separation.

A successful CI Run, synthetic fixture, process-local diagnostic store, code-level Kill Switch contract or hash-only evidence cannot substitute for any blocker decision.

## Permanent validation lineage

| Gate | Documented Head | Final natural Run | Maven aggregate | Focused result | Status |
| --- | --- | ---: | --- | --- | --- |
| P5 Secret Material Source | `3ba5e2eecee61b3aab8bc1fd89ddab4a241a36e1` | `30372656382` / #878 | `967 / 0 / 0 / 0` | `21 / 0 / 0 / 0` | `BACKEND_NEUTRAL_MATERIAL_SOURCE_PERMANENTLY_VALIDATED` |
| P6 Token Lifecycle | `3a98467084b7dae159e586ae8f870e660464f6a4` | `30414994006` / #880 | `993 / 0 / 0 / 0` | `26 / 0 / 0 / 0` | `DINGTALK_TOKEN_LIFECYCLE_IMPLEMENTED_DEFAULT_DISABLED` |
| P7 Read-only Invocation | `bcd375c3e1a88f0b9e0b48d2254dc6df2dcaa49c` | `30426801370` / #882 | `1032 / 0 / 0 / 0` | `39 / 0 / 0 / 0` | `GOVERNED_READ_ONLY_INVOCATION_IMPLEMENTED_DEFAULT_DISABLED` |
| P8 Operations Diagnostics | `2a310553d4de552c064906ee1f57efb742e18162` | `30445509949` / #887 | `1080 / 0 / 0 / 0` | `48 / 0 / 0 / 0` | `CONNECTOR_OPERATIONS_DIAGNOSTICS_IMPLEMENTED_DEFAULT_DISABLED` |
| P9 Fault/Security/Rehearsal | `4ef20330a48288520a66956a7e26072f5d8d39bc` | `30449344853` / #890 | `1132 / 0 / 0 / 0` | `52 / 0 / 0 / 0` | `PERMANENTLY_VALIDATED` |

The final P9 Run completed all four permanent jobs successfully and produced four unexpired artifacts. Each ZIP was downloaded and its local SHA-256 exactly matched GitHub:

| Artifact | ID | SHA-256 |
| --- | ---: | --- |
| Maven | `8723037813` | `635d5d3b5d0ab202a59892ab920623067f7120026a91a5cea0dd0534674c5585` |
| Vben | `8722853930` | `ac6db2f291bb5c1245ae608211e5691e95e1e7f8e98d0813d9b770fead03d17f` |
| Mobile | `8722833894` | `51ab4ae0d69b7d6d90e4c463a6e8ac55618328ff94af90dd471233e8645493f9` |
| Hygiene | `8722812447` | `73fcccadca1660b96e69d29791c6fae58197d3a180d717e6cd5228f71d90157a` |

## Retained failure and bounded-fix lineage

No failed Run was cancelled, deleted, hidden or used as a target for a direct rerun.

- R0 Run `30356987426` / #873 retained the pre-M5 boundary mismatch; a minimal architecture-test rebaseline accepted mainline V33-V48 while continuing to reject V49 and M6-A ownership of M5 semantics.
- P5 Run `30370207896` / #876 retained three source-shape false positives; the bounded fix changed helper shape only and did not weaken Secret lifecycle or approval-command boundaries.
- P8 Run `30443390978` / #885 retained a Token single-flight handoff defect; the bounded correction rechecked an installed valid cache entry and added no retry, worker, persistence or fallback.
- P9 Run `30447754692` / #888 retained the Secret scanner finding a complete PEM header marker in test source; the minimal fix assembled the same runtime marker from safe fragments and did not reduce scan strength.

Every successful replacement was produced by a new commit and a new natural workflow Run.

## Repository and parallel-workstream boundaries

- M6-A owns no Flyway migration beyond the accepted mainline V48 state and adds no V49;
- `.github/workflows/approval-platform-validation.yml` remains the single automatic PR/main validation workflow;
- PR #68, PR #69 and PR #70 remain independent Open + Draft workstreams and their Heads must not be changed by M6-A acceptance;
- Issues #62, #13 and #14 remain Open;
- Issue #63 may close only after Merge Commit completion and successful main post-merge permanent validation;
- M5 migration semantics remain inherited unchanged from `main`.

## Final evidence and merge-readiness gate

The commit containing this document must complete a new natural full workflow. Its four artifacts must be downloaded and matched exactly. A separate `M6_A_FINAL_ACCEPTANCE_EVIDENCE.md` commit must then complete another natural full workflow with another four exact artifact matches.

Only that documented Head may be considered for Ready and Merge Commit, and only while `main` remains unchanged, behind remains zero, mergeability and checks remain successful, reviews contain no blocker, auto-merge remains disabled, V48 and the single-workflow boundary remain intact, and the frozen PR Heads remain unchanged.

`FORMAL_ACCEPTANCE_DOES_NOT_EQUAL_PRODUCTION_ENABLEMENT`

`PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED`

`APPROVAL_STATE_MUTATION_NOT_AUTHORIZED`
