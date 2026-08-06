# M6-F P8-G1 — Completeness, Security, Compatibility and Production-Readiness Audit

Status: `P8_G1_AUDIT_COMPLETE_NO_BLOCKER_PENDING_EXACT_HEAD_PERMANENT_VALIDATION`

Permanent conclusion: `AI_IS_NOT_AN_OPERATOR`

## 1. Purpose and Gate boundary

P8-G1 audits the complete frozen M6-F scope after P7 Formal Acceptance and P8-R0 final rebaseline. It verifies that the implementation, tests, migrations, clients, workflow and permanent evidence agree with the non-executing authority model and that no stale assumption, compatibility defect or production-readiness blocker remains.

P8-G1 adds no product capability. The exact audit Head must pass the sole permanent workflow and independent Artifact reconstruction before P8-G2 Formal Acceptance may start. Ready, auto-merge, merge, Issue closure and M6-G remain prohibited.

## 2. Exact audit baseline

| Item | Exact value at P8-G1 start |
| --- | --- |
| Repository | `akaryc1b/approval-platform` |
| Target branch | `main` |
| Exact current `main` | `492a428627d3be707d5723350506302ca04841b0` |
| Pull Request | `#88 — M6-F: controlled automation and AI governance` |
| Formal branch | `agent/m6-f-controlled-automation-and-ai-governance` |
| Exact accepted P8-R0 Head | `59b2f161982f30bdfc56ba0bf34c26e23d5b35ad` |
| Compare | ahead `167`, behind `0`; merge base equals current `main` |
| PR state | Open / Draft / mergeable / not merged |
| Commits | `167` |
| Changed files | `141` |
| Additions / deletions | `27444 / 28` |
| Reviews | none |
| `REQUEST_CHANGES` | none |
| Unresolved Review Threads | none |
| Highest governed migration | unique `V50`; no V51 |
| Automatic PR/main workflow | only `.github/workflows/approval-platform-validation.yml` |
| Auto-merge | disabled and not configured |
| Issues #81, #82, #62, #13, #14 | Open |
| PR #83 | Merged / Closed and unchanged |

No `main` drift occurred after P8-R0. The audit therefore requires no Merge Commit rebaseline.

P8-R0 permanent evidence:

- exact Head `59b2f161982f30bdfc56ba0bf34c26e23d5b35ad`;
- natural Run `31080901527` / #1305: success;
- Maven core `1463`, JDBC `318`, aggregate `1781`, all with zero failures, errors or skips;
- permanent M6 transport boundary `154/154`;
- P8-R0 Acceptance comment `5201740440`.

## 3. Audit method

The audit re-read production source, integration contracts, architecture tests, permanent Node boundaries, clients, migrations and workflow rather than relying only on PR prose. The reviewed surfaces include:

- `ControlledAutomationProposal`;
- `ControlledAutomationActionWhitelist`;
- `ControlledAutomationGovernanceEvaluator`;
- `ControlledAutomationConfirmationService`;
- `ControlledAutomationReauthenticationVerifier`;
- `ControlledAutomationLineageStore` and `JdbcControlledAutomationLineageStore`;
- `OpenAiResponsesProductionRuntimeFactory`, Transport Admission, Circuit, Rate and Usage implementation;
- all six AI governance controllers and raw-request boundary filter;
- V49 durable Evidence/State/Event and V50 Lineage/Event migrations;
- fresh-install and historical upgrade tests;
- P7 adversarial, fault, concurrency and incident tests;
- Web and Mobile advisory/confirmation components;
- `.github/workflows/approval-platform-validation.yml` and the permanent M6 Node boundary chain.

The accompanying permanent Node audit binds the findings below to exact code patterns, file inventory, migration versions and workflow topology.

## 4. A — Controlled Automation completeness

### 4.1 Proposal remains permanently non-executable

`ControlledAutomationProposal` fixes:

- authority to `NON_EXECUTABLE_PROPOSAL`;
- status to `PROPOSED` at creation;
- explicit human confirmation required;
- Reauthentication requirement to `REQUIRED` for any defined Action;
- typed closed parameters only;
- tenant/operator/resource/policy/source evidence as hashes and immutable version references;
- no command payload, credential, HTTP body, SQL, script, dynamic module or executable lifecycle state.

A Proposal has no method that invokes an application service, Provider or Connector.

### 4.2 Action Whitelist remains exact and empty in production

The Action Whitelist interface exposes only canonical definitions, parameter schema, risk and Reauthentication metadata. The accepted production decision remains:

`EMPTY_PENDING_EXISTING_COMMAND_AUDIT`

No production Action, placeholder Action, demonstration Action or test-only Action is authorized. The exact P5 decision remains:

`P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND`

### 4.3 Fresh Governance Evaluation is complete and read-only

Every evaluation reloads a fresh governance snapshot and the current whitelist. The decision sequence binds and verifies:

- Tenant evidence;
- Operator evidence;
- active Proposal status, lineage and expiry;
- source evidence existence, hash and integrity;
- whitelist version and Action definition drift;
- current Policy version/hash and decision;
- feature flag and Kill Switch;
- Permission and resource authorization;
- resource identity, state and expected version;
- separation of duties;
- application-command preconditions;
- Reauthentication readiness.

The evaluator produces read-only evidence and has no command, Provider, persistence mutation or retry path.

### 4.4 Confirmation remains explicit and non-executing

Confirmation requires:

- `EXPLICIT_CLICK`;
- exact Proposal ID and lineage hash;
- an eligible fresh evaluation and exact evaluation hash;
- exact Tenant and Operator identity;
- unexpired Proposal and challenge;
- accepted Reauthentication evidence bound to Proposal and evaluation.

The resulting authority is `NON_EXECUTABLE_CONFIRMATION`; `commandAdmitted` is permanently false and `singleUseRequired` is true. Confirmation evidence carries no password, TOTP, API key, bearer token, session credential, permission token, command payload, SQL or script.

### 4.5 Production Reauthentication is honestly unavailable

The production default verifier returns `UNAVAILABLE`. No session expiry, operation reason, audit record or UI click is misrepresented as fresh Reauthentication. This blocks command admission rather than weakening the requirement.

### 4.6 P4 Lineage semantics are complete

The Java and PostgreSQL contracts agree on:

- exact Registration replay for the same identity/key/payload;
- conflict for key or identity reuse with different payload;
- tenant/operator isolation;
- one row-locked expected revision/status CAS transition;
- one append-only Registration event and one optional append-only terminal event;
- unique terminal winner;
- `CANCELLED` with zero attempts;
- `SUCCEEDED`, `FAILED`, `PARTIAL` or `UNKNOWN` with exactly one attempt;
- `UNKNOWN` terminal and no automatic retry;
- physical mutation/deletion rejection;
- deferred State/Event equivalence.

No path bypasses an existing application command service because no command adapter is admitted at all. No `Provider -> direct command` path exists.

**A conclusion:** complete for the frozen non-executing scope; no blocker found.

## 5. B — AI Governance completeness

### 5.1 Inventory and version traceability

The read-only inventory covers the three accepted capabilities:

- approval summary;
- material completeness;
- risk signals.

Each entry binds exact Provider, Provider version, model snapshot, Prompt template ID/version/hash, `KnowledgeSourceVersion.none()`, Policy ID/version/hash and output-schema ID/version.

### 5.2 Secret minimization

Operations views expose only the Secret version evidence hash and effective/expiry window. Raw API-key material is acquired only inside the scoped sender callback after Admission, DNS and verified TLS. The key is not returned by governance operations or stored in V49/V50.

### 5.3 Shared Runtime controls

`OpenAiResponsesProductionRuntimeFactory` owns one shared:

- `RateLimiter`;
- `CircuitBreaker`;
- `CostPolicy`;
- Kill Switch snapshot;
- process-local `UsageLedger`.

Tenant bindings are cached and bounded, but read-only Control Health and Usage snapshots do not create a Binding. The operations composition root reuses the same factory; it does not instantiate a second Runtime, Circuit, RateLimiter or Usage Ledger.

P7-C corrected atomic Circuit state/generation reads and fail-closed Runtime Control/Usage drift detection during composite Incident Readiness.

**B conclusion:** complete for read-only governance; no duplicate control plane or Secret exposure found.

## 6. C — Operations API completeness

The complete operations surface remains exactly six endpoints:

1. `GET /api/approval/management/ai-governance/snapshot`;
2. `GET /api/approval/management/ai-governance/change-plan?operation=<CANARY|ROLLOUT|ROLLBACK>`;
3. `GET /api/approval/management/ai-governance/control-health`;
4. `GET /api/approval/management/ai-governance/usage`;
5. `GET /api/approval/management/ai-governance/history?from=<canonical Instant>&to=<canonical Instant>`;
6. `GET /api/approval/management/ai-governance/incident-readiness?from=<canonical Instant>&to=<canonical Instant>`.

Every controller requires tenant-scoped management `READ`, uses `Cache-Control: no-store`, declares GET only and exposes no mutation mapping.

The high-priority raw-request filter runs before identity resolution and rejects:

- a missing, duplicate, conflicting or non-canonical Tenant header;
- non-GET methods;
- method-override headers;
- request bodies or transfer encoding;
- unknown, missing or duplicate query parameters;
- unknown Change Plan operations;
- non-canonical, reversed, overlong, future or excessive-lookback time windows.

Read-only sources do not:

- call `bind()`;
- acquire a Provider permit;
- read Secret material;
- call the Provider;
- write durable state;
- apply a plan, mutate traffic or execute rollback.

Change Plan retains planned traffic zero and all apply/deployment/mutation/Provider/command authority false.

**C conclusion:** exact, tenant-scoped and fail-closed; no write or execution endpoint found.

## 7. D — Persistence and upgrade compatibility

### 7.1 Version ownership

- M6-E owns exact V49 durable approval-assistance Evidence/State/Event;
- M6-F owns exact V50 controlled-automation Lineage/Event;
- migration versions remain continuous and unique through V50;
- no V51 exists;
- neither historical migration is modified by P8.

### 7.2 V49 guarantees

V49 stores bounded, hash-only evidence and exact version references. It constrains Provider attempts to `0..1`, ties invocation-started to attempts, permanently forbids retry/fallback flags, limits advisory cardinalities, enforces classification/result coherence, bounds retention and stores no raw Provider body, Prompt text or Secret.

Evidence rows are immutable. State supports one ACTIVE-to-TOMBSTONED CAS transition. Events are append-only. Deferred constraint triggers require exact State/Event agreement. Retention expiry cannot be applied early and no automatic Tombstone process exists.

### 7.3 V50 guarantees

V50 enforces hash formats, closed states/outcomes, exact attempt counts, no automatic retry, immutable identity, one terminal revision, tenant-scoped uniqueness, append-only Event identity, FK ownership and deferred State/Event equivalence.

`JdbcControlledAutomationLineageStore` uses Spring transactions, row locks and exact replay/conflict comparison. Event or State failure rolls the transaction back atomically.

### 7.4 Fresh install and historical upgrade

Real PostgreSQL tests validate fresh installation and upgrades from historical versions through V50, including the heavy V27 dataset path. Upgrade-only paths assert zero side effects in M5 and M6 execution/evidence tables and verify the complete expected schema/index set.

**D conclusion:** V49/V50 ownership, schema, constraints, indexes and upgrade compatibility are coherent; no database blocker or V51 need found.

## 8. E — Security audit

The accepted security posture includes:

- Tenant and Operator evidence binding;
- tenant-scoped authorization before source access;
- other-tenant History and Usage isolation;
- exact global Usage redaction to a saturation boolean;
- lowercase SHA-256 validation without attacker-input normalization;
- immutable component and composite evidence hashes;
- Prompt/command/HTTP/SQL/Shell/Flowable/Connector injection rejection;
- Secret redaction and callback-scoped material;
- Proposal expiry and stale Policy/State/Version rejection;
- Registration and terminal replay protection;
- terminal post-dispatch `UNKNOWN` with no automatic retry;
- fail-closed malformed input, Provider output, persistence failure and composite drift.

P7-A, P7-B and P7-C exercise these boundaries through real HTTP binding, controlled Transport, production Runtime semantics and real PostgreSQL transactions.

**E conclusion:** no fail-open path, cross-tenant exposure or command-injection authority found.

## 9. F — Fault and concurrency audit

P7 coverage is executable rather than documentation-only:

- adversarial HTTP, Proposal, Confirmation and evidence tests are part of Maven Core and permanent Node boundaries;
- Provider/Transport/Circuit/Usage fault tests use controlled production semantics;
- History and Lineage fault/concurrency tests use real PostgreSQL;
- deterministic concurrency uses `CountDownLatch`, virtual-thread `ExecutorService`, controlled clocks and row locks;
- no P7 concurrency acceptance test relies on `Thread.sleep`, randomness, a real Provider or a real Secret;
- architecture and static tests verify the production implementation, not a separate test-only command implementation.

P8-R0 rebuilt Maven core `1463`, JDBC `318`, aggregate `1781`, with zero failures, errors or skips. JDBC sharding selected `79` classes, produced `78` reports and identified exactly one expected abstract class without a report, with zero duplicate selections and zero non-abstract omissions.

**F conclusion:** P7 coverage is real, deterministic and aligned with production classes; no remaining concurrency blocker found.

## 10. G — Web and Mobile audit

PC and Mobile use equivalent frozen confirmation-boundary semantics:

- `AI_IS_NOT_AN_OPERATOR`;
- `NON_EXECUTABLE`;
- Action `NOT_AUTHORIZED`;
- authorization preview `ACTION_NOT_WHITELISTED`;
- whitelist `EMPTY_PENDING_EXISTING_COMMAND_AUDIT`;
- Reauthentication `UNAVAILABLE`;
- no target resource or typed parameter;
- disabled confirmation button;
- explicit warning that page load, refresh, tab change, Enter, countdown and retry do not confirm or execute;
- explicit statement that Confirmation does not equal command success.

No executable client API, “confirm and execute” behavior or rendered developer comment was found in these components. P8-R0 Vben and Mobile type/build gates succeeded.

**G conclusion:** PC/Mobile semantics are aligned, advisory and non-executing; no misleading executable UI found.

## 11. H — Workflow and repository audit

The repository retains exactly one automatic PR/main workflow:

`.github/workflows/approval-platform-validation.yml`

Its permissions are read-only (`contents: read`). It performs:

- tracked-tree and temporary-artifact hygiene;
- permanent M4/M5/M6 boundary tests;
- Java 21 Maven Core verification;
- four deterministic real-PostgreSQL JDBC shards;
- independent shard-selection/report reconciliation;
- Maven evidence assembly;
- Vben boundary/type/build gates;
- Mobile type/H5/WeChat gates;
- four final Artifact classes.

The audit found no:

- temporary automatic workflow;
- `continue-on-error` bypass;
- Maven test skip flag in the permanent verification command;
- disabled P7 test;
- committed patch/base64 payload;
- local absolute path;
- production Secret value;
- debug command backdoor;
- direct-main modification or auto-merge configuration.

Failures and failure Artifacts are retained by `if: always()` upload steps; the workflow does not delete failed Run history.

**H conclusion:** permanent CI and repository hygiene are suitable for P8 Formal Acceptance; no workflow blocker found.

## 12. I — Honest limitations

M6-F provides a secure non-executing controlled-automation foundation and AI governance capability. It does not provide:

- a non-empty production Action Whitelist;
- a production command or application-command adapter;
- Production Reauthentication;
- approve, reject/return, transfer, withdraw, terminate, migrate or any process-state command;
- automatic Retry, fallback, Rollback, Notification, Incident execution or Retention Tombstone;
- actual Provider billing;
- durable P6-D cost-upper-bound History;
- durable Circuit or Control Health time-series;
- Canary, rollout, deployment or traffic mutation;
- Provider, model, Prompt, Policy or Secret mutation;
- direct Flowable or `ACT_*` access;
- arbitrary HTTP, SQL, Shell, script or Connector execution;
- Queue, Worker, Scheduler, Listener, Polling or autonomous execution.

These limitations are intentionally preserved and must be repeated in P8-G2 Formal Acceptance, Ready/Merge evidence and Issue #81 closure.

## 13. Audit conclusion

No blocking completeness, security, compatibility, upgrade, client, workflow or production-readiness defect was found within the frozen M6-F scope.

No production Correction is required by P8-G1.

The audit is not accepted until the exact document/static-boundary Head passes the complete permanent workflow, all four Artifacts are independently verified, test/shard totals are rebuilt and Review state is re-read.

Until then:

`P8_G1_PENDING_EXACT_HEAD_VALIDATION`

`P8_G2_PROHIBITED`

`READY_MERGE_ISSUE_CLOSURE_PROHIBITED`

`AI_IS_NOT_AN_OPERATOR`
