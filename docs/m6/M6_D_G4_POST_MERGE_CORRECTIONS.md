# M6-D G4 Post-Merge Review Corrections

Status: `FORMAL_CORRECTION_DOCUMENTED`

## 1. Baseline and purpose

- repository: `akaryc1b/approval-platform`
- source Pull Request: #70
- source documented Head: `8a62d3c8037ad5720e30b6918153750dd591c6e5`
- M6-D Merge Commit / correction base: `21c086e57bc5814d8083076550d9fda71adabb4a`
- source natural `push -> main` Run: `30542735901`
- correction branch: `agent/m6-d-g4-post-merge-review-corrections`
- correction Pull Request: #77
- tracked Issue: #66
- parent Issue: #62

This is a bounded post-merge correction. It does not modify the history of merged PR #70. It addresses only five actionable correctness/security findings submitted after PR #70 merged.

The commit containing this document is the final documented correction Head. Its exact SHA, permanent Run and artifact digests are recorded in PR #77 after validation because a commit cannot contain its own SHA.

## 2. Source main verification

Run `30542735901` is bound to:

- branch: `main`
- Head: `21c086e57bc5814d8083076550d9fda71adabb4a`
- workflow: `.github/workflows/approval-platform-validation.yml`

All jobs completed successfully:

- Java 21 / Maven / PostgreSQL: `success`
- Vben TypeScript / production build: `success`
- UniApp TypeScript / H5 / WeChat: `success`
- Repository hygiene: `success`

Source-main Maven evidence:

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

Source-main artifact verification:

| Artifact | ID | Size | GitHub digest / downloaded ZIP SHA-256 |
|---|---:|---:|---|
| `approval-maven-30542735901` | `8759612602` | `26744` | `6c2135630e42074496aac9b8f743ad15c4e85011ed2b89e97d6e3aa65187a38d` |
| `approval-vben-30542735901` | `8759417655` | `18885` | `a1ac3ea294ced4fb954ba7c15c815e197c16a9c80d5ac107ec0d03052162ea70` |
| `approval-mobile-30542735901` | `8759398739` | `9795` | `6db7c0daf676721971e4fd4b8fcd869e0ad21b1f095f420f8064ab5788d5520d` |
| `approval-hygiene-30542735901` | `8759375862` | `9239` | `9f9421f5c9489f3570da98a8912819cda0f3ec39f76f03a9edc86d1b637c00b7` |

Every source-main ZIP was independently downloaded. Each local SHA-256 exactly matched the GitHub artifact digest.

## 3. Actionable post-merge findings and corrections

### Finding 1 — stale circuit completion closes a newer circuit generation

Severity: `P1`

A successful completion from an earlier CLOSED permit could arrive after a concurrent failure opened the circuit and unconditionally close it.

Correction:

- circuit state now carries a monotonically increasing generation;
- each permit binds the generation in which it was acquired;
- stale records and stale releases cannot mutate a newer circuit generation;
- only a current HALF_OPEN probe may close a HALF_OPEN circuit;
- current CLOSED healthy completions reset only the current failure counter.

Commits:

- `2a434f02a6747f0ba04707e5cd5eb67d17c83ab0`
- `66cc7231acae329c9d1acd0e3e4f4f640db976e4`

Regression:

- `AiProviderCircuitBreakerGenerationTest`: `1 / 1`

### Finding 2 — protocol Provider/capability changes omitted from deployment changes

Severity: `P1`

For an existing validation-profile key, Provider-version or capability-set changes could be omitted from the change list even though the deployment snapshot hash changed.

Correction:

- added `VALIDATION_PROVIDER_CHANGED`;
- added `VALIDATION_CAPABILITIES_CHANGED`;
- profile fingerprints now bind Provider version, sorted capabilities, request/response schema hashes, byte limits and closed protocol flags;
- either metadata change produces critical change evidence and mandatory human review.

Commits:

- `80c2b627f3fbd430a10a2f4fdcf010ba8e57ea20`
- `39945da0fd1095d813f446cd1d982b71e5e1161b`

Regression:

- `AiProviderDeploymentProfileMetadataChangeTest`: `1 / 1`

### Finding 3 — activation review constructor accepts unrelated bundle hash

Severity: `P2`

The public canonical record constructor validated only the SHA-256 syntax of `bundleHash`.

Correction:

- the canonical constructor recomputes the deterministic bundle hash after normalizing all fields;
- any mismatch fails closed;
- `REVIEW_COMPLETE` evidence cannot be altered while retaining an unrelated hash.

Commits:

- `881bb744731246401d277dd5d4e997fe3a432126`
- `dd4841b85aab913910a63413a1aeb9456b633b12`

Regression:

- `AiProviderActivationReviewBundleHashTest`: `1 / 1`

### Finding 4 — delimiter ambiguity in audit evidence hashing

Severity: `P2`

Audit input fields allowed delimiter characters that were also used to join hash inputs.

Correction:

- all audit hash tuples use deterministic length framing;
- Provider/model/Prompt-metadata/knowledge-metadata/policy/schema version tuples are length-framed;
- delimiter placement cannot change field boundaries or produce an equivalent canonical tuple.

Commits:

- `7a23ac288e0584be2e8aae83b6edd125fa9d4f4a`
- `820a8ae5899e0c0e1e0d74b53bfe57eb671cb2a1`

Regression:

- `AiAuditRecordFramingTest`: `1 / 1`

### Finding 5 — Provider collection/depth limits omitted from preflight

Severity: `P2`

Route matching enforced Provider character limits but not the Provider capability's collection-size and depth limits.

Correction:

- Provider matching can consume the exact server-owned data-minimization policy;
- `maximumCollectionSize` and `maximumDepth` must not exceed the Provider capability contract;
- startup preflight uses the data-policy-aware match;
- nesting-limit mismatch blocks the route before any Provider invocation.

Commits:

- `03d06138619753f37ab33fd48e143a87b9dca1fa`
- `b9843ac80abd61904325eb7dd3a6cd4c82fcb0bb`
- `4944c3e285b7740e67670d2fa63a8fe40eda3db2`

Regression:

- `AiProviderRegistryNestingLimitTest`: `1 / 1`

## 4. Correction code-Head permanent verification

Correction code Head:

`39945da0fd1095d813f446cd1d982b71e5e1161b`

Permanent workflow:

- Run ID: `30597183681`
- Run number: `950`
- conclusion: `success`
- Java 21 / Maven / PostgreSQL: `success`
- Vben TypeScript / production build: `success`
- UniApp TypeScript / H5 / WeChat: `success`
- Repository hygiene: `success`

Maven evidence recalculated from `maven-verify.log`:

- aggregate: `1394 / 0 / 0 / 0`
- AI SPI: `12 / 12`
- AI Core: `90 / 90`
- five new finding regressions: `5 / 5`
- request security/minimization: `6 / 6`
- evidence hashing plus framing regression: `3 / 3`
- activation review bundle tests including hash regression: `5 / 5`
- Circuit Breaker tests including generation regression: `3 / 3`
- deployment change tests including protocol metadata regression: `4 / 4`
- Provider registry tests including nesting regression: `3 / 3`
- activation/runtime trust: `16 / 16`
- transport review: `21 / 21`
- deployment/readiness: `19 / 19`
- ArchUnit module boundaries: `10 / 10`
- `BUILD SUCCESS`: present

Correction code-Head artifact verification:

| Artifact | ID | Size | GitHub digest / downloaded ZIP SHA-256 |
|---|---:|---:|---|
| `approval-maven-30597183681` | `8780684018` | `26848` | `b11fbddf84c887868fb7cf08c42939547f44ad81167bd27c3fa19be024679851` |
| `approval-vben-30597183681` | `8780586798` | `18933` | `27cd834ddcc9007738c97bfff3982dedce2de041b71e3e124cfa5cc8d39fb8b1` |
| `approval-mobile-30597183681` | `8780574119` | `9786` | `2639b77fe8e734318b13a070244f4c2d1adea7ffe893087592c35d32bf5cd678` |
| `approval-hygiene-30597183681` | `8780559458` | `9254` | `d2fa15de28c98a57c49b41e8ef15cf1ff60b1e56d8a8f6d2389ec3c38f06cfc1` |

Every correction code-Head ZIP was independently downloaded. Each local SHA-256 exactly matched the GitHub artifact digest.

## 5. Safety and compatibility conclusion

The five fixes strengthen correctness and fail-closed behavior without adding product capability.

The correction contains no:

- real Provider adapter or HTTP client;
- DNS, TLS or network egress;
- runtime Secret material or signature calculation;
- production credential, Prompt or customer knowledge;
- attachment extraction, RAG, embeddings or vector database;
- AI persistence, durable state, Outbox, Queue, Worker or Scheduler;
- participant or management AI endpoint;
- AI-driven approval decision or process command;
- executable activation or transport acceptance;
- Flyway migration;
- second automatic workflow;
- M6-E or M6-F capability.

M6-D remains advisory-only, `UNVERIFIED_ADVISORY`, human-review-required and non-executable. The correction does not modify M5 migration/runtime-binding behavior, M6-A connector invocation, M6-B event delivery or M6-C Draft-only template/component semantics.

## 6. Remaining closure gates

PR #77 must remain Draft until the exact documented correction Head:

- completes the full permanent workflow successfully;
- produces four independently SHA-256-matched artifacts;
- has recalculated Maven and focused evidence;
- has no unresolved actionable Review finding;
- is behind `0` relative to current `main`.

The five original PR #70 threads must be answered and resolved with links to PR #77 evidence.

Final integration may use only a Merge Commit with exact expected Head. Issue #66 remains Open after correction-PR merge until the natural correction `push -> main` Run, four correction-main artifacts, final Maven/focused evidence and final Review closure are complete.
