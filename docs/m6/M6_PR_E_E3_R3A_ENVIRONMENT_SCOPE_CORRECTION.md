# M6-PR-E E3-R3A Environment Scope Correction

## Scope

This bounded correction retains both earlier failed natural Runs and changes only the test-harness environment scope used to provide the exact Tomcat JAR to the R3A reviewer.

It adds no dependency upgrade, scanner exclusion, finding deletion, suppression, exception, severity downgrade, product capability, Workflow, deployment, or Production Promotion.

## Retained natural Run

Natural `pull_request` Run `31786303116` / `#1459` executed at exact Head:

```text
b14a8e541dd53d2b59588dc3ae521a7640eab614
```

Eight physical Jobs succeeded. Repository Hygiene Job `94722860841` failed in the aggregate transport boundary with two failures:

```text
E2 full generator executes only in GitHub Actions and emits retained canonical evidence
resolved Maven POM license metadata must not be globally omitted

E4 full scanner emits E4→I1→I2→R1→I3→R2A→R2B→I4 exact-head chain
E2 graph drift fba7aecf6e7199850812da8d3bac3257534898176312cfe3b08e4661ed7a4d9c
```

The correction introduced for Run `31782585844` did solve the original failure:

```text
R3A CI materializes the exact Tomcat JAR before evidence review = PASS
R3A exact Maven and JAR evidence executes in GitHub Actions = PASS
```

The retained Hygiene Artifact is:

```text
Artifact ID: 9213654158
Bytes:       20638
SHA-256:     8a8e1da731e6cfadc7f1d45686280ba3ec8541cf17a1cec2174e072f71b05f87
```

The downloaded ZIP byte count and SHA-256 independently matched GitHub metadata.

## Root cause

Classification:

```text
TEST_HARNESS_ENVIRONMENT_SCOPE_LEAK
```

ECMAScript static imports evaluate dependency modules before registered tests execute. The first correction materialized the exact Tomcat JAR during module evaluation and assigned its isolated temporary path to the parent process variable:

```text
M6_PR_E_E2_MAVEN_REPOSITORY
```

That assignment therefore affected earlier registered E2 and E4 test callbacks. Their full Maven evidence generation saw a temporary repository containing the copied Tomcat JAR but not the complete resolved POM/license corpus, which caused the license completeness assertion and inherited graph digest to fail.

No product defect, vulnerability disposition change, scanner-identity drift, dependency graph change, or production Maven graph drift was observed.

## Correction

The exact artifact preparation is now lazy and cached without mutating the parent process environment:

1. `ensureExactTomcatRepository()` creates the process-owned temporary repository only when a CI test requests it;
2. the helper retains the exact pinned `maven-dependency-plugin:3.11.0:copy` invocation and exact `tomcat-embed-core:11.0.15:jar` coordinate;
3. the helper test proves that the parent `M6_PR_E_E2_MAVEN_REPOSITORY` value is unchanged;
4. only the R3A reviewer child process receives the temporary repository path in its copied environment;
5. E2 and E4 continue to use their inherited complete Maven repository and canonical graph inputs;
6. the temporary repository is removed on process exit;
7. GitHub and scanner tokens remain removed from the Maven materialization child environment.

## Required validation

The correction is accepted only if one new natural `pull_request` Run at the resulting correction Head proves:

- all nine physical Jobs succeed;
- E2 full generator license metadata completeness passes;
- E4 inherited E2 graph digest remains exact;
- the no-parent-environment-leakage test passes;
- the R3A exact Maven and JAR evidence test passes;
- Tomcat remains `NOT_APPLICABLE` only under the four already-defined positive conditions;
- HttpComponents Core remains `UNRESOLVED` and release-blocking;
- no same-Head rerun, `workflow_dispatch`, empty trigger commit, or second automatic Workflow is used.

## Permanent boundary

```text
M6_PR_E_E3_R3A_FIRST_FAILED_RUN_RETAINED
M6_PR_E_E3_R3A_SECOND_FAILED_RUN_RETAINED
M6_PR_E_E3_R3A_ENVIRONMENT_SCOPE_CORRECTED
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
