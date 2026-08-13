# M6-PR-E E3-I3 — OSV Runtime and Deployment Precondition Audit

Status: `E3_I3_OSV_RUNTIME_DEPLOYMENT_AUDIT_REVIEWED_CLOSURE_PENDING`

This evidence-only batch reviews exactly four E4 OSV findings against the accepted E2 graph, executable-server dependency declarations, repository configuration and bounded runtime evidence. It performs no dependency upgrade, scanner/rule change, suppression, severity downgrade, Workflow change, Flyway migration, product authority, deployment or Production Promotion.

## Permanent review rules

```text
VERSION_MATCH != APPLICABLE
RUNTIME_DEPENDENCY != AUTOMATICALLY_APPLICABLE
DEFAULT_CONFIG != ALL_SUPPORTED_DEPLOYMENTS
NOT_APPLICABLE_REQUIRES_POSITIVE_ABSENT_PRECONDITION_EVIDENCE
APPLICABLE_REQUIRES_POSITIVE_RUNTIME_AND_EXTERNAL_INPUT_EVIDENCE
UPSTREAM_SEVERITY_IS_IMMUTABLE_INPUT
NO_SUPPRESSION
NO_SEVERITY_DOWNGRADE
NO_EXCEPTION
NO_DEPENDENCY_UPGRADE
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
```

## Exact evidence basis

- review basis Head: `448e0fe2808a3d2f02d8ee0deb16480a4ed46494`
- accepted E2 graph digest: `0b6868f057a72fb8c7a4c9d0529f4469381f0024f403873c6d92121e4b34ee0a`
- `apps/server/pom.xml` blob: `89026f9c844b27a11a65c478a3d7abb57c7c053f`
- `apps/server/src/main/resources/application.yml` blob: `b8fdf7350881239c2c1c1091ac8e373bf285c2ef`
- `PostgreSqlContainerTest.java` blob: `cc944cd84410f894fb9fcf363613115c2b797509`

The E2 graph proves the executable-server root-to-component paths used below. GitHub Actions must re-hash all three repository evidence files and must retain the exact E2 graph digest before applying these decisions.

## Reviewed findings

### CVE-2026-40976 / GHSA-8v8j-3hxp-93wr — `NOT_APPLICABLE`

Spring's official advisory requires all of the following, including the absence of `spring-boot-health`. The exact E2 graph contains both:

- `approval-server -> spring-boot-starter-actuator@4.0.2 -> spring-boot-actuator-autoconfigure@4.0.2`
- `approval-server -> spring-boot-starter-actuator@4.0.2 -> spring-boot-health@4.0.2`

Therefore the mandatory `SPRING_BOOT_HEALTH_ABSENT` precondition is positively false for this exact build graph. No severity mutation or suppression is used.

### CVE-2026-42198 / GHSA-98qh-xjc8-98pq — `APPLICABLE`

`org.postgresql:postgresql@42.7.9` is a direct runtime dependency of `approval-server`. The repository's default datasource is PostgreSQL, and the exact server module contains a Testcontainers test that calls `DriverManager.getConnection(...)` against PostgreSQL 16 and executes `select 1`.

pgjdbc's 42.7.11 security release states that affected clients accept a server-provided SCRAM PBKDF2 iteration count without an upper bound and that a malicious or compromised PostgreSQL server can consume unbounded client CPU. The current 42.7.9 version predates the `scramMaxIterations` fix. This finding therefore receives an `APPLICABLE` disposition and remains a release-blocking remediation item. E3-I3 does not perform the upgrade.

### CVE-2026-54291 / GHSA-j92g-9f8w-j867 — remains `UNRESOLVED`

The same pgjdbc 42.7.9 runtime path is packaged, loaded and invoked. pgjdbc states that only `channelBinding=require` connections are affected; default `prefer` and `allow`/`disable` behaviour are not.

The repository fallback JDBC URL does not request `channelBinding=require` and repository search found no `channelBinding` setting. However `APPROVAL_DB_URL` is externally configurable, so repository evidence cannot prove that every supported deployment excludes the vulnerable configuration. The finding therefore remains fail-closed `UNRESOLVED`.

### CVE-2026-41293 / GHSA-r29c-68gh-xp6x — remains `UNRESOLVED`

The exact E2 graph packages `tomcat-embed-core@11.0.15` through `spring-boot-starter-web`. Apache Tomcat identifies the vulnerable path as HTTP/2 request-header validation. Spring Boot documents `server.http2.enabled=false` by default, the repository application configuration does not enable it, and repository search found no HTTP/2 customizer.

That is sufficient to record the default deployment precondition as absent, but not sufficient to classify every supported deployment because Spring external configuration can enable HTTP/2. The finding remains `UNRESOLVED`.

## Expected cumulative E3 state

Applying E3-I3 after the accepted E3-I2 overlay must produce:

```text
total findings: 208
cumulative reviewed findings: 7
NOT_APPLICABLE: 3
APPLICABLE: 1
UNRESOLVED: 204
releaseBlocked: true
```

The `APPLICABLE` pgjdbc finding requires remediation or a separately governed disposition before E3 closure. The two conditional findings above and all other unreviewed findings remain unresolved. Authoritative GitHub alert inventories remain unavailable.

```text
M6_PR_E_E3_I3_OSV_RUNTIME_DEPLOYMENT_AUDIT_REVIEWED
M6_PR_E_E3_FINDING_TRIAGE_REQUIRED
M6_PR_E_E3_CLOSURE_NOT_ACCEPTED
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
```
