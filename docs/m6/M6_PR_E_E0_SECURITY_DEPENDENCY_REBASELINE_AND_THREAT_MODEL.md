# M6-PR-E E0 Security and Dependency Rebaseline and Threat Model

Status: `M6_PR_E_E0_STAGING_COMPLETE_REMOTE_BINDING_PENDING`

Tracking:

- repository: `akaryc1b/approval-platform`;
- parent acceptance: Issue `#82`;
- parent milestone: Issue `#62`;
- security/dependency workstream: Issue `#97`;
- Draft PR: `#98`;
- branch: `agent/m6-pr-e-security-dependency-evidence-closure`;
- exact R0 base/main: `1747b22123fd71cccd8334853ad7060c6645b443`;
- last remotely verified R0 Head: `3a48fb78604539316f4e333a1a96f4a678698c54`;
- R0 validation: Run `31245986911` / `#1370`, natural `pull_request`, success;
- parallel database workstream: Issue `#91` / Draft PR `#92`, excluded from this Gate.

## 1. Purpose and Gate boundary

E0 defines the exact evidence contract, threat model, ownership boundary and no-zero-claim rules for the two M6 production-readiness blockers:

- `PRB-16`: authoritative Code Scanning, Secret Scanning and Dependabot Security Alert inventory;
- `PRB-17`: exact dependency vulnerability applicability and reachability evidence.

E0 is not scanner implementation and does not close either blocker. It adds no approval, AI, Connector, Event, Template, automation, Production Reauthentication, Worker, Queue, Scheduler, Retry, Rollback, Notification, Retention, deployment, rollout, traffic mutation or Production Promotion capability.

This staging record is intentionally not marked as remotely accepted. The exact E0 commit, natural PR Run, Jobs and Artifacts must be externally bound after one controlled branch update. A commit cannot embed its own SHA without self-reference.

## 2. Inherited M6 decision

```text
M6_G_ACCEPTANCE_PASSED
M6_PRODUCTION_READINESS_BLOCKED
PARENT_CLOSURE_BLOCKER=15
PRODUCTION_READINESS_BLOCKER=2
CORRECTABLE_DEFECT=0
```

No Draft branch result changes the parent decision. Only accepted, merged and exact-main evidence can update the M6 blocker matrix.

## 3. Exact repository evidence posture

### 3.1 Existing controls

- `SECURITY.md` defines private disclosure guidance and permanent identity, tenant, Webhook, masking, authorization, operations and Secret-management boundaries.
- `.github/dependabot.yml` schedules weekly Maven, npm and GitHub Actions maintenance updates.
- `.github/workflows/approval-platform-validation.yml` remains the sole automatic PR/main Workflow.
- The Workflow declares `contents: read`, uses natural `pull_request` and `push -> main` evidence, and retains Maven, Vben, Mobile and Hygiene Artifacts.
- Repository Hygiene rejects temporary patches, generated payloads and unsafe tracked-tree content.

### 3.2 Evidence gaps retained by E0

E0 does not find permanent accepted evidence for:

- Code Scanning alert inventory and exact analysis coverage;
- Secret Scanning alert inventory, locations and scan-history completeness;
- Dependabot Security Alert inventory;
- Private Vulnerability Reporting enablement state;
- repository advisory inventory;
- deterministic exact-SHA SBOM;
- complete Maven dependency/plugin graph;
- complete pnpm workspace/lock graph;
- immutable GitHub Actions reference inventory;
- vulnerability applicability and reachability decisions;
- versioned exception/suppression register;
- CODEOWNERS-based ownership routing.

The currently available connector does not expose dedicated alert-list operations. Therefore the only honest E0 disposition for the three alert classes is:

```text
EVIDENCE_UNAVAILABLE
```

This is not an alert count.

## 4. Supported dependency and toolchain baseline

| Ecosystem | Exact R0 baseline |
| --- | --- |
| Java | `21` |
| Maven | `>=3.9.6` |
| Spring Boot | `4.0.2` |
| Flowable | `8.0.0` |
| ArchUnit | `1.4.2` |
| Testcontainers | `2.0.5` |
| Maven Compiler Plugin | `3.14.0` |
| Maven Surefire Plugin | `3.5.5` |
| Maven Enforcer Plugin | `3.5.0` |
| Maven Checkstyle Plugin | `3.6.0` |
| JaCoCo | `0.8.15` |
| Node | `^22.18.0 || ^24.0.0` |
| pnpm | `10.33.4`, minimum `10.0.0` |
| TypeScript | `5.9.3` |
| GitHub Actions | accepted Workflow references major `v4` tags; immutable full-SHA posture not proved |

Maintenance PRs are freshness inputs only. They are neither vulnerability findings nor authorization to merge an upgrade.

## 5. Protected assets

| Asset | Security property |
| --- | --- |
| Source and Workflow content | integrity and exact-SHA identity |
| Security alert inventories | completeness, authorization and timestamp |
| Candidate Secret locations | confidentiality and strict redaction |
| Dependency graphs and SBOMs | deterministic completeness and scope |
| Scanner databases/rules | immutable version or digest |
| SARIF and scanner reports | integrity, bounded disclosure and exact commit |
| Finding dispositions | evidence-backed, owned and reviewable |
| Exception register | explicit scope, owner and expiry |
| Workflow Artifacts | exact Head/Branch binding, digest and retention |
| Review and Issue decisions | non-repudiation and no unsupported closure |

## 6. Actors and trust boundaries

### 6.1 Actors

- external vulnerability reporter;
- untrusted Pull Request author;
- repository collaborator;
- repository administrator/security manager;
- compromised dependency maintainer;
- compromised GitHub Action tag or release;
- compromised scanner update channel;
- malicious or mistaken suppressor;
- GitHub App/token with insufficient or excessive permissions;
- CI runner executing untrusted repository content.

### 6.2 Trust boundaries

1. untrusted PR content -> GitHub-hosted runner;
2. repository content -> third-party Action/scanner binary;
3. GitHub metadata APIs -> retained evidence envelope;
4. alert summary -> exact alert instances/locations/analyses;
5. dependency manifest -> resolved graph -> packaged runtime;
6. scanner finding -> applicability/reachability decision;
7. candidate Secret -> redacted operator-facing evidence;
8. PR Artifact -> accepted main Artifact;
9. mutable tag -> immutable commit/digest;
10. logical owner role -> verified GitHub identity.

## 7. Threat inventory

| ID | Threat | Required E0 control | Residual disposition |
| --- | --- | --- | --- |
| `T01` | API denial or disabled feature is reported as zero alerts | explicit availability state and HTTP/result preservation | `EVIDENCE_UNAVAILABLE` |
| `T02` | empty first page hides later alerts | full pagination proof and terminal-page evidence | `PARTIAL` until complete |
| `T03` | alert belongs to another ref or stale commit | commit/ref/analysis binding | `UNRESOLVED` |
| `T04` | repository-wide alert inventory is confused with exact-SHA coverage | separate inventory, analysis and location evidence | `UNRESOLVED` |
| `T05` | mutable Action tag changes after review | full-length commit SHA or verified digest | release blocker until pinned/approved |
| `T06` | scanner binary or vulnerability database changes silently | immutable tool version/digest and database timestamp/digest | `UNRESOLVED` |
| `T07` | Maven profile, BOM, plugin or transitive dependency omitted | complete reactor/plugin/scope graph | `PARTIAL` |
| `T08` | pnpm workspace, lockfile or generated upstream dependency omitted | exact workspace/lock/bootstrap scope inventory | `PARTIAL` |
| `T09` | test/build-only finding is misreported as runtime reachable | scope and packaging analysis | `UNRESOLVED` |
| `T10` | vulnerable code is packaged but not callable, or callable but not externally reachable | load/call/reachability evidence | `UNRESOLVED` |
| `T11` | broad suppression hides unrelated findings | exact rule/path/fingerprint scope, owner and expiry | release blocker |
| `T12` | candidate Secret is written to logs or Artifacts | redact value and surrounding sensitive context; retain type/location hash only | release blocker |
| `T13` | fork/untrusted PR obtains write token or Secret | least privilege; no Secret-dependent step on untrusted code | release blocker |
| `T14` | cache poisoning replaces dependencies or scanner output | immutable keys, no untrusted write-to-trusted restore path | `UNRESOLVED` |
| `T15` | Artifact is missing, substituted or bound to wrong Head | ID, bytes, digest, Head, Branch and expiry verification | release blocker |
| `T16` | severity downgrade is used to make CI green | retain upstream severity and separate local disposition | release blocker |
| `T17` | accepted exception never expires | mandatory owner, approval, expiry and non-renewal review | release blocker |
| `T18` | GitHub SBOM is assumed complete when dependency graph is partial | compare GitHub SBOM with Maven/pnpm/Actions graphs | `PARTIAL` |
| `T19` | CODEOWNERS identity is missing or invalid | explicit role-to-GitHub-identity assignment before Ready | `UNRESOLVED` |
| `T20` | scanner success is confused with Production Readiness | update only `PRB-16`/`PRB-17`; parent blockers remain independent | permanent boundary |

## 8. No-zero-claim boundary

The following invariants are permanent:

```text
NO_ALERT_API_VISIBILITY != ZERO_ALERTS
PERMISSION_DENIED != ZERO_ALERTS
FEATURE_DISABLED != ZERO_ALERTS
RESOURCE_NOT_FOUND != ZERO_ALERTS
EMPTY_FIRST_PAGE != COMPLETE_EMPTY_INVENTORY
NO_SCANNER_CONFIG != ZERO_FINDINGS
DEPENDENCY_UPDATE_PR != APPLICABLE_VULNERABILITY
SBOM_EXPORT != COMPLETE_REACHABILITY_ANALYSIS
```

An empty inventory may be recorded only when all of the following are true:

1. the authoritative source executed successfully;
2. the required feature was proved enabled/eligible;
3. the caller permission was proved sufficient;
4. every page was retrieved;
5. the source scope was explicit;
6. the evidence was bound to the repository and exact acceptance Head where the source supports commit binding;
7. the raw machine-readable result was retained with a digest;
8. no contradictory source exists.

## 9. Authoritative source contract

The detailed contract is in:

`docs/m6/M6_PR_E_SECURITY_EVIDENCE_SOURCE_CONTRACT.md`

Required sources include:

- repository posture and private-vulnerability-reporting status;
- Code Scanning alerts, analyses and instances;
- Secret Scanning alerts, locations and scan history;
- Dependabot Security Alerts;
- repository security advisories;
- GitHub dependency-graph SBOM;
- deterministic Maven, pnpm and GitHub Actions graphs;
- code/Secret scanner outputs;
- applicability/reachability decisions;
- exception register.

## 10. Finding dispositions

Only the following finding dispositions are allowed:

```text
APPLICABLE
NOT_APPLICABLE
UNREACHABLE
MITIGATED
ACCEPTED_WITH_EXPIRY
UNRESOLVED
EVIDENCE_UNAVAILABLE
```

`NOT_APPLICABLE`, `UNREACHABLE` and `MITIGATED` require positive evidence. Absence of a call-site search result is not sufficient by itself.

## 11. Release-blocking rules

The PR-E workstream remains Draft when any of the following is true:

- an alert class is `EVIDENCE_UNAVAILABLE` without an explicitly accepted availability limitation;
- any Critical/High finding remains `UNRESOLVED`;
- any severity has credible tenant-isolation, authorization, Secret, RCE, injection, deserialization, SSRF, workflow-supply-chain or evidence-integrity impact and remains unresolved;
- a suppression has no owner or expiry;
- an Artifact contains candidate Secret material;
- scanner or Action identity is mutable and not formally accepted;
- exact test/scanner selection is incomplete;
- an actionable Review finding remains open.

## 12. Ownership state

The role matrix is in:

`docs/m6/M6_PR_E_SECURITY_OWNERSHIP_AND_REMEDIATION_MATRIX.md`

No repository `CODEOWNERS` file was proved at R0. E0 therefore records logical roles but does not invent a team or user identity. A verified GitHub identity is required before E6 Ready.

## 13. E0 verification plan

The permanent E0 boundary must verify:

- exact no-zero-claim markers;
- exact finding-disposition set;
- evidence availability states;
- exact 40-character commit SHA requirement;
- mandatory redaction and retention fields;
- no product-authority expansion;
- `PRB-16` and `PRB-17` remain open;
- Issues `#82`, `#62`, `#91`, `#13` and `#14` remain independently governed.

## 14. E0 exit decision

Staging result:

```text
M6_PR_E_E0_STAGING_COMPLETE
M6_PR_E_E0_REMOTE_BINDING_PENDING
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
NO_PRODUCT_AUTHORITY_EXPANSION
NO_DEPENDENCY_UPGRADE_IN_E0
NO_SCANNER_IMPLEMENTATION_IN_E0
```

E0 may be marked remotely accepted only after:

1. a fresh exact-state read confirms unchanged `main`, PR #98 and Issue states;
2. the E0 files are committed to PR #98 in one controlled branch update;
3. one natural PR Workflow completes successfully at the exact E0 Head;
4. all nine physical Jobs and all four Artifacts are verified;
5. Review and Thread state is re-read;
6. no unsupported alert-count claim appears in PR/Issue metadata.

`AI_IS_NOT_AN_OPERATOR`
