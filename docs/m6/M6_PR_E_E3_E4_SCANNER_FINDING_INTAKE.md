# M6-PR-E E3 — E4 Scanner Finding Intake

Status: `E3_E4_SCANNER_FINDING_INTAKE_ENABLED_TRIAGE_PENDING`

This record advances the already accepted E3 applicability/reachability framework without rewriting its historical baseline. The original E3 framework Head remains `28b7cca970aba1fcaab05aceb200a2987042d973`; E4 scanner evidence is accepted at exact Head `6b6bf9f6de806d78826fa7b4ed346d1b615edd81`, canonical SHA-256 `61decb6d66ffbcc61b880779e46fd228739d74767187f9d0ab6ede83ca21b9a6`, natural Run `31456791476 / #1405`, 9/9 physical Jobs successful.

The accepted E4 snapshot contains exactly:

```text
OSV:      117
Gitleaks:  27
zizmor:    61
Semgrep:    3
Total:    208
```

The authoritative GitHub Code Scanning, Secret Scanning and Dependabot Security Alert inventories remain `EVIDENCE_UNAVAILABLE`; E4 scanner completeness therefore does not close PRB-16.

## Intake invariants

```text
E4_SCANNER_INPUT_AVAILABLE
E4_SCANNER_COVERAGE_COMPLETE
SCANNER_COVERAGE_COMPLETE != AUTHORITATIVE_INVENTORY_COMPLETE
SCANNER_FINDING != E3_DISPOSITION
SCANNER_SEVERITY_LABEL != NORMALIZED_SEVERITY_BAND
VERSION_MATCH != APPLICABLE
NO_CALLSITE_MATCH != UNREACHABLE
RAW_SECRET_REPORT_MUST_NOT_BE_RECONSTRUCTED
ALL_SCANNER_FINDINGS_INITIAL_DISPOSITION_UNRESOLVED
E3_SCANNER_FINDINGS_UNRESOLVED
```

The machine intake consumes only the normalized E4 canonical payload. It never reads raw Gitleaks candidate Secret material and never reconstructs raw scanner reports.

For every E4 finding the intake must:

1. preserve exact `findingId` and `sourceClass`;
2. preserve raw upstream severity when supplied, or record an explicit scanner-no-severity marker;
3. use normalized `severityBand = UNKNOWN` until evidence-backed review;
4. enter the existing closed E3 disposition set as `UNRESOLVED`;
5. retain exact source identity and current-tree or historical location metadata;
6. attach logical owner and reviewer roles from `M6_PR_E_SECURITY_OWNERSHIP_AND_REMEDIATION_MATRIX.md` without inventing GitHub identities;
7. retain four explicit reachability dimensions (`packaged`, `loaded`, `invoked`, `externallyReachable`) with `value = null` and evidence explaining why scanner presence cannot answer them;
8. retain zero automatic mitigations and zero automatic exceptions;
9. cover every E4 finding exactly once with duplicate identities rejected.

OSV intake preserves the exact E2 component reference and explicitly marks the root-to-component dependency path incomplete until E3 graph-path review. Gitleaks intake retains only rule, commit, path, line and fingerprint metadata; it never retains candidate Secret values. zizmor intake is tagged with `WORKFLOW_SUPPLY_CHAIN` impact for release gating without declaring exploitability. Semgrep intake preserves exact current-tree rule/path/location without source snippets.

Logical roles remain identities-pending:

```text
E4_OSV_SCANNER -> Java Dependency Owner / Application Security Reviewer
E4_GITLEAKS    -> Security Triage Owner / Application Security Reviewer
E4_ZIZMOR      -> Workflow Supply-Chain Owner / Application Security Reviewer
E4_SEMGREP     -> Application Security Reviewer / Security Triage Owner
```

Because Gitleaks and zizmor map to existing high-risk `SECRET` and `WORKFLOW_SUPPLY_CHAIN` categories, their unresolved findings are release-blocking. Other scanner findings remain unresolved until severity, path, deployment and reachability evidence is reviewed; no unknown severity is silently downgraded.

## Current gate

This stage makes the scanner finding inventory machine-actionable but does not accept E3 closure, PRB-16, PRB-17, Ready or Merge.

```text
M6_PR_E_E3_APPLICABILITY_FRAMEWORK_DEFINED
M6_PR_E_E3_SCANNER_FINDING_INTAKE_ENABLED
M6_PR_E_E3_FINDING_TRIAGE_REQUIRED
M6_PR_E_E3_CLOSURE_NOT_ACCEPTED
M6_PR_E_E4_SCANNER_EVIDENCE_ACCEPTED
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
ISSUE_97_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
AI_IS_NOT_AN_OPERATOR
```
