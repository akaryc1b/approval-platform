# M6-PR-E E4 — H5 OSV Identity Drift Correction

## Classification

Run `31761012236 / #1450` proved the H5 dependency-graph rebaseline itself was correct: E4 passed the exact graph guard and completed the full scanner process before the downstream R2B reconciliation failed.

The exact failure was:

```text
E4 full scanner emits E4→I1→I2→R1→I3→R2A→R2B→I4 exact-head chain
R2B current OSV identity-set drift
```

This is classified as:

```text
EVIDENCE_COMPATIBILITY_BUG
DYNAMIC_OSV_IDENTITY_SET_FROZEN_AS_EXACT_CURRENT_SET
```

It is not an H5 D3/D4 product regression and is not a scanner-tool failure.

## Problem

The historical R2B identity reconciliation correctly retained the accepted OSV finding history, but it also froze one dynamic current OSV identity set at exactly 117 findings. A later valid OSV database addition therefore caused the evidence pipeline to stop before it could emit and preserve that new finding for review.

```text
NEW_OSV_FINDING != ACCEPTED_DISPOSITION
NEW_OSV_FINDING != SCANNER_FAILURE
NEW_OSV_FINDING != AUTHORIZATION
```

## Correction boundary

The historical R2B verifier and its historical contract remain unchanged. Their existing Git blobs are retained under explicit `-legacy` paths.

An additive compatibility wrapper is introduced only for the exact H5 graph:

```text
e4cffa00582d61a62f5c41548f8da4b8bfb28dd50b7db3aa5d1aa42cd503ddfd
```

The wrapper is bound to the successful Run `31689796691 / #1440` current OSV baseline:

```text
retained OSV identities: 117
finding-set SHA-256:
42d4ce93ce58eb76e07faa556d32c6b8f7feb1e9a3f3f600eab9c971c3fe5da6
E4 canonical SHA-256:
d2226389e2bf691d879ffa170a7bf7d7f6ad397dca13ee6416d9a702b6ee2f88
```

Every one of those 117 identities must remain present. Their deletion or replacement is not accepted.

```text
HISTORICAL_OSV_IDENTITY_DELETION_NOT_ACCEPTED
```

The Gitleaks, Semgrep and zizmor identity sets remain exact and unchanged.

## New OSV findings

Only additive OSV identities are tolerated, and only on the exact H5 graph. Every addition is preserved in reconciliation evidence with package, aliases, scopes, component references, severity evidence and fixed-version metadata and is forced to:

```text
UNRESOLVED
REVIEW_REQUIRED
releaseBlocked=true
```

The evidence path does not infer applicability, reachability or remediation from scanner presence.

```text
NEW_OSV_FINDINGS_REMAIN_UNRESOLVED
```

A bounded maximum of 64 newly observed OSV identities is enforced. Larger drift fails closed instead of producing unbounded evidence.

## Security invariants

```text
NO_SUPPRESSION
NO_SEVERITY_DOWNGRADE
NO_EXCEPTION
NO_FINDING_DELETION_CLAIM
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED
```

The correction does not change scanner versions, scanner rules, scanner inputs, MySQL dependencies, H5 D3/D4 behavior, workflow triggers or production authorization.

## Validation state

```text
H5_OSV_IDENTITY_DRIFT_CORRECTION_STAGED
FULL_SCANNER_REVALIDATION_REQUIRED
CURRENT_NEW_OSV_IDENTITIES_NOT_YET_REVIEWED
PR_92_REMAINS_DRAFT
```
