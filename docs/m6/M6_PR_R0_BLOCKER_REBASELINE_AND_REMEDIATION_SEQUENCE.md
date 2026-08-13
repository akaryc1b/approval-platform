# M6 Production Readiness Blocker Rebaseline and Remediation Sequence

Status: `M6_PR_BLOCKER_REBASELINE_COMPLETE`

Selected first remediation workstream: `M6-PR-E — Security and Dependency Evidence Closure`

Tracking Issue: `#97`

## 1. Purpose and boundary

M6-G completed G0 through G5 and produced two distinct decisions:

```text
M6_G_ACCEPTANCE_PASSED
M6_PRODUCTION_READINESS_BLOCKED
```

This record starts an independent post-M6-G production-readiness planning phase. It is not a new M6-G Gate, does not reopen PR #93, and does not reinterpret M6-G acceptance.

R0 is planning and evidence rebaseline only. It adds no production product capability, no execution authority and no security scanner implementation.

Permanent R0 prohibitions:

- no modification of `main`;
- no modification or reopening of PR #93;
- no modification of Draft PR #92;
- no Connector, Event, Template, AI, approval-command or automation capability;
- no Production Reauthentication;
- no automatic Retry, Rollback, Notification or Retention;
- no Worker, Queue, Scheduler, Listener or Polling;
- no Canary, deployment, rollout, traffic mutation or Production Promotion;
- no second automatic PR/main Workflow;
- no dependency upgrade or Dependabot merge in R0;
- no Ready or Merge action in R0;
- Issues #82, #62, #91, #13 and #14 remain independently governed and Open.

## 2. Exact R0 identity

| Field | Exact value |
| --- | --- |
| Repository | `akaryc1b/approval-platform` |
| Default branch | `main` |
| Exact current main | `1747b22123fd71cccd8334853ad7060c6645b443` |
| M6-G PR | `#93`, Merged / Closed |
| M6-G Merge Commit | `1747b22123fd71cccd8334853ad7060c6645b443` |
| Main drift after M6-G | none; compare is `identical`, ahead `0`, behind `0` |
| M6-G natural main Run | `31159512922` / `#1342`, `push -> main`, success |
| M6-G tracking Issue | `#82`, Open |
| Parent Issue | `#62`, Open |
| Parallel database blocker | `#91` / Draft PR `#92` |
| R0 selected Issue | `#97` |
| R0 branch | `agent/m6-pr-e-security-dependency-evidence-closure` |
| Automatic PR/main Workflow | `.github/workflows/approval-platform-validation.yml` only |
| Auto-merge | disabled |

Because current `main` is still the M6-G Merge Commit, the accepted G5 Run and four main Artifacts remain the exact main baseline. R0 does not manufacture a replacement M6-G Run.

## 3. Inherited M6-G decision matrix

The permanent M6-G matrix contains:

| Classification | Count |
| --- | ---: |
| `PARENT_CLOSURE_BLOCKER` | `15` |
| `PRODUCTION_READINESS_BLOCKER` | `2` |
| `NON_BLOCKING_LIMITATION` | `3` |
| `CORRECTABLE_DEFECT` | `0` |

The current R0 disposition is below.

| ID | Domain | M6-G state | Post-G5 delta | Current disposition |
| --- | --- | --- | --- | --- |
| `PRB-01` | Connector production ownership | M6-A operated-production B01–B20 gates incomplete | no merged main change | remains `PARENT_CLOSURE_BLOCKER` |
| `PRB-02` | Durable SDK/Event runtime | no durable production subscription, Event store, delivery Worker or operated recovery | no merged main change | remains `PARENT_CLOSURE_BLOCKER` |
| `PRB-03` | Customer Provider gate | no customer production authorization, egress, on-call or release rehearsal | no merged main change | remains `PARENT_CLOSURE_BLOCKER` |
| `PRB-04` | Retention execution | durable posture exists; no operated tombstone executor | no merged main change | remains `PARENT_CLOSURE_BLOCKER` |
| `PRB-05` | Action Whitelist | `EMPTY_PENDING_EXISTING_COMMAND_AUDIT` | no merged main change | remains `PARENT_CLOSURE_BLOCKER` |
| `PRB-06` | P5 execution | `P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND` | no merged main change | remains `PARENT_CLOSURE_BLOCKER` |
| `PRB-07` | Production Reauthentication | `UNAVAILABLE`, fail-closed | no merged main change | remains `PARENT_CLOSURE_BLOCKER` |
| `PRB-08` | Qualifying Application Command | no existing command satisfies all safety gates | no merged main change | remains `PARENT_CLOSURE_BLOCKER` |
| `PRB-09` | Rollback execution | plans and review only; no operated executor | no merged main change | remains `PARENT_CLOSURE_BLOCKER` |
| `PRB-10` | Incident response execution | operated response, Notification, Retention and release controls incomplete | no merged main change | remains `PARENT_CLOSURE_BLOCKER` |
| `PRB-11` | Actual Provider billing | cost envelope only | no merged main change | remains `PARENT_CLOSURE_BLOCKER` |
| `PRB-12` | Durable cost history | process-local usage only | no merged main change | remains `PARENT_CLOSURE_BLOCKER` |
| `PRB-13` | Durable Circuit/control timeline | process-local state only | no merged main change | remains `PARENT_CLOSURE_BLOCKER` |
| `PRB-14` | Canary/rollout/traffic mutation | non-executable plans only | no merged main change | remains `PARENT_CLOSURE_BLOCKER` |
| `PRB-15` | MySQL 8.4 production compatibility | production-equivalent support incomplete | Draft PR #92 has advanced but remains unmerged, incomplete and explicitly non-production-supported | remains `PARENT_CLOSURE_BLOCKER` |
| `PRB-16` | Dedicated GitHub alert inventory | Code Scanning, Secret Scanning and Dependabot Security Alert inventory not authoritatively exposed | no dedicated repository evidence or connector inventory operation | remains `PRODUCTION_READINESS_BLOCKER` |
| `PRB-17` | Dependency applicability | exact resolved-graph CVE applicability/reachability not independently proved | maintenance PRs increased, but no applicability evidence was added to main | remains `PRODUCTION_READINESS_BLOCKER` |

No blocker is reclassified merely because a Draft branch contains partial work. Only accepted, merged and exact-main evidence can change the M6 parent decision.

## 4. Repository security and dependency rebaseline

### 4.1 Existing controls

- `SECURITY.md` directs reporters to GitHub Private Vulnerability Reporting and prohibits public disclosure of unpatched vulnerabilities, tokens, customer data or exploit details.
- The security baseline requires verified identity, tenant context, request identity, signed/replay-protected Webhooks, field permissions, masking, secondary authorization for cross-tenant administration, no arbitrary SQL in operations and external Secret management.
- `.github/dependabot.yml` schedules weekly update PRs for Maven, npm and GitHub Actions, with an open-PR limit of five per ecosystem.
- The sole automatic PR/main Workflow uses least-privilege `contents: read` at workflow level.
- Existing repository hygiene rejects temporary patches, generated payloads and unsafe base configuration.

### 4.2 Missing permanent evidence

R0 found no repository integration for:

- CodeQL or another governed code scanner;
- Gitleaks, TruffleHog or another governed Secret scanner;
- OSV-Scanner, OWASP Dependency-Check or another governed vulnerability scanner;
- GitHub Dependency Review;
- deterministic SBOM generation such as CycloneDX or Syft;
- vulnerability reachability/applicability analysis;
- a versioned suppression/exception register;
- repository `CODEOWNERS` ownership routing.

The available GitHub connector does not provide dedicated list operations for Code Scanning, Secret Scanning or Dependabot Security Alerts. Therefore:

```text
NO_ALERT_API_VISIBILITY != ZERO_ALERTS
EMPTY_CODE_SEARCH != ZERO_VULNERABILITIES
DEPENDENCY_UPDATE_PR != APPLICABLE_VULNERABILITY
```

### 4.3 Supported dependency ecosystems

| Ecosystem | Exact current baseline |
| --- | --- |
| Java | Java `21`, Maven reactor |
| Spring Boot | `4.0.2` |
| Flowable | `8.0.0` |
| ArchUnit | `1.4.2` |
| Testcontainers | `2.0.5` |
| Node | `^22.18.0 || ^24.0.0` |
| pnpm | `10.33.4`, minimum `10.0.0` |
| TypeScript | `5.9.3` |
| GitHub Actions | currently pinned mainly to major `v4` references in the permanent Workflow |

### 4.4 Open maintenance PR inventory

R0 observed open update PRs in these categories:

| PR | Ecosystem | Update | R0 classification |
| ---: | --- | --- | --- |
| `#94` | GitHub Actions | `actions/download-artifact` `4 -> 8` | maintenance; breaking/runtime and artifact-behavior review required |
| `#73` | GitHub Actions | `actions/upload-artifact` `4 -> 7` | maintenance; artifact-shape compatibility review required |
| `#4` | GitHub Actions | `actions/setup-java` `4 -> 5` | maintenance; Node 24 runner compatibility review required |
| `#2` | GitHub Actions | `actions/checkout` `4 -> 7` | maintenance; checkout security and runner compatibility review required |
| `#1` | GitHub Actions | `actions/setup-node` `4 -> 7` | maintenance; Node 24 runner and cache posture review required |
| `#5` | Maven BOM/runtime | Spring Boot `4.0.2 -> 4.1.0` | major product dependency review; not auto-mergeable |
| `#84` | Maven build plugin | flatten `1.7.3 -> 1.8.0` | build-time maintenance |
| `#72` | Maven build plugin | enforcer `3.5.0 -> 3.6.3` | build-time maintenance |
| `#6` | Maven build plugin | compiler `3.14.0 -> 3.15.0` | build-time maintenance |
| `#3` | Maven build plugin | surefire `3.5.5 -> 3.5.6` | test-time maintenance |
| `#7` | npm/dev | TypeScript `5.9.3 -> 7.0.2` | major toolchain update; compatibility review required |

These PRs are inputs to dependency review. R0 does not merge, close, rebase or treat them as security findings.

## 5. Remediation workstream decomposition

R0 separates ownership and authority domains so that one workstream cannot silently expand another.

| Workstream | Blockers | Core scope | Authority impact |
| --- | --- | --- | --- |
| `M6-PR-A` | `PRB-01` | Connector Secret backend, endpoint/egress, rotation, operated audit, on-call, DR and release gates | high external/operational authority |
| `M6-PR-B` | `PRB-02` | durable Event store, Outbox delivery, Worker, recovery and operated delivery audit | high runtime side-effect scope |
| `M6-PR-C` | `PRB-05`–`PRB-08` | qualifying command audit, Action Whitelist, Production Reauthentication and bounded command execution | highest command/authorization risk |
| `M6-PR-D` | `PRB-04`, `PRB-09`–`PRB-14` | retention, rollback, incident response, billing, cost history, circuit timeline, rollout posture | broad operated-production scope |
| `M6-PR-E` | `PRB-16`, `PRB-17` | authoritative alert inventory, SBOM, scanning, applicability and reachability evidence | no product authority expansion |
| `M6-PR-F` | `PRB-15` | PostgreSQL 16 / MySQL 8.4 production equivalence | large persistence/operations scope; Draft PR #92 already tracks it |
| `M6-PR-G` | `PRB-03` | customer Provider authorization, egress, on-call, release and production rehearsal | external Provider and customer operations scope |

The Provider gate is separated from Connector production ownership because its customer authorization, data egress, model/provider operations and release evidence have different owners and threat boundaries.

## 6. Prioritization method

R0 uses five dimensions:

- **Closure leverage** — number and importance of formal blockers directly addressable;
- **Authority safety** — lower product/command/Secret mutation authority scores higher;
- **Independence** — ability to proceed without waiting for another product workstream;
- **Evidence determinism** — ability to bind outputs to exact commit and reproduce them;
- **Delivery risk** — engineering and operational uncertainty; lower risk scores higher.

Scores range from `1` (least favorable) to `5` (most favorable).

| Workstream | Closure leverage | Authority safety | Independence | Evidence determinism | Delivery risk | Total / 25 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `M6-PR-E` Security evidence | 4 | 5 | 5 | 5 | 4 | **23** |
| `M6-PR-D` Operations | 5 | 2 | 2 | 3 | 1 | 13 |
| `M6-PR-C` Controlled execution | 4 | 1 | 3 | 4 | 1 | 13 |
| `M6-PR-F` MySQL 8.4 | 3 | 4 | 3 | 4 | 1 | 15 |
| `M6-PR-A` Connector operations | 2 | 2 | 2 | 3 | 2 | 11 |
| `M6-PR-B` Event runtime | 2 | 2 | 3 | 4 | 2 | 13 |
| `M6-PR-G` Provider production gate | 2 | 2 | 1 | 3 | 1 | 9 |

The score is a sequencing aid, not a declaration that the highest-scoring workstream makes M6 Production Ready. Parent closure remains blocked until all required workstreams meet their own acceptance contracts.

## 7. Selected first remediation workstream

R0 selects:

```text
M6-PR-E — Security and Dependency Evidence Closure
```

Reasons:

1. it directly addresses both current `PRODUCTION_READINESS_BLOCKER` entries;
2. it adds no approval, AI, Connector, Event or automation authority;
3. it is independent of Draft PR #92 and other product workstreams;
4. exact-SHA inventory, SBOM and scanner evidence can be made deterministic;
5. it improves the trustworthiness of every later remediation workstream;
6. it can distinguish maintenance freshness from actual vulnerability exposure;
7. it avoids selecting the highest-risk command/reauthentication workstream before the security evidence foundation exists.

Selection does not close `PRB-16` or `PRB-17`. It only authorizes their independent remediation process.

## 8. PR-E evidence contract

Every evidence item must include:

| Field | Requirement |
| --- | --- |
| Repository | exact `owner/name` |
| Commit | exact 40-character SHA |
| Branch/event | exact source branch and natural trigger |
| Tool/source | exact GitHub feature, scanner or generator identity |
| Version | immutable version, digest or verified release identity |
| Generated at | UTC timestamp |
| Scope | Maven/npm/GitHub Actions/code/Secret paths included and excluded |
| Result | machine-readable inventory or report |
| Disposition | governed finding classification |
| Redaction | no candidate Secret or customer data in logs/artifacts |
| Retention | permanent Workflow Artifact class and expiry |
| Reproduction | exact command/configuration or authoritative API query |

Allowed finding dispositions:

```text
APPLICABLE
NOT_APPLICABLE
UNREACHABLE
MITIGATED
ACCEPTED_WITH_EXPIRY
UNRESOLVED
EVIDENCE_UNAVAILABLE
```

An empty result is acceptable only when the authoritative source executed successfully for the exact SHA and the evidence proves that the queried feature and scope were enabled and complete.

## 9. PR-E Gate plan

### E0 — Exact rebaseline and threat model

- exact SHA, manifests, lockfiles, workflow and security-feature inventory;
- ownership and evidence-source matrix;
- exact alert-query semantics;
- failure and no-zero-claim contract;
- no implementation before E0 acceptance.

### E1 — GitHub alert inventory

- Code Scanning;
- Secret Scanning;
- Dependabot Security Alerts;
- Private Vulnerability Reporting posture;
- feature/permission availability;
- exact unavailable/disabled states remain blockers.

### E2 — Dependency graph and SBOM

- deterministic Maven reactor and plugin graph;
- deterministic pnpm workspace/lock graph;
- immutable GitHub Actions inventory;
- machine-readable SBOM bound to exact SHA;
- source, scope, package and license evidence.

### E3 — Applicability and reachability

- map every finding to resolved dependency paths;
- classify runtime, build, test, client, server and workflow exposure;
- record packaging/loading/invocation/reachability evidence;
- require owner and expiry for exceptions;
- fail closed on unresolved release-blocking findings.

### E4 — Code and Secret scanning

- pinned reproducible scanners or authoritative GitHub features;
- least-privilege permissions;
- redacted output;
- narrow, owned and expiring suppressions;
- no broad skip or severity downgrade to make CI green.

### E5 — Permanent Workflow and artifacts

- extend only the existing automatic Workflow;
- preserve the four permanent Artifact classes;
- normally retain security evidence inside `Hygiene` or `Maven`;
- exact selection/no-omission verification;
- failed Run and Correction discipline;
- no deployment or Promotion.

### E6 — Formal acceptance and controlled merge

- exact inventory, SBOM, findings and dispositions;
- final Review/security closure;
- Ready and ordinary Merge Commit as separate actions;
- natural `push -> main` verification;
- independent download and SHA-256 verification of final Artifacts;
- update only `PRB-16` and `PRB-17` if fully satisfied;
- #82/#62 remain Open while any Parent Closure Blocker remains.

## 10. Low-frequency CI strategy

R0 creates one planning commit and one Draft PR. It contains no scanner or dependency implementation.

Subsequent authorized implementation should batch remote validation:

```text
Batch A: E0 + E1 authoritative inventory       -> 1 PR Workflow
Batch B: E2 + E3 SBOM/applicability             -> 1 PR Workflow
Batch C: E4 + E5 permanent scanning/evidence    -> 1 final PR Workflow
E6 Ready/Merge                                  -> no code Push
Post-merge natural push -> main                 -> 1 main Workflow
```

E0 through E6 remain independently recorded even when remote Runs are batched.

Rules:

- no empty commit;
- no documentation-only trigger after final Head;
- no Push while the same Head has a queued/in-progress/successful Run;
- no same-Head rerun except one proven infrastructure failure;
- Product, Test, Workflow and Artifact failures require independent Correction commits;
- no weakened assertion, broad exclusion, reduced Artifact evidence or expanded permissions to obtain green CI.

## 11. Issue and branch decision

| Item | Decision |
| --- | --- |
| Selected Issue | `#97 — [M6-PR-E] Security and Dependency Evidence Closure` |
| Branch | `agent/m6-pr-e-security-dependency-evidence-closure` |
| Target | `main` |
| Initial content | this R0 record only |
| PR state | Open + Draft |
| Auto-merge | disabled |
| Merge method after all Gates | ordinary Merge Commit only |
| PR #93 | immutable historical M6-G evidence |
| PR #92 | parallel, untouched, Draft and excluded |

## 12. R0 final decision

```text
M6_PR_BLOCKER_REBASELINE_COMPLETE
SELECTED_FIRST_REMEDIATION_WORKSTREAM=M6_PR_E_SECURITY_AND_DEPENDENCY_EVIDENCE_CLOSURE
NO_PRODUCT_IMPLEMENTATION_IN_R0
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
ISSUE_97_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
ISSUE_91_REMAINS_OPEN
ISSUE_13_REMAINS_OPEN
ISSUE_14_REMAINS_OPEN
AI_IS_NOT_AN_OPERATOR
```