# M6-PR-E E3-R3B OSV Drift Evidence Retention

## Scope

This bounded correction changes only the fail-closed diagnostic path for a mutable OSV identity-set drift. It does not reconcile, accept, delete, suppress, except, downgrade, or otherwise change any finding or disposition.

It adds no dependency upgrade, scanner exclusion, product capability, Workflow, deployment, or Production Promotion.

## Retained natural Run

Natural `pull_request` Run `31787309507` / `#1460` executed at exact Head:

```text
3343dcbb41a232cadb58cfc72178bc3ff3012bf1
```

Eight non-Hygiene physical Jobs succeeded. Repository Hygiene Job `94726015956` completed with 284 tests, 283 passing and one failing:

```text
E4 full scanner emits E4→I1→I2→R1→I3→R2A→R2B→I4 exact-head chain
error: R2B current OSV identity-set drift
```

The same Run positively proved the two earlier R3A corrections:

```text
E2 full generator executes only in GitHub Actions and emits retained canonical evidence = PASS
R3A CI materializes the exact Tomcat JAR without parent environment leakage = PASS
R3A exact Maven and JAR evidence executes in GitHub Actions = PASS
```

Retained Hygiene Artifact:

```text
Artifact ID: 9214099699
Bytes:       34481
SHA-256:     5cadec3f125a57614def8eb8343b9e81d487a0f7336d85ab8f366163717d9c5c
```

The downloaded ZIP byte count and SHA-256 independently match GitHub metadata.

## Historical correction

The retained log for natural Run `31782585844` / `#1458` also contains `R2B current OSV identity set drift`. Therefore its failure was not only the fixed Tomcat JAR path assumption; the mutable OSV database had already moved beyond the reconciled 117-identity contract at that earlier Head.

The two R3A correction commits did not introduce the OSV drift. Run `#1460` proves their exact JAR materialization and parent-environment isolation paths pass while the independent OSV identity gate remains closed.

## Current accepted identity boundary

The last reviewed reconciliation evidence remains natural Run `31689796691` / `#1440`:

```text
Current OSV finding count:       117
Current OSV finding-set SHA-256: 9fd43df6ceeee506d41126c42bf2d5ad725ff3ae2568070c2554780f00ad4965
```

The accepted historical 115-identity set remains immutable. The two reviewed additions remain retained and unresolved:

```text
GHSA-x4m4-345f-5h5g — Tomcat — NOT_APPLICABLE only under R3A positive evidence
GHSA-hf6x-8p5f-cgmf — HttpComponents Core — UNRESOLVED / release-blocking
```

No new count, hash, identity, applicability conclusion, or disposition is accepted by this correction.

## Root cause and evidence gap

Classification:

```text
CURRENT_OSV_IDENTITY_SET_DRIFT
FAIL_CLOSED_DRIFT_EVIDENCE_VISIBILITY_GAP
```

The E4 scanner completed and returned normalized evidence to its parent test. The parent verifier correctly rejected the current identity set before any downstream acceptance. However, the E4 evidence was held in captured child stdout, and the test printed the canonical E4 marker only after all verifiers succeeded. The fail-closed exception therefore reached the retained Hygiene log without the actual current OSV identities needed for an exact delta review.

## Correction

Before the existing `requireIdentitySet('current OSV', ...)` assertion, the verifier now:

1. computes the current count and deterministic finding-set SHA-256;
2. compares them with the still-unchanged 117-identity contract;
3. only on mismatch, emits one canonical JSON line between permanent markers;
4. retains only normalized OSV fields: finding identity, upstream identity, aliases, package, component references, scopes, severity, and fixed versions;
5. explicitly records that raw reports and candidate secret material are not retained;
6. records `releaseBlocked: true`;
7. immediately executes the unchanged fail-closed identity-set assertion.

Permanent markers:

```text
M6_PR_E_E3_R2B_OSV_IDENTITY_DRIFT_EVIDENCE_BEGIN
M6_PR_E_E3_R2B_OSV_IDENTITY_DRIFT_EVIDENCE_END
```

A static boundary test proves the evidence-retention call precedes the fail-closed assertion and contains no authorization, suppression, exception, or severity-downgrade path.

## Required validation

One new natural `pull_request` Run at the resulting diagnostic Head must:

- retain the complete normalized OSV drift evidence and its actual count/hash;
- remain failed if the current identity set differs from the reviewed 117-identity contract;
- keep E2, E4 inherited graph, R3A JAR materialization, and R3A exact Maven/JAR evidence passing;
- execute all nine physical Jobs without changing Workflow topology;
- use no same-Head rerun, `workflow_dispatch`, empty trigger commit, or second automatic Workflow.

The retained evidence will be compared exactly with Run `#1440` before any separate reconciliation correction is considered.

## Permanent boundary

```text
M6_PR_E_E3_R3B_OSV_DRIFT_EVIDENCE_RETENTION_DEFINED
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
