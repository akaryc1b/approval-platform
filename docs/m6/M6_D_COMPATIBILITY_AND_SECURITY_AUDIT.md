# M6-D Compatibility and Security Audit

Status: `FORMAL_G1_AUDIT_DOCUMENTED`

## 1. Audit identity and baseline

- repository: `akaryc1b/approval-platform`
- workstream: Issue #66 under parent Issue #62
- Pull Request: #70
- branch: `agent/m6-d-ai-foundation`
- target: `main`
- original implementation baseline: `d769722cf7dd5418739a91ad4c45ca1a1c147502`
- original implementation Head: `9d588215e869c8f1332c0bc1a2809fbd235c2efa`
- formal rebaseline `main`: `735e41526371ea481b31af377e3410d085160f7e`
- controlled rebaseline Merge Commit: `c0078be9669c4936edb73f3c195f75fc0f6bc9e8`
- R0 validated Head: `7f812ce0e08a5644f861b6d3c6bcd7b900af89f0`
- R0 permanent Run: `30537697139` / #919
- G1 corrected implementation Head: `66f4e3104bb0f34d636a6dea9f37bc4be833abfb`
- G1 corrected implementation Run: `30539705558` / #933
- PR relation at corrected implementation Head: behind `0`

The commit containing this document is the final G1 audited Head. Its exact SHA, permanent Run and artifact digests are recorded in the permanent PR #70 G1 evidence comment after validation because a Git commit cannot contain its own SHA.

The current-main Flyway set is `V2` through `V37`, plus `V39` through `V48`. Integer continuity is not assumed. M6-D adds or modifies no Flyway migration relative to the formal rebaseline.

The only workflow with automatic `pull_request` or `push` triggers is `.github/workflows/approval-platform-validation.yml`. Manual or reusable workflow files are not treated as a second automatic workflow.

## 2. Audited scope

The audit covered the complete PR #70 diff after the current-main Merge Commit, including:

- `approval-ai-spi` production and test sources;
- `approval-ai-core` production and test sources;
- Maven reactor and ArchUnit integration;
- three permanent M6-D Node boundary suites;
- immutable configuration and deployment evidence;
- external Secret reference and offline trust metadata;
- non-executable activation review contracts;
- offline Provider transport review contracts;
- compatibility with current M4, M5 and M6-A/B/C mainline state;
- stale migration, workflow and baseline assumptions.

No G1 change adds an AI product entry point, real Provider, network client, runtime Secret retrieval, persistence, migration, participant/management API, Web/Mobile AI surface or approval/process command.

## 3. SPI and module boundaries

### 3.1 `approval-ai-spi`

Observed and accepted:

- no Spring dependency;
- no Flowable dependency;
- no HTTP client or `java.net` dependency;
- no JDBC/database dependency;
- no host Web/Mobile or Controller dependency;
- production SPI remains provider-neutral;
- deterministic Provider, resolver and transport mapper implementations remain test-only.

### 3.2 `approval-ai-core`

Observed and accepted:

- depends only on the domain module and AI SPI in production;
- does not access Flowable `ACT_*` tables;
- has no Spring, Flowable, `java.net`, `java.sql`, `javax.sql` or PostgreSQL dependency;
- has no reverse dependency on host Web/Mobile or business Controller packages;
- introduces no Maven cycle;
- current server reactor includes the AI modules while retaining all current-main connector modules;
- current ArchUnit reactor retains current-main modules and adds the AI modules and AI no-framework/no-network/no-persistence rule.

## 4. Advisory-only invariant

Observed and accepted:

- `AiAdvisoryResult.Authority` contains only `ADVISORY`;
- assertion status contains only `UNVERIFIED_ADVISORY`;
- `needsHumanReview` is mandatory and cannot be false;
- no result field represents an approval decision or process command;
- recommendation types cannot approve, reject, return, transfer, withdraw, terminate or migrate;
- service output validation rejects authoritative or command language;
- failure, timeout, cancellation, low confidence, invalid output and unknown outcomes fail closed;
- AI output cannot set approval state, task state, process state or runtime binding.

## 5. Server-owned identity, tenant and permission boundaries

Observed and accepted:

- tenant, operator, request and trace identities originate from `AiServerRequestContext`;
- the advisory intent does not carry authority, audit, lease, engine or worker identity;
- the authorized resource must match the server context tenant;
- field permission is applied before Provider request construction;
- data minimization and masking occur before Provider mapping;
- unauthorized fields cannot enter `AiProviderRequest`;
- attachment content is not extracted;
- client data cannot manufacture or overwrite tenant, operator, permission, audit, lease or engine identity.

## 6. Data minimization, masking, Secret and observability boundaries

Observed and accepted:

- confidential or restricted data must be rejected or redacted before mapping;
- field count, character count and collection depth are bounded;
- prompt-injection-like input is rejected by the minimization boundary;
- no production Prompt body, customer knowledge asset or attachment extraction exists;
- no API key, credential, token, password, private key, authorization header value or Secret material is stored in code, logs, evidence or artifacts;
- routing metrics expose only closed, low-cardinality capability/result/provider-type/policy/circuit dimensions;
- tenant, operator, user, request, trace, instance, task and Provider-response data are not metric tags;
- transport audit evidence stores hashes, versions, classifications, stable codes and bounded counts only;
- raw request body, raw response body, header values and network payload are prohibited.

## 7. G1 security findings and corrections

### Finding G1-F1 — raw high-cardinality execution/audit identities

Severity: `correctness/security boundary`.

Observed before correction:

- `AiAuditRecord` directly stored request, trace, tenant, operator, resource, authorization and human-decision identifiers;
- `AiAdvisoryExecutionEvidence` directly stored the same request/subject/resource identifiers and route ID.

Although these fields did not contain Prompt or Secret material, they violated the formal G1 requirement that audit evidence retain controlled hashes, versions, classifications, stable codes and bounded counters rather than raw high-cardinality identities.

Correction:

- raw request/subject/resource identity is converted to domain-separated SHA-256 evidence hashes;
- raw route identity is converted to a route evidence hash;
- human-decision reference is converted to a decision evidence hash;
- deterministic aggregate audit/execution evidence hashes bind versions, classification, usage, circuit state and bounded counters;
- no raw tenant/operator/request/trace/resource/authorization/decision identifier is retained in the evidence record;
- Provider selection, invocation and advisory behavior are unchanged.

Relevant append-only commits:

- `4bebee3b981b4bf767b1887e03147ea781672c59`
- `121543b8acee6c2af871217b5a7f72f047f9b65c`
- `ae6fa8ce476fcad47346c5de0d2182367bc3f156`
- `1144fd8d9df1935b8de6551512cb6f1a6eb68345`
- `33d4f9a7796ed3a36dffc8888fae048831c228ca`
- `d798596fa0707d3c79f6f4c05d3871a3dbdd3b02`
- `bd56c30bc1b927584b51f84767908af7e90f54ca`

### Finding G1-F2 — raw activation reviewer identity

Severity: `security/review-evidence boundary`.

Observed before correction:

- `ReviewerApproval` stored a raw reviewer ID.

Correction:

- the stored field is now `reviewerEvidenceHash`;
- the factory hashes the bounded input reviewer identity with domain separation;
- two-person review still requires at least two distinct approved reviewer hashes and two distinct roles;
- review completion remains non-authorizing and non-executable;
- no reviewer ID is retained in the review bundle or bundle hash input.

Relevant append-only commits:

- `becddc3bff5680e22dfc442d7319baf18256d94e`
- `4a179686906315c9cb91f555993ca68e57c9c1cb`
- `50826caa42a849ea7e97f3b552273174fd1a93db`
- `aa6b921b7aea25a043754c7aee0aed266e0d23f9`
- `f852bc50fede17d43005a91cac8acbfbd6a2cef9`
- `227265b1d3b084af340955903a61116a2dea9776`
- `224875de63b910ed32d3a0f183b6fd41e307741f`
- `0afa623344efbdaccec4f7fa8fba3a3631f57bb3`
- `7acfdfe9e13d1f034ef94d1ca53f12d4318646f7`
- `4ca6964fa0e467eed25d8fab20ac7943c4ef2e3f`

### Finding G1-F3 — stale tests after evidence hardening

Severity: `compatibility/test fixture`.

Observed:

- activation-plan fixtures still invoked the record constructor with raw reviewer values;
- the contract-boundary reflection test still expected removed raw audit fields.

Correction:

- activation-plan tests now use the hash-producing reviewer factory;
- audit contract tests now require exactly the hash-only record fields;
- no production behavior was weakened.

Relevant append-only commits:

- `dc38efcfa000de548f28bb2d4a60c63d16d234dd`
- `b10cd6968d8d016b596635b0e80709963c9025f9`
- corrected implementation Head: `66f4e3104bb0f34d636a6dea9f37bc4be833abfb`

No unresolved G1 security or compatibility finding remains at document creation.

## 8. Version, provenance and reproducibility

Observed and accepted:

- each advisory result carries exact Provider, model, Prompt-template, knowledge-source, policy and output-schema version references;
- the artifact registry authorizes exact Provider/version/capability and exact artifact metadata;
- configuration snapshot evidence is immutable and content-hash verified;
- deployment snapshot evidence is immutable, exact-version bound and `FAULT_DRILL_ONLY`;
- canonical payload evidence stores field pointer/type/hash/byte count/classification/redaction evidence, not raw values;
- transport mapping binds canonical payload, request schema, response schema and signing-input hashes;
- transport lifecycle and audit bind request, envelope, lifecycle and audit hashes;
- corrected execution and audit evidence bind server-owned request/subject/resource hashes, exact versions, classification and usage/circuit evidence;
- equivalent bounded metadata produces deterministic evidence hashes;
- identity, version or content-hash mismatch fails closed.

Because M6-D contains no production Provider activation, these records form a review/evidence chain rather than a production dispatch authority. No evidence record grants invocation or production authority.

## 9. Provider routing and execution boundary

Observed and accepted:

- route ordering is deterministic and server-owned;
- exact Provider and exact model/artifact authorization are required;
- invocation budget bounds input fields, characters, timeout and minimum confidence;
- circuit state is an operational gate and never an authority source;
- a request invokes at most one Provider;
- pre-invocation candidate selection is distinct from post-invocation fallback;
- post-invocation fallback is prohibited;
- unsafe retry is prohibited;
- cancellation and timeout are explicit, bounded and fail closed;
- production source contains no real Provider adapter, HTTP client, DNS lookup, TLS handshake, signing operation, network egress or runtime Secret retrieval.

## 10. External Secret reference boundary

Observed and accepted:

- only external Secret reference metadata is stored;
- inline Secret material is rejected;
- Secret resolver inspection is metadata-only;
- deterministic resolver implementation remains test-only;
- a Secret reference does not prove that a Secret is configured;
- a Secret reference does not authorize Secret retrieval, Provider invocation, network access or production activation;
- protocol validation is structural/offline evidence and not production readiness;
- deployment readiness remains fault-drill readiness only.

## 11. Activation, lease and kill-switch boundary

Observed and accepted:

- kill-switch states are limited to `DISABLED` and `FAULT_DRILL_ONLY`;
- no enabled/active production state exists;
- activation lease states cannot grant authority;
- activation plan mode is permanently `NON_EXECUTABLE_REVIEW_ONLY`;
- two-person review cannot be satisfied by one reviewer or one role;
- reviewer identities are retained only as hashes after G1 correction;
- `REVIEW_COMPLETE` does not enable production;
- readiness and review checklists cannot apply a change;
- activation plan, lease, kill switch and review bundle cannot resolve a Secret, access a network, invoke a Provider, enable production or authorize approval automation.

## 12. Offline Provider transport review

Observed and accepted:

- transport mapper performs offline structural mapping only;
- no production mapper implementation exists;
- mapping requests contain hashes and metadata only;
- canonical payload evidence stores no raw values;
- signing-input evidence computes no signature and stores no sensitive header value;
- sensitive header names including authorization, cookie, proxy authorization and API-key headers are rejected;
- lifecycle evaluation consumes precomputed fixture observations only;
- connection error, malformed response, schema drift, unknown fields, oversized/empty response and unknown outcome fail closed;
- raw request/response bodies and network payload are prohibited from audit evidence;
- transport acceptance remains permanently `NON_EXECUTABLE_TRANSPORT_ACCEPTANCE`.

## 13. Deployment and operations safety

Observed and accepted:

- configuration and deployment snapshots are immutable and hash-bound;
- startup preflight does not invoke a Provider;
- dry-run assembly proves zero-call behavior;
- failure drills consume precomputed observations and do not operate a Provider;
- metrics remain low-cardinality;
- no production credential, Prompt, customer knowledge or attachment extraction exists;
- no AI persistence, durable AI state, Outbox, Queue, Worker or Scheduler exists;
- M6-D adds no Flyway migration;
- M6-D adds no second automatic workflow.

## 14. Mainline compatibility

The final PR path audit found no M6-D modification to:

- M4 identity/governance implementation;
- M5 migration plan, intent, attempt, verification or reconciliation semantics;
- M5 runtime binding or process release lifecycle;
- M6-A production connector invocation or Secret-material lifecycle;
- M6-B SDK/event delivery semantics;
- M6-C template import, component registry or Draft-only semantics;
- Web or Mobile AI product entry points;
- persistence migration files.

Current-main Maven modules, connector modules, architecture dependencies and workflow checks were retained during conflict resolution. M6-D only reapplied its two existing AI modules and three existing permanent Node boundary suites.

## 15. Corrected implementation validation

Permanent workflow Run `30539705558` / #933 is bound to corrected implementation Head `66f4e3104bb0f34d636a6dea9f37bc4be833abfb` and completed `success`.

Jobs:

- Java 21 / Maven / PostgreSQL: success;
- Vben TypeScript / production build: success;
- UniApp TypeScript / H5 / WeChat: success;
- Repository hygiene: success.

Maven evidence recalculated from the artifact:

- aggregate: `1389 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `85 / 85`;
- request security/minimization: `6 / 6`;
- deterministic hash-only evidence: `2 / 2`;
- activation-review/runtime-trust slice: `15 / 15`;
- transport-review slice: `21 / 21`;
- ArchUnit `ModuleBoundariesTest`: `10 / 10`;
- `BUILD SUCCESS`: present.

Permanent Node boundaries:

- AI foundation: `10 / 10`;
- activation review: `6 / 6`;
- transport review: `7 / 7`.

Artifact verification:

| Artifact | ID | Size | GitHub digest | Downloaded ZIP SHA-256 |
|---|---:|---:|---|---|
| `approval-maven-30539705558` | `8758376246` | `26740` | `sha256:01e60ed347dc996e03d4c822495a909392e418f0cd9ebce5bf45b664f4849c93` | `01e60ed347dc996e03d4c822495a909392e418f0cd9ebce5bf45b664f4849c93` |
| `approval-vben-30539705558` | `8758204610` | `18919` | `sha256:aafa3b4cce2a50b1f135e784b90b34e84f9801d5065b0be8db7896ec876363cb` | `aafa3b4cce2a50b1f135e784b90b34e84f9801d5065b0be8db7896ec876363cb` |
| `approval-mobile-30539705558` | `8758183190` | `9786` | `sha256:31a7377f89fc49dad27de8e759180316452baf4dcf2c8261428dc7860df32bd2` | `31a7377f89fc49dad27de8e759180316452baf4dcf2c8261428dc7860df32bd2` |
| `approval-hygiene-30539705558` | `8758159151` | `9249` | `sha256:9bb2a2b617be2da70fdcbf8daddca05e65222af3be8b8865dc7c0950505e6685` | `9bb2a2b617be2da70fdcbf8daddca05e65222af3be8b8865dc7c0950505e6685` |

## 16. Retained failure evidence

No failed or cancelled Run was deleted.

Retained G1 evidence includes:

- Run `30539095357` / #926: superseded while the first evidence-hardening series was still being completed;
- Run `30539262868` / #929: Hygiene failed because a new Node assertion searched for a call-site string rather than the static factory declaration;
- Run `30539358538` / #930: Hygiene failed because the assertion examined the bounded factory input parameter instead of only stored review-record fields;
- Run `30539449053` / #931: Maven failed because two stale test fixtures still used the old reviewer constructor and an old contract test still expected raw audit fields;
- all R0 and historical M6-D failure/cancellation evidence remains retained.

The final corrected implementation Run #933 is a new full workflow, not a rerun or partial substitute.

## 17. Explicitly not implemented

This audit does not accept or imply:

- a real OpenAI, Anthropic, Azure OpenAI, Gemini or other Provider adapter;
- a real HTTP Provider client, DNS/TLS/egress operation or signature;
- runtime Secret material retrieval;
- production Prompt or customer knowledge;
- attachment extraction, RAG, vector database or embeddings;
- AI persistence, durable state, Outbox, Queue, Worker or Scheduler;
- participant or management AI API;
- Web/Mobile AI page;
- AI summary, risk recommendation or material-check product entry point;
- AI-driven approval decision or process command;
- executable activation, transport acceptance or controlled automation;
- M6-E or M6-F.

## 18. G1 disposition

At document creation:

- the compatibility and AI security audit is complete;
- three bounded findings were recorded and corrected with append-only commits;
- corrected implementation Head `66f4e3104bb0f34d636a6dea9f37bc4be833abfb` has a complete permanent success Run and four exact artifact digest matches;
- no unresolved code-level security or compatibility finding remains;
- PR #70 remains Open + Draft;
- Issue #66 remains Open;
- G2 may begin only after this document Head itself completes the permanent workflow and four-artifact verification.
