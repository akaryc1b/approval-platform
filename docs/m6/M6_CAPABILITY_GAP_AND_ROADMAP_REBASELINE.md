# M6 Capability Gap and Roadmap Rebaseline

Status: `M6_R1_CURRENT_TRUTH_REBASELINE_IMPLEMENTATION_EVIDENCE_PENDING`

Date: `2026-07-31`

Tracking:

- parent milestone: Issue #62;
- rebaseline workstream: Issue #78;
- source branch: `main`;
- source Head: `cefb2bf35701ca8af39ce6607e1b25c58ffb9a4e`.

This record replaces no historical issue text and grants no runtime authority. It records the
current repository truth after M6-A through M6-D and the M6-D bounded correction were merged.
The historical description on Issue #62 remains useful bootstrap history, but its M5, V33,
parallel-branch and initial-scope assumptions are no longer the current operating baseline.

## 1. Current-truth baseline

### 1.1 Git and issue identity

| Workstream | Pull Request | Documented Head | Merge Commit | Issue state |
| --- | ---: | --- | --- | --- |
| M6-A Connector Foundation | #67 | `66c8dc456a9c82c96ef0454c221a9b0e0c332e17` | `ebe7cb1ef92cb835810146f3120bd23ea94c586a` | #63 closed / completed |
| M6-B SDK and Event Ecosystem | #68 | `4dad7c7fd3d4985919909d2155f8f899bf1bd8d6` | `eebfad58628f12cb684320b098ae70d81dbc88c9` | #64 closed / completed |
| M6-C Template and Component Ecosystem | #69 | `498dc56e4695944a056625b56f4438a856d616e8` | `83a2a1d8163465864d19d0b4c7c52504380d63e1` | #65 closed / completed |
| M6-D AI Foundation | #70 | `8a62d3c8037ad5720e30b6918153750dd591c6e5` | `21c086e57bc5814d8083076550d9fda71adabb4a` | #66 closed / completed after correction |
| M6-D G4 correction | #77 | `792c4db1ec77e57c5afa6e315d4102fd424e04b0` | `cefb2bf35701ca8af39ce6607e1b25c58ffb9a4e` | tracked by #66 |

At audit start:

- `main` is exactly `cefb2bf35701ca8af39ce6607e1b25c58ffb9a4e`;
- no pull request is open;
- parent Issue #62 is open;
- Issues #13 and #14 are open;
- PRs #67, #68, #69, #70 and #77 are merged / closed and must not be reopened or modified.

### 1.2 Permanent main validation

The source Head is covered by natural `push -> main` Run `30600177229`, run number `957`.

- Java 21 / Maven / PostgreSQL: `success`;
- Vben TypeScript / production build: `success`;
- UniApp TypeScript / H5 / WeChat: `success`;
- Repository hygiene: `success`;
- Maven aggregate: `1396 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `92 / 92`;
- seven M6-D correction regressions: `7 / 7`;
- ArchUnit module boundaries: `10 / 10`;
- Maven reactor: `BUILD SUCCESS`.

Source-main artifacts remain unexpired and were previously independently matched:

| Artifact | ID | Size | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `8781698901` | `26846` | `de376db36c1362e2dcfff6f03e85d2ebccce99d4d877b2194a1bd741cb996dcb` |
| Vben | `8781600941` | `18841` | `1e12e875a538ce4dcabefa1aed31190cea89e93cadb3b73fc54b878798d2d81a` |
| Mobile | `8781589492` | `9789` | `363bad78ad3093b5425dc9e165164d2349de027bd25160aeab786ce505431a1b` |
| Hygiene | `8781577297` | `9250` | `3f0aa769ade11f5450afcb85057b86cb6bbfccf4b1017cc4900220de1a0cef10` |

### 1.3 Maven reactor and executable application binding

The top-level reactor contains:

- `server-modules`;
- `integrations/host-sdk`;
- `apps/server`;
- `examples/generic-spring-host`.

The server-module reactor contains the M6 connector and AI modules, including
`approval-connector-spi`, `approval-ai-spi`, `approval-ai-core`, DingTalk contract/token/HTTP
modules, connector routing/invocation/operations modules, application, persistence and
architecture tests.

The executable `apps/server` dependency graph is narrower:

- it includes connector credential, routing, token, invocation and operations cores;
- it does not include `approval-ai-spi` or `approval-ai-core`;
- it does not include `approval-connector-dingtalk` or `approval-connector-dingtalk-http`.

Consequences:

1. module presence does not prove executable-application binding;
2. the M6-D AI foundation is not currently reachable from the executable server;
3. the DingTalk HTTP transport implementation is not currently an executable-server dispatch
   authority;
4. app-level production capability cannot be inferred from reactor compilation alone.

### 1.4 Workflow and migration truth

The only workflow with automatic `pull_request` or `push` triggers remains:

`.github/workflows/approval-platform-validation.yml`

It contains the four permanent jobs listed above. Manual/reusable workflows do not replace this
path.

The current Flyway history has an exact highest version of `V48`:

- SQL migrations: `V2` through `V37`, and `V39` through `V48`;
- Java migration: `V38__Create_immutable_process_migration_plans`;
- no `V49` or higher migration exists at this baseline.

No later stage may assume `V49` remains free. Every stage must rescan the actual migration set
before claiming a version.

### 1.5 Exposed runtime surfaces

Current M6-specific application surfaces are:

- M6-A GET-only, tenant-scoped, no-store connector diagnostics under
  `/api/approval/management/connector-operations/diagnostics` and `/summary`, conditional on
  `approval.connector.operations-diagnostics.enabled=true`;
- M6-C management POST endpoints for bounded local preview and exact tenant-local DRAFT creation
  under `/api/approval/management/process-template-imports`;
- no participant or management AI API;
- no PC or Mobile AI surface;
- no AI automation proposal API;
- no AI governance operations API/UI.

The M6-C POST endpoints mutate only the existing editable DRAFT lifecycle. They do not publish,
deploy, activate, launch a process instance or execute an approval command.

## 2. Readiness vocabulary

The following terms are not interchangeable:

- `CONTRACT_ONLY`: types, validation rules or ports exist, but no executable runtime path is bound;
- `TEST_ONLY`: deterministic fixtures or adapters prove behavior but are unavailable to production;
- `RUNTIME_READY`: an application path is wired and can operate under explicit configuration, but
  production ownership or activation may still be blocked;
- `IMPLEMENTED_NOT_PRODUCTION_READY`: meaningful implementation exists, but one or more required
  Secret, egress, persistence, operations, incident, capacity or activation controls are absent;
- `PRODUCTION_READY`: complete runtime binding, external dependency governance, operations,
  rollback, incident response and acceptance evidence exist.

Therefore:

```text
interface exists
!= runtime wiring exists
!= production path is usable
!= operations and rollback exist
!= formal milestone completion
```

## 3. M6-A through M6-D capability matrix

| Capability | Owner | Implementation | Current class | Tenant/auth boundary | Persistence / external dependency | Operations / failure semantics | Acceptance and remaining gap | Target |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Connector SPI, provider descriptors, typed payloads and deterministic selection | M6-A | `approval-connector-spi` | `CLOSED_AND_ACCEPTED` foundation | trusted connector context and server-owned identity contracts | none required for contracts | deterministic fail-closed contracts | accepted as foundation, not execution authority | M6-G verify |
| DingTalk request/response contracts and conformance | M6-A | `approval-connector-dingtalk` | `IMPLEMENTED_NOT_PRODUCTION_READY` | tenant/credential references are server-owned | needs endpoint and credential material | typed failures; deterministic tests | module is not bound into `apps/server` | M6-E Provider/egress coordination or deferred connector gate |
| DingTalk JDK HTTP transport | M6-A | `approval-connector-dingtalk-http` | `IMPLEMENTED_NOT_PRODUCTION_READY` | endpoint/credential policies exist | real network and Secret backend required | timeout/size/policy tests exist | absent from executable app; production egress not approved | Deferred independent connector production gate |
| Connector Secret material | M6-A | credential contracts plus app status configuration | `CONTRACT_ONLY` | browser cannot provide trusted material | real backend missing | app explicitly reports `backend_not_selected` | B01 remains blocked | Deferred independent connector production gate |
| DingTalk token lifecycle | M6-A | token coordinator/cache/policy and conditional app configuration | `IMPLEMENTED_NOT_PRODUCTION_READY` | tenant route and credential binding gates | concrete material source and token endpoint port required | single-flight, lifetime, kill-switch contracts | default disabled and required production beans/ownership absent | Deferred independent connector production gate |
| Governed synchronous read-only connector invocation | M6-A | invocation coordinator and conditional app configuration | `IMPLEMENTED_NOT_PRODUCTION_READY` | server route, authorization evidence and token gates | concrete dispatch port required | bounded timeout, no unsafe mutation | default disabled; production dispatch is not bound | Deferred or M6-G limitation |
| Connector operations diagnostics | M6-A | operations core and GET-only controller | `RUNTIME_READY` process-local | tenant-scoped management permission | bounded in-memory/process-local store | no-store GET, bounded pagination and summary | not durable audit, default disabled | M6-G verify limitation |
| Production connector execution | M6-A | blocker catalog B01-B20 | `BLOCKED_EXTERNAL` | tenant allowlist and mutation separation unresolved | Secret, endpoint, egress, durable audit, capacity and operations unresolved | on-call/incident/DR/change approval unresolved | formal acceptance was not production enablement | Deferred independent production gate; does not silently block M6-E unless reused |
| Java/TypeScript SDK and versioned event contracts | M6-B | `integrations/host-sdk`, `packages/approval-sdk`, `contracts/sdk/v1` | `CLOSED_AND_ACCEPTED` library boundary | public clients cannot manufacture trusted identity or authority | no production transport required for library use | deterministic compatibility/signature verification | accepted as SDK contracts | M6-G compatibility audit |
| Deterministic transport, emission, telemetry, aggregation and checkpoint models | M6-B | SDK modules and fixtures | `TEST_ONLY` / `CONTRACT_ONLY` | reference-only identity and credentials | process-local scripted stores | deterministic retry/replay/checkpoint decisions | no platform runtime binding | Deferred unless required by a concrete integration |
| Durable event delivery, Outbox, subscription and reconciliation | M6-B | absent | `MISSING` | would require server authorization and tenant ownership | DB/broker/worker/scheduler required | unknown result and replay operations absent | not part of accepted M6-B boundary | Deferred explicitly; not required for M6 AI assistance unless later proven |
| Bounded template package validation and local preview | M6-C | application services and management API | `RUNTIME_READY` | trusted identity filter, DESIGN permission, tenant rewrite | local Form Package/registry evidence | strict bounds and fail-closed decoding | accepted | M6-G end-to-end verify |
| Exact tenant-local DRAFT creation | M6-C | governed import coordinator and management API | `RUNTIME_READY` | TRANSFER permission, reason, idempotency and audit chain | existing DRAFT persistence | stale preview/hash and cross-tenant failures fail closed | accepted only for DRAFT | M6-G end-to-end verify |
| Marketplace, remote package download and dynamic components | M6-C | intentionally absent | `OUT_OF_M6` | unsafe authority must never be package supplied | remote/network/catalog would be required | no safe activation path exists | explicitly excluded | Deferred / future milestone |
| AI provider-neutral SPI, minimization, provenance and advisory contracts | M6-D | `approval-ai-spi`, `approval-ai-core` | `CLOSED_AND_ACCEPTED` foundation | server-owned context types, field permissions, masking, minimization | no runtime external dependency | bounded routing, timeout, circuit and hash-only evidence | accepted as non-executable foundation | M6-E consume without weakening |
| Deterministic mock AI Provider and protocol/transport fixtures | M6-D | test source only | `TEST_ONLY` | synthetic only | no network/Secret | deterministic failures and evaluation | cannot satisfy a user-facing production path | M6-E tests only |
| AI application wiring and approval context projection | M6-D | absent from `apps/server` | `MISSING` | current SPI types are not sufficient application authorization | application/domain integration required | no API-level stale-state behavior | blocks usable assistance | M6-E P1-P3 |
| Real AI Provider adapter, Secret resolution and egress | M6-D | absent | `MISSING` / `BLOCKED_EXTERNAL` | server-owned selection and allowlists required | real Secret, DNS/TLS, network and provider dependency | budget/rate/circuit/kill-switch incident ownership required | blocks production AI assistance | M6-E P6 required gate |
| Durable AI assistance evidence and feedback gate | M6-D | absent | `MISSING` | tenant isolation and audit linkage required | new migration and retention/delete ownership required | CAS, replay and audit integrity required | blocks reliable production traceability | M6-E P4 required gate |
| AI read-only API and PC/Mobile experience | M6-D | absent | `MISSING` | client must not submit trusted identity/provider | consumes bounded orchestrator | authority-confusion failure not yet testable | blocks approval-assistance product path | M6-E P5 |
| AI automation proposal and governance | M6-D | intentionally absent | `MISSING` | AI cannot manufacture operator or authorization | proposal evidence may require persistence | confirmation, CAS and reconciliation absent | cannot execute commands | M6-F |
| Identity, tenant, authorization and audit foundation | cross-cutting | existing application/security/audit services | `RUNTIME_READY` for existing APIs | server-owned principal and request context | existing persistence where applicable | current management governance | must be reused, never copied into Provider output | M6-E/M6-F/M6-G |

## 4. Gap classification and priority

### 4.1 Closed and accepted

- M6-A provider-neutral connector contracts and bounded read-only orchestration foundations;
- M6-B Java/TypeScript SDK contracts, compatibility and deterministic fixtures;
- M6-C bounded local package preview and tenant-local DRAFT import;
- M6-D advisory-only Provider SPI/core, minimization, provenance, circuit and zero-call
  activation/transport review boundaries;
- the seven M6-D post-merge corrections.

Closed child Issues mean these bounded scopes were formally accepted. They do not convert every
related production blocker into a completed capability.

### 4.2 P0 gaps for M6-E

1. executable application binding for AI without importing Provider authority into the browser;
2. server-owned approval context projection with field permissions, masking and minimization;
3. bounded assistance result contracts and no-command enforcement;
4. synchronous governed orchestration with exact Provider/model/Prompt/policy/schema versions;
5. durable minimal evidence and feedback quarantine;
6. read-only API plus PC/Mobile authority-safe presentation;
7. one real production Provider gate with Secret, egress, cost, rate, circuit, kill-switch and
   incident governance.

### 4.3 P0/P1 gaps for M6-F

1. typed non-executable proposal model;
2. server policy/precondition/authorization preview;
3. human confirmation and separation of duties;
4. idempotency, CAS, replay protection and immutable evidence chain;
5. a valuable existing application command selected through an explicit whitelist;
6. AI governance inventory, activation, rollout, rollback, budget and operations surfaces.

### 4.4 Gaps not pulled into M6-E or M6-F

The following are deferred or out of M6 unless a later evidence-backed correction changes the
milestone Definition of Done:

- general commercial marketplace;
- remote/dynamic component implementation;
- arbitrary scripts, expressions, code loading or HTTP/tool execution;
- general RAG, embeddings or vector database platform;
- general Agent platform;
- unrelated event-delivery infrastructure;
- background connector automation unrelated to approval assistance;
- M7 capabilities.

## 5. Rebaseline decisions

### 5.1 M6-E production Provider decision

Decision: `PRODUCTION_PROVIDER_REQUIRED_FOR_M6_E_COMPLETION`.

A deterministic mock remains mandatory for CI and contract parity, but it cannot be presented as
a production approval-assistance capability. M6-E may develop P0-P5 before P6, but M6-E must not
be marked complete and parent Issue #62 must not close until one explicitly selected real Provider
adapter passes the P6 production gate.

The specific Provider is intentionally not selected by this document. Selection must occur in an
independent P6 gate with exact capability, endpoint, model, region, data-processing, legal/privacy,
cost and incident evidence. CI must never call a paid or customer Provider.

### 5.2 M6-E persistence decision

Decision: `DURABLE_MINIMAL_ASSISTANCE_EVIDENCE_REQUIRED`.

Production traceability cannot rely on process-local memory. M6-E P4 must own a separate migration
for minimal tenant-scoped evidence. Raw visible fields, raw Provider requests/responses, Secrets,
tokens and Prompt bodies must not be persisted. Allowed data is limited to bounded outcome fields,
versions, classifications, counters, timestamps, hash-framed evidence and explicit retention/delete
metadata.

At this baseline, the next numeric version appears to be `V49`; this is only a current candidate,
not a reservation. P4 must rescan the actual repository immediately before implementation and stop
on any ownership conflict.

### 5.3 Attachment, RAG and knowledge decision

Decision: `ATTACHMENT_EXTRACTION_AND_GENERAL_RAG_DEFERRED`.

M6-E may include attachment metadata in the server-owned context. It must not extract or transmit
attachment content without a later independent threat, data-classification and retention gate.
General RAG, embeddings and vector storage are outside M6.

### 5.4 M6-F executable action decision

Decision: `ACTION_WHITELIST_EMPTY_PENDING_EXISTING_COMMAND_AUDIT`.

No executable action is authorized by M6-R1. M6-F P0-P4 and P6-P7 may establish proposal and
governance foundations, but M6-F cannot be declared complete merely with an empty whitelist.
Before P5, the repository must identify at least one existing, business-valuable, low-risk,
server-authorized application command with explicit idempotency, stale-state handling, audit and
rollback/compensation semantics.

The following remain prohibited without a separate high-risk gate:

- approve;
- reject or return;
- transfer;
- withdraw;
- terminate;
- migrate;
- publish or activate a template/Provider;
- permission or Secret changes;
- arbitrary script, SQL, HTTP or connector command.

A command must not be invented only to claim that controlled automation exists. If no qualifying
existing command is found, M6-F and parent Issue #62 remain open.

### 5.5 Background execution decision

M6-E is synchronous and bounded. It introduces no AI Queue, Worker or Scheduler. M6-F may not add
background command execution unless a later independent gate proves durable ownership, lease,
reconciliation, kill-switch and incident semantics. Provider timeout does not justify unsafe retry.

## 6. Rebaselined M6-E scope

M6-E is `Governed AI Approval Assistance`, not automatic approval.

Required slices:

1. P0 rebaseline, data-flow and threat model;
2. P1 server-owned approval context projection;
3. P2 bounded `ADVISORY` / `UNVERIFIED_ADVISORY` result contracts with
   `needsHumanReview=true`;
4. P3 governed at-most-one-Provider orchestration, no post-invocation fallback or unsafe retry;
5. P4 dedicated durable minimal evidence migration and feedback quarantine;
6. P5 read-only API and PC/Mobile experience with visible limitations/provenance;
7. P6 one real Provider gate, server-owned Secret source, egress allowlist and incident controls;
8. P7 adversarial/fault/concurrency acceptance, Formal Acceptance, Merge Commit and post-main
   verification.

Definition of Done:

- trusted tenant/operator/authorization cannot be browser supplied;
- unauthorized fields are removed before Provider mapping;
- no AI result is a fact confirmation or approval decision;
- no output contains an executable command or command credential;
- exact Provider/model/Prompt/policy/schema versions are traceable;
- durable evidence is tenant-isolated, bounded and contains no raw sensitive payload;
- read-only API/UI semantics do not imply authority;
- a real Provider path passes P6 without real calls in CI;
- all permanent validation, artifacts, reviews, merge and post-main gates pass.

## 7. Rebaselined M6-F scope

M6-F is `Controlled Automation and AI Governance`. AI remains advisory and can create only a
non-executable proposal.

Required chain:

```text
AI advisory
-> typed proposal
-> server policy and precondition evaluation
-> authorization preview
-> explicit human confirmation
-> existing application command service
-> immutable audited result
```

The direct path `Provider -> command` is permanently prohibited.

Required slices:

1. P0 authority threat model and qualifying-command audit;
2. P1 typed proposal without executable credential;
3. P2 server policy/precondition/authorization preview;
4. P3 human confirmation, reauthentication and separation of duties;
5. P4 idempotency, CAS, replay and evidence chain;
6. P5 bounded execution through an existing whitelisted command only;
7. P6 Provider/model/Prompt/policy inventory, budget, activation, canary, kill-switch, rollout,
   rollback and read-only operations;
8. P7 adversarial/fault/concurrency acceptance;
9. P8 Formal Acceptance, Merge Commit and post-main verification.

Definition of Done includes at least one qualifying meaningful action. Until P5 proves one, the
action whitelist remains empty and no command may execute.

## 8. Rebaselined M6-G scope

M6-G is an independent overall acceptance workstream, not a documentation-only closeout.

It must re-read current `main` and verify the combined M6-A through M6-F chain, exact Merge
Commits, natural main Runs, artifacts, migrations, runtime configuration, API/UI semantics,
operations, upgrade, rollback and incident readiness.

M6-G must stop and require a bounded correction when a required production path is still test-only,
a runtime contract is not bound, durable state lacks migration/rollback, Provider governance is
incomplete, UI implies AI authority, or automation lacks idempotency/concurrency evidence.

## 9. Dependency graph and merge order

```text
M6-R1 #78
  -> M6-E P0-P7
      -> M6-F P0-P8
          -> M6-G G0-G5
              -> parent Issue #62 closure
```

Rules:

- each stage starts from the then-current `main`;
- no stacking on an unmerged branch;
- no M6-E work before M6-R1 post-main acceptance;
- no M6-F work before M6-E post-main acceptance;
- no M6-G work before M6-F post-main acceptance;
- Merge Commit only; no rebase, squash, amend, force push or direct main push.

## 10. Security threat model

Permanent threats and controls:

| Threat | Required control |
| --- | --- |
| Cross-tenant context or result | server-owned tenant context, tenant-scoped reads/writes, redacted not-found behavior |
| Forged operator/permission/audit | authenticated principal and server request context; ignore client authority fields |
| Prompt/tool injection | structured allowlisted context, bounded outputs, no tool/command channel, adversarial tests |
| Data exfiltration | field permissions, masking, minimization, Provider allowlist, no raw audit payload |
| SSRF/redirect/DNS rebinding | exact HTTPS endpoint and model allowlists, egress proxy/policy, no redirect, DNS/TLS checks |
| Confused deputy | Provider output never carries authority; server re-evaluates policy and authorization |
| Stale state or replay | expected state/version, expiry, CAS, idempotency and immutable lineage |
| Unsafe retry/unknown result | no post-invocation fallback; no non-idempotent retry; durable reconciliation where commands exist |
| Cost or capacity exhaustion | input/output/token/time budgets, rate limits, circuit, kill-switch and low-cardinality metrics |
| Provider compromise/drift | exact Provider/model/Prompt/policy versions, activation inventory, canary, drift detection and rollback |
| UI authority confusion | persistent AI/unverified/human-review labels and no direct approval-command proxy |
| Secret leakage | server-owned backend, references only in domain data, redacted logs/artifacts, rotation and incident controls |

AI never manufactures tenant, operator, permission, authority, audit, worker, lease or engine
identity. Production code never queries or modifies Flowable `ACT_*` tables directly.

## 11. Rollback and incident strategy

M6-E and M6-F must be operable through independent controls:

- Provider and automation kill switches default deny;
- configuration and activation use versioned immutable snapshots;
- rollout supports disabled, evaluation, canary and bounded activation states;
- rollback restores a previously accepted snapshot without replaying partial Provider or command
  activity;
- Provider partial invocation has no fallback;
- command unknown outcomes require immutable evidence and reconciliation, not AI self-correction;
- incident evidence excludes Secret, Prompt body and customer content;
- operations surfaces are read-only for ordinary clients;
- on-call ownership, escalation and retention/deletion procedures are acceptance evidence, not
  comments only.

## 12. Parent Issue #62 closure blockers

Issue #62 must remain open while any of the following is true:

1. M6-R1 has not completed Merge Commit and post-main validation;
2. M6-E lacks server-owned context projection, read-only product surface or no-command enforcement;
3. M6-E uses only a test mock and lacks the required real Provider gate;
4. M6-E lacks durable minimal evidence, retention/delete and tenant isolation;
5. M6-F lacks a qualifying valuable action or executes outside an existing application command
   service;
6. confirmation, CAS, idempotency, stale-state, replay or audit-chain controls are incomplete;
7. AI governance operations, kill-switch, rollout/rollback or incident evidence is incomplete;
8. any required production path remains test-only or default-open;
9. a P0/P1/P2 actionable review/security finding remains unresolved;
10. M6-G overall acceptance and natural post-main evidence are incomplete.

Issues #13 and #14 are unrelated closure invariants and remain open.

## 13. M6-R1 Definition of Done

M6-R1 is complete only when:

- this document records the exact current truth and issue links;
- the M6-E, M6-F and M6-G issues exist and are linked from Issue #62 by a dated comment;
- the PR contains no M6-E/M6-F product capability, Provider, Secret, network, persistence,
  migration, Queue, Worker, Scheduler or command;
- the exact documented Head passes the permanent four-job workflow;
- all four artifacts are downloaded and independently SHA-256 matched;
- reviews and threads contain no actionable finding;
- Ready and Merge Commit gates pass;
- the natural merge `push -> main` Run and four main artifacts pass again;
- Issue #78 receives final evidence and closes with `state_reason=completed`.

Only after that post-main closure may M6-E begin.
