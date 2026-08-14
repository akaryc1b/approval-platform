# M6-PR-E OSV Database Drift Fail-Closed Correction

Issue: [#97 — M6-PR-E Security and Dependency Evidence Closure](https://github.com/akaryc1b/approval-platform/issues/97)

Related observations: [PR #109](https://github.com/akaryc1b/approval-platform/pull/109), [PR #108](https://github.com/akaryc1b/approval-platform/pull/108)

## Exact observed baseline

The defect was observed on 2026-08-14 against unchanged `main`:

```text
main = 779c4fbd09dcf17d45cc523e725222797cc5cb85
```

Two unrelated pull-request validations failed within the same period:

```text
PR #109
Run 31789934796 / #1464
Head f647b1382444dcf2da4abb917f9956ccfb763408
Failing Job: Repository hygiene
Failing subtest: E4 full scanner emits E4→I1→I2→R1→I3→R2A→R2B→I4 exact-head chain
Error: R2B current OSV identity-set drift

PR #108
Run 31790072513 / #1465
Head 20074cd86fa4c5796834d772097cebb99c84d0e5
Conclusion: failure on the same unchanged main baseline
```

PR #109 changed only Product Readiness documentation, a read-only preflight script and its boundary test. Its Maven, Persistence JDBC, Vben and Mobile jobs completed successfully; the failure occurred in the permanent M6 security-evidence chain.

## Root cause

The E4 scanner resolves the current exact dependency graph and queries the live OSV database. The current normalized evidence does not expose an immutable OSV database snapshot identity:

```text
databaseSnapshotIdentity = null
databaseSnapshotIdentityAvailability = NOT_EXPOSED_BY_CURRENT_NORMALIZED_OSV_EVIDENCE
```

R2B nevertheless requires the live OSV result to equal one previously reviewed exact identity set:

```text
accepted historical OSV set = 115 findings
reviewed database-drift set  = 117 findings
```

When OSV publishes, changes or withdraws an advisory for an unchanged dependency graph, the current finding-set hash changes. The verifier then fails before it retains the new normalized findings for E3 applicability and reachability review.

This is classified as:

```text
EVIDENCE_MODEL_DEFECT
LIVE_DATABASE_WITHOUT_RETAINED_SNAPSHOT_IDENTITY
UNREVIEWED_FINDINGS_MUST_REMAIN_RELEASE_BLOCKING
```

It is not evidence that the Product Readiness files introduced a product, dependency, workflow or authorization regression.

## Required correction semantics

The accepted historical evidence remains immutable. A new live OSV result is observation evidence, not automatically accepted evidence.

The verifier must distinguish three states:

1. `ACCEPTED_HISTORICAL_OSV_IDENTITY_SET`
   - exact historical 115-finding identity set;
   - existing accepted workflow-supply-chain verifier remains unchanged.
2. `REVIEWED_OSV_DATABASE_DRIFT_IDENTITY_SET`
   - exact previously reviewed 117-finding identity set;
   - the two reviewed additions remain retained as `UNRESOLVED`;
   - existing accepted reason codes remain.
3. `UNREVIEWED_OSV_DATABASE_DRIFT_IDENTITY_SET`
   - any other complete, normalized, duplicate-free live OSV identity set;
   - every current OSV finding is retained with upstream identity, aliases, package, component references, scopes, severity and fixed-version metadata;
   - every retained current finding is marked `UNRESOLVED`;
   - the accepted OSV identity set is not changed;
   - `currentOsvFindingReviewRequired=true`;
   - `releaseBlocked=true`.

The correction must remove the old count-only compatibility path. A result containing 115 findings is not accepted unless its exact identity-set hash matches the accepted historical set.

## Boundaries that remain exact

This correction does not make all scanner drift non-blocking.

The following remain exact and fail closed:

- Gitleaks identity set;
- Semgrep identity set and reviewed identity transition;
- zizmor zero-finding result;
- duplicate scanner identities;
- incomplete OSV package, component-reference, scope or upstream metadata;
- scanner completion and redaction boundaries;
- workflow inventory, immutable action pins, checkout credentials, template-injection repair and physical Job count.

## Non-authorizations

This correction adds no:

- scanner suppression or ignore path;
- exception or severity downgrade;
- finding deletion claim;
- dependency, POM or lockfile change;
- Workflow change or second automatic Workflow;
- Flyway migration;
- product Java/TypeScript capability;
- Connector, Provider, Secret, AI, scheduler or traffic authority;
- Ready, merge, deployment or Production Promotion authority.

It does not close `PRB-16`, `PRB-17`, Issue #97, Issue #82 or Issue #62.

## Acceptance for this correction

The correction is acceptable only when a natural pull-request validation against its exact Head proves:

- the live OSV result is either matched to a reviewed set or retained as unreviewed current evidence;
- no current OSV finding is silently discarded;
- unreviewed current findings remain `UNRESOLVED` and release-blocking;
- deterministic scanner identities remain exact;
- all nine physical Jobs complete successfully;
- no same-Head rerun, empty commit, force push, suppression, exception or severity downgrade is used.

```text
OSV_DATABASE_DRIFT_RETAINED_WITHOUT_ACCEPTANCE
CURRENT_OSV_IDENTITY_SET_REQUIRES_E3_REVIEW
OSV_DATABASE_SNAPSHOT_IDENTITY_UNAVAILABLE
ACCEPTED_OSV_IDENTITY_SET_UNCHANGED
RELEASE_REMAINS_BLOCKED
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
ISSUE_97_REMAINS_OPEN
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
```
