# M6-PR-E E2 Deterministic Dependency Graph and SBOM

Status: `M6_PR_E_E2_IMPLEMENTATION_STAGED`

Tracking: Issue `#97`, Draft PR `#98`.

## 1. Gate purpose and boundary

E2 produces exact-commit machine-readable dependency evidence for the repository-supported Maven reactor, committed pnpm workspace and tracked GitHub Actions. E2 does not perform vulnerability applicability analysis, code scanning, Secret scanning, dependency upgrades, Ready, Merge, deployment or Production Promotion.

Permanent E2 boundaries:

```text
DEPENDENCY_GRAPH != VULNERABILITY_FINDING
MAINTENANCE_PR != APPLICABLE_VULNERABILITY
SBOM != REACHABILITY_ANALYSIS
MUTABLE_ACTION_REF != IMMUTABLE_ACTION_IDENTITY
GENERATED_UPSTREAM_TREE != FIRST_PARTY_DEPENDENCY_GRAPH
NO_LOCKFILE != ZERO_DEPENDENCIES
```

`PRB-16` and `PRB-17` remain Open in E2. E2 only establishes dependency identity and graph evidence needed by later E3 applicability/reachability analysis.

## 2. Exact starting identity

- `main`: `1747b22123fd71cccd8334853ad7060c6645b443`;
- accepted E0 Head: `05b830864913b8213267e1eaf4c100d013ba880b`;
- E0 natural Run: `31367846138` / `#1386`, success;
- PR #98 remains Open / Draft / unmerged;
- E1 retained authoritative GitHub alert/API unavailability and made no zero-alert claim.

A future E2 natural Run must bind its evidence to the exact PR head from `pull_request.head.sha`, not merely the synthetic PR merge commit.

## 3. Maven graph contract

The exact repository reactor contains 26 Maven projects: the root, `server-modules` aggregator, its 21 modules, `integrations/host-sdk`, `apps/server`, and `examples/generic-spring-host`.

The E2 generator pins:

```text
org.apache.maven.plugins:maven-dependency-plugin:3.11.0
```

It executes `dependency:tree` once across the reactor with no scope filter so compile, runtime, provided and test dependencies remain distinguishable in the resolved graph. It also executes `resolve-plugins` with transitive plugin dependencies retained. Both outputs are normalized into sorted machine-readable evidence.

Imported BOM identity is retained separately from the resolved tree, including the accepted Spring Boot, Flowable and Testcontainers BOMs. Plugin identity is not inferred from version properties alone; resolved plugin output is retained with a digest.

License information is read only from resolved Maven POM metadata already present after graph resolution. Missing license metadata is recorded as `EVIDENCE_UNAVAILABLE`; it is never guessed.

## 4. pnpm graph contract

The tracked workspace file contains broad globs, but the exact committed workspace at E0 resolves to six package projects:

1. repository root;
2. `packages/approval-sdk`;
3. `packages/contracts`;
4. `packages/form-schema`;
5. `packages/process-dsl`;
6. `examples/connector-smoke`.

The four shared packages and connector smoke package declare no external package dependencies. The root declares exactly one external package:

```text
typescript@5.9.3
```

The repository does not commit a root `pnpm-lock.yaml`. E2 therefore records:

```text
ROOT_PNPM_LOCKFILE_ABSENT
COORDINATE_GRAPH_EXACT_FROM_PINNED_MANIFEST
PACKAGE_TARBALL_INTEGRITY_NOT_REPOSITORY_PINNED
```

This is a limitation, not an empty dependency claim. `.upstream/vben`, `.upstream/unibest` and RuoYi bootstrap trees are generated mutable workspaces and are not reclassified as first-party root workspace dependencies. Their bootstrap commit/lock boundaries remain separate evidence inputs.

## 5. GitHub Actions graph contract

Every tracked `.github/workflows/*.yml` file is inventoried, including manual workflows. Every external `uses:` value records:

- workflow path;
- declared `owner/repository[/path]@ref`;
- whether the declared ref is already a full 40-character SHA;
- the immutable commit observed for known major-tag refs;
- whether the workflow is automatic or manual;
- repository-level `contents: read` posture where declared.

The static action-resolution baseline is:

`docs/m6/m6-pr-e-e2-action-resolution-baseline.json`

Major refs such as `@v4` remain classified as mutable even when their current commit is known. E2 records, but does not merge, maintenance update PRs.

The exact E2 action-resolution baseline is:

| Declared action | Current resolved commit |
| --- | --- |
| `actions/checkout@v4` | `11d5960a326750d5838078e36cf38b85af677262` |
| `actions/setup-java@v4` | `cf277c60eb25467037889841efdb72551f06f6c3` |
| `actions/setup-node@v4` | `49933ea5288caeca8642d1e84afbd3f7d6820020` |
| `actions/upload-artifact@v4` | `ea165f8d65b6e75b540449e92b4886f43607fa02` |
| `actions/upload-artifact/merge@v4` | `ea165f8d65b6e75b540449e92b4886f43607fa02` |
| `actions/download-artifact@v4` | `d3f86a106a0bac45b974a628896c90dbdf5c8093` |

These resolved commits describe the current mutable major tags; they do not make the repository declarations immutable. `actions/setup-java@v4` is also upstream-deprecated, but E2 does not perform the independent compatibility review required to upgrade it.

Open GitHub Actions maintenance inputs at the E2 rebaseline are PRs `#1`, `#2`, `#4`, `#73` and `#94`. Each is classified only as:

```text
MAINTENANCE_MAJOR_UPDATE_NOT_VULNERABILITY_FINDING
```

## 6. Machine-readable output

The generator is:

`scripts/security/m6-pr-e-e2-generate-sbom.mjs`

The schema is:

`docs/m6/m6-pr-e-e2-sbom.schema.json`

The E2 boundary is imported by the existing permanent M6 Node aggregate. In GitHub Actions it runs the full Maven resolution and emits one canonical JSON payload between exact markers:

```text
M6_PR_E_E2_SBOM_BEGIN
<canonical single-line JSON>
M6_PR_E_E2_SBOM_END
```

Because the existing Hygiene Job already redirects the M6 aggregate to `m6-ai-transport-review-boundary.log`, E2 evidence is retained inside the existing Hygiene Artifact class. No second Workflow and no fifth Artifact class are introduced.

The canonical payload is sorted before hashing. `contentSha256` is the SHA-256 of the payload with `contentSha256` omitted, making independent reconstruction deterministic.

## 7. Fail-closed conditions

E2 fails when any of the following occurs:

- exact PR head cannot be derived;
- Maven is unavailable in the authoritative CI environment;
- Maven dependency-tree resolution fails;
- fewer than 26 reactor roots are resolved;
- dependency JSON cannot be parsed;
- plugin resolution fails;
- a workspace manifest is omitted or duplicated;
- a non-exact package version is introduced without a committed lockfile;
- an external `uses:` reference is omitted from the inventory;
- an action resolution baseline conflicts with an observed exact workflow ref;
- raw candidate Secret material is detected in generated evidence.

No failed source becomes an empty graph.

## 8. E2 exit condition

E2 may be accepted only after one controlled branch update naturally produces one successful PR Workflow Run, all nine physical Jobs and four existing Artifact classes succeed, the Hygiene Artifact contains a parseable E2 payload bound to the exact E2 Head, its digest independently reconstructs, and Review/Thread state remains clean.

E2 acceptance does not close `PRB-16` or `PRB-17` and does not authorize E4 scanners or E6 Ready/Merge.

```text
PRB_16_REMAINS_OPEN
PRB_17_REMAINS_OPEN
NO_READY
NO_MERGE
NO_DEPLOYMENT
NO_PRODUCTION_PROMOTION
AI_IS_NOT_AN_OPERATOR
```
