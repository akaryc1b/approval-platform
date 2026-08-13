# M6-PR-E / E3-R2B — Final Acceptance

Status: `M6_PR_E_R2B_INFRASTRUCTURE_ACCEPTED_PENDING_FINAL_DOCUMENTED_HEAD_VALIDATION`

This record accepts the independently mergeable security-evidence and GitHub Actions supply-chain infrastructure implemented by E0 through E4 and E3-R2B. It does **not** accept M6-PR-E security closure, does not close `PRB-16` or `PRB-17`, and does not authorize production promotion.

## 1. Exact implementation evidence

| Field | Exact value |
| --- | --- |
| Repository | `akaryc1b/approval-platform` |
| Base `main` | `1747b22123fd71cccd8334853ad7060c6645b443` |
| Pull Request | `#98` |
| Issue | `#97` |
| Implementation Head | `c07295e38d6bb9c3717ad727f873ab7112a6e752` |
| Natural implementation Run | `31688917633` / `#1439` |
| Run conclusion | `success` |
| Physical Jobs | `9 / 9 success` |
| Same-Head reruns | `0` |
| Force pushes | `0` |
| Empty trigger commits | `0` |
| Deployments | `0` |

Exact Job IDs:

```text
Repository hygiene                    94411449231
Java 21 / Maven core                 94411449138
Persistence JDBC / shard 0           94411449352
Persistence JDBC / shard 1           94411449343
Persistence JDBC / shard 2           94411449425
Persistence JDBC / shard 3           94411449513
Java 21 / Maven / PostgreSQL         94412052256
Vben TypeScript / production build   94411449379
UniApp TypeScript / H5 / WeChat      94411449244
```

## 2. Exact scanner and reconciliation state

The exact-Head scanner chain executed once and emitted:

```text
E2 canonical SHA-256:        7a70fe56e2411cdb85f947d121004fdfd34048d1a002f6842c45a27af3563d1c
E3 canonical SHA-256:        312076795b2a48e5ec064b148194a64f4abaef815fcf72ca9e77dfeba74f430c
E4 canonical SHA-256:        2e7f29622bc08b206fd7f29e236b5296ea81a3daecdb819f43df8e59185ad22e
E3-I1 canonical SHA-256:     24f99415c4f0a47009a65690fb7d14b7cdfb94424331b9a9c72c51beaaa63a14
E3-I2 canonical SHA-256:     2cb0ad6d9d48cf1357dc6a62ef7e3801ca8450e8df7709f3f147bab36eaf7009
E3-R1 canonical SHA-256:     b084078169904cf96a2634ba331cd4f2cec77e726a1908ae960f2971f2c384c5
E3-I3 canonical SHA-256:     aa942acf1c5f05da0abe965f419a61d20d3260aa707b518b19c451ffa00465af
E3-R2A canonical SHA-256:    a0849d126d5037eba5eadcc404e6277a6c2ac409d49b44bc3e63484a7896314b
E3-R2B canonical SHA-256:    6406e786410fd7e8ad48fa5a79adfcb238fc981219df65cc9a23a85e5c75f399
M6_PR_E_E3_R2B_REMEDIATION_CANONICAL_SHA256=6406e786410fd7e8ad48fa5a79adfcb238fc981219df65cc9a23a85e5c75f399
E3-I4 canonical SHA-256:     f822a44706c0de783f9e0176e4f5d901f59580156ed6cf88e07ed95fdbbf3e35
```

Current exact finding set:

```text
OSV:       117
Gitleaks:   27
zizmor:      0
Semgrep:     3
Total:     147
```

Identity-level reconciliation proves:

- all `115` historically accepted OSV identities remain present;
- exactly two reviewed OSV identities were added by advisory-database drift;
- `GHSA-x4m4-345f-5h5g` / `CVE-2026-34487` remains `UNRESOLVED`;
- `GHSA-hf6x-8p5f-cgmf` / `CVE-2026-54399` remains `UNRESOLVED`;
- no historical OSV identity was deleted;
- the accepted Gitleaks identity set remains exact;
- the reviewed Semgrep identity transition remains exact;
- the current zizmor identity set is empty;
- no suppression, exception, severity downgrade, broad exclusion or finding-deletion claim was introduced.

The composed I4 decision remains:

```text
NOT_APPLICABLE:   3
APPLICABLE:       0
UNRESOLVED:     144
releaseBlocked: true
authoritative GitHub inventory complete: false
```

The planned `145 / 142` counts in the pre-run R2B contract are superseded by this exact identity-level implementation evidence. Scanner totals are not treated as stable constants.

## 3. Workflow supply-chain remediation accepted

The implementation permanently proves:

- `43` reviewed external Action uses are pinned to immutable commit SHAs with version comments;
- all `14` checkout steps use `persist-credentials: false`;
- the single template-injection path uses a step-scoped environment variable and quoted shell data;
- no `pull_request_target` exists;
- least-privilege workflow permissions remain;
- exactly one automatic PR/main workflow remains;
- the governed inventory remains nine workflow files and nine physical Jobs;
- the permanent Artifact classes remain exactly Maven, Vben, Mobile and Hygiene;
- the 58 historical R2B workflow findings and three R2A cooldown findings remain retained as remediated history rather than deleted history.

## 4. Correction lineage

| Natural Run | Head | Result | Exact correction |
| --- | --- | --- | --- |
| `31658751966` / `#1436` | `869d49cb7e9ef45109d3ac79f804908e5c58451a` | failure | Replaced the fixed `115` OSV count assertion with exact retained/added/removed identity reconciliation. |
| `31678475915` / `#1437` | `5ac406013da08fc482a0bf512e83aeef88a07dd0` | failure | Identified a new Semgrep finding created by tainted dynamic `RegExp` construction in the R2B verifier. |
| `31688917633` / `#1439` | `c07295e38d6bb9c3717ad727f873ab7112a6e752` | success | Replaced dynamic construction with a literal parser plus explicit indentation comparison and retained the permanent regression boundary. |

No failed Run was erased, rewritten or rerun at the same Head.

## 5. Permanent regression evidence

```text
Maven Core:                 1463 / 0 failures / 0 errors / 0 skipped
Persistence JDBC:            325 / 0 / 0 / 0
Aggregate Maven tests:      1788 / 0 / 0 / 0
selected JDBC classes:        80 / 80 unique
Surefire report classes:      79
expected abstract classes:     1
duplicate selections:          0
non-abstract missing:          0
M6 transport Node boundary: 263 / 263 PASS
Vben type-check/build:       success
Mobile type-check/H5/WeChat: success
```

## 6. Independently verified implementation Artifacts

All four ZIPs bind exact implementation Head `c07295e38d6bb9c3717ad727f873ab7112a6e752`. Each was independently downloaded, its SHA-256 matched the GitHub Artifact digest, and `unzip -t` reported no errors.

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `9176627310` | `358474` | `9fd8729ab18bf289135788f8ef72b556314f8a1c0856661eb967ed0da10c168c` |
| Vben | `9176583516` | `18616` | `895bf7f152bc2566ece9531302b2bb5865bed859f6d1c052d4688dac3b7a4816` |
| Mobile | `9176570026` | `9808` | `6aa74ae77be16a9655913d22d57cccf5aa0b537e55e4dbe9ad62653e858781b6` |
| Hygiene | `9176650547` | `149204` | `1f10bac696633d8e0c942b929c9608850eb986c79ab7377677ced0674694332a` |

## 7. Final documented Head gate

This document cannot contain the SHA of the commit that contains itself. The final documented Head is therefore accepted only when all of the following externally agree without another repository change:

1. PR #98 `head_sha`;
2. the natural final `pull_request` Run `head_sha`;
3. all nine final Job identities and success conclusions;
4. all four final Artifact `workflow_run.head_sha` values;
5. an unchanged merge base and mergeable PR;
6. no `REQUEST_CHANGES`, unresolved review thread or actionable security review finding.

After that final gate, PR #98 may be marked Ready and merged with an ordinary Merge Commit as independently accepted scanner, SBOM and workflow-hardening infrastructure.

## 8. Explicit non-closure decision

Merging this independently accepted infrastructure does not falsify the remaining security work:

```text
M6_PR_E_R2B_INFRASTRUCTURE_ACCEPTED
M6_PR_E_SECURITY_CLOSURE_NOT_ACCEPTED
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
ISSUE_97_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
ISSUE_91_REMAINS_OPEN
ISSUE_13_REMAINS_OPEN
ISSUE_14_REMAINS_OPEN
NO_ZERO_ALERT_CLAIM
NO_SUPPRESSION
NO_EXCEPTION
NO_SEVERITY_DOWNGRADE
NO_FINDING_DELETION_CLAIM
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
AI_IS_NOT_AN_OPERATOR
```

A later final Security Closure PR must rebaseline the then-final `main`, obtain authoritative GitHub alert inventories or retain the exact external permission blocker, and close every applicable/reachable release blocker before `PRB-16`, `PRB-17` or Issue #97 can pass.
