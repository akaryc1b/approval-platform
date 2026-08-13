# M6-PR-E / E3-R2B — Run 1435 Controlled Correction

## Scope

This append-only record retains the third natural E3-R2B implementation failure and defines the next correction boundary. It changes no Workflow, Dependabot policy, production Java or TypeScript, Flyway migration, dependency version, database workstream, deployment, release, or production authority.

The governed R2B remediation batch remains exactly:

```text
43  zizmor/unpinned-uses
14  zizmor/artipacked
 1  zizmor/template-injection
---
58  total
```

## Retained natural Run

```text
Run ID:          31591546881
Run number:      1435
Head:            b22a98aa0c435a479e7011089f5b2f6ca386af5c
Event:           pull_request / synchronize
Run attempt:     1
Conclusion:      failure
Physical Jobs:   9
Success:         8
Failure:         Repository hygiene
Failed Job ID:   94097445137
Same-Head reruns: 0
```

The following Jobs succeeded:

```text
Java 21 / Maven core
Persistence JDBC / shard 0
Persistence JDBC / shard 1
Persistence JDBC / shard 2
Persistence JDBC / shard 3
Java 21 / Maven / PostgreSQL
Vben TypeScript / production build
UniApp TypeScript / H5 / WeChat
```

The permanent M6 Node aggregate completed:

```text
258 tests
257 pass
1 fail
```

## Exact failure classification

```text
EVIDENCE_BUG
SEMGREP_LOCATION_DERIVED_FINDING_IDENTITY_DRIFT
```

The failing assertion was:

```text
reviewed finding absent from intake
E4_SEMGREP:62035cd7f4e3f9b84870d80ef358bf05071fdbcb32652b6db96073e3d0f6f3bb
```

The accepted E3-I2 review binds the historical Semgrep finding to:

```text
rule:
  rules.javascript.lang.security.audit.detect-non-literal-regexp
path:
  scripts/security/m6-pr-e-e2-generate-sbom.mjs
historical source blob:
  b57586084a14101613231fc5ba4a999cbeac1b08
historical location:
  9:394-9:437
historical finding ID:
  62035cd7f4e3f9b84870d80ef358bf05071fdbcb32652b6db96073e3d0f6f3bb
disposition:
  NOT_APPLICABLE
review evidence:
  ATTACKER_CONTROLLED_REGEXP_PATTERN_ABSENT
```

R2B evidence-generator refactoring retained the exact reviewed expression but moved it to a new source line:

```text
current source blob:
  f2c5861a2c952b2665c59a0f1e788111eb0db105
current location:
  73:394-73:437
current finding ID:
  3d0897409d6b051f33f283afea8e0760c95336b44c58622cf938c3a43b14267f
```

Semgrep finding IDs are derived from scanner, rule, scanner path, start line, and start column. The line relocation therefore changed the machine identity even though the rule, path, expression, exploit-precondition analysis, and disposition did not change.

This correction does not rewrite the historical E3-I2 review and does not silently treat the finding as removed. It adds one explicit, machine-verifiable identity transition that binds both historical and current identities, both source blobs, both locations, the exact source expression, and the retained `NOT_APPLICABLE` review evidence.

## Retained failed-Run Artifacts

All four ZIPs were independently downloaded, matched GitHub byte size and SHA-256 metadata, and passed ZIP integrity verification. They remain failure evidence and are not Acceptance evidence.

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `9139390772` | `358434` | `f2f4442418adf68d6dc4dc7066dbf287ed361bf4ad45a314cfac8e1c2fa34a43` |
| Vben | `9139362233` | `18872` | `a5a52b2e3efc3561865fc7d0a70f52978f1ada242d4c09d3611fadd9da44180a` |
| Mobile | `9139342680` | `9792` | `0bc1fa16f61910b882cb4b6dfb15416aa7cdf1da4077a460cdbe2b16c3e44222` |
| Hygiene | `9139420190` | `33902` | `54dc94acb51fd6747cdb3c2d560e8611c261b1e735d1467f9c9ad3404c4071a4` |

## Correction invariants

```text
HISTORICAL_I2_REVIEW_RETAINED
CURRENT_SEMGREP_IDENTITY_RETAINED
EXACT_RULE_PATH_EXPRESSION_AND_BLOB_BINDING
NO_FINDING_DELETION_CLAIM
NO_SUPPRESSION
NO_IGNORE
NO_SEVERITY_DOWNGRADE
NO_EXCEPTION
NO_BROAD_PATH_EXCLUSION
NO_SAME_HEAD_RERUN
NO_EMPTY_TRIGGER_COMMIT
NO_WORKFLOW_DISPATCH
NO_AMEND
NO_REBASE
NO_FORCE_PUSH
FAILED_RUN_AND_ARTIFACTS_RETAINED
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_RELEASE
NO_PRODUCTION_PROMOTION
```

Until a new exact correction Head completes all nine Jobs, one scanner execution, zero current Zizmor findings, exact selected-test coverage, and four exact-Head Artifacts, the Gate remains:

```text
M6_PR_E_E3_R2B_WORKFLOW_SUPPLY_CHAIN_REMEDIATION_PENDING
M6_PR_E_E3_FINDING_TRIAGE_REQUIRED
M6_PR_E_E3_CLOSURE_NOT_ACCEPTED
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
ISSUE_97_REMAINS_OPEN
PR_98_REMAINS_OPEN_DRAFT_UNMERGED
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
AI_IS_NOT_AN_OPERATOR
```
