# M6-PR-E E3-R2A — Dependabot Cooldown Remediation

Status: `E3_R2A_DEPENDABOT_COOLDOWN_REMEDIATION_IMPLEMENTED_VALIDATION_PENDING`

This remediation addresses only the three `zizmor/dependabot-cooldown` findings classified `APPLICABLE` by E3-I4. It changes no GitHub Actions Workflow, action identity, checkout credential behavior, template-injection boundary, dependency version, scanner rule, suppression, Flyway migration, product source, deployment or Production Promotion.

## Prior accepted evidence

- prior accepted Head: `bab2816ad0b8a43eaa022214170a79ae8af1473f`;
- prior I4 finding-set SHA-256: `d12463e28555e88fbed0e9ae73a83232296fb1879e2d7479a91f4e89255bc2fe`;
- prior `.github/dependabot.yml` blob: `6ad5b685c038fff8f2fded0ae9de0152dc0f6a34`;
- reviewed cooldown findings: `3`;
- reviewed current zizmor findings before R2A: `61`.

Historical I4 evidence remains immutable. R2A does not delete or rewrite the three prior finding identities.

## Exact configuration change

Each existing updater keeps its original package ecosystem, root directory, weekly schedule and open-PR limit. R2A adds only:

```yaml
cooldown:
  default-days: 7
```

to the existing Maven, npm and GitHub Actions updater entries.

Target `.github/dependabot.yml` blob:

```text
38ba75af261c084b5c8984c52fb1bf23439fd1a9
```

GitHub documents `cooldown.default-days` for these ecosystems. Cooldown delays Dependabot version updates; it does not delay Dependabot security updates. Therefore this control adds a supply-chain observation window without intentionally slowing security-update delivery.

## Scanner/remediation contract

The three historical cooldown findings may be treated as remediated only when all of the following hold:

1. current `.github/dependabot.yml` has the exact target blob above;
2. zizmor completes successfully at the exact PR Head;
3. current zizmor contains zero `zizmor/dependabot-cooldown` findings;
4. the three historical finding IDs remain retained in the remediation record;
5. remaining I4 findings are not reclassified by absence of these three findings;
6. no suppression, severity downgrade or exception is added.

Expected deterministic workflow-supply-chain transition:

```text
zizmor/dependabot-cooldown  3 -> 0  (historically remediated)
zizmor/unpinned-uses       43 -> 43 (still APPLICABLE)
zizmor/artipacked          14 -> 14 (still APPLICABLE)
zizmor/template-injection   1 -> 1  (still APPLICABLE)
current zizmor total       61 -> 58
```

The overall scanner total is not hard-coded because independent advisory databases may acquire new findings over time. The zizmor source set is pinned and must satisfy the exact transition above.

## Evidence-chain repair

Run #1424 validated I4 but the permanent Hygiene log did not directly emit an I4 canonical marker; its I4 digest was independently reconstructed outside the Artifact. R2A closes that evidence-integrity gap without a second scan: the existing full E4 scanner test must emit one chain from the same in-memory scanner payload:

```text
E4 -> E3-I1 -> E3-I2 -> E3-R1 -> E3-I3 -> E3-R2A -> E3-I4
```

Both R2A and current I4 canonical payloads must be retained in the existing Hygiene Artifact.

## Permanent invariants

```text
REMEDIATION != HISTORY_REWRITE
COOLDOWN_FINDING_ABSENCE_REQUIRES_EXACT_DEPENDABOT_BLOB
DEPENDABOT_COOLDOWN != SECURITY_UPDATE_DELAY
CURRENT_ZIZMOR_SET_PLUS_REMEDIATED_HISTORY == PRIOR_REVIEWED_I4_SET
NO_WORKFLOW_CHANGE_IN_R2A
NO_ACTION_PIN_CHANGE_IN_R2A
NO_CHECKOUT_CREDENTIAL_CHANGE_IN_R2A
NO_TEMPLATE_INJECTION_CHANGE_IN_R2A
NO_SUPPRESSION
NO_SEVERITY_DOWNGRADE
NO_EXCEPTION
M6_PR_E_E3_FINDING_TRIAGE_REQUIRED
M6_PR_E_E3_CLOSURE_NOT_ACCEPTED
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
AI_IS_NOT_AN_OPERATOR
```
