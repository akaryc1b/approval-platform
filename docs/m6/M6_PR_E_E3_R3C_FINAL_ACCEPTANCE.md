# M6-PR-E E3-R3C Final Acceptance Candidate

Status: `DOCUMENTED_HEAD_VALIDATION_REQUIRED`

Issue: `#97`

Pull Request: `#106`

This record accepts no security closure, Ready transition, merge, deployment, or Production Promotion by itself. It records the exact implementation history and defines the final natural validation required for this bounded infrastructure and applicability-review slice.

## Exact documented-head construction

The documented Head is an ordinary two-parent Merge Commit constructed from:

```text
PR #106 implementation Head: c1402d15b5d98accad1a220f9194c36b565833d8
current main:                03e682eb102056b6cc1cdceb211f15f6cc6b0e15
```

The `main` parent contains the accepted documentation-authority rebaseline from PR #112. Its changes do not overlap the R3A/R3B/R3C security implementation.

No rebase, squash, force update, direct `main` push, empty trigger commit, auto-merge, deployment, or production traffic mutation is used.

## Accepted implementation model pending final Run

The implementation preserves the byte-identical generic OSV drift verifier merged through PR #110 as:

```text
scripts/security/m6-pr-e-e3-verify-workflow-supply-chain-remediation-generic.mjs
```

A thin wrapper adds only the exact R3C reviewed identity boundary. The live decision model is:

1. exact historical 115 identities -> accepted historical path;
2. exact reviewed 117 identities -> reviewed R2B path;
3. exact reviewed 120 identities -> reviewed R3C path;
4. any other complete, normalized, duplicate-free current identity set -> generic `UNREVIEWED_OSV_DATABASE_DRIFT_IDENTITY_SET`, every current finding retained `UNRESOLVED`, `currentOsvFindingReviewRequired=true`, and `releaseBlocked=true`.

Historical scanner binary, source commit, and version remain retained observation evidence. They are not permanent live-scanner invariants and cannot turn a scanner republish into a false finding-identity failure.

## Exact reviewed R3C delta

The reviewed transition is:

```text
prior reviewed identities: 117
retained identities:       117
added identities:            3
removed identities:          0
current reviewed identities:120
```

The exact reviewed current set is bound by:

```text
finding-set SHA-256: c1f9b73ce713bc09035ce34e7ad7d0b14329933f51dbd466aa0843e5066d1142
```

The three latest findings remain `UNRESOLVED` and release-blocking:

```text
GHSA-v3jc-474w-2wm6 / CVE-2026-54428
org.apache.httpcomponents.core5:httpcore5-h2:5.3.6
BUILD_PLUGIN

GHSA-hjcp-jmpx-g3qm / CVE-2026-64607
org.apache.httpcomponents.client5:httpclient5:5.5.2
BUILD_PLUGIN

GHSA-qv9r-c865-cp47 / CVE-2026-49844
org.apache.logging.log4j:log4j-api:2.25.3
COMPILE_RUNTIME
```

Together with the two previously reviewed additions, all five reviewed additions remain `UNRESOLVED`. No finding is deleted, suppressed, excepted, downgraded, or converted into a zero-alert claim.

## Validation history

### Run #1469 — superseded implementation attempt

```text
Run ID:     31793340350
Run number: 1469
Head:       f1db1d752f09283ec7bdf85664f6e3d7d2c21d30
Conclusion: failure
```

The exact 120-identity set matched. The failures were evidence-model defects:

- historical scanner binary/source metadata was incorrectly enforced as an immutable live invariant;
- the R3B boundary test still required superseded begin/end log markers removed by PR #110.

No product, dependency, authorization, or workflow regression was identified.

### Run #1484 attempt 1 — implementation Head

```text
Run ID:      31802387685
Run number:  1484
Head:        c1402d15b5d98accad1a220f9194c36b565833d8
PR merge ref:c6f2c215f5e4a684cdc613831cf2c904515fd5e0
Base:        03e682eb102056b6cc1cdceb211f15f6cc6b0e15
Conclusion:  failure
```

Repository Hygiene, all four Persistence JDBC shards, Vben, and Mobile succeeded. Maven Core reached `approval-engine-flowable` after the first 18 reactor modules completed, then Maven Central returned HTTP `429 Too Many Requests` for the Flowable 8.0.0 and H2 2.4.240 POMs. This is classified as a proven external dependency-download infrastructure failure.

### Run #1484 attempt 2 — single authorized infrastructure retry

One failed-jobs rerun was used for the exact same Head. This is the only same-Head rerun in this gate.

Maven Core succeeded at Job `94774280643`. Repository Hygiene, all four Persistence JDBC shards, Vben, and Mobile also succeeded. The aggregate Job `94774886388` failed only because `actions/download-artifact` found five Maven part artifacts but prepared and downloaded only the Core artifact for the retried attempt. The shard verifier therefore reported zero downloaded shard manifests even though all four shard Jobs were successful.

The attempt retained two same-name Maven artifacts:

```text
attempt-1 Maven Artifact 9219857193
bytes: 319290
SHA-256: 9c4428a7a9a4e6f5b2ab192c5e133ef5cdbe795fd42d551dc14406f4147010d9

attempt-2 Maven Artifact 9219982405
bytes: 54888
SHA-256: df2bb3bf1e953e030d1a4b0b8cd1fdf369f2c43068b697dce8a2c9639a673a39
```

Neither incomplete aggregate is accepted as final Maven evidence. No second same-Head rerun is authorized.

## Author-side focused validation

Before publication of the implementation Head, syntax and focused contract checks proved:

- exact 115, 117, and 120 identity modes are distinguished by full identity-set hash;
- a synthetic 121st unknown identity enters the generic unreviewed path;
- all unknown current findings remain `UNRESOLVED` and release-blocking;
- Gitleaks, Semgrep, and zizmor identity boundaries remain exact;
- duplicate and incomplete scanner evidence fails closed;
- no suppression, exception, severity downgrade, finding deletion, Ready, merge, deployment, or Production Promotion path exists.

## Final documented-head acceptance requirement

One natural `pull_request` Run at the resulting documented Head must:

- complete all physical Jobs successfully on its first attempt;
- preserve the single automatic Workflow and four permanent Artifact classes;
- retain exact test-selection coverage with no duplicate or omitted required test;
- retain current OSV evidence through the reviewed or generic unreviewed fail-closed path;
- produce one complete Maven Artifact plus Vben, Mobile, and Hygiene Artifacts;
- allow every final ZIP to be independently downloaded, byte-count checked, SHA-256 verified, and ZIP-integrity checked;
- leave PRB-16, PRB-17, Issue #97, Issue #82, and Issue #62 open.

After that exact documented Head succeeds, the bounded infrastructure/applicability slice may be marked Ready and merged with an ordinary Merge Commit. Issue #97 remains open for final authoritative GitHub inventory and final-main dependency closure.

```text
M6_PR_E_R3C_DOCUMENTED_HEAD_VALIDATION_REQUIRED
GENERIC_OSV_FAIL_CLOSED_MODEL_PRESERVED
R3C_EXACT_120_IDENTITY_REVIEW_DEFINED
FIVE_REVIEWED_OSV_FINDINGS_REMAIN_UNRESOLVED
UNKNOWN_FUTURE_OSV_IDENTITIES_REMAIN_VISIBLE_AND_RELEASE_BLOCKING
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
ISSUE_97_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
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
