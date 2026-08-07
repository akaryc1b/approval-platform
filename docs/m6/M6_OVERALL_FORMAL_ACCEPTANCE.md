# M6 Overall Formal Acceptance

Status: `M6_G_FORMAL_ACCEPTANCE_IMPLEMENTED_PENDING_EXACT_HEAD_PERMANENT_VALIDATION`

Acceptance decision: `M6_G_ACCEPTANCE_PASSED`

Production decision: `M6_PRODUCTION_READINESS_BLOCKED`

## 1. Scope and exact identity

This record formally accepts M6-A through M6-F exactly as implemented, audited and permanently validated through G2. It authorizes no new Connector, Event runtime, Template marketplace, Provider, executable Action, approval command, Production Reauthentication, Worker, Queue, Scheduler, automatic Retry, Rollback, Notification, Retention, Canary, deployment, rollout, traffic mutation or Production Promotion.

| Field | Exact value |
| --- | --- |
| Repository | `akaryc1b/approval-platform` |
| Exact base SHA | `0cf6572770953a46fe5b16d15ecdff78cf607855` |
| Exact accepted G2 Head | `007c973eeffdc07c94ee46602afb8827be2dc231` |
| Branch | `agent/m6-g-overall-formal-acceptance-and-production-readiness` |
| Pull Request | `#93` |
| Tracking Issue | `#82` |
| Parent Issue | `#62` |
| Parallel database blocker | `#91` / Draft PR `#92` |
| G2 commit count relative to base | `7` |
| Planned G3 final commit count relative to base | `10` |
| Planned G3 final changed-file count | `10` |
| Planned G3 final additions / deletions | `1804 / 0` |
| Migration | unique `V49`, unique `V50`; no `V51+` |
| Workflow | `.github/workflows/approval-platform-validation.yml` only |
| Auto-merge | disabled |
| PR state at G3 implementation gate | Open / Draft / mergeable / unmerged |
| Reviews / `REQUEST_CHANGES` / unresolved threads | none / none / none |

### Exact Final Head binding

A commit cannot embed its own SHA without cryptographic self-reference. The exact G3 Final Head is therefore bound after the single Batch C push by equality of:

1. PR #93 `head_sha`;
2. the natural G3 `pull_request` Workflow `head_sha`;
3. all nine Job run identities;
4. all four Artifact `workflow_run.head_sha` values;
5. the compare result against unchanged `main`.

The exact SHA, Run, Jobs, Artifacts, reconstructed counts and final review state must be written to PR #93 and Issue #82 metadata without another code commit. Any later commit invalidates G3 and requires a new complete permanent Run.

Marker: `G3_FINAL_RUN_EXTERNAL_BINDING_REQUIRED`

## 2. Workstream and correction inventory

| Workstream | PR | Final Head | Merge Commit | Issue |
| --- | ---: | --- | --- | ---: |
| M6-A Connector Foundation | `#67` | `66c8dc456a9c82c96ef0454c221a9b0e0c332e17` | `ebe7cb1ef92cb835810146f3120bd23ea94c586a` | `#63` |
| M6-B SDK and Event Ecosystem | `#68` | `4dad7c7fd3d4985919909d2155f8f899bf1bd8d6` | `eebfad58628f12cb684320b098ae70d81dbc88c9` | `#64` |
| M6-C Template and Component Ecosystem | `#69` | `498dc56e4695944a056625b56f4438a856d616e8` | `83a2a1d8163465864d19d0b4c7c52504380d63e1` | `#65` |
| M6-D AI Foundation | `#70` | `8a62d3c8037ad5720e30b6918153750dd591c6e5` | `21c086e57bc5814d8083076550d9fda71adabb4a` | `#66` |
| M6-E Governed AI Approval Assistance | `#83` | `e5c5eb6fef3c715f37b3ae3664eb371f2e96f2ca` | `492a428627d3be707d5723350506302ca04841b0` | `#80` |
| M6-F Controlled Automation and AI Governance | `#88` | `74249ce30dabc4ede3e75c16b28b554df3c79a51` | `497109620f080c3590cee1f3f42ddbd58a5ab025` | `#81` |

Correction PRs `#74`, `#75`, `#77`, `#89` and `#90` remain Merged / Closed. M6-E corrections were append-only inside PR #83. Historical PRs are immutable evidence and receive no new commits from M6-G.

## 3. M6-A Connector acceptance

Accepted:

- server-owned credential references and material boundary;
- bounded Token acquisition, refresh, rotation and single-flight behavior;
- exact tenant routing;
- governed synchronous read-only invocation;
- default-disabled production wiring;
- bounded redacted diagnostics;
- fault, timeout, security and concurrency acceptance.

No direct Connector command, approval-state mutation, cross-tenant credential access, Secret response/persistence, unsafe fallback or automatic Retry exists. M6-A B01–B20 operated-production gates remain blocked.

## 4. M6-B SDK and Event acceptance

Accepted:

- versioned Java and TypeScript SDK/Event contracts;
- canonical JSON and signature input;
- timestamp, nonce, replay and idempotency rules;
- collision-free replay identity;
- strict calendar-date validation;
- compatibility manifests, minimum-client rules and deprecation windows;
- deterministic bounded transport and audit contracts.

Durable production subscription, event store, Outbox delivery, broker, Worker, listener, scheduler, automatic recovery and operated delivery audit remain absent. Event cannot directly call AI or an application command.

## 5. M6-C Template and Component acceptance

Accepted:

- deterministic package, Template, Form, UI Schema and component identity;
- exact version/hash binding;
- raw-size, depth, count, Unicode, duplicate-key and unknown-field limits;
- side-effect-free preview;
- exactly one tenant-local editable `DRAFT`;
- data-only component descriptors and read-only fallback.

Scripts, arbitrary HTML/expression execution, remote modules, dynamic loading, direct publish/deploy/activate, process commands and migration authority are prohibited.

## 6. M6-D AI Foundation acceptance

Accepted:

- Provider SPI isolation;
- Provider/model/Prompt metadata/knowledge metadata/policy/output-schema version traceability;
- server-owned tenant, operator, permission and resource evidence;
- field permission, masking and minimization before invocation;
- timeout, cancellation, Circuit, Rate, Cost and Kill-Switch evidence;
- fail-closed schema/transport behavior;
- test-only deterministic Providers.

Provider-to-command, AI-to-Flowable, AI-to-migration, permission mutation, Provider mutation, Secret mutation and traffic mutation are prohibited. Raw customer Prompt/input, raw Provider response and Secret Material are not accepted durable audit content.

## 7. M6-E Governed AI Approval Assistance acceptance

Accepted:

- every successful result remains `ADVISORY`, `UNVERIFIED_ADVISORY` and `needsHumanReview=true`;
- availability GET is zero-call and zero-write;
- generation POST is explicit-user-action only;
- at most one Provider attempt;
- exact Release, Form Package, Form Schema and UI Schema provenance;
- pre-dispatch and post-Provider task revalidation;
- stale or missing task after Provider causes zero durable evidence write;
- post-dispatch UNKNOWN causes no second Provider call;
- V49 tenant-scoped hash/provenance-only evidence and append-only events;
- explicit Web/Mobile semantic parity.

Customer production Provider authorization, operated retention, actual billing and durable cost history remain blocked.

## 8. M6-F Controlled Automation and Governance acceptance

Accepted:

- typed bounded `NON_EXECUTABLE_PROPOSAL`;
- fresh tenant/operator/permission/resource/state/version/policy/whitelist/separation-of-duties evaluation;
- explicit evidence-bound `NON_EXECUTABLE_CONFIRMATION`;
- Production Reauthentication honestly `UNAVAILABLE` and fail-closed;
- V50 hash-only lineage, idempotency, row-lock CAS, replay, cancellation, PARTIAL and UNKNOWN;
- PostgreSQL nearest-microsecond rounding where remainder `<500ns` rounds down and `>=500ns` rounds up, including second carry;
- exactly six tenant-scoped GET-only `no-store` governance views;
- one shared Runtime, Circuit, RateLimiter and Usage Ledger;
- read-only Incident and Rollback review.

Permanent blockers:

- Action Whitelist `EMPTY_PENDING_EXISTING_COMMAND_AUDIT`;
- Action count `0`;
- `P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND`;
- no qualifying executable Application Command;
- no automatic Retry, Rollback, Notification or Retention.

## 9. Permanent authority chain

The permanent authority chain is:

`AI advisory -> typed non-executable proposal -> fresh server policy/precondition evaluation -> fresh authorization preview -> explicit human confirmation -> existing application command service -> immutable audited result`

The repository currently stops before the command-service step.

The permanently prohibited shortcut is:

`Provider -> direct command`

`AI_IS_NOT_AN_OPERATOR`

AI cannot manufacture tenant, operator, permission, authorization, audit, worker, lease, database-vendor, engine or command authority.

## 10. Tenant, Secret, Event and Template integrity

Tenant/operator/permission/audit/worker/credential identities are server-owned. Browser, Mobile and public SDK payloads cannot manufacture trusted authority. Connector Secret Material cannot enter AI evidence. AI model/Prompt metadata cannot enter Connector credential requests. SDK/Event payloads carry no usable Secret or command authority. Event replay is deterministic and cannot create production AI, Connector or Template side effects. Template/Form/UI provenance is exact and components cannot bypass field permissions or load remote executable code.

## 11. Data minimization and durable evidence

Masking, field permissions and minimization occur before Provider dispatch. V49/V50 store bounded hashes, versions, classifications, counters, state and append-only lineage only. They store no raw Prompt, raw input, raw Provider output, Secret or command payload. Durable evidence acceptance passes. Operated retention execution does not.

## 12. Persistence, clean install and upgrade

The composed PostgreSQL Flyway path contains Java V38 and unique SQL V49/V50 with no V51+. Clean and historical PostgreSQL paths reach V50 without execution side effects. Real PostgreSQL tests cover constraints, foreign keys, deferred invariants, row locks, CAS, replay, concurrency, timestamps and nearest-microsecond rounding. H2-only evidence is not used for acceptance.

## 13. Dual-database commitment re-read

Issue #91 and Draft PR #92 were created after the initial G0/G1 inventory and explicitly block #82 and #62 until PostgreSQL 16 and MySQL 8.4 receive production-equivalent migration, JDBC, Flowable, concurrency, fault, operations and permanent CI evidence.

Current `main` remains unchanged and PostgreSQL-only accepted. PR #92 is parallel, Open + Draft and excluded from the M6-G code diff. M6-G does not merge, copy or claim its unaccepted MySQL work.

Exact blocker markers:

`DUAL_DATABASE_COMMITMENT_RESTORED`

`MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED`

This is a `PARENT_CLOSURE_BLOCKER`, not a hidden M6-G implementation task.

## 14. Rollback and Incident Response

Accepted:

- deterministic fault classifications;
- durable UNKNOWN posture;
- manual rollback review;
- read-only Incident Readiness;
- retained fault, security, concurrency and incident rehearsals.

Blocked:

- operated rollback execution;
- automatic notification/response;
- operated retention execution;
- durable control timeline;
- customer on-call, change and release approval.

## 15. Operations and observability

Connector diagnostics, Template preview/DRAFT import and AI snapshot/change-plan/control-health/usage/history/incident-readiness are bounded, redacted and read-only. Governance is tenant-scoped, `no-store`, creates no Runtime Binding and invokes no Provider. Metrics and evidence use closed low-cardinality labels and leak no other-tenant or exact-global usage.

## 16. Web and Mobile

PC and Mobile share advisory status, require explicit generation, expose human-review, risk, UNKNOWN, stale and fail-closed posture, and provide no Provider, Policy, Secret, permission, deployment, traffic-mutation or executable automation controls. Neither client can manufacture trusted tenant, operator, permission or worker identity.

## 17. Fault, concurrency and PostgreSQL acceptance

Evidence covers timeout/cancellation/schema drift, post-dispatch UNKNOWN, Circuit OPEN/HALF_OPEN, tenant/global saturation, version drift, retention due, history zero-repair failure, Confirmation/CAS concurrency and lineage replay. Controlled-automation PostgreSQL concurrency remains `8/8`; nearest-microsecond regression remains `7/7`.

## 18. Workflow and repository acceptance

The repository has one automatic PR/main Workflow. Natural `pull_request` and `push -> main` evidence is authoritative. The Workflow has nine physical Jobs and four Artifact classes. JDBC selection remains deterministic across four non-overlapping shards. No broad skip, empty shard, temporary Workflow, patch payload, credential, direct-main change, auto-merge or meaningless rerun is accepted. `workflow_dispatch` may diagnose but cannot replace natural PR/main evidence.

## 19. Review and security status

PR #93 is Open + Draft and currently has no Review, `REQUEST_CHANGES` or unresolved Review Thread. Historical PR #68 thread metadata remains disclosed; its defects were corrected by PR #74.

Dedicated Code Scanning, Secret Scanning and Dependabot Security Alert inventory is not exposed through the current connector, and current dependency CVE applicability/reachability is not independently established. No unsupported zero-alert claim is made. These remain `PRODUCTION_READINESS_BLOCKER` items.

## 20. G0, G1 and G2 exact evidence

| Gate | Retained failed Run | Correction Head | Final successful Run | Node boundary |
| --- | --- | --- | --- | ---: |
| G0/G1 | `31150986907` / #1329 | `d5e6b181050a12b0601291f8a458da5ffa05f9e0` | `31151203020` / #1332 | `185/185` |
| G2 | `31152842432` / #1337 | `007c973eeffdc07c94ee46602afb8827be2dc231` | `31153142533` / #1338 | `218/218` |

G2 final Job IDs:

`92786836119`, `92786836133`, `92786836145`, `92786836150`, `92786836163`, `92786836171`, `92786836179`, `92786836202`, `92787231533`.

| Artifact | ID | Bytes | SHA-256 | Expires |
| --- | ---: | ---: | --- | --- |
| Maven | `8984120607` | `358416` | `fa063d4702178d475c53eafa54c68c7312705980fc8819370e41ae0956287cfa` | `2026-11-05T06:12:30Z` |
| Vben | `8984103025` | `18927` | `0119f521931ad2548a87380feb1194bfdac7303898bd00ddf3fb7a758405b845` | `2026-11-05T06:12:30Z` |
| Mobile | `8984087438` | `9803` | `38314f3d276b2b4706d63c35df010e9c8180b70a33254faac1bb82b4ca7e29e0` | `2026-11-05T06:12:30Z` |
| Hygiene | `8984069702` | `17133` | `851f5bcc507014ba529241e063c1a7ea05aec791e2f71b1d0dd07a79a26c0f4c` | `2026-11-05T06:12:30Z` |

G2 reconstruction:

- Maven Core `1463 / 0 failures / 0 errors / 0 skipped`;
- Persistence JDBC `325 / 0 / 0 / 0`;
- aggregate `1788 / 0 / 0 / 0`;
- AI SPI `12`;
- AI Core `204`;
- OpenAI `102`;
- application `233`;
- architecture `159`;
- server `266`;
- Node aggregate `218/218`;
- JDBC selected/unique `80/80`;
- Surefire reports `79`;
- expected abstract class without report `1`;
- duplicate selections `0`;
- non-abstract missing reports `0`;
- Vben tests/type-check/build succeeded;
- Mobile type-check, H5 and WeChat builds succeeded.

## 21. G3 permanent boundary and expected count

G3 adds `scripts/tests/m6-g-overall-formal-acceptance-boundary.test.mjs` with exactly `12` permanent assertions and imports it through the existing M6 aggregate. The expected successful G3 Node aggregate is `230/230`. The actual count must be reconstructed from the final Hygiene Artifact and written to PR #93 and Issue #82 metadata.

## 22. Retained failures and corrections

Batch A Run #1329 retained three over-specific new assertions. Batch B Run #1337 retained a truncated new test file. Both were independent Test Bug corrections. No product behavior changed. Every failed Run and Artifact remains retained. Neither failed Head was rerun. No test was skipped and no permission, authority or capability was widened.

## 23. Honest limitations and blocker matrix

Authoritative matrix:

`docs/m6/M6_G_PRODUCTION_READINESS_BLOCKER_MATRIX.md`

Limitations include default-disabled production Connector/Provider paths, contract-only Event delivery, no customer Provider rehearsal, no operated retention, no actual billing/durable cost, no durable Circuit timeline, empty Action Whitelist, skipped P5, unavailable Production Reauthentication, no qualifying executable command, no automatic Retry/Rollback/Notification/Retention, no Canary/deployment/traffic mutation, incomplete security inventory and incomplete MySQL 8.4 production compatibility.

## 24. G3 final Run external evidence contract

This immutable repository contract precedes its own validation. PR #93 and Issue #82 metadata must record, without another code commit:

- exact G3 Final Head;
- natural `pull_request` Run ID and Run Number;
- Event, Branch, Status and Conclusion;
- nine successful Job IDs;
- four Artifact IDs, bytes, SHA-256, expiry, Head and Branch;
- Maven/JDBC/Node/Web/Mobile reconstruction;
- final Review, Thread and main-drift re-read;
- final actual PR Run count.

Until then:

```text
READY_MERGE_ISSUE_CLOSURE_PROHIBITED
G3_FINAL_RUN_EXTERNAL_BINDING_REQUIRED
```

## 25. Ready, merge and post-main gates

Ready requires exact G3 Head, one natural successful PR Run, nine successful Jobs, four independently verified Artifacts, zero Correctable Defects, zero Review/Security blocker, behind `0`, unchanged `main`, auto-merge disabled and no duplicate queued/in-progress Run.

Merge requires a fresh re-read after Ready and ordinary Merge Commit with exact expected Head. Squash, rebase, auto-merge, force merge and direct-main push are prohibited.

After merge, Issue decisions wait for a natural `push -> main` Run and four new main Artifacts. PR Artifacts, dispatch, rerun, empty commit or main mutation cannot substitute.

## 26. Issue gates

Because Parent Closure Blockers remain:

- Issue #82 remains Open after M6-G merge;
- parent Issue #62 remains Open;
- Issue #91 remains Open;
- Issues #13 and #14 remain Open;
- no automatic follow-up Issue is created;
- M7 does not start.

## 27. CI usage

| Item | Count |
| --- | ---: |
| Planned normal PR Runs | `3` |
| Actual PR Runs through G2 | `4` |
| Correction Runs through G2 | `2` |
| Same-Head reruns | `0` |
| Empty commits | `0` |
| Planned G3 final PR Run | `1` |
| Post-main Runs so far | `0` |
| Deployment / Production Promotion | `0 / 0` |

## 28. Formal decisions

```text
M6_G_ACCEPTANCE_PASSED
M6_PRODUCTION_READINESS_BLOCKED
M6_G_FORMAL_ACCEPTANCE_COMPLETE
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
ISSUE_91_REMAINS_OPEN
ISSUE_13_REMAINS_OPEN
ISSUE_14_REMAINS_OPEN
NO_NEW_M6_PRODUCT_CAPABILITY
NO_PRODUCTION_PROMOTION
AI_IS_NOT_AN_OPERATOR
```
