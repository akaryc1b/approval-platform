# M6-PR-E E4 — MySQL 8.4 P3-H5 Dependency Graph Rebaseline

## Scope

This record closes only the security-evidence compatibility gap exposed when the long-lived MySQL 8.4 compatibility branch was rebased onto the accepted M6-PR-E scanner infrastructure.

It does **not** change H5 D3/D4 product behavior, does not disposition a scanner finding, does not authorize MySQL production support, and does not authorize Ready, merge, deployment, rollout or Production Promotion.

```text
DEPENDENCY_GRAPH_CHANGE != VULNERABILITY_DISPOSITION
HISTORICAL_REMEDIATION != HISTORY_REWRITE
CURRENT_OSV_REVALIDATION_REQUIRED
SCANNER_MUST_EXECUTE_AT_TARGET_GRAPH
NO_SUPPRESSION
NO_SEVERITY_DOWNGRADE
NO_EXCEPTION
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```

## Triggering evidence

Run B for PR #92 executed the exact current H5 dependency graph and failed only at the E4 pre-scan graph guard.

```text
Run:          31696972899 / #1448
Head:         de04fd43e62e00ef5ffa77336f54e698d99bc4f0
Base:         a3b3bdec13edbdc20dfbdd316f025f22861b9697
E2 content:   69b574782e0f105d17f3f25fe76b0f752c858028a44c9298339f68104751e10c

prior accepted graph:
2cc0000745441ebb70b7dd9ad6b17e5c9d6e27981ea213c7005c9bed3e09df94

current H5 graph:
e4cffa00582d61a62f5c41548f8da4b8bfb28dd50b7db3aa5d1aa42cd503ddfd

classification:
SECURITY_BASELINE_STALENESS_LONG_LIVED_BRANCH_DEPENDENCY_GRAPH_DRIFT
```

The scanner, scanner test and historical E4 baseline were byte-identical to current `main`; the graph changed because PR #92 intentionally adds the MySQL compatibility dependency surface.

## Exact accepted projection delta

Independent reconstruction from the final accepted PR #98 Hygiene artifact and PR #92 Run B Hygiene artifact proves:

```text
reactor projects:                 26 -> 26
Maven components:                202 -> 205
Maven edges:                     306 -> 311
reactor roots changed:                 false
imported BOMs changed:                 false
resolved plugins changed:              false
plugin-resolution SHA changed:         false
pnpm graph changed:                    false
accepted GitHub Actions graph changed: false
limitations changed:                   false
```

Exactly three Maven components are added:

```text
pkg:maven/com.mysql/mysql-connector-j@9.5.0?type=jar
pkg:maven/org.flywaydb/flyway-mysql@11.14.1?type=jar
pkg:maven/org.testcontainers/testcontainers-mysql@2.0.3?type=jar
```

No component is removed or changed.

Exactly six dependency edges are added:

```text
approval-integration-jdbc -> spring-tx
approval-persistence-jdbc -> mysql-connector-j
approval-persistence-jdbc -> flyway-mysql
approval-persistence-jdbc -> testcontainers-mysql
approval-server -> mysql-connector-j
approval-server -> testcontainers-mysql
```

Exactly one resolved dependency-tree edge is removed:

```text
spring-jdbc -> spring-tx
```

This edge-topology change does not remove the `spring-tx` component; `spring-tx` remains present and is directly resolved from `approval-integration-jdbc`.

## Compatibility rule

The accepted E4 baseline remains historically frozen at:

```text
2cc0000745441ebb70b7dd9ad6b17e5c9d6e27981ea213c7005c9bed3e09df94
```

The additive compatibility layer permits exactly one later graph target:

```text
MYSQL_8_4_P3_H5_DEPENDENCY_GRAPH_REBASELINE
2cc0000745441ebb70b7dd9ad6b17e5c9d6e27981ea213c7005c9bed3e09df94
->
e4cffa00582d61a62f5c41548f8da4b8bfb28dd50b7db3aa5d1aa42cd503ddfd
```

Canonical transition policy SHA-256:

```text
84bb310e52f540128d0dea0d6e7a3779054aa68021ae6c9138520d7fb6fa1d41
```

Any other dependency graph remains fail-closed.

## Historical pgJDBC remediation

E3-R1 remains immutable:

```text
0b6868f057a72fb8c7a4c9d0529f4469381f0024f403873c6d92121e4b34ee0a
->
2cc0000745441ebb70b7dd9ad6b17e5c9d6e27981ea213c7005c9bed3e09df94
```

The H5 graph transition does not rewrite that history. Carry-forward is permitted only when the current E4 execution completes OSV at the target graph and again proves the two historical pgJDBC findings absent.

The historical identifiers remain:

```text
GHSA-98qh-xjc8-98pq / CVE-2026-42198
GHSA-j92g-9f8w-j867 / CVE-2026-54291
```

If either finding returns, remediation verification fails closed.

## Acceptance boundary

This rebaseline authorizes only execution of the existing full E4 scanner against the exact H5 graph. Scanner output remains triage input, not a disposition.

```text
M6_PR_E_E4_H5_GRAPH_REBASELINE_STAGED
E4_FULL_SCANNER_TARGET_GRAPH_AUTHORIZED
CURRENT_FINDINGS_NOT_YET_REVIEWED
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
PR_92_REMAINS_DRAFT
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```
