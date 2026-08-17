# M6-PR-E E3-R3A — OSV Drift Applicability and Maven Plugin Provenance

## 1. Gate identity and frozen scope

This bounded gate reviews only the two High-severity OSV identities added after the accepted R2A scanner baseline:

- `GHSA-x4m4-345f-5h5g` / `CVE-2026-34487` / `org.apache.tomcat.embed:tomcat-embed-core:11.0.15`;
- `GHSA-hf6x-8p5f-cgmf` / `CVE-2026-54399` / `org.apache.httpcomponents.core5:httpcore5:5.3.6`.

Exact source baseline:

```text
repository: akaryc1b/approval-platform
source main: 779c4fbd09dcf17d45cc523e725222797cc5cb85
source main Run: 31697747890 / #1449 / push / success
source E2 canonical SHA-256:
ec0d711d233aebc7c552fce27ba489ff698e0cc962493094d0518c10048db6d3
review contract SHA-256:
aee05d7ad0452b2f3a29212afa367b96aff73fea1f9e5fd307dc1f236d440f6b
```

This gate does not upgrade a dependency, suppress a scanner finding, add an exclusion, downgrade severity, delete a finding identity, close a blocker, authorize Ready or Merge, deploy, or perform Production Promotion.

## 2. Evidence contract

Machine-readable contract:

`docs/m6/m6-pr-e-e3-r3a-osv-drift-review.json`

Executable reviewer:

`scripts/security/m6-pr-e-e3-r3a-review-osv-drift.mjs`

Permanent boundary:

`scripts/tests/m6-pr-e-e3-r3a-osv-drift-applicability-boundary.test.mjs`

The executable reviewer obtains the exact workflow Head, reconstructs the Maven runtime graph, reconstructs Maven plugin ownership from `resolve-plugins`, inspects the resolved Tomcat JAR, and scans tracked first-party production source/configuration. The emitted evidence is canonical and SHA-256 bound to the exact Head.

## 3. Tomcat cloud-membership finding

### 3.1 Upstream condition

The advisory concerns the Tomcat cloud-membership clustering component exposing a Kubernetes bearer token through logging. The affected source path belongs to the Tomcat Tribes cloud-membership implementation.

The scanner maps the advisory to `tomcat-embed-core:11.0.15`, so package presence alone is insufficient for either a positive or negative applicability decision.

### 3.2 Required positive evidence

R3A permits `NOT_APPLICABLE` only when the exact current graph proves all of the following:

1. `approval-server` resolves exactly one `tomcat-embed-core:11.0.15` runtime component;
2. the executable runtime graph contains no `org.apache.tomcat:tomcat-tribes`;
3. the resolved `tomcat-embed-core-11.0.15.jar` contains no entry under `org/apache/catalina/tribes/membership/cloud/`;
4. tracked first-party production source and configuration contain no Tomcat Tribes package, cloud-membership provider, token stream provider, cluster service, or `tomcat-tribes` activation marker.

A missing call-site search by itself is not sufficient. The dependency graph, JAR-entry boundary and first-party source/configuration boundary must all agree.

### 3.3 Disposition

When those exact conditions pass, the current deployment graph receives:

```text
GHSA-x4m4-345f-5h5g = NOT_APPLICABLE
reason = VULNERABLE_CLOUD_MEMBERSHIP_CODE_NOT_PACKAGED_OR_CONFIGURED
```

This does not claim that `11.0.15` is a fixed Tomcat version. Revalidation is mandatory if the Tomcat version changes, `tomcat-tribes` is introduced, clustering/cloud membership is configured, or a related provider class is added.

## 4. HttpComponents Core build-plugin finding

### 4.1 Upstream condition

The advisory concerns unbounded HTTP/1 header parsing that can cause memory-exhaustion denial of service when an attacker controls an HTTP message with excessive header count or length.

The component is not in the executable application runtime graph; it is present in Maven's resolved build-plugin graph.

### 4.2 Required plugin provenance

The R3A reviewer parses Maven `resolve-plugins` output as owner-to-resolved-component evidence and requires the exact owner set for `httpcore5:5.3.6`.

Expected owner:

```text
org.springframework.boot:spring-boot-maven-plugin:4.0.2
```

The same plugin resolution set must also contain:

```text
org.springframework.boot:spring-boot-buildpack-platform:4.0.2
org.apache.httpcomponents.client5:httpclient5:5.5.2
org.apache.httpcomponents.core5:httpcore5-h2:5.3.6
```

This is exact Maven plugin ownership evidence. It is not represented as an invented direct dependency edge when Maven provides only the resolved plugin component set.

### 4.3 Disposition

Build-plugin scope alone does not prove the vulnerable parser unreachable. Maven plugins can perform network operations while resolving or executing build behavior, and an attacker-controlled or compromised repository/mirror response has not been positively excluded by the current evidence.

Therefore:

```text
GHSA-hf6x-8p5f-cgmf = UNRESOLVED
reason = BUILD_PLUGIN_HTTP1_PARSE_PATH_REQUIRES_SEPARATE_REMEDIATION
```

A separate remediation gate must either move the resolved component to a fixed version or provide independently reviewed positive evidence that closes the exact build-network path. This gate adds no exception and no expiry-based acceptance.

## 5. Resulting M6 security state

After this bounded review, and only when its executable evidence passes:

```text
current findings:                 147
reviewed finding delta:             2
cumulative reviewed findings:      12
historically remediated findings:  61
NOT_APPLICABLE:                     4
UNRESOLVED:                       143
releaseBlocked:                  true
authoritative inventory complete: false
```

The finding identity set remains unchanged. One exact finding changes from `UNRESOLVED` to evidence-backed `NOT_APPLICABLE`; the build-plugin finding remains release-blocking.

## 6. Permanent non-closure boundary

```text
M6_PR_E_E3_R3A_REVIEW_DEFINED
M6_PR_E_SECURITY_CLOSURE_NOT_ACCEPTED
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
ISSUE_97_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
ISSUE_91_REMAINS_OPEN
NO_FINDING_DELETION
NO_SUPPRESSION
NO_EXCEPTION
NO_SEVERITY_DOWNGRADE
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
AI_IS_NOT_AN_OPERATOR
```
