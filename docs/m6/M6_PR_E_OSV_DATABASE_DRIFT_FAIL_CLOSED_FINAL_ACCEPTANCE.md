# M6-PR-E OSV Database Drift Fail-Closed — Final Acceptance

Status: `M6_PR_E_OSV_DATABASE_DRIFT_FAIL_CLOSED_ACCEPTED_PENDING_FINAL_DOCUMENTED_HEAD_VALIDATION`

This record accepts only the fail-closed evidence-model correction in PR #110. Unreviewed live OSV database drift is retained as complete, normalized, `UNRESOLVED` and release-blocking current evidence. This does not accept the current identities as reviewed findings, close M6-PR-E security work, deploy anything or authorize Production Promotion.

## Exact implementation evidence

| Field | Exact value |
| --- | --- |
| Repository | `akaryc1b/approval-platform` |
| Base `main` | `779c4fbd09dcf17d45cc523e725222797cc5cb85` |
| Pull Request / Issue | `#110` / `#97` |
| Implementation Head | `6dfd9bbec3103a08263f3d30ff2323877bca298a` |
| Natural Run | `31794417910` / `#1469` |
| Result | `9 / 9 success` |
| Same-Head reruns / force pushes / empty triggers / deployments | `0 / 0 / 0 / 0` |

```text
Java 21 / Maven core                  94748237763
Repository hygiene                    94748237765
Persistence JDBC / shard 0            94748237790
Vben TypeScript / production build    94748237793
Persistence JDBC / shard 2            94748237836
UniApp TypeScript / H5 / WeChat       94748237867
Persistence JDBC / shard 3            94748237877
Persistence JDBC / shard 1            94748237882
Java 21 / Maven / PostgreSQL          94748803142
```

`Repository hygiene` completed `Verify M6 AI transport review boundaries` successfully.

## Exact scanner evidence

```text
E2:     d848c1b291d909f2bc4dea6a83be8f1bf574738f8f559f6e2ce71db472448483
E3:     7abce16a082ffea592d9b802322342d17ee0abf9d86059311dc6df2e27dc2008
E4:     4a8054dc0b0715a9fff62978f0217064d0cb6b87b27eb698bc87746c35999e67
E3-I2:  f94dddd579d43f6668da3f731e5b646a290c7f3bd41707d4717f5d82fe3d9ab3
E3-R1:  b46096857a4fb0ad2b474b9885c9d26f12687376e47b0ce34553740880e4aa1d
E3-I3:  b4f463a0adffc5093d611e5d8ee2ada6ccc124882a6ba71da16bc4d8e2e32121
E3-R2A: c27ee1aecd59423325e583d0228f150cc9193f89d99282ebb7826a8acc5e6ad8
E3-R2B: 6e98f9a7e20ffc17fabe3c3cab92309366c5e168cdee90c1ed0365ee25ebad12
E3-I4:  b8cbb7686e6144634b4a5ed5f8c4351410d092a96666e5b737f261d07a721565

OSV:       120
Gitleaks:   27
Semgrep:     3
zizmor:      0
Total:     150

osvIdentityMode = UNREVIEWED_OSV_DATABASE_DRIFT_IDENTITY_SET
acceptedOsvIdentitySetMatched = false
reviewedCurrentOsvIdentitySetMatched = false
currentOsvFindingReviewRequired = true
unreviewedCurrentOsvFindingCount = 120
NOT_APPLICABLE:   3
APPLICABLE:       0
UNRESOLVED:     147
releaseBlocked: true
```

This is the intended result. The 120 current OSV findings are retained for later E3 applicability/reachability review and are not accepted merely because the Workflow can process them.

## Accepted semantics and lineage

The implementation distinguishes the exact accepted historical set, the exact previously reviewed drift set, and every other complete normalized duplicate-free live set. The third state stays `UNRESOLVED`, requires review and blocks release. A count of 115 no longer implies acceptance without the exact accepted identity hash.

Gitleaks, Semgrep, zizmor, duplicate/incomplete evidence, Action pins, checkout boundaries, workflow inventory and physical Job count remain exact. No suppression, exception, severity downgrade or finding-deletion claim is added.

Exact accepted blobs:

| Path | Git blob SHA |
| --- | --- |
| `scripts/security/m6-pr-e-e3-verify-workflow-supply-chain-remediation.mjs` | `1ff31b063e019dcf3802dfd473339c2976e18484` |
| `scripts/tests/m6-pr-e-e3-r2b-workflow-supply-chain-remediation-boundary-accepted.test.mjs` | `2c6218eda079c0a73ba7cc9c02757e709f7fd0b5` |
| `scripts/tests/m6-pr-e-e3-r2b-scanner-identity-reconciliation.test.mjs` | `c77ed6c103b131ba87ccd59254e9dbf3f631542d` |
| `docs/m6/M6_PR_E_OSV_DATABASE_DRIFT_FAIL_CLOSED_CORRECTION.md` | `56c3f7992661231f2256743f140a4aed1a6fb158` |

The historical placeholder Fixture calls only the frozen verifier; the live wrapper remains identity-aware and fail closed.

| Run | Head | Result | Evidence |
| --- | --- | --- | --- |
| `31792109160` / `#1468` | `f7da469ab1ef71f0edc8a7d9f99f1e870b7f389b` | failure | Four frozen placeholder tests still called the live identity wrapper. |
| `31794417910` / `#1469` | `6dfd9bbec3103a08263f3d30ff2323877bca298a` | success | Frozen Fixture isolated; import separation permanently asserted. |

No failed Run was erased or rerun at the same Head. The M6 transport Node boundary completed `267 / 267 PASS`.

## Independently verified Artifacts

Each ZIP binds implementation Head `6dfd9bbec3103a08263f3d30ff2323877bca298a`; local SHA-256 matched GitHub and `unzip -t` passed.

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Hygiene | `9216859580` | `162136` | `78f0be5f846542fe41b3c590fd40e309b84e2d04953bf656f56622f02cb85e15` |
| Maven | `9216841219` | `358438` | `7fcde37a25e07663f9d65dbb6f87458de526ac7edb416274fdc2250d45a38060` |
| Vben | `9216813948` | `18860` | `d3166bc11dc04d3c49970d58313917a9d857809d4f6d540780d80da32d0853e5` |
| Mobile | `9216798269` | `9810` | `7c49f719e752db61b95d9e8a33cb0502c1aaaa944e5ea789a74d266a97e938cd` |

## Final documented Head gate

This document cannot contain its own commit SHA. PR #110 may be marked Ready and merged only after one natural final `pull_request` Run proves the final documented Head with all nine Jobs and four Artifact classes successful, the PR still mergeable, no actionable review finding, and no scope expansion. Only an ordinary Merge Commit is authorized; squash, rebase, force update, auto-merge, deployment and Production Promotion remain forbidden.

```text
M6_PR_E_OSV_DATABASE_DRIFT_FAIL_CLOSED_ACCEPTED
M6_PR_E_SECURITY_CLOSURE_NOT_ACCEPTED
OSV_DATABASE_DRIFT_RETAINED_WITHOUT_ACCEPTANCE
CURRENT_OSV_IDENTITY_SET_REQUIRES_E3_REVIEW
OSV_DATABASE_SNAPSHOT_IDENTITY_UNAVAILABLE
ACCEPTED_OSV_IDENTITY_SET_UNCHANGED
RELEASE_REMAINS_BLOCKED
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
ISSUE_97_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
READY_AUTHORIZED_AFTER_FINAL_DOCUMENTED_HEAD_VALIDATION
MERGE_COMMIT_AUTHORIZED_AFTER_FINAL_DOCUMENTED_HEAD_VALIDATION
NO_SQUASH
NO_REBASE
NO_FORCE_UPDATE
NO_AUTO_MERGE
NO_SUPPRESSION
NO_EXCEPTION
NO_SEVERITY_DOWNGRADE
NO_FINDING_DELETION_CLAIM
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
AI_IS_NOT_AN_OPERATOR
```
