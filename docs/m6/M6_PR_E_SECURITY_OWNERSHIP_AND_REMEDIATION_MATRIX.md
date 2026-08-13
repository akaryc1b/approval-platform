# M6-PR-E Security Ownership and Remediation Matrix

Status: `E0_LOGICAL_OWNERSHIP_DEFINED_GITHUB_IDENTITIES_PENDING`

## 1. Ownership rule

E0 defines logical roles only. It does not invent a GitHub user or team. The repository must bind each required role to a verified GitHub identity before E6 Ready.

Current exact state:

```text
CODEOWNERS_NOT_PROVEN
SECURITY_ROLE_IDENTITIES_PENDING
```

## 2. Role matrix

| Role | Required responsibility | Minimum authority | Current GitHub identity |
| --- | --- | --- | --- |
| Repository Administrator | security-feature posture, API access and branch/ruleset settings | admin | `UNASSIGNED_GITHUB_IDENTITY` |
| Security Triage Owner | alert review, Secret-safe handling and severity validation | security/admin or equivalent | `UNASSIGNED_GITHUB_IDENTITY` |
| Java Dependency Owner | Maven graph, BOM/plugin scope and Java applicability | write/triage | `UNASSIGNED_GITHUB_IDENTITY` |
| JavaScript/TypeScript Dependency Owner | pnpm graph, client/build scope and package applicability | write/triage | `UNASSIGNED_GITHUB_IDENTITY` |
| Workflow Supply-Chain Owner | Action identity, permissions, pinning and Artifact integrity | write/triage | `UNASSIGNED_GITHUB_IDENTITY` |
| Application Security Reviewer | code finding reachability and exploitability review | review | `UNASSIGNED_GITHUB_IDENTITY` |
| Operations/SRE Reviewer | deployment-mode exposure, mitigation and incident readiness | review | `UNASSIGNED_GITHUB_IDENTITY` |
| Release Decision Owner | final blocker and exception acceptance | admin/maintain | `UNASSIGNED_GITHUB_IDENTITY` |

One person may hold multiple roles, but the evidence must disclose that fact. High-risk exception approval must not be silently self-approved when a distinct reviewer is required by the repository's governance.

## 3. Evidence RACI

| Evidence class | Responsible | Accountable | Consulted | Informed |
| --- | --- | --- | --- | --- |
| Code Scanning inventory | Security Triage Owner | Repository Administrator | Application Security Reviewer | Release Decision Owner |
| Secret Scanning inventory | Security Triage Owner | Repository Administrator | Workflow Supply-Chain Owner | Release Decision Owner |
| Dependabot Security Alerts | Security Triage Owner | Release Decision Owner | Java and JS/TS Dependency Owners | Repository Administrator |
| GitHub SBOM | Workflow Supply-Chain Owner | Release Decision Owner | Java and JS/TS Dependency Owners | Security Triage Owner |
| Maven graph | Java Dependency Owner | Release Decision Owner | Application Security Reviewer | Security Triage Owner |
| pnpm graph | JavaScript/TypeScript Dependency Owner | Release Decision Owner | Application Security Reviewer | Security Triage Owner |
| Action inventory | Workflow Supply-Chain Owner | Repository Administrator | Security Triage Owner | Release Decision Owner |
| Applicability/reachability | owning ecosystem lead | Application Security Reviewer | Operations/SRE Reviewer | Release Decision Owner |
| Exception register | finding owner | Release Decision Owner | Security Triage Owner | Repository Administrator |
| Post-main evidence | Workflow Supply-Chain Owner | Release Decision Owner | Security Triage Owner | Issue #97 stakeholders |

## 4. Remediation priority

| Priority | Finding rule | Gate behavior |
| --- | --- | --- |
| `P0` | credential/Secret exposure, RCE, auth/tenant bypass, workflow token compromise, evidence tampering | immediate release block; no exception without explicit emergency governance |
| `P1` | Critical/High applicable or credibly reachable vulnerability | release block |
| `P2` | Medium with externally reachable, cross-tenant, integrity or durable-evidence impact | release block until disposition or approved expiry |
| `P3` | Low/informational or build/test-only finding | triage required; may proceed only with evidence-backed disposition |
| `PX` | evidence source unavailable or incomplete | relevant blocker remains open; never treated as clean |

## 5. Required remediation record

Every finding must have:

```text
finding_id
source_class
component
version
manifest_or_path
dependency_path
scope
severity
upstream_advisory
applicability
reachability
owner
remediation
validation
reviewer
state
created_at
updated_at
expiry
```

## 6. Exception acceptance

`ACCEPTED_WITH_EXPIRY` requires:

- an exact finding, component and affected deployment scope;
- explicit business justification;
- no credible tenant, authorization, Secret, RCE, injection, deserialization, SSRF, workflow-supply-chain or evidence-integrity bypass left unmitigated;
- compensating controls;
- accountable owner;
- independent approval where required;
- fixed expiry;
- revalidation trigger on dependency, configuration, runtime or exposure change.

Expiry cannot be omitted or auto-renewed.

## 7. CODEOWNERS plan

Before E6 Ready, the workstream must either:

1. add a reviewed `CODEOWNERS` file on the PR base branch with valid users/teams and required review policy; or
2. retain an explicit approved limitation showing equivalent deterministic ownership/review enforcement.

A `CODEOWNERS` file on only the feature branch does not protect unrelated PRs until merged to the base branch. Draft PRs do not automatically request code-owner review; the Ready transition is the enforcement point.

Recommended ownership scopes to decide in E1/E2:

```text
.github/workflows/**
.github/dependabot.yml
SECURITY.md
docs/m6/M6_PR_E_*
pom.xml
**/pom.xml
package.json
pnpm-lock.yaml
pnpm-workspace.yaml
scripts/ci/**
scripts/tests/m6-pr-e-*
```

This is a plan, not an identity assignment.

## 8. Gate state

```text
LOGICAL_SECURITY_OWNERSHIP_DEFINED
GITHUB_OWNER_IDENTITIES_PENDING
CODEOWNERS_ENFORCEMENT_PENDING
NO_EXCEPTION_ACCEPTED_IN_E0
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
```
