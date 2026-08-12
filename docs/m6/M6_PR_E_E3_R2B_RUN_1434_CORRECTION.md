# M6-PR-E / E3-R2B — Run 1434 Controlled Correction

## Scope

This append-only record retains the second natural E3-R2B implementation failure and defines the next correction boundary. It changes no Workflow, Dependabot policy, production Java or TypeScript, Flyway migration, dependency version, database workstream, deployment, release, or production authority.

The governed R2B remediation batch remains exactly:

```text
43  zizmor/unpinned-uses
14  zizmor/artipacked
 1  zizmor/template-injection
---
58  total
```

## Retained natural Run

```text
Run ID:        31590518190
Run number:    1434
Head:          3a0388533ba5e8bbbfaed70fca79b74d4910bfd2
Event:         pull_request / synchronize
Run attempt:   1
Conclusion:    failure
Physical Jobs: 9
Success:       8
Failure:       Repository hygiene
Same-Head reruns: 0
```

The following Jobs succeeded:

```text
Java 21 / Maven core
Persistence JDBC / shard 0
Persistence JDBC / shard 1
Persistence JDBC / shard 2
Persistence JDBC / shard 3
Java 21 / Maven / PostgreSQL
Vben TypeScript / production build
UniApp TypeScript / H5 / WeChat
```

The permanent M6 Node aggregate completed:

```text
258 tests
255 pass
3 fail
```

## Exact failure classification

| Classification | Exact failure | Controlled correction |
| --- | --- | --- |
| `EVIDENCE_BUG` | The R2A compatibility change altered the accepted no-options V1 canonical from `65e2036c1738f2b4eee56cc3bfc154eb9856401ec5ece2673268eb45c96270cc` to `0f42bfbec5a98fdc93123d6e3d6243301101b1638efa7d3ec83747aceab096a3`. | Restore strict historical V1 behavior and exact reason codes when no successor plan is supplied. |
| `EVIDENCE_BUG` | The zero-Zizmor R2B successor was evaluated through the historical R2A default path and failed with `R2A current zizmor count mismatch 0`. | Permit a successor only through an explicit, exact `subsequentWorkflowRemediationPlan` that validates the R2B repository, prior accepted Head, accepted R2A canonical, I4 finding-set digest, retained Dependabot blob, 9 source/target Workflow blobs, 58 historical identities, reviewed Action identities, invariants, and zero-Zizmor target. |
| `TEST_BUG` | The permanent full scanner chain did not pass the exact R2B plan to the versioned R2A successor verifier. | Pass the exact committed R2B plan and retain the historical default-path assertions. |

No same-Head rerun, empty trigger commit, workflow dispatch, amend, rebase, force push, failure deletion, or Artifact deletion is authorized.

## Retained failed-Run Artifacts

All four ZIPs were independently downloaded, matched GitHub byte size and SHA-256 metadata, and passed ZIP integrity verification. They remain failure evidence and are not Acceptance evidence.

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `9138982978` | `358360` | `102762f6766114a88e04a410cb11ab6449c493e95435e6d5e58f04da316e235b` |
| Vben | `9138947772` | `18762` | `5874a8fe0958ffcbdd6f6ef8d034cb44a4b499680cb8d04bc973004b7036f2da` |
| Mobile | `9138935349` | `9829` | `4625110cfdea02a5e1efbcb1dee0695ca6ac963a1894156a18cdb445d974e6f6` |
| Hygiene | `9139002376` | `33866` | `a37b50e8b010f751be59c0c2522901462a6df1771c11b3128195a91b20b9e086` |

## Correction invariants

```text
LEGACY_R2A_V1_CANONICAL_EXACT
R2B_SUCCESSOR_REQUIRES_EXACT_EXPLICIT_PLAN
NO_IMPLICIT_ZERO_FINDING_PROMOTION
NO_SAME_HEAD_RERUN
NO_EMPTY_TRIGGER_COMMIT
NO_WORKFLOW_DISPATCH
NO_AMEND
NO_REBASE
NO_FORCE_PUSH
FAILED_RUN_AND_ARTIFACTS_RETAINED
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_RELEASE
NO_PRODUCTION_PROMOTION
```

Until a new exact correction Head completes all nine Jobs, one scanner execution, zero current Zizmor findings, exact selected-test coverage, and four exact-Head Artifacts, the Gate remains:

```text
M6_PR_E_E3_R2B_WORKFLOW_SUPPLY_CHAIN_REMEDIATION_PENDING
M6_PR_E_E3_FINDING_TRIAGE_REQUIRED
M6_PR_E_E3_CLOSURE_NOT_ACCEPTED
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
ISSUE_97_REMAINS_OPEN
PR_98_REMAINS_OPEN_DRAFT_UNMERGED
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
AI_IS_NOT_AN_OPERATOR
```
