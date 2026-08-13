# M6-PR-E E3-R1 — pgJDBC Security Remediation

Status: `E3_R1_PGJDBC_REMEDIATION_IMPLEMENTED_VALIDATION_PENDING`

This remediation is limited to the exact runtime component proven applicable by E3-I3. It does not upgrade Spring Boot, Flowable, Testcontainers, GitHub Actions, scanners, product authority, Flyway migrations, deployment or Production Promotion.

## Prior accepted evidence

- prior Head: `f7875de5c2de17ae9d8f923227e3a6898cd31ab0`
- prior E3-I3 canonical SHA-256: `6d97f57e9ee778e9bce3ec0790714b0f113e1640dadb90f309828ac04cb9194f`
- prior E2 graph digest: `0b6868f057a72fb8c7a4c9d0529f4469381f0024f403873c6d92121e4b34ee0a`
- prior applicable finding: `CVE-2026-42198 / GHSA-98qh-xjc8-98pq`
- sibling unresolved pgJDBC finding: `CVE-2026-54291 / GHSA-j92g-9f8w-j867`

Historical evidence remains in the immutable E3-I3 commit and Run #1414. Remediation must not rewrite that history.

## Version decision

The repository imported Spring Boot dependency management without inheriting the Boot parent. The root `pom.xml` therefore adds one explicit `dependencyManagement` override before the imported Spring Boot BOM:

```text
org.postgresql:postgresql 42.7.9 -> 42.7.13
```

`42.7.13` is the current pgJDBC 42.7.x patch selected for this remediation. It contains the `42.7.11` SCRAM PBKDF2 iteration-limit fix for `CVE-2026-42198`, the `42.7.12` channel-binding enforcement fix for `CVE-2026-54291`, and the subsequent 42.7.13 SCRAM fail-closed maintenance fix.

No other managed dependency is intentionally upgraded.

## Deterministic graph transition

Expected exact resolved changes are restricted to:

```text
org.postgresql:postgresql       42.7.9  -> 42.7.13
org.checkerframework:checker-qual 3.52.0 -> 3.55.1
```

Projected E2 graph digest:

```text
2cc0000745441ebb70b7dd9ad6b17e5c9d6e27981ea213c7005c9bed3e09df94
```

The existing E4 scanner graph-drift guard remains enabled. Natural CI must independently resolve the Maven graph and match this digest exactly. A different graph is a validation failure, not an accepted approximation.

## Scanner/remediation semantics

The two historical pgJDBC findings are expected to disappear from the current OSV inventory after the fixed component is resolved. Absence is accepted only when all of the following are true:

1. current E2 graph digest equals the exact target digest;
2. current E4 OSV scanner completes successfully;
3. neither historical GHSA/CVE identity is present in current normalized OSV findings;
4. the remediation plan retains the prior finding IDs and prior dispositions;
5. no suppression, severity downgrade or exception is introduced.

A removed scanner finding is not deleted from history. It becomes a retained remediation transition linked to the prior accepted E3-I3 evidence.

## Permanent invariants

```text
REMEDIATION != HISTORY_REWRITE
SCANNER_FINDING_ABSENCE_REQUIRES_FIXED_GRAPH_EVIDENCE
DEPENDENCY_OVERRIDE != SPRING_BOOT_UPGRADE
NO_SUPPRESSION
NO_SEVERITY_DOWNGRADE
NO_EXCEPTION
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
M6_PR_E_E3_CLOSURE_NOT_ACCEPTED
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
AI_IS_NOT_AN_OPERATOR
```
