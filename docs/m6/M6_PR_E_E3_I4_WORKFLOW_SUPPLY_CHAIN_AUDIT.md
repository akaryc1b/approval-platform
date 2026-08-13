# M6-PR-E E3-I4 — GitHub Actions Workflow Supply-Chain Audit

Status: `E3_I4_WORKFLOW_SUPPLY_CHAIN_AUDIT_REVIEWED_VALIDATION_PENDING`

This batch reviews exactly the 61 current E4 zizmor findings retained by Run #1420 at Head `a88ca0267f199c98026e041aa2a43f84f5491b8e`. It is evidence-only: no Workflow, Dependabot, dependency, scanner, suppression, Flyway, product source, deployment or production authority is changed.

## Exact review basis

- E4 canonical SHA-256: `3c0908693520584b5e62fca108e1af571f2af310fd92becd4ac3fb87347ebc95`
- E3-I3 canonical SHA-256: `38b39c38f8b2352b651c23f6c89d696f44a111d6a9f01970f393c7a1766c3d0f`
- current scanner findings: `206`
- current zizmor findings: `61`
- prior dispositions: `NOT_APPLICABLE=3`, `APPLICABLE=0`, `UNRESOLVED=203`
- prior cumulative reviewed findings: `7`
- historical remediations: `2`

All nine Workflow blob SHAs and `.github/dependabot.yml` are bound in `m6-pr-e-e3-i4-reviewed-findings.json`. Natural validation must reject source drift and must prove that the complete current zizmor finding identity set exactly equals the reviewed 61-item set.

## Closed rule inventory

```text
zizmor/unpinned-uses       43  -> APPLICABLE
zizmor/artipacked          14  -> APPLICABLE
zizmor/dependabot-cooldown  3  -> APPLICABLE
zizmor/template-injection   1  -> APPLICABLE
TOTAL                       61
```

### `zizmor/unpinned-uses` — 43 applicable

The repository commits symbolic major refs such as `actions/checkout@v4`, `actions/setup-java@v4`, `actions/setup-node@v4`, `actions/upload-artifact@v4`, `actions/upload-artifact/merge@v4` and `actions/download-artifact@v4`. E2 independently retains the exact commit each symbolic ref currently resolves to, but the committed Workflow still delegates future executable action selection to a mutable external tag.

The current E2 action-resolution baseline supplies an exact R2 target for each symbolic ref. A current resolution SHA is evidence of today's target; it is not equivalent to committing an immutable action identity.

### `zizmor/artipacked` — 14 applicable

Affected `actions/checkout@v4` steps omit `persist-credentials: false`, so checkout persists the workflow credential for later process access on the runner. Repository-level `contents: read` limits mutation authority, but does not make a bearer credential absent and does not prove later repository/dependency code cannot read it.

The bounded remediation is to set `persist-credentials: false` on every affected checkout step while preserving existing fetch-depth behavior where present.

### `zizmor/dependabot-cooldown` — 3 applicable

The Maven, npm and GitHub Actions Dependabot updaters are scheduled weekly but define no cooldown. Pull-request-only dependency updates reduce autonomous mutation, but do not provide a time buffer against a newly published compromised dependency becoming an update candidate.

The bounded remediation is one seven-day default cooldown for each existing updater, without changing cadence, ecosystem coverage or PR limits.

### `zizmor/template-injection` — 1 applicable

The automatic PR/main Workflow directly interpolates `steps.selection.outputs.tests` into a shell `run` block as the Maven `-Dtest` argument. That output is produced by a repository script executed from the pull-request checkout. A pull request can therefore modify the output producer, and GitHub expression expansion occurs before the shell interprets the generated script.

The static matrix values themselves are not the unsafe boundary; the pull-request-modifiable step output is. The bounded remediation is to pass the output through a step `env` value and reference a normal shell variable inside `run` rather than embedding the `${{ ... }}` expression directly in shell source.

## Post-review machine state

If and only if the natural current-Head scan contains exactly the reviewed 61 zizmor identities and all source blobs remain unchanged, the expected state is:

```text
current findings:              206
cumulative reviewed findings:   68
historically remediated:          2
NOT_APPLICABLE:                   3
APPLICABLE:                      61
UNRESOLVED:                     142
releaseBlocked:                true
```

`APPLICABLE` is not an authorization to merge or a severity rewrite. All 61 Workflow supply-chain findings remain release-blocking until a separate remediation cycle removes the vulnerable conditions and the scanner independently confirms the transition.

## R2 bounded remediation backlog

```text
43  replace symbolic action refs with reviewed immutable SHAs plus version comments
14  set actions/checkout persist-credentials: false
 3  set Dependabot cooldown default-days: 7
 1  move untrusted step output to env/shell-variable boundary
```

R2 is not implemented by E3-I4.

## Permanent invariants

```text
MUTABLE_REF != IMMUTABLE_ACTION_IDENTITY
CURRENT_RESOLVED_SHA != COMMITTED_SHA_PIN
CONTENTS_READ != CREDENTIAL_NOT_PRESENT
WORKFLOW_DISPATCH != SUPPLY_CHAIN_UNREACHABLE
DEPENDABOT_PULL_REQUEST_ONLY != COOLDOWN_PRESENT
STATIC_MATRIX != TRUSTED_PULL_REQUEST_MODIFIABLE_STEP_OUTPUT
TEMPLATE_EXPANSION != SHELL_DATA_BOUNDARY
SCANNER_FINDING != AUTOMATIC_SUPPRESSION
NO_SUPPRESSION
NO_SEVERITY_DOWNGRADE
NO_EXCEPTION
NO_WORKFLOW_CHANGE_IN_E3_I4
NO_DEPENDABOT_CHANGE_IN_E3_I4
M6_PR_E_E3_CLOSURE_NOT_ACCEPTED
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
AI_IS_NOT_AN_OPERATOR
```
