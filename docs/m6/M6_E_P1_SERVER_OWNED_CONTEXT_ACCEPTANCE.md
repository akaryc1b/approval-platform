# M6-E P1 Server-Owned Approval Context Acceptance

Status: `M6_E_P1_FORMALLY_ACCEPTED_PENDING_DOCUMENTED_HEAD_VALIDATION`

Date: `2026-07-31`

Tracking:

- parent milestone: Issue #62;
- roadmap rebaseline: Issue #78, closed / completed;
- workstream: Issue #80;
- Pull Request: #83;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- target: `main`;
- exact base: `fcf031da9e6e04b15a1255044021a7fdd6637421`.

This record accepts only M6-E P1. It does not authorize P2 contracts, Provider invocation,
production Prompt or customer knowledge, runtime Secret material, network egress, persistence,
Flyway migration, an API/UI, automation, an executable action or any approval command.

## 1. P0 entry gate

P1 began only after P0 completed its independent documented-Head acceptance.

Accepted P0 evidence:

- exact documented Head: `f920c92fd2674eeae06f0ea2d03552601d8dd663`;
- permanent Run: `30617608285` / run number `963`;
- event: `pull_request`;
- attempt: `1`;
- conclusion: `success`;
- all four permanent jobs: `success`;
- all four artifacts: independently downloaded and SHA-256 matched;
- Maven aggregate: `1396 / 0 / 0 / 0`;
- M6-E P0 authority boundary: `6 / 6`;
- no actionable Review finding;
- PR #83 remained Open + Draft.

At P1 acceptance, current `main` remains
`fcf031da9e6e04b15a1255044021a7fdd6637421`. The M6-E branch is ahead of main and behind zero.
Issues #80, #62, #13 and #14 remain open.

## 2. P1 accepted scope

P1 creates a purpose-specific, server-owned approval-assistance projection in the existing AI Core.
It reuses the accepted M6-D identity, authorization, minimization and Provider-request contracts
instead of creating a parallel authority model.

Accepted production files:

- `server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/ApprovalAssistanceContextAssembler.java`;
- `server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/ApprovalAssistanceContextProjection.java`.

Accepted permanent Java tests:

- `ApprovalAssistanceContextAssemblerTest`;
- `ApprovalAssistanceProcessSnapshotTest`;
- `ApprovalAssistanceProjectionInvariantTest`;
- `ApprovalAssistanceAttachmentMetadataInvariantTest`.

Accepted permanent architecture boundary:

- `scripts/tests/m6-e-approval-assistance-boundary.test.mjs`;
- loaded through the existing `scripts/tests/m3-repository-hygiene.test.mjs` gate;
- no second automatic workflow.

P1 adds no controller, application-service binding, Provider adapter, database store, migration,
runtime configuration, browser code or mobile code.

## 3. Server-owned input model

The assembler accepts only a bounded server-owned input composed of:

1. authenticated `AiServerRequestContext`;
2. exact `AiAuthorizedResource` and field allowlist;
3. process definition/version/compiler/content-hash evidence;
4. optional complete release-version and release-package-hash evidence;
5. current task or process-instance identity and state version;
6. exact Form Schema and content hash;
7. exact UI Schema and resolved permission context;
8. submission revision;
9. server-read values keyed by the current Form Schema;
10. sensitive-field keys;
11. versioned `AiDataMinimizationPolicy`;
12. bounded required Provider capabilities.

The assembler accepts no browser-manufactured trusted identity, complete persistence entity,
attachment body, external URL, executable instruction or application command.

## 4. Accepted projection evidence

The immutable projection binds:

- tenant, operator, request and trace evidence;
- authorization resource type, resource ID and authorization reference;
- process definition key/version/compiler/content hash;
- Form Schema key/version/schema version/content hash and total field count;
- UI Schema version/hash and exact permission context;
- task or instance state, state version and observation time;
- submission revision;
- Provider-safe fields after authorization, visibility, masking and minimization;
- Provider capability requirements;
- five-dimensional input limits;
- data-policy version;
- authorized-visible, Provider-field, masked, omitted and attachment-metadata counts;
- explicit evidence that attachment extraction was not attempted.

The five-dimensional input limits are:

- maximum input fields;
- maximum text characters per value;
- maximum total text characters;
- maximum collection size;
- maximum nesting depth.

## 5. Fail-closed authority and integrity invariants

P1 permanently enforces the following before any future Provider mapping can be used:

### 5.1 Tenant and resource binding

- request, authorization resource and current state must have the same tenant;
- an `APPROVAL_TASK` resource must bind the exact current pending task;
- a `PROCESS_INSTANCE` resource must bind the exact current running instance;
- a form-submission authority is not accepted by this P1 product boundary;
- task permission context must equal the current task-definition key.

### 5.2 Process, Form and UI Schema binding

- process form key/version must equal the supplied Form Schema;
- Form Schema key/version must equal the supplied UI Schema;
- resolved permissions must use the exact UI Schema version;
- the permission context must exist in the supplied UI Schema;
- release version and package hash must be both present or both absent;
- a blank package hash cannot satisfy complete release evidence.

### 5.3 Field authorization and minimization

- every supplied value, permission, sensitive key and authorized key must exist in the Form Schema;
- duplicate Form Schema keys fail closed;
- hidden, unauthorized, absent and null fields do not enter the source projection;
- masking and minimization occur through the existing `AiDataMinimizer` before Provider-safe fields
  are created;
- every Provider-safe field key must remain in the exact authorization allowlist;
- duplicate Provider-safe field keys fail closed;
- every Provider-safe runtime value is recursively revalidated by the immutable projection.

### 5.4 Attachment boundary

- attachments are metadata-only;
- raw bytes, extracted text, arbitrary maps and external links are prohibited;
- each metadata item contains exactly `attachmentId`, `fileName`, `contentType`, `sizeBytes` and
  `sha256`;
- metadata text is non-blank and bounded consistently with `AiSourceField.AttachmentMetadata`;
- `sizeBytes` is a non-negative `Long`;
- policy-omitted attachments contribute zero Provider-output attachment count;
- attachment evidence counts are derived from final Provider-safe fields, not pre-minimization input;
- `attachmentExtractionAttempted` must remain `false`.

### 5.5 Evidence and limit consistency

- declared Provider limits cannot exceed the accepted M6-D safe bounds;
- Provider field count cannot exceed the declared field limit;
- text, total-text, collection and depth limits are revalidated on direct projection construction;
- Provider field count must equal projection evidence;
- masked field count must equal actual masked output;
- attachment metadata count must equal actual Provider-safe attachment metadata;
- `providerFieldCount + omittedFieldCount` must equal the Form Schema field count;
- authorized-visible fields cannot exceed the Form Schema field count;
- masked fields cannot exceed Provider fields.

These invariants apply even if a caller attempts to construct the public projection record directly
without using the assembler.

## 6. Retained append-only hardening sequence

P1 used append-only commits. Intermediate natural Runs were retained and superseded when source
review identified additional defense-in-depth requirements. They were not rerun, deleted, hidden or
used as final acceptance evidence.

Known superseded exact Heads and Runs include:

| Exact Head | Run | Run number | Final conclusion | Superseding reason |
| --- | ---: | ---: | --- | --- |
| `19880b8ca585a7ae3e9dc9e2e1bca5405d62bb93` | `30619146091` | `969` | cancelled | projection-own invariants still required |
| `4d2c2aab2f0dd5f3b4f3a040466fe0a6c14f67cb` | `30619847766` | `972` | cancelled | complete Provider-budget and evidence validation still required |
| `5b60f48c3a48b4aba7e02eba3b7b02199f1f6199` | `30620425181` | `975` | cancelled | attachment budget semantics required alignment with the minimizer |
| `23ca7201bc6c745b8da81c5b52543cf967a9e953` | `30620768835` | `977` | cancelled | schema-field and omitted-count evidence still required |
| `ef79724aa521b6404ec60a594dae6881dd39bc5f` | `30621349042` | `981` | cancelled | permanent static assertions still required |
| `183b6cd3e0904ea2a39d4616c20e257631be70c9` | `30621453091` | `982` | cancelled | direct attachment scalar validation still required |

The hardening findings were:

1. normalize optional release hash before checking complete release evidence;
2. make process/form, task/instance and permission-context binding projection-own invariants;
3. carry and revalidate the complete five-dimensional input budget;
4. derive and verify field, mask and attachment evidence from final Provider-safe output;
5. align attachment character budgeting with the accepted minimizer semantics;
6. bind total Form Schema field count and verify omitted count;
7. retain M6-D upper bounds even on direct construction;
8. validate attachment metadata scalar types, non-blank text and exact length bounds.

No hardening commit introduced Provider, Secret, network, persistence, API/UI or command authority.

## 7. Successful P1 implementation verification

Exact implementation Head:

`50c574aa4bbbf1521d0fb2df9684f0c5d61a68da`

Permanent workflow:

- Run ID: `30621626455`;
- run number: `984`;
- event: `pull_request`;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- attempt: `1`;
- conclusion: `success`.

Jobs:

| Job | Job ID | Result |
| --- | ---: | --- |
| Java 21 / Maven / PostgreSQL | `91127322732` | success |
| Vben TypeScript / production build | `91127322847` | success |
| UniApp TypeScript / H5 / WeChat | `91127322765` | success |
| Repository hygiene | `91127322728` | success |

Maven evidence recalculated from `maven-verify.log`:

- aggregate: `1418 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `114 / 114`;
- architecture module: `138 / 138`;
- ArchUnit `ModuleBoundariesTest`: `10 / 10`;
- P1 focused Java tests: `22 / 22`;
- `BUILD SUCCESS`: present.

P1 focused Java evidence:

| Test class | Result |
| --- | ---: |
| `ApprovalAssistanceContextAssemblerTest` | `7 / 7` |
| `ApprovalAssistanceProjectionInvariantTest` | `11 / 11` |
| `ApprovalAssistanceAttachmentMetadataInvariantTest` | `2 / 2` |
| `ApprovalAssistanceProcessSnapshotTest` | `2 / 2` |

Permanent governance evidence from the Repository hygiene job:

- M6-E P0/P1 authority boundary: `7 / 7`;
- combined repository-hygiene entrypoint: `12 / 12`;
- existing M6-D foundation boundary: `10 / 10`;
- existing M6-D activation boundary: `6 / 6`;
- existing M6-D transport boundary: `7 / 7`.

## 8. P1 implementation artifact verification

Every ZIP was independently downloaded and locally SHA-256 hashed. Each local SHA-256 exactly
matches the GitHub artifact digest.

| Artifact | ID | Size | GitHub/local SHA-256 | Match |
| --- | ---: | ---: | --- | --- |
| Maven | `8789832419` | `27034` | `f799650ef0fd351544e4ae2230aefa74be7a5c663ff7c906333d706db4dc235e` | exact |
| Vben | `8789652124` | `18908` | `b9a060ee49c980870e51c546a1537bdba96cf9d497aff67fc35d1a1205dc0705` | exact |
| Mobile | `8789634316` | `9791` | `0d5617ba7ca88b92e5121be40b20dea6fb0f32d1b63e83166fde834269c27ce5` | exact |
| Hygiene | `8789614912` | `9246` | `09f5b0938322f82df8b4c59ef13aed42efd746b968a706a223e461e45621cbaa` | exact |

All four artifacts are unexpired and expire at `2026-10-29T09:54:16Z`.

## 9. Review state before this record

Before this acceptance record was committed:

- PR #83 remained Open + Draft;
- mergeable: `true`;
- branch compare: ahead `27`, behind `0`;
- changed files: `11`;
- submitted reviews: none;
- requested reviewers: none;
- unresolved review threads: none;
- top-level PR comments: one prior P0 acceptance evidence comment and no actionable finding;
- reactions: none;
- auto-merge was not enabled.

The documented Head created by this record must receive a new permanent workflow and four new
independently matched artifacts. Run `30621626455` cannot substitute for that documented-Head
validation.

## 10. Explicit absence of P2-P7 capability

P1 introduces no:

- approval-assistance-specific advisory request/result contract;
- executable application orchestration;
- Provider invocation or production Provider adapter;
- production Prompt, customer knowledge, RAG, embedding or vector store;
- runtime Secret material or network egress;
- persistence, retention policy implementation or Flyway migration;
- read-only assistance endpoint;
- PC or Mobile AI experience;
- AI Queue, Worker, Scheduler, listener, polling or autonomous retry;
- automation proposal or executable action;
- approve, reject, return, transfer, withdraw, terminate, migrate, publish or activate command;
- M6-F capability.

## 11. P1 formal decision

M6-E P1 is accepted as a server-owned, Provider-safe approval-context projection, subject to the
new documented-Head permanent validation.

P2 may begin only after:

1. the exact documented Head Run succeeds;
2. all four documented-Head artifacts are independently SHA-256 matched;
3. Maven aggregate, AI Core and P1 focused evidence are recalculated;
4. Review, thread, comment and reaction checks contain no actionable finding;
5. PR #83 remains Draft;
6. current main is unchanged or is merged into the branch through an ordinary Merge Commit and
   fully revalidated.

P2 may define bounded approval-assistance contracts only. It must not invoke a Provider, bind a
production endpoint, add persistence or create any approval command.

`M6_E_P1_ACCEPTED_NOT_PRODUCTION_ENABLED`

`AI_IS_NOT_AN_OPERATOR`

`PROVIDER_TO_DIRECT_COMMAND_PROHIBITED`
