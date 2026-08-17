# M6-PR-E E3-R3A CI JAR Materialization Correction

## Scope

This bounded correction retains the E3-R3A applicability decisions and changes only the permanent test harness that prepares the exact Tomcat artifact for inspection.

It adds no dependency upgrade, scanner exclusion, finding deletion, suppression, exception, severity downgrade, product capability, Workflow, deployment, or Production Promotion.

## Retained failed evidence

Natural `pull_request` Run `31782585844` / `#1458` executed at exact Head:

```text
87b7215f0718446625d8c4a9c8eb46c2b2ba0569
```

Eight physical Jobs succeeded. Repository Hygiene Job `94711378949` failed only in R3A test `264` after the runtime dependency tree and Maven plugin-provenance reconstruction had run.

The exact failure was:

```text
Tomcat embed-core JAR is unavailable:
/home/runner/.m2/repository/org/apache/tomcat/embed/
tomcat-embed-core/11.0.15/tomcat-embed-core-11.0.15.jar
```

Classification:

```text
TEST_INFRASTRUCTURE_BUG / CROSS_JOB_MAVEN_REPOSITORY_ASSUMPTION
```

The Hygiene Artifact remains retained:

```text
Artifact ID: 9212341836
Bytes:       34854
SHA-256:     8d0cac74c624ff22df534000c6f5992c5351ed2be7bd9802227bc437832470e2
```

The downloaded ZIP byte count and SHA-256 independently matched GitHub metadata.

## Root cause

Repository Hygiene is an independent Job. The R3A reviewer correctly reconstructed Maven runtime and build-plugin graphs, but `dependency:tree` does not guarantee that the resolved runtime JAR bytes are present in that Job's default local Maven repository.

The reviewer then inspected a fixed `~/.m2/repository` path and therefore depended on an artifact side effect that another independent Job could not provide.

No product defect, vulnerability disposition change, or scanner-identity drift was observed.

## Correction

Before the R3A applicability module is imported, the permanent aggregate now imports:

```text
scripts/tests/m6-pr-e-e3-r3a-ci-jar-materialization-boundary.test.mjs
```

The correction:

1. invokes the pinned `maven-dependency-plugin:3.11.0:copy` goal non-recursively;
2. resolves exactly `org.apache.tomcat.embed:tomcat-embed-core:11.0.15:jar`;
3. copies the versioned JAR into a new process-owned temporary Maven-repository layout;
4. sets only `M6_PR_E_E2_MAVEN_REPOSITORY` for the later R3A reviewer child process;
5. verifies the exact filename, readable JAR entries, and absence of the vulnerable cloud-membership package prefix;
6. removes the temporary directory when the Node process exits;
7. strips GitHub and scanner tokens from the Maven child-process environment.

This removes the cross-Job artifact assumption without changing the existing automatic Workflow or weakening the exact JAR inspection.

## Required validation

The correction is accepted only if one new natural `pull_request` Run at the resulting correction Head proves:

- all nine physical Jobs succeed;
- the new materialization boundary passes;
- the original R3A exact Maven and JAR evidence test passes;
- Tomcat remains `NOT_APPLICABLE` only under the four already-defined positive conditions;
- HttpComponents Core remains `UNRESOLVED` and release-blocking;
- no same-Head rerun, `workflow_dispatch`, empty trigger commit, or second automatic Workflow is used.

## Permanent boundary

```text
M6_PR_E_E3_R3A_FAILED_RUN_RETAINED
M6_PR_E_E3_R3A_CI_JAR_MATERIALIZATION_CORRECTED
M6_PR_E_SECURITY_CLOSURE_NOT_ACCEPTED
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
ISSUE_97_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
ISSUE_91_REMAINS_OPEN
PR_92_UNCHANGED
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
