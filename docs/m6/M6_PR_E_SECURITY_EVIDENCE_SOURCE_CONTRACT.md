# M6-PR-E Security Evidence Source Contract

Status: `E0_EVIDENCE_SOURCE_CONTRACT_DEFINED_REMOTE_BINDING_PENDING`

## 1. Contract objective

Every security or dependency conclusion must be reproducible, exact-SHA-bound where the source supports commit identity, complete for its declared scope, redacted, retained and independently reviewable.

No source may be silently substituted for another source. A successful build is not a Code Scanning inventory. A Dependabot update PR is not a Dependabot Security Alert. A GitHub SBOM is not reachability analysis. A local scanner does not prove the state of GitHub-hosted alert features.

## 2. Canonical evidence envelope

Every machine-readable evidence item must contain:

| Field | Requirement |
| --- | --- |
| `schemaVersion` | exact supported contract version |
| `evidenceId` | unique stable identifier |
| `repository` | exact `owner/name` |
| `commitSha` | exact 40-character lowercase Git SHA |
| `sourceRef` | branch/ref/query identity |
| `event` | `pull_request`, `push`, `manual_read` or `local_reproduction` |
| `generatedAt` | UTC RFC 3339 timestamp |
| `sourceClass` | exact source enum |
| `tool` | product/tool identity |
| `toolVersion` | immutable version or digest |
| `apiVersion` | API version when applicable |
| `permissionsObserved` | exact permission/role evidence |
| `scope` | included/excluded languages, manifests, paths, profiles and dependency scopes |
| `availability` | exact availability state |
| `completeness` | pagination, selection and omission proof |
| `redaction` | no candidate Secret value in retained evidence |
| `retention` | Artifact class, ID, digest and expiry |
| `payloadDigest` | SHA-256 of canonical retained payload |
| `result` | bounded machine-readable source result |

Schema:

`docs/m6/m6-pr-e-security-evidence-envelope.schema.json`

## 3. Source classes

```text
GITHUB_REPOSITORY_POSTURE
GITHUB_PRIVATE_VULNERABILITY_REPORTING
GITHUB_REPOSITORY_ADVISORIES
GITHUB_CODE_SCANNING_ALERTS
GITHUB_CODE_SCANNING_ANALYSES
GITHUB_CODE_SCANNING_INSTANCES
GITHUB_SECRET_SCANNING_ALERTS
GITHUB_SECRET_SCANNING_LOCATIONS
GITHUB_SECRET_SCANNING_SCAN_HISTORY
GITHUB_DEPENDABOT_ALERTS
GITHUB_DEPENDENCY_GRAPH_SBOM
LOCAL_MAVEN_DEPENDENCY_GRAPH
LOCAL_MAVEN_PLUGIN_GRAPH
LOCAL_PNPM_DEPENDENCY_GRAPH
WORKFLOW_ACTION_INVENTORY
CODE_SCANNER_SARIF
SECRET_SCANNER_REDACTED_REPORT
APPLICABILITY_REACHABILITY_DECISION
SECURITY_EXCEPTION_REGISTER
```

## 4. Availability states

```text
AVAILABLE_COMPLETE
AVAILABLE_EMPTY
AVAILABLE_PARTIAL
DISABLED
INELIGIBLE
PERMISSION_DENIED
AUTHENTICATION_FAILED
RATE_LIMITED
TRANSIENT_FAILURE
EVIDENCE_UNAVAILABLE
```

`AVAILABLE_EMPTY` is a positive result. It requires successful execution, feature/eligibility proof, full pagination and retained raw output. All other non-complete states keep the relevant blocker open.

## 5. HTTP and source-result interpretation

| Result | Required disposition |
| --- | --- |
| `200` with complete pagination and valid schema | `AVAILABLE_COMPLETE` or `AVAILABLE_EMPTY` |
| `200` with missing pages, truncated payload or missing scope | `AVAILABLE_PARTIAL` |
| `202` for asynchronous SBOM generation | pending; not complete |
| `302` completed SBOM fetch | validate download, digest and payload before complete |
| `304` | valid only with retained prior payload, ETag and unchanged query/commit contract |
| `401` | `AUTHENTICATION_FAILED` |
| `403` | `PERMISSION_DENIED` unless the source gives a more specific disabled/ineligible reason |
| `404` | `EVIDENCE_UNAVAILABLE`; never infer empty |
| `429` or rate-limit exhaustion | `RATE_LIMITED` |
| `5xx`, network failure or malformed source response | `TRANSIENT_FAILURE` |
| source feature proved disabled | `DISABLED` |
| repository/source not eligible | `INELIGIBLE` |

## 6. GitHub source requirements

### 6.1 Code Scanning

Authoritative inventory requires both:

1. repository alert list with all pages and states;
2. analyses/instances proving the accepted commit/ref was analyzed.

Minimum permission evidence:

```text
Code scanning alerts: read
```

Required endpoint classes:

```text
GET /repos/{owner}/{repo}/code-scanning/alerts
GET /repos/{owner}/{repo}/code-scanning/analyses
GET /repos/{owner}/{repo}/code-scanning/alerts/{alert_number}/instances
GET /repos/{owner}/{repo}/code-scanning/default-setup
```

An empty alert list without accepted-commit analysis evidence is not `AVAILABLE_EMPTY` for exact-SHA acceptance.

### 6.2 Secret Scanning

Authoritative inventory requires:

1. all repository alerts and states;
2. all locations for each retained alert;
3. scan-history evidence where available;
4. feature/eligibility and administrator/read-permission evidence.

Minimum permission evidence:

```text
Secret scanning alerts: read
repository administrator/security role where required
```

Required endpoint classes:

```text
GET /repos/{owner}/{repo}/secret-scanning/alerts
GET /repos/{owner}/{repo}/secret-scanning/alerts/{alert_number}/locations
GET /repos/{owner}/{repo}/secret-scanning/scan-history
```

Retained output must redact Secret values. Location identity may be stored as a bounded path/ref/commit hash plus detector type; raw candidate values are prohibited.

### 6.3 Dependabot Security Alerts

Authoritative inventory requires all pages and all states, plus exact manifest/dependency path reconciliation against the accepted dependency graph.

Minimum permission evidence:

```text
Dependabot alerts: read
```

Required endpoint:

```text
GET /repos/{owner}/{repo}/dependabot/alerts
```

An update PR does not replace this endpoint and is not itself a vulnerability finding.

### 6.4 Private Vulnerability Reporting and Advisories

Private Vulnerability Reporting enablement is a posture value only. It does not prove that no private reports or advisories exist.

Required source classes:

```text
GET /repos/{owner}/{repo}/private-vulnerability-reporting
repository security advisory inventory with appropriate permission
```

### 6.5 GitHub Dependency Graph SBOM

The GitHub dependency-graph SBOM is an SPDX source and may be requested for public resources with read/no-auth access where supported.

Required endpoint:

```text
GET /repos/{owner}/{repo}/dependency-graph/sbom
```

A successful export must retain SPDX version, creation info, package identifiers, relationships and payload digest. It must be compared against local Maven, pnpm and Workflow Action inventories before completeness is claimed.

## 7. Local graph requirements

### 7.1 Maven

The Maven evidence must include:

- all reactor modules;
- imported BOMs;
- direct and transitive dependencies;
- runtime, compile, provided and test scope separation;
- build and report plugins;
- profiles used by accepted production/test builds;
- dependency paths for every finding;
- resolved versions, not only declared property values.

### 7.2 pnpm

The pnpm evidence must include:

- exact package manager and Node engine;
- root workspace configuration;
- all committed lockfiles;
- direct and transitive package identities;
- production/dev/optional/peer classification;
- bootstrap-generated upstream boundaries;
- explicit exclusion of mutable caches and untracked generated trees from first-party evidence.

### 7.3 GitHub Actions

Every `uses:` reference must record:

- repository/action path;
- declared reference;
- resolved immutable commit SHA;
- verified upstream repository identity;
- permission and Secret exposure;
- input source and whether untrusted PR content can influence it.

A major tag such as `@v4` is mutable and cannot be called immutable evidence.

## 8. Completeness proof

Every list source must retain:

```text
page_size
page_count
item_count
next_link_observed
terminal_page_observed
query_filters
sort_order
retrieved_at
```

Every deterministic local selection must retain:

```text
discovered_count
selected_count
unique_count
duplicate_count
missing_count
excluded_count
exclusion_reasons
```

## 9. Redaction contract

Prohibited retained content:

- raw Secret/candidate Secret;
- customer data;
- raw production Prompt/input/output;
- credential values;
- access tokens;
- full sensitive environment dumps.

Allowed Secret evidence:

```text
detector_type
redacted_location
commit_or_ref_hash
path_hash
line_range_or_location_hash
state
resolution
first_seen_at
last_seen_at
```

`redaction.candidateSecretsExcluded` must be `true` for every retained evidence envelope.

## 10. Finding dispositions

```text
APPLICABLE
NOT_APPLICABLE
UNREACHABLE
MITIGATED
ACCEPTED_WITH_EXPIRY
UNRESOLVED
EVIDENCE_UNAVAILABLE
```

Each disposition requires:

- exact finding identity;
- component and dependency path;
- runtime/build/test/client/server/Action scope;
- packaged/loaded/invoked/external-reachability evidence;
- exploit preconditions;
- mitigations;
- owner;
- decision time;
- reviewer;
- expiry where applicable.

## 11. Exceptions and suppressions

Every exception requires:

```text
exception_id
finding_id
exact_scope
rationale
owner
approver
created_at
expires_at
compensating_controls
revalidation_trigger
```

Prohibited:

- wildcard project-wide suppression without a bounded finding set;
- no-expiry exceptions;
- severity rewrite of upstream data;
- suppression solely to make CI pass;
- automatic renewal.

## 12. Retention and Artifact contract

PR-E must retain security evidence within the governed four-Artifact model, normally Hygiene or Maven. Each final Artifact record must include:

```text
artifact_id
artifact_name
bytes
sha256
head_sha
head_branch
run_id
run_number
expires_at
zip_integrity
```

Candidate Secrets must never be included in any Artifact.

## 13. Gate decision

```text
E0_EVIDENCE_SOURCE_CONTRACT_DEFINED
ALERT_INVENTORIES_NOT_YET_OBTAINED
DEPENDENCY_APPLICABILITY_NOT_YET_PROVEN
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
```
