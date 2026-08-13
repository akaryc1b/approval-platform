# M6-PR-E / E3-R2B — GitHub Actions Workflow Supply-Chain Remediation

## 1. Gate purpose

E3-R2B is one append-only remediation batch for the 58 Workflow supply-chain findings that remained after E3-R2A:

| Rule | Historical findings | Remediation |
| --- | ---: | --- |
| `zizmor/unpinned-uses` | 43 | Replace the reviewed symbolic `v4` refs with the reviewed immutable 40-character commit SHAs and retain `# v4` comments. |
| `zizmor/artipacked` | 14 | Add `persist-credentials: false` to every affected `actions/checkout` step without changing existing checkout behavior. |
| `zizmor/template-injection` | 1 | Move `steps.selection.outputs.tests` into a step-scoped environment variable and quote the shell variable. |
| **Total** | **58** | One controlled remediation batch. |

The governing transformations are:

```text
REMEDIATION != HISTORY_REWRITE
MUTABLE_REF -> REVIEWED_IMMUTABLE_SHA
PERSISTED_CHECKOUT_CREDENTIAL -> PERSIST_CREDENTIALS_FALSE
SHELL_TEMPLATE_EXPANSION -> STEP_ENV_DATA_BOUNDARY
```

This Gate does not rewrite the E2 action-resolution baseline, the E3-I4 finding set, E3-R2A evidence, historical scanner evidence, failed Runs, or historical finding identities.

## 2. Bound evidence

The machine-readable remediation plan is:

```text
docs/m6/m6-pr-e-e3-r2b-workflow-supply-chain-remediation.json
```

It binds:

- repository identity;
- prior accepted Head `05f422b4cdab397fc1126e6dc10f571b01cec8c5`;
- E3-I4 finding-set SHA-256 `d12463e28555e88fbed0e9ae73a83232296fb1879e2d7479a91f4e89255bc2fe`;
- prior E3-I4 canonical SHA-256;
- prior E3-R2A canonical SHA-256 and three cooldown finding identities;
- prior exact E4 canonical SHA-256 and scanner counts;
- all nine source Workflow blob SHAs;
- all nine target Workflow blob SHAs;
- all 43 unpinned, 14 artipacked, and one template-injection historical finding IDs;
- the reviewed Action repository/ref/SHA/comment mapping;
- exact-head runtime evidence requirements.

The plan is a transformation contract. The exact candidate Head and the current E4/R2B canonical SHA-256 values are emitted by the verifier at runtime because a committed file cannot contain the SHA of the commit that contains itself. Final Acceptance evidence records the implementation Head and Run metadata append-only after Run A.

## 3. Reviewed immutable Action identities

| Action repository | Reviewed prior ref | Reviewed immutable SHA | Comment |
| --- | --- | --- | --- |
| `actions/checkout` | `v4` | `11d5960a326750d5838078e36cf38b85af677262` | `# v4` |
| `actions/setup-java` | `v4` | `cf277c60eb25467037889841efdb72551f06f6c3` | `# v4` |
| `actions/setup-node` | `v4` | `49933ea5288caeca8642d1e84afbd3f7d6820020` | `# v4` |
| `actions/upload-artifact` | `v4` | `ea165f8d65b6e75b540449e92b4886f43607fa02` | `# v4` |
| `actions/upload-artifact/merge` | `v4` | `ea165f8d65b6e75b540449e92b4886f43607fa02` | `# v4` |
| `actions/download-artifact` | `v4` | `d3f86a106a0bac45b974a628896c90dbdf5c8093` | `# v4` |

The symbolic refs were re-resolved before implementation. They still resolved to the reviewed SHAs. No `UPSTREAM_SYMBOLIC_REF_DRIFT` was observed, and no new upstream SHA or major Action version was introduced.

## 4. Workflow behavior boundary

The governed inventory remains:

```text
9 Workflow files
1 automatic pull_request/main + push/main Workflow
8 workflow_dispatch-only Workflows
9 physical Jobs in approval-platform-validation.yml
4 permanent Artifact classes: Hygiene, Maven, Mobile, Vben
```

E3-R2B does not change Workflow names, triggers, job names, `needs`, matrix shape, runners, timeouts, permissions, cache behavior, Maven commands, selected-test semantics, Web commands, Mobile commands, scanner selection, deployment behavior, permanent Artifact names, paths, or retention shape.

All 14 checkout steps retain any existing settings. The hygiene checkout retains `fetch-depth: 0` and adds only `persist-credentials: false`.

The Persistence JDBC command retains the exact selected-test semantics:

```yaml
env:
  SELECTED_TESTS: ${{ steps.selection.outputs.tests }}
run: |
  mvn ... -Dtest="$SELECTED_TESTS" ...
```

The GitHub expression is data at the step environment boundary, not shell source. The shell uses a quoted variable and does not use `eval`, `source`, or command substitution to execute the value.

## 5. Permanent verifier

The permanent verifier is:

```text
scripts/security/m6-pr-e-e3-verify-workflow-supply-chain-remediation.mjs
```

The permanent boundary test is:

```text
scripts/tests/m6-pr-e-e3-r2b-workflow-supply-chain-remediation-boundary.test.mjs
```

The verifier fails closed unless all of the following are true:

1. repository, exact Head, and current E4 canonical digest agree;
2. current scanner execution count is exactly one;
3. the R2A Dependabot blob remains unchanged;
4. no suppression or ignore path is supplied;
5. current zizmor evidence is complete and contains zero findings;
6. the exact governed Workflow inventory is nine files with no missing or extra file;
7. every current Workflow content hashes to its target Git blob SHA;
8. all 43 Action uses resolve to the reviewed immutable SHA with a readable `# v4` comment;
9. all 14 checkout steps use `persist-credentials: false`;
10. the affected shell step contains the step-scoped environment boundary, quoted shell variable, and no direct expression expansion;
11. `pull_request_target` and write permissions remain absent;
12. exactly one automatic Workflow remains;
13. the four permanent Artifact classes remain Hygiene, Maven, Mobile, and Vben;
14. all 58 historical finding identities reconcile as absent from the exact current zizmor set;
15. the current non-zizmor scanner counts have not silently drifted from the bound R2A baseline;
16. the canonical R2B evidence payload is tied to the exact current Head and E4 evidence.

The E3-I4 reducer composes E3-R2A and E3-R2B remediation histories. It requires:

```text
CURRENT_ZIZMOR_SET + R2A_REMEDIATED_HISTORY + R2B_REMEDIATED_HISTORY
== PRIOR_REVIEWED_I4_SET
```

The expected post-remediation triage state, absent unrelated scanner drift, is:

```text
current findings:                 145
cumulative reviewed findings:      68
historically remediated findings:  63
NOT_APPLICABLE:                     3
APPLICABLE:                         0
UNRESOLVED:                       142
releaseBlocked:                  true
```

## 6. Security and release boundary

```text
ZERO_ZIZMOR_FINDINGS != ZERO_SECURITY_FINDINGS
CURRENT_SCAN_EXECUTION_COUNT == 1
NO_SUPPRESSION
NO_IGNORE
NO_SEVERITY_DOWNGRADE
NO_EXCEPTION
NO_BROAD_PATH_EXCLUSION
NO_PERMISSION_WIDENING
NO_PULL_REQUEST_TARGET
NO_SECOND_AUTOMATIC_WORKFLOW
NO_FIFTH_PERMANENT_ARTIFACT_CLASS
NO_ACTION_MAJOR_UPGRADE
NO_DEPENDABOT_CHANGE
NO_DEPLOYMENT
NO_RELEASE
NO_CANARY
NO_ROLLOUT
NO_TRAFFIC_MUTATION
NO_PRODUCTION_PROMOTION
```

Even after the Workflow findings reach zero, authoritative GitHub Code Scanning, Secret Scanning, and Dependabot Security Alert inventories remain unavailable. OSV, Gitleaks, and Semgrep findings remain for separate applicability and disposition work.

Therefore E3-R2B must retain:

```text
M6_PR_E_E3_FINDING_TRIAGE_REQUIRED
M6_PR_E_E3_CLOSURE_NOT_ACCEPTED
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
ISSUE_97_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
ISSUE_91_REMAINS_OPEN
ISSUE_13_REMAINS_OPEN
ISSUE_14_REMAINS_OPEN
PR_98_REMAINS_OPEN_DRAFT_UNMERGED
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
AI_IS_NOT_AN_OPERATOR
```

## 7. CI budget and current Gate state

The implementation candidate is developed on the non-triggering staging branch. PR #98 formal branch receives only a complete implementation candidate and, after Run A, one final documented Head.

```text
Planned full PR CI Runs: 2
Same-Head reruns:        0
Empty trigger commits:   0
workflow_dispatch runs:  0
Force pushes:            0
Deployments:             0
```

Until exact-head Run A, final evidence, Run B, all nine physical Job results, and all four independently verified Artifact ZIPs are recorded, the Gate remains:

```text
M6_PR_E_E3_R2B_WORKFLOW_SUPPLY_CHAIN_REMEDIATION_PENDING
```
