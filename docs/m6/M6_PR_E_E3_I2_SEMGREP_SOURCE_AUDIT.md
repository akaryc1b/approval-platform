# M6-PR-E E3-I2 — Semgrep Source Audit

Status: `E3_I2_SEMGREP_SOURCE_AUDIT_REVIEWED_CLOSURE_PENDING`

This evidence batch reviews exactly the three E4 Semgrep current-tree findings and does not modify product source, scanner rules, severity, suppressions, dependencies, Workflow, Flyway, deployment or production authority.

## Exact source binding

- E3-I1 Head: `234ed3d41b049ca9475e58e29e1204587a992693`
- E3-I1 canonical intake SHA-256: `9f0f5b9ecd4604492a1b250cae3b8d21bcd658efd4d9646b46aea1250436fbec`
- Semgrep finding count: `3`

## Reviewed decisions

1. `62035cd7f4e3f9b84870d80ef358bf05071fdbcb32652b6db96073e3d0f6f3bb` — `NOT_APPLICABLE`.
   The dynamic RegExp pattern is constructed only from five fixed local XML tag literals (`type`, `scope`, `groupId`, `artifactId`, `version`). Attacker-controlled repository/POM values become matched text, not the regular-expression pattern.

2. `d90e0567afb2502b6f6d8b34a30fbc4ec211b835a95e101200548b94f9425bd7` — `NOT_APPLICABLE`.
   The plain socket performs TCP connection only and is immediately wrapped in an `SSLSocket`; HTTPS hostname identification, SNI, TLS 1.2/1.3 restriction and the TLS handshake complete before request or API-key bytes are written. The returned secure channel stores and writes through the `SSLSocket`.

3. `e6b83b7719d2bef5092127dd47f2a34c17d9c92ce3b9581b4381dd6b25432445` — remains `UNRESOLVED`.
   `applyChange` still performs dynamic object-key writes without explicit rejection of `__proto__`, `constructor` or `prototype`. The server's fixed `ApprovalDefinition` record model reduces normal API provenance risk, but client-local merge input provenance has not been proven closed. No suppression is permitted.

## Invariants

```text
SOURCE_MATCH != APPLICABLE
FALSE_POSITIVE_REQUIRES_POSITIVE_ABSENT_PRECONDITION_EVIDENCE
TYPESCRIPT_TYPE != RUNTIME_INPUT_VALIDATION
SERVER_FIXED_RECORD != PROOF_OF_ALL_CLIENT_LOCAL_PROVENANCE
UNRESOLVED_PROTOTYPE_POLLUTION_REMAINS_OPEN
NO_SUPPRESSION
NO_SEVERITY_DOWNGRADE
NO_EXCEPTION
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
```

After applying this reviewed overlay to the accepted 208-finding intake, exactly two findings advance from `UNRESOLVED` to `NOT_APPLICABLE`; the prototype-pollution finding and all 205 other findings remain unresolved. E3 and PRB-17 remain open.

```text
M6_PR_E_E3_I2_SEMGREP_SOURCE_AUDIT_REVIEWED
M6_PR_E_E3_FINDING_TRIAGE_REQUIRED
M6_PR_E_E3_CLOSURE_NOT_ACCEPTED
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
```
