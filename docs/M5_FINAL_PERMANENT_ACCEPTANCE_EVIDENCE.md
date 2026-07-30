# M5 Final Permanent Acceptance Evidence

## 1. Governance decision

M5 — Governed Process Instance Migration and Release Operations is:

```text
ACCEPTED / FINAL_PERMANENTLY_VALIDATED / READY_FOR_CONDITIONAL_MERGE
```

Accepted scope:

- M5-A: `ACCEPTED / SUPPORTED_WITH_LIMITATIONS`;
- M5-B and M5-C: `ACCEPTED / PERMANENTLY_VALIDATED`;
- M5-D1 through M5-D8: `COMPLETE / PERMANENTLY_VALIDATED`;
- M5-E1 and M5-E2: `COMPLETE / PERMANENTLY_VALIDATED`;
- M5-F1 and M5-F2: `COMPLETE / PERMANENTLY_VALIDATED`;
- M5-G1 and M5-G2: `COMPLETE / PERMANENTLY_VALIDATED`.

This acceptance authorizes only the repository merge described by the final pre-merge checklist. It does **not** authorize a real production migration, production Canary, migration worker, orchestration run, aggregation run, reconciliation run or feature-flag enablement.

Production migration execution remains:

```text
NOT_AUTHORIZED
```

## 2. Final validated functional and regression Head

- Head: `9c567a30e7430ee43d7b4ad418a70923edf5c360`
- Branch: `agent/m5-governed-process-instance-migration`
- Workflow: `Approval Platform Validation`
- Run ID: `30338254460`
- Run number: `#864`
- Run URL: `https://github.com/akaryc1b/approval-platform/actions/runs/30338254460`
- Conclusion: `success`

All four permanent jobs succeeded:

- Repository hygiene: success;
- Java 21 / Maven / PostgreSQL: success;
- Vben TypeScript / production build: success;
- UniApp TypeScript / H5 / WeChat: success.

This document is added after the successful G2 regression Head. The subsequent documentation-only Head and its permanent Run are recorded in PR #58 and the final merge report because a committed document cannot self-record a commit and Run that do not yet exist.

## 3. Delivered M5 protocol

### 3.1 Governed execution protocol

M5-D permanently provides:

- exact admission of an authorized immutable migration plan;
- tenant-scoped immutable intent and selected-instance lineage;
- bounded one-instance Attempts;
- ownership, lease and command-fence evidence;
- exactly one public Flowable migration call per governed dispatch;
- the engine call outside every platform database transaction;
- bounded public readback and exact target verification;
- exact-target runtime-binding CAS;
- immutable instance completion or binding-conflict evidence;
- durable `UNKNOWN` / `AMBIGUOUS_UNKNOWN`;
- independent one-shot read-only reconciliation;
- deterministic `CANONICAL_FIRST_V1` Canary;
- bounded one-shot orchestration with Kill Switch recheck before every dispatch;
- deterministic plan-level aggregation from immutable D1–D7 evidence;
- append-only aggregate, event and plan-completion evidence.

### 3.2 Read-only Operations diagnostics

M5-E exposes seven tenant-scoped GET handlers through management and Mobile prefixes:

- tenant summary;
- bounded plan list;
- exact plan detail;
- bounded selected-instance list;
- plan diagnostics;
- filtered/paginated diagnostic-instance list;
- exact instance diagnostics and stable lifecycle timeline.

E2 adds closed filters for status, instance, time range, failure class and reconciliation state; a 100-row maximum page size; server-owned sort mapping; duplicate/unknown/overlong parameter rejection; bounded timestamps with explicit offsets; safe unknown-enum representation; irreversible short owner references; no-store cache policy; Web detail diagnostics; and card-oriented Mobile diagnostics.

No E1/E2 route creates a command, calls Flowable, starts a worker, starts reconciliation or mutates migration/runtime state.

### 3.3 Fault, security and observability hardening

M5-F permanently validates:

- 24 controlled fault-injection cases;
- 24 security negative cases;
- durable UNKNOWN and no automatic retry;
- stale ownership/fence rejection and duplicate-outcome prevention;
- exact completion evidence requirements;
- tenant isolation and non-leaking resource enumeration;
- GET-only API and command-free Web/Mobile clients;
- strict input bounds and server-owned SQL ordering;
- redacted errors, logs, metrics and traces;
- failure-open observability that cannot change migration semantics;
- a closed low-cardinality metric catalog;
- 14 example-only environment-configured alerts/SLO signals.

Registered metric families are:

```text
approval.migration.operations.read
approval.migration.operations.read.latency
approval.migration.safety.event
approval.migration.safety.feature.enabled
```

No metric label contains tenant, operator, definition, release, plan, intent, Attempt, instance, engine, request, trace, owner, message, exception, SQL or free-text identity.

### 3.4 Release rehearsal and readiness

M5-G1 permanently validates:

- 18 release preconditions;
- clean and historical PostgreSQL/Flyway upgrade rehearsal;
- six explicit safe configuration defaults;
- startup no-execution/no-worker/no-scheduler/no-scanner boundaries;
- deterministic Canary and bounded-orchestration limits;
- E1/E2 and Web/Mobile read-only verification;
- observability/redaction checks;
- a ten-step UNKNOWN/reconciliation operator procedure;
- stop-the-line, evidence-preserving rollback and incident escalation;
- 18 isolated dry-run cases;
- 23 production-readiness checklist items;
- 14 operator scenarios.

No real production endpoint, credential, process instance or migration execution was used during the rehearsal.

## 4. Final test evidence

### 4.1 Maven reactor

- tests: `704`;
- failures: `0`;
- errors: `0`;
- skipped: `0`;
- reactor: `BUILD SUCCESS`;
- total duration: `08:04 min`.

| Module | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Approval Domain | 61 | 0 | 0 | 0 |
| Definition Compiler | 11 | 0 | 0 | 0 |
| Connector SPI | 3 | 0 | 0 | 0 |
| Integration Core | 12 | 0 | 0 | 0 |
| Generic Connector | 6 | 0 | 0 | 0 |
| Integration JDBC | 4 | 0 | 0 | 0 |
| Engine Flowable | 48 | 0 | 0 | 0 |
| Approval Application | 166 | 0 | 0 | 0 |
| Persistence JDBC / PostgreSQL | 281 | 0 | 0 | 0 |
| Host SDK | 7 | 0 | 0 | 0 |
| Architecture Tests | 9 | 0 | 0 | 0 |
| Approval Server | 91 | 0 | 0 | 0 |
| Generic Host Example | 5 | 0 | 0 | 0 |

### 4.2 Permanent M5 Node boundaries

- groups: `15`;
- tests: `138`;
- pass: `138`;
- fail: `0`;
- cancelled: `0`;
- skipped: `0`.

Focused final gates:

- E2 advanced diagnostics: `5 / 5`;
- F2 deep hardening: `7 / 7`;
- G1 production readiness: `6 / 6`;
- G2 final acceptance: `7 / 7`.

### 4.3 Client validation

- Vben client-management boundary: success;
- Vben TypeScript: success;
- Vben production build: success;
- UniApp TypeScript: success;
- UniApp H5 production build: success;
- WeChat Mini Program production build: success.

## 5. Final artifact integrity

Every G2 artifact ZIP was downloaded and independently hashed. Each local SHA-256 exactly matched GitHub's recorded digest.

| Artifact | Artifact ID | GitHub digest / downloaded ZIP SHA-256 | Result |
| --- | ---: | --- | --- |
| `approval-maven-30338254460` | `8680189169` | `8dbb0a0a0c7f665ed56eed5f767cff49d5fb99dbfb6a353adbd87f8c63d64c84` | exact match |
| `approval-vben-30338254460` | `8680036270` | `6c207ef2e36afa8e253c8a822c52e795080227909a8e610b3ca535f4d684328c` | exact match |
| `approval-mobile-30338254460` | `8680019534` | `b2e3695b2bfd00229eaa1cf5d3ee90aad6e750703c516427f5232415f149ab4b` | exact match |
| `approval-hygiene-30338254460` | `8679998281` | `ddacc72c25945b90d3a6c1f2de97fcaa4ef57e1755209a25c6d0ee3a7b844f9d` | exact match |

All artifacts were unexpired when verified and had a GitHub retention expiry of 2026-10-26.

## 6. Flyway and repository hygiene

- Flyway is continuous through V48;
- M5 owns V33–V48;
- no V49 exists;
- fresh database migration reaches V48;
- historical upgrade cases cover V1, V13, V23, V31 and V36–V47;
- a V27 heavy fixture upgrades 5,000 instances/tasks without changing existing evidence;
- schema upgrade creates no migration command/outcome/reconciliation/orchestration/aggregation evidence;
- no production source queries or modifies Flowable `ACT_*` tables;
- no production credential or production migration endpoint is committed;
- exactly one automatic PR/main workflow remains:
  `.github/workflows/approval-platform-validation.yml`;
- workflow permissions remain `contents: read`;
- no temporary workflow, self-committing helper or hidden write path remains.

## 7. Pre-document PR and repository state

At the successful G2 functional/regression Head:

- `main`: `d769722cf7dd5418739a91ad4c45ca1a1c147502`;
- PR: #58;
- base: `main`;
- head branch: `agent/m5-governed-process-instance-migration`;
- head: `9c567a30e7430ee43d7b4ad418a70923edf5c360`;
- relation: ahead `342`, behind `0`;
- state: Open;
- Draft: true;
- merged: false;
- mergeable: true;
- reviews: none;
- requested changes: none;
- unresolved review threads: none;
- repository auto-merge setting: disabled;
- merge commit method: enabled.

The final documentation Head, PR body, readiness transition and merge are separately rechecked after this evidence is committed and permanently validated.

## 8. Issue and parallel M6 state

At final G2 functional validation:

- Issue #13: Open;
- Issue #14: Open;
- Issue #56: Open pending successful M5 merge.

Independent M6 PRs remained frozen and separate:

| PR | Branch | Head | State |
| --- | --- | --- | --- |
| #67 | `agent/m6-a-connector-foundation` | `4f59b12dff8b9988c4509b54fbbcb61046069fc9` | Open + Draft |
| #68 | `agent/m6-b-sdk-event-ecosystem` | `330dbdd035e436459ffdedf0d2b0c8e07dac7e6c` | Open + Draft |
| #69 | `agent/m6-c-template-component-ecosystem` | `72acb3ba18602c09c28bfe08b58f8b91e6efe6e4` | Open + Draft |
| #70 | `agent/m6-d-ai-foundation` | `9d588215e869c8f1332c0bc1a2809fbd235c2efa` | Open + Draft |

No M6 branch, PR, issue, source, protocol, test, configuration or migration was modified by the M5 work.

## 9. Permanent safety invariants

1. execution is default disabled;
2. worker is default disabled;
3. orchestration is default disabled;
4. aggregation is default disabled and cannot trigger instance execution;
5. automatic reconciliation is default disabled;
6. Kill Switch is rechecked before every new dispatch;
7. UNKNOWN is durable;
8. UNKNOWN is never automatically retried;
9. reconciliation never redispatches migration;
10. ownership, lease and fencing remain server authoritative;
11. stale workers cannot write an outcome;
12. one instance has at most one authoritative terminal outcome;
13. runtime binding changes only through exact CAS;
14. completion requires verifiable evidence;
15. API reads are tenant-scoped and permission-protected;
16. Web and Mobile are read-only;
17. logs, metrics and traces are redacted and bounded;
18. metric labels are low-cardinality closed enums;
19. no direct Flowable `ACT_*` access exists;
20. no production scheduler or cross-tenant scanner exists;
21. no public or hidden migration command exists;
22. no unauthorized V49 exists;
23. no second automatic workflow exists;
24. production migration execution remains `NOT_AUTHORIZED`.

## 10. Known limitations and residual risks

- Flowable migration capability is supported only within the documented model/engine limitations and exact verification rules.
- The platform and Flowable engine cannot share a cross-system atomic transaction; ambiguity is handled by durable UNKNOWN and reconciliation rather than fake rollback.
- Real production capacity, topology, operational ownership and change-window validation were not performed in M5.
- Gateway rate limiting remains deployment-owned; the application observes bounded 429 results and never maintains an unbounded per-tenant rate map.
- Tenant/plan UNKNOWN backlog is diagnosed through authorized E2 reads, not a global high-cardinality gauge or background scanner.
- Alert rules are examples with environment placeholders and are not connected to a real production alert manager.
- Application rollback preserves V48 schema/evidence; Flyway downgrade is not supported.
- A production enablement package still requires an independent human authorization, private environment review, named operator ownership, production configuration review and a separately authorized Canary plan.

## 11. Retained failure lineage

Failed and automatically cancelled development Runs remain in GitHub history. None was deleted, hidden, blindly rerun or described as successful evidence.

Representative retained evidence includes:

- E2 Run #814: static false positive on a read-only enum;
- E2 Run #815: PostgreSQL timestamp binding type mismatch;
- F2 Run #839: legacy static metric-location expectation;
- F2 Run #846: formatting-dependent reconciliation boundary assertion;
- G1 Run #859: Node test escape syntax error;
- G1 Run #860: incorrect static assumption about pre-M5 migration file location.

Each failure was diagnosed, corrected by a new non-forced commit and followed by a new natural permanent workflow Run. Product/security assertions were not deleted or weakened.

## 12. Merge conditions

PR #58 may be marked Ready and merged with a **merge commit** only after the documentation Head passes the same permanent workflow and a final recheck confirms:

- all four jobs successful;
- no new review/requested-change/unresolved thread;
- mergeable true;
- behind zero;
- no M6 contamination;
- Flyway still V48 with no V49;
- Issues #13/#14 remain Open;
- auto-merge remains disabled;
- all execution flags remain default false;
- production migration execution remains `NOT_AUTHORIZED`.

Merging the code does not authorize production instance migration.
