# M6-PR-E / E3-R2B — Run 1433 Controlled Correction

## Scope

This append-only record classifies and corrects the first natural E3-R2B implementation Run. It changes no production Java or TypeScript, Flyway migration, dependency version, Dependabot policy, Workflow trigger, Job graph, permission, Artifact class, deployment, release, or production authority.

The R2B implementation remains the single reviewed remediation batch for:

```text
43  zizmor/unpinned-uses
14  zizmor/artipacked
 1  zizmor/template-injection
58  total
```

## Retained failed evidence

```text
Implementation Head: 69f1305030020cd7c7affd35a80542963a4c06ef
Run ID:             31574836736
Run number:         1433
Event:              pull_request / synchronize
Conclusion:         failure
Physical Jobs:      9
Successful Jobs:    8
Failed Jobs:        Repository hygiene
Same-Head reruns:   0
```

Maven Core, all four Persistence JDBC shards, the Maven aggregate, Vben, and Mobile succeeded. The permanent M6 transport/security aggregate failed three compatibility assertions.

The failed Run retained all four permanent Artifact classes:

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `9132801183` | `358548` | `0639c6575e6f6fc35d4efdab24fe7d7842d079f153c6dcfea6ae732028820338` |
| Vben | `9132779472` | `18893` | `5db0d3e299a1cfab1f2ebf8ff95819d0d380665065c84c08be3217c2e11b6d8b` |
| Mobile | `9132761288` | `9796` | `d00ac610e0fb6ddfe6806b725a24ec6251fe84eb0a5ba83e7024428f0b81d8cd` |
| Hygiene | `9132739789` | `25487` | `9694f1cf7d2d6f2a8239d5a8a610bf1e6ee1b0650f0e155064771bf6e499f18a` |

The archives were independently downloaded, matched the recorded byte sizes and SHA-256 digests, and passed ZIP integrity validation. They remain failure evidence and are not acceptance evidence.

## Classification

| Classification | Failure | Correction |
| --- | --- | --- |
| `TEST_BUG` | A historical test still required `actions/upload-artifact/merge@v4` after the reviewed immutable pin was applied. | Require the reviewed immutable SHA plus the readable `# v4` comment. |
| `EVIDENCE_BUG` | The E2 generator accepted only the historical source Workflow blobs and rejected the complete exact R2B target state. | Admit either the complete historical source state or the complete reviewed R2B target state; reject mixed, unknown, or drifted states. |
| `EVIDENCE_BUG` | E4 dependency-graph reconstruction consumed current pinned Workflow declarations as though they rewrote the already accepted historical E2 graph. | Record current immutable Workflow evidence accurately while retaining an explicit canonical historical E2 graph projection for accepted graph lineage. |

The already published failure is not rerun, amended, rebased, removed, or hidden.

## Controlled correction boundary

The correction preserves the staging R2A compatibility rule: current Zizmor findings and per-rule counts may only decrease from the accepted R2A state, the three cooldown findings must remain absent, and any increase or cooldown reappearance fails closed. R2B independently requires the exact reviewed target Workflow blobs, all 58 historical identities, and current Zizmor count zero before remediation evidence can be accepted.

The following remain prohibited:

```text
NO_SAME_HEAD_RERUN
NO_EMPTY_TRIGGER_COMMIT
NO_WORKFLOW_DISPATCH
NO_AMEND_OF_PUBLISHED_HISTORY
NO_REBASE_OF_PUBLISHED_HISTORY
NO_FORCE_PUSH
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_RELEASE
NO_PRODUCTION_PROMOTION
```

## Gate state

A new natural `pull_request / synchronize` Run at a new correction Head must prove all nine physical Jobs, one current scanner execution, current Zizmor count zero, exact selected-test coverage, and all four exact-Head Artifact ZIPs. Until that succeeds and a final documented Head passes Run B:

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
