# M6-D G4 Post-Merge Review Corrections

Status: `SEVEN_FINDINGS_CORRECTED_FINAL_DOCUMENTATION_PENDING_VALIDATION`

## 1. Exact correction identity

- repository: `akaryc1b/approval-platform`
- source Pull Request: #70
- source documented Head: `8a62d3c8037ad5720e30b6918153750dd591c6e5`
- source M6-D Merge Commit: `21c086e57bc5814d8083076550d9fda71adabb4a`
- source natural `push -> main` Run: `30542735901`
- correction branch: `agent/m6-d-g4-post-merge-review-corrections`
- correction Pull Request: #77
- correction base: `main` at `21c086e57bc5814d8083076550d9fda71adabb4a`
- tracked Issue: #66
- parent Issue: #62

This is an append-only bounded post-merge correction. It does not alter the history of merged PR #70 and does not add new AI product capability.

The commit containing this document is the final documented correction Head. Its exact SHA, permanent Run, test evidence and artifact digests are recorded in PR #77 after validation because a Git commit cannot contain its own SHA.

## 2. Source main permanent verification

Run `30542735901` is the natural `push -> main` Run for M6-D Merge Commit `21c086e57bc5814d8083076550d9fda71adabb4a`.

Jobs:

- Java 21 / Maven / PostgreSQL: `success`
- Vben TypeScript / production build: `success`
- UniApp TypeScript / H5 / WeChat: `success`
- Repository hygiene: `success`

Maven evidence:

- aggregate: `1389 / 0 / 0 / 0`
- AI SPI: `12 / 12`
- AI Core: `85 / 85`
- request security/minimization: `6 / 6`
- deterministic evidence hashing: `2 / 2`
- activation/runtime trust: `15 / 15`
- transport review: `21 / 21`
- deployment/readiness: `18 / 18`
- ArchUnit module boundaries: `10 / 10`
- `BUILD SUCCESS`: present

Artifacts, independently downloaded and SHA-256 matched:

| Artifact | ID | Size | SHA-256 |
|---|---:|---:|---|
| Maven | `8759612602` | `26744` | `6c2135630e42074496aac9b8f743ad15c4e85011ed2b89e97d6e3aa65187a38d` |
| Vben | `8759417655` | `18885` | `a1ac3ea294ced4fb954ba7c15c815e197c16a9c80d5ac107ec0d03052162ea70` |
| Mobile | `8759398739` | `9795` | `6db7c0daf676721971e4fd4b8fcd869e0ad21b1f095f420f8064ab5788d5520d` |
| Hygiene | `8759375862` | `9239` | `9f9421f5c9489f3570da98a8912819cda0f3ec39f76f03a9edc86d1b637c00b7` |

## 3. Original post-merge findings on PR #70

### Finding 1 — stale circuit completion closes a newer circuit generation

Severity: `P1`

Correction:

- circuit state carries a monotonically increasing generation;
- permits bind the generation in which they were acquired;
- stale record/release operations cannot mutate a newer circuit generation;
- only a current HALF_OPEN probe may close a HALF_OPEN circuit.

Commits:

- `2a434f02a6747f0ba04707e5cd5eb67d17c83ab0`
- `66cc7231acae329c9d1acd0e3e4f4f640db976e4`

Regression: `AiProviderCircuitBreakerGenerationTest` — `1 / 1`.

### Finding 2 — protocol Provider/capability changes omitted from deployment evidence

Severity: `P1`

Correction:

- added `VALIDATION_PROVIDER_CHANGED`;
- added `VALIDATION_CAPABILITIES_CHANGED`;
- profile fingerprints bind Provider, sorted capabilities, schemas, limits and closed protocol flags;
- either change is critical and requires human review.

Commits:

- `80c2b627f3fbd430a10a2f4fdcf010ba8e57ea20`
- `39945da0fd1095d813f446cd1d982b71e5e1161b`

Regression: `AiProviderDeploymentProfileMetadataChangeTest` — `1 / 1`.

### Finding 3 — activation review constructor accepts unrelated bundle hash

Severity: `P2`

Correction:

- canonical construction recomputes the normalized bundle hash;
- a mismatched hash fails closed.

Commits:

- `881bb744731246401d277dd5d4e997fe3a432126`
- `dd4841b85aab913910a63413a1aeb9456b633b12`

Regression was initially `1 / 1` and is extended by Finding 6 below.

### Finding 4 — delimiter ambiguity in audit evidence hashing

Severity: `P2`

Correction:

- audit tuples use deterministic length framing;
- the complete Provider/model/Prompt-metadata/knowledge-metadata/policy/schema tuple is length-framed.

Commits:

- `7a23ac288e0584be2e8aae83b6edd125fa9d4f4a`
- `820a8ae5899e0c0e1e0d74b53bfe57eb671cb2a1`

Regression: `AiAuditRecordFramingTest` — `1 / 1`.

### Finding 5 — Provider collection/depth limits omitted from Startup Preflight

Severity: `P2`

Correction:

- Provider matching can consume the exact data-minimization policy;
- `maximumCollectionSize` and `maximumDepth` must fit the Provider capability;
- Startup Preflight uses the data-policy-aware match.

Commits:

- `03d06138619753f37ab33fd48e143a87b9dca1fa`
- `b9843ac80abd61904325eb7dd3a6cd4c82fcb0bb`
- `4944c3e285b7740e67670d2fa63a8fe40eda3db2`

Regression: `AiProviderRegistryNestingLimitTest` — `1 / 1`.

All five PR #70 threads were answered with correction evidence and resolved.

## 4. First correction validation and Ready review

Corrected code Head `39945da0fd1095d813f446cd1d982b71e5e1161b` passed Run `30597183681` / #950:

- all four jobs: `success`
- Maven: `1394 / 0 / 0 / 0`
- AI SPI: `12 / 12`
- AI Core: `90 / 90`
- five finding regressions: `5 / 5`
- four artifacts independently SHA-256 matched.

The first documented correction Head `8f0899594bb11182c814f6c5025558bfee6fb638` passed Run `30597595375` / #951 with the same Maven/focused result and four new exact artifact matches.

Run #951 is retained successful evidence, but it is not the final merge evidence because the subsequent Ready-for-Review Codex review found Findings 6 and 7. PR #77 was immediately returned to Draft before merge.

## 5. Ready-review findings on PR #77

### Finding 6 — activation review hash still used delimiter joins

Severity: `P2`

The canonical constructor recomputed the hash, but adjacent identifiers containing `|` could still redistribute field boundaries.

Correction:

- every activation-review canonical field is length-framed;
- every sorted reviewer tuple is independently length-framed;
- `ReviewerApproval.create` also benefits from the framed hash function;
- delimiter redistribution cannot reuse an existing bundle hash.

Commits:

- `877cb4618be5c3be6476dfb56de257434905ae88`
- `41ac7ec2d769186f75d3252a8107a705902f1dae`

Regression:

- `AiProviderActivationReviewBundleHashTest`: `2 / 2`, including `delimiterRedistributionCannotReuseBundleHash`.

### Finding 7 — invocation-time matcher omitted the exact data policy

Severity: `P2`

Startup Preflight enforced collection/depth limits, but the Coordinator invocation path still called the legacy two-argument matcher.

Correction:

- `AiAdvisoryCoordinator` passes the exact server-owned `dataPolicy` to `registry.matches(provider, route, dataPolicy)`;
- an incompatible collection/depth policy blocks route selection even when Startup Preflight is not an enforced dependency;
- Provider invocation count remains zero.

Commits:

- `5cca9afb088c0575aff7a21f71e2246a34c89da5`
- `b38eea8a59a4be19444eac823dc55dc83070d9c3`

Regression:

- `AiAdvisoryCoordinatorProviderNestingLimitTest`: `1 / 1`.

Both PR #77 Ready-review threads were answered with Run #955 evidence and resolved.

## 6. Seven-finding code-Head permanent verification

Exact code Head:

`b38eea8a59a4be19444eac823dc55dc83070d9c3`

Permanent workflow:

- Run ID: `30598769246`
- Run number: `955`
- conclusion: `success`
- Java 21 / Maven / PostgreSQL: `success`
- Vben TypeScript / production build: `success`
- UniApp TypeScript / H5 / WeChat: `success`
- Repository hygiene: `success`

Maven evidence recalculated from `maven-verify.log`:

- aggregate: `1396 / 0 / 0 / 0`
- AI SPI: `12 / 12`
- AI Core: `92 / 92`
- seven finding regressions: `7 / 7`
- request security/minimization: `6 / 6`
- evidence hashing/framing: `3 / 3`
- activation-review bundle/hash/framing: `6 / 6`
- Circuit Breaker including generation: `3 / 3`
- deployment change including protocol metadata: `4 / 4`
- Provider registry including nesting: `3 / 3`
- Coordinator including invocation-time nesting: `6 / 6`
- activation/runtime trust: `17 / 17`
- transport review: `21 / 21`
- deployment/readiness: `19 / 19`
- ArchUnit module boundaries: `10 / 10`
- `BUILD SUCCESS`: present

Artifacts, independently downloaded and SHA-256 matched:

| Artifact | ID | Size | SHA-256 |
|---|---:|---:|---|
| Maven | `8781232591` | `26769` | `dd8139d288b16b4bfb1ff265336cccc7d5d679602802add512c6b3446bfaa838` |
| Vben | `8781133003` | `18841` | `6e73502976f3b49168c1ed427327a994ad9e336f3af00843088e994d0c97f7e4` |
| Mobile | `8781122868` | `9796` | `837a3d4676b661895ef6fe190f876409ff1f3912db70d2d49a1ddd593cff816d` |
| Hygiene | `8781109911` | `9242` | `e363fd9a20bdb48681b0dbca037ab3a60a574b838050998bd08a103015092ff3` |

Artifacts expire `2026-10-29T02:21:40Z` and are currently unexpired.

## 7. Permanent scope boundary

The correction contains no:

- real OpenAI, Anthropic, Azure OpenAI, Gemini or other Provider adapter;
- real HTTP client, DNS lookup, TLS handshake or network egress;
- runtime Secret retrieval, Secret material or signature calculation;
- production credential, Prompt content or customer knowledge;
- attachment extraction, RAG, embeddings or vector database;
- AI persistence, durable state, Outbox, Queue, Worker or Scheduler;
- participant or management AI endpoint or Web/Mobile AI surface;
- AI-driven approval decision or process command;
- executable activation or transport acceptance;
- Flyway migration;
- second automatic workflow;
- M6-E or M6-F capability.

M6-D remains `ADVISORY`, `UNVERIFIED_ADVISORY`, `needsHumanReview = true` and non-executable. M5 migration/runtime binding, M6-A connector invocation, M6-B event delivery and M6-C Draft-only semantics are unchanged.

## 8. Remaining closure gates

PR #77 must remain Draft until this final documented correction Head:

- completes a new full permanent workflow successfully;
- produces four independently SHA-256-matched artifacts;
- has recalculated Maven and focused evidence;
- is behind `0` relative to current `main`;
- has no unresolved actionable Review finding.

Ready and merge remain separate. Final integration may use only a Merge Commit with exact expected Head. Issue #66 remains Open after correction merge until the natural correction `push -> main` Run, four correction-main artifact matches, final Maven/focused evidence and final Review closure are complete.
