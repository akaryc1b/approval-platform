# M6-G G0 — Exact Current-main Rebaseline and Scope Freeze

Status: `M6_G_SCOPE_FROZEN`

Evidence date: `2026-08-07`

Repository: `akaryc1b/approval-platform`

Branch: `agent/m6-g-overall-formal-acceptance-and-production-readiness`

Target PR: `[M6-G] M6 Overall Formal Acceptance and Production Readiness`

Tracking Issue: `#82`

Parent Issue: `#62`

## 1. Purpose and non-mutation record

G0 freezes the exact repository, workflow, artifact, review, migration, configuration and issue baseline used by G1–G5. It adds no product capability and grants no runtime or production authority.

Before this record was prepared, GitHub was re-read rather than trusting the handoff. The M6-G branch was then created directly from the exact current `main`. At the G0 freeze:

- no M6-G PR exists;
- the branch is identical to `main`, ahead `0`, behind `0`, commits `0`;
- no code or document has been pushed to the branch;
- no Workflow, rerun or `workflow_dispatch` was triggered;
- no empty commit was created;
- `main` was not modified;
- no Ready, merge, deployment, rollout, traffic mutation or Production Promotion occurred.

```text
M6_G_SCOPE_FROZEN
NO_NEW_M6_PRODUCT_CAPABILITY
NO_PRODUCTION_PROMOTION
CI_BATCHING_ENABLED
```

## 2. Exact repository baseline

| Field | Exact value |
| --- | --- |
| Default branch | `main` |
| Current `main` | `0cf6572770953a46fe5b16d15ecdff78cf607855` |
| Handoff expected `main` | `0cf6572770953a46fe5b16d15ecdff78cf607855` |
| Main drift | `NONE` |
| Current main source | ordinary Merge Commit for PR `#90` |
| Commit after PR #90 | `NONE` |
| M6-G branch Head | `0cf6572770953a46fe5b16d15ecdff78cf607855` |
| Branch relation | `identical`, ahead `0`, behind `0` |
| M6-G PR | absent |
| Repository auto-merge | disabled |

No dependency, Connector, Event, Template, AI, Persistence, Web, Mobile or Workflow change was merged after the M6-F rounding correction. The accepted final M6-F main Run therefore remains the exact G0 source evidence.

## 3. M6-F correction lineage

| Stage | PR | Final Head | Ordinary Merge Commit |
| --- | ---: | --- | --- |
| M6-F Formal Acceptance | `#88` | `74249ce30dabc4ede3e75c16b28b554df3c79a51` | `497109620f080c3590cee1f3f42ddbd58a5ab025` |
| PostgreSQL instant precision correction | `#89` | `d37d529937bf9cdf761a819487ac62df99134a13` | `10a9d1ad415c9abd29aab92f54f1149420daf60d` |
| PostgreSQL nearest-microsecond rounding correction | `#90` | `52e598d3859c9c76ff807e842e046f3d6dbcdff1` | `0cf6572770953a46fe5b16d15ecdff78cf607855` |

Run `31096465533` belongs to the superseded precision-only Merge Commit. It is historical evidence and cannot replace the final rounding baseline.

## 4. Final natural main Workflow

| Field | Exact value |
| --- | --- |
| Workflow | `Approval Platform Validation` |
| Run ID | `31106899863` |
| Event | `push` |
| Branch | `main` |
| Head | `0cf6572770953a46fe5b16d15ecdff78cf607855` |
| Status | `completed` |
| Conclusion | `success` |

### 4.1 Nine physical Jobs

| Job | ID | Result |
| --- | ---: | --- |
| Vben TypeScript / production build | `92634299993` | `success` |
| UniApp TypeScript / H5 / WeChat | `92634300047` | `success` |
| Persistence JDBC / shard 2 | `92634300057` | `success` |
| Java 21 / Maven core | `92634300070` | `success` |
| Persistence JDBC / shard 0 | `92634300079` | `success` |
| Persistence JDBC / shard 1 | `92634300105` | `success` |
| Repository hygiene | `92634300195` | `success` |
| Persistence JDBC / shard 3 | `92634300409` | `success` |
| Java 21 / Maven / PostgreSQL | `92634995283` | `success` |

No duplicate queued or in-progress Run existed for this exact Head during the re-read.

## 5. Final main Artifacts

Each ZIP was independently downloaded. Local byte counts and SHA-256 values matched GitHub metadata.

| Kind | Artifact ID | Bytes | SHA-256 | Head / Branch | Expires |
| --- | ---: | ---: | --- | --- | --- |
| Maven | `8970028695` | `361466` | `3a88eecfa10d6e921e12553275611c2acdc31c82598e20ce011d3785e44ad68c` | `0cf657...` / `main` | `2026-11-04T13:38:45Z` |
| Vben | `8969997953` | `18923` | `397507add84f62ea236a3969713fa8f853c1a21a67979843726d6687c904b376` | `0cf657...` / `main` | `2026-11-04T13:38:45Z` |
| Mobile | `8969969278` | `9810` | `3d6dbf96f3034ebd96b7eb97199c8a4c5a2b9a7c4e49f7102f307845926a391e` | `0cf657...` / `main` | `2026-11-04T13:38:45Z` |
| Hygiene | `8969944075` | `15707` | `755732d32bfd50ea9a89e77541f75da87474ecee8d632345f5663cbd3fd3faa2` | `0cf657...` / `main` | `2026-11-04T13:38:45Z` |

Independent reconstruction:

- Maven Core `1463 / 0 failures / 0 errors / 0 skipped`;
- Persistence JDBC `325 / 0 / 0 / 0`;
- aggregate `1788 / 0 / 0 / 0`;
- AI SPI `12`, AI Core `204`, OpenAI `102`;
- application `233`, architecture `159`, server `266`;
- PostgreSQL nearest-microsecond regression `7/7`;
- controlled-automation PostgreSQL concurrency `8/8`;
- permanent M6 boundary `176/176`;
- JDBC selected/unique `80/80`, reports `79`, expected abstract `1`, duplicates `0`, non-abstract missing `0`;
- Vben client `10/10`, renderer `3/3`, designer `2/2`;
- Web and Mobile type/build gates succeeded.

## 6. Formal M6 PR inventory

| Workstream | PR | Final Head | Merge Commit | Issue |
| --- | ---: | --- | --- | ---: |
| M6-A Connector Foundation | `#67` | `66c8dc456a9c82c96ef0454c221a9b0e0c332e17` | `ebe7cb1ef92cb835810146f3120bd23ea94c586a` | `#63` |
| M6-B SDK and Event | `#68` | `4dad7c7fd3d4985919909d2155f8f899bf1bd8d6` | `eebfad58628f12cb684320b098ae70d81dbc88c9` | `#64` |
| M6-C Template and Component | `#69` | `498dc56e4695944a056625b56f4438a856d616e8` | `83a2a1d8163465864d19d0b4c7c52504380d63e1` | `#65` |
| M6-D AI Foundation | `#70` | `8a62d3c8037ad5720e30b6918153750dd591c6e5` | `21c086e57bc5814d8083076550d9fda71adabb4a` | `#66` |
| M6-E AI Approval Assistance | `#83` | `e5c5eb6fef3c715f37b3ae3664eb371f2e96f2ca` | `492a428627d3be707d5723350506302ca04841b0` | `#80` |
| M6-F Controlled Automation/Governance | `#88` | `74249ce30dabc4ede3e75c16b28b554df3c79a51` | `497109620f080c3590cee1f3f42ddbd58a5ab025` | `#81` |

All are Merged / Closed and are immutable evidence.

## 7. Correction PR inventory

| Correction | PR | Final Head | Merge Commit |
| --- | ---: | --- | --- |
| M6-B post-merge corrections | `#74` | `b29ebf522df964a13e85504d429fbb370b4b5189` | `5559fb54fd36208d57d3a3f3728755f022cc4b80` |
| M6-C post-merge corrections | `#75` | `dc57e1b6e802423f8e88aa9c0811c98dfda2339f` | `735e41526371ea481b31af377e3410d085160f7e` |
| M6-D post-merge corrections | `#77` | `792c4db1ec77e57c5afa6e315d4102fd424e04b0` | `cefb2bf35701ca8af39ce6607e1b25c58ffb9a4e` |
| M6-F precision correction | `#89` | `d37d529937bf9cdf761a819487ac62df99134a13` | `10a9d1ad415c9abd29aab92f54f1149420daf60d` |
| M6-F rounding correction | `#90` | `52e598d3859c9c76ff807e842e046f3d6dbcdff1` | `0cf6572770953a46fe5b16d15ecdff78cf607855` |

M6-E corrections were append-only in PR #83. Failed Runs remain retained.

## 8. Issue state

| Issue | State |
| --- | --- |
| `#63`, `#64`, `#65`, `#66`, `#80`, `#81` | Closed / completed |
| `#82` | Open |
| `#62` | Open |
| `#13` | Open |
| `#14` | Open |

Issue #81’s prerequisite for beginning M6-G is satisfied. No issue closure is authorized by G0.

## 9. Review state

No current `REQUEST_CHANGES` or uncorrected actionable M6 code finding was found. PR #69/#70/#77/#83/#88/#89 threads inspected in the accepted lineage are resolved. PR #67/#74/#75/#90 have no review threads.

PR #68 retains three unresolved historical thread records. Their nonce-boundary, delimiter-collision and invalid-date defects were corrected by PR #74 and validated post-main. G0 preserves both facts: current code is corrected, but historical thread metadata is not falsely reported as resolved.

## 10. Dependency and security evidence

Ten open Dependabot maintenance PRs exist outside `main`: `#1`, `#2`, `#3`, `#4`, `#5`, `#6`, `#7`, `#72`, `#73`, `#84`. They propose Actions, Maven-plugin, Spring Boot and TypeScript upgrades. None is part of the G0 baseline and none may be merged as unrelated M6-G scope.

Release notes for some updates mention security fixes, but that does not establish CVE applicability or reachability in the current dependency graph. The connector available to this audit does not expose dedicated Code Scanning, Secret Scanning or Dependabot Security Alert inventories. G0 therefore makes no unsupported zero-alert claim. G1 must classify this evidence-access limitation explicitly.

## 11. Workflow and repository baseline

The only automatic PR/main workflow remains:

`.github/workflows/approval-platform-validation.yml`

It uses normal `pull_request` and `push` triggers plus a manual dispatch entry that is not accepted as a replacement for natural PR/main evidence. No second automatic workflow or temporary M6-G workflow exists. Permanent hygiene rejects temporary patch payloads, generated artifacts and stale helper files.

## 12. Migration baseline

The governed migration upper bound is unique `V50`:

- V38 is a Java Flyway migration;
- V49 is unique in `db/migration`;
- V50 is unique in `m6f/db/migration`;
- no V51 or higher migration exists;
- clean-install and historical upgrade tests cover the composed migration locations.

M6-G may not edit historical migrations or add a new migration unless a proven correctable defect requires it.

## 13. Dependency baseline

- Java `21`;
- Spring Boot `4.0.2`;
- Flowable `8.0.0`;
- ArchUnit `1.4.2`;
- Testcontainers `2.0.5`;
- Node `^22.18.0 || ^24.0.0`;
- pnpm `10.33.4`;
- TypeScript `5.9.3`.

## 14. Configuration and runtime defaults

Migration execution, worker, orchestration, aggregation and automatic reconciliation are default-disabled. The migration kill switch is configured off by default.

Connector defaults:

- production Secret Material disabled;
- backend selection `BLOCKED_PENDING_BACKEND_SELECTION`;
- DingTalk token lifecycle disabled;
- governed invocation disabled;
- operations diagnostics disabled;
- tenant routing disabled;
- generic connector disabled overall.

AI defaults:

- OpenAI production Runtime exists as code but `APPROVAL_AI_OPENAI_ENABLED=false` by default;
- runtime creation requires complete server-owned Secret/version, policy-window, Kill Switch, rate, budget-envelope and Circuit settings;
- invalid or expired configuration fails closed;
- CI performs no paid/customer Provider call.

Controlled Automation defaults:

- Action Whitelist `EMPTY_PENDING_EXISTING_COMMAND_AUDIT`;
- Action count `0`;
- P5 `P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND`;
- Production Reauthentication unavailable/fail-closed;
- no application command binding or executable Action.

## 15. Operations, Web and Mobile baseline

M6-F exposes exactly six tenant-scoped, GET-only, `no-store`, non-mutating governance views: inventory snapshot, change plan, control health, usage, durable history and incident readiness. They create no Runtime Binding, read no raw Secret and call no Provider.

M6-E Web and Mobile surfaces require an explicit user action to request assistance, display `ADVISORY`, `UNVERIFIED_ADVISORY` and `needsHumanReview`, and expose no approval or automation command authority. Controlled-automation confirmation controls remain disabled/unavailable.

## 16. Honest known limitations and provisional closure blockers

The following remain absent or incomplete and cannot be implemented secretly in M6-G:

- M6-A B01–B20 production connector approvals and operated controls;
- durable production event subscription/delivery runtime;
- production Template marketplace, remote component loading or activation;
- customer production Provider authorization/rehearsal and operated egress/on-call gate;
- operated retention executor;
- non-empty qualifying Action Whitelist;
- P5 execution;
- Production Reauthentication;
- executable application command binding;
- automatic Retry, Rollback, Notification or Retention;
- actual Provider billing and durable cost history;
- durable Circuit/Control Health time-series;
- Canary, rollout, deployment or traffic mutation.

These are not permission to add capability. G1/G2 must classify them against Issue #82.

## 17. M6-G allowed scope

M6-G may add exact rebaseline/audit/acceptance documents, permanent boundary tests, end-to-end tests for existing behavior, and minimal independent corrections for proven defects in promised behavior. It may update PR/Issue evidence metadata after validation.

## 18. M6-G prohibited scope

M6-G may not add a Connector, Event delivery product, Template marketplace, remote component loader, Provider, model/Prompt/Policy/Secret mutation, executable command, Action Whitelist entry, Production Reauthentication, Worker/Queue/Scheduler/Listener/Polling, automatic Retry/Rollback/Notification/Retention, Flowable internal-table access, arbitrary HTTP/SQL/Shell/Script execution, Canary, deployment, rollout, traffic mutation or Production Promotion.

## 19. Acceptance and Production Readiness rules

M6-G acceptance may pass with Production Readiness blocked only when the audit/tests/artifacts are real and complete, documents are factual, no correctable Product/Security defect or review blocker remains, and the limitations are not disguised.

The final acceptance token is exactly one of:

```text
M6_G_ACCEPTANCE_PASSED
M6_G_ACCEPTANCE_BLOCKED
```

The separate Production Readiness token is exactly one of:

```text
M6_PRODUCTION_READY
M6_PRODUCTION_READINESS_BLOCKED
```

Issue #82 and parent #62 remain open while any Issue #82 parent-closure blocker remains. Issues #13 and #14 remain open in every outcome.

## 20. Low-frequency CI plan

Gate order remains `G0 -> G1 -> G2 -> G3 -> G4 -> G5`.

- Batch A: independent G0 and G1 commits, one branch publication and one natural PR Run;
- Batch B: G2, one natural PR Run;
- Batch C: G3 Formal Acceptance, one final natural PR Run;
- G4: no code push, Ready and ordinary Merge Commit only;
- G5: one natural `push -> main` Run.

No empty commit, formatting-only push, repeated same-Head run, assertion weakening, test skip, second workflow, deployment or Production Promotion is authorized.

## 21. G0 exit record

G0 is complete. It does not decide G1, the final M6-G acceptance result or Production Readiness.

```text
M6_G_SCOPE_FROZEN
NO_NEW_M6_PRODUCT_CAPABILITY
NO_PRODUCTION_PROMOTION
CI_BATCHING_ENABLED
G1_NOT_YET_DECIDED
READY_MERGE_ISSUE_CLOSURE_PROHIBITED
AI_IS_NOT_AN_OPERATOR
```
