# M6-PR-E E4 — Code, Secret, Dependency and Workflow Supply-Chain Scanner Evidence

## Purpose

E4 adds reproducible scanner evidence to the existing `M6-PR-E` evidence chain without creating a second automatic workflow, a tenth physical Job, a fifth permanent Artifact class, a product capability, or production authority. It consumes the exact E2 dependency graph and feeds redacted findings into the E3 applicability/reachability process.

E4 does **not** replace unavailable authoritative GitHub Code Scanning, Secret Scanning or Dependabot Security Alert inventories. It also does not convert a scanner match into a vulnerability disposition.

## Permanent interpretation boundaries

```text
SCANNER_SUCCESS != ZERO_FINDINGS
ZERO_SCANNER_FINDINGS != AUTHORITATIVE_GITHUB_ZERO_ALERTS
SCANNER_FINDING != E3_DISPOSITION
DEPENDENCY_MATCH != APPLICABLE
CODE_PATTERN_MATCH != EXPLOITABLE
SECRET_CANDIDATE != CONFIRMED_LIVE_SECRET
RAW_SECRET_REPORT_MUST_NOT_BE_RETAINED
RAW_CODE_SNIPPET_REPORT_MUST_NOT_BE_RETAINED
SCANNER_INSTALLATION_MUST_BE_PINNED
MUTABLE_REMOTE_RULESET_PROHIBITED
SCANNER_TOOL_FAILURE_BLOCKS_EVIDENCE_CLOSURE
FINDINGS_REQUIRE_E3_APPLICABILITY_REACHABILITY_TRIAGE
```

No scanner finding is suppressed in the E4 baseline. Any future suppression requires exact finding/rule/path scope, rationale, owner, approver where required, creation time, expiry and a revalidation trigger. Broad path exclusions, global rule-family exclusions, severity downgrades used to make CI green, unowned suppressions and automatic renewal are prohibited.

## Scanner set

E4 uses four independent evidence sources.

1. **OSV-Scanner 2.5.0** for known vulnerabilities in the exact resolved third-party Maven dependency/BOM/plugin graph and committed pnpm external dependency set produced by E2. Its source identity is `google/osv-scanner@a258868211a57052da6bd323f758b8388dee02bb`. It is built from the exact Go module version with Go 1.26.5 whose Linux amd64 archive SHA-256 is retained in the scanner baseline.
2. **Gitleaks 8.30.1** for full Git-history candidate-secret scanning. The exact Linux x64 release asset SHA-256 is retained. No repository Gitleaks ignore/config file is permitted without a separately reviewed suppression contract, and the scan uses all available Git history refs. `--redact=100` is mandatory and raw reports are deleted after normalization.
3. **zizmor 1.26.1** for GitHub Actions / local action / Dependabot supply-chain auditing. It runs with `--offline`, strict collection and SARIF output; GitHub token variables are removed from the subprocess environment.
4. **Semgrep CE 1.172.0** for source-code security rule matching. The scanner image uses the exact version tag and records the resolved image digest at runtime. Rules are cloned from exact `semgrep/semgrep-rules@40b8c63f75dc7c22c8a77482d73bfb864b146f7e`; only security-path YAML rules under Java, JavaScript and TypeScript are selected, and the selected rule content digest is retained. No mutable Registry ruleset such as `p/default` is used. An unreviewed `.semgrepignore` is rejected rather than silently honored.

## Redaction and retention

Raw scanner reports exist only inside the runner temporary directory and are deleted before E4 returns. The existing Hygiene log retains only canonical normalized metadata.

- Gitleaks never retains `Secret`, raw `Match`, commit author/email/message or scanner stdout.
- Semgrep never retains source `lines`, metavariable captures or finding message text.
- zizmor retains only rule ID, level and source location metadata; SARIF message/snippet text is discarded.
- OSV retains package identity, advisory ID/aliases, fixed versions and upstream severity metadata needed for E3; full advisory prose is discarded.

No candidate Secret may appear in the retained canonical payload.

## Execution and CI shape

E4 is imported into the existing `scripts/tests/m6-ai-transport-review-boundary.test.mjs` aggregate. Actual scanner execution occurs only when `GITHUB_ACTIONS=true`. The existing Hygiene Job and existing `m6-ai-transport-review-boundary.log` retain the canonical E4 marker; the existing `approval-hygiene-*` Artifact remains the only E4 Artifact destination.

The automatic workflow count remains exactly one and the physical Job model remains nine. E4 requires no GitHub token and no Workflow permission expansion beyond the existing `contents: read`.

A scanner may validly complete with findings. Findings are evidence inputs and do not by themselves constitute a scanner execution defect. Tool installation failure, collection failure, parse failure or incomplete scanner execution fails the boundary test and blocks evidence closure.

## Current closure boundary

E4 scanner execution can establish a reproducible finding inventory for these exact scanner inputs. It cannot make unavailable authoritative GitHub alert APIs become available, and it cannot close E3 until each real finding has an E3 disposition with the required reachability evidence.

```text
M6_PR_E_E4_SCANNER_IMPLEMENTATION_DEFINED
E4_SCANNER_FINDINGS_REQUIRE_E3_TRIAGE
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
ISSUE_97_REMAINS_OPEN
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
AI_IS_NOT_AN_OPERATOR
```
