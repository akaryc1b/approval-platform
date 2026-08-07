# M6-G G2 — End-to-End and Production Readiness Acceptance

Status: `M6_G_END_TO_END_ACCEPTANCE_PASSED`

Production decision: `M6_PRODUCTION_READINESS_BLOCKED`

Exact accepted input Head: `d5e6b181050a12b0601291f8a458da5ffa05f9e0`

Exact base: `main@0cf6572770953a46fe5b16d15ecdff78cf607855`

Branch: `agent/m6-g-overall-formal-acceptance-and-production-readiness`

Draft PR: `#93`

Tracking Issue: `#82`

Parent Issue: `#62`

## 1. Purpose

G2 verifies that the already accepted M6-A through M6-F capabilities compose safely as one system. It does not redesign the product and does not add a Connector, Event runtime, Template marketplace, Provider, executable Action, Production Reauthentication, automatic Retry/Rollback/Notification/Retention, Canary, deployment, rollout, traffic mutation or Production Promotion.

G0 and G1 are prerequisites and are permanently validated at Run `31151203020` / #1332. G1 reports zero unresolved `CORRECTABLE_DEFECT` items. G2 therefore evaluates integration and Production Readiness separately.

## 2. End-to-end scenario matrix

Every row below is permanently asserted by `scripts/tests/m6-g-end-to-end-production-readiness-boundary.test.mjs` and by the existing Java/PostgreSQL/Web/Mobile boundary suites it references.

| ID | Integrated scenario | Exact evidence | Result | Meaning |
| --- | --- | --- | --- | --- |
| `G2-01` | Connector metadata and AI Runtime coexist without tenant cross-line | `ControlledAutomationGovernanceConfiguration`, M6-A tenant-routing acceptance | `PASS` | Usage/history reads receive the authenticated tenant and never enumerate another tenant. |
| `G2-02` | Connector Secret Material cannot enter AI durable evidence | V49 schema and `ApprovalAssistanceDurableEvidence` | `PASS` | Evidence stores hashes/provenance only; no raw Secret, API key, credential material, request or response column exists. |
| `G2-03` | AI Provider metadata cannot enter Connector credential requests | `CredentialMaterialRequest` | `PASS` | Connector material requests are server-owned Connector contracts and have no AI model/Prompt/schema dependency. |
| `G2-04` | SDK/Event payloads carry no Secret or trusted authority | `m6-sdk-event-boundary.test.mjs` | `PASS` | Public clients cannot manufacture tenant/operator/permission/audit/credential authority. |
| `G2-05` | Template/Component versions align with AI Form/UI provenance | `ApprovalAssistanceGenerationServiceTest.projectionUsesExactTrustedReleaseFormAndUiProvenance` | `PASS` | Exact package, Form and UI hashes/versions are projected from trusted storage. |
| `G2-06` | AI Assistance uses exact Release, Form and UI Schema | same projection test plus `PendingTaskDetails` provenance | `PASS` | No definition-hash or fabricated UI fallback is accepted. |
| `G2-07` | Task changes after Provider completion cause zero evidence write | `changedTaskAfterProviderFailsBeforeEvidenceStore` and `missingTaskAfterProviderFailsBeforeEvidenceStore` | `PASS` | One Provider attempt may have occurred, but stale/missing task returns `STALE_TASK` and writes zero evidence. |
| `G2-08` | Post-dispatch timeout/I/O UNKNOWN never causes a second call | `OpenAiResponsesPostDispatchUnknownAcceptanceTest` | `PASS` | Resolve/connect/exchange and Secret read counts remain exactly one; result classification is UNKNOWN. |
| `G2-09` | AI durable evidence cannot become command authority | `ApprovalAssistanceDurableEvidence` and M6-F authority boundary | `PASS` | Hash-only advisory evidence contains no command payload/admission field. |
| `G2-10` | Controlled Automation cannot bypass the empty whitelist | `M6_F_ACTION_WHITELIST_DECISION.md` and `ControlledAutomationActionWhitelist` | `PASS` | `EMPTY_PENDING_EXISTING_COMMAND_AUDIT` resolves no Action. |
| `G2-11` | Confirmation cannot bypass unavailable Production Reauthentication | `ControlledAutomationConfirmationServiceTest` | `PASS` | Unavailable verification yields `REAUTHENTICATION_UNAVAILABLE`, no evidence and no confirmation ID. |
| `G2-12` | Governance operations do not create Runtime Binding | `ControlledAutomationGovernanceConfiguration` and P8-G1 boundary | `PASS` | Read views call snapshot methods only and never call Runtime `bind`. |
| `G2-13` | Incident Readiness does not invoke the Provider | `ControlledAutomationGovernanceConfiguration` | `PASS` | It composes snapshot/control/usage/history/manual rollback-review evidence only. |
| `G2-14` | Circuit OPEN is not reported healthy | `ControlledAutomationGovernanceControlHealthContracts` | `PASS` | OPEN maps to `AI_PROVIDER_CIRCUIT_OPEN`. |
| `G2-15` | Circuit HALF_OPEN is not reported healthy | `ControlledAutomationGovernanceControlHealthContracts` | `PASS` | HALF_OPEN maps to `AI_PROVIDER_CIRCUIT_HALF_OPEN`. |
| `G2-16` | Tenant rate saturation leaks no other-tenant usage | `ControlledAutomationGovernanceUsageContracts` | `PASS` | Tenant saturation is closed; `otherTenantUsageExposed=false`. |
| `G2-17` | Global rate saturation leaks no exact global count | `ControlledAutomationGovernanceUsageContracts` | `PASS` | Only a saturation posture is exposed; `globalExactUsageExposed=false`. |
| `G2-18` | Durable history failure performs no repair write | `JdbcApprovalAssistanceGovernanceHistoryFaultIntegrationTest` | `PASS` | Missing V49 table throws and leaves state/event counts unchanged. |
| `G2-19` | Web never automatically triggers Provider generation | Web `ApprovalAssistancePanel.vue` | `PASS` | Exactly one generation call is reachable from an explicit click and in-flight duplicates are blocked. |
| `G2-20` | Mobile never automatically triggers Provider generation | Mobile `ApprovalAssistancePanel.vue` | `PASS` | Same explicit-click advisory contract as Web. |
| `G2-21` | Event replay cannot duplicate AI, Connector or Template side effects | SDK replay/worker permanent boundary | `PASS` | Replay is deterministic contract validation and no production delivery Worker or automatic AI/command path exists. |
| `G2-22` | Clean install reaches V50 without execution side effects | `freshAndHistoricalUpgradePathsReachV50WithoutExecutionSideEffects` fresh case | `PASS` | Clean PostgreSQL schema reaches unique V50 and creates no migration/AI execution rows. |
| `G2-23` | Historical upgrade paths reach V50 without evidence mutation | `JdbcApprovalMigrationUpgradeIntegrationTest` | `PASS` | V1/V13/V23/V27/V31/V36–V49 paths validate, including 5,000-row V27 evidence preservation. |
| `G2-24` | PostgreSQL locking, CAS and replay semantics remain native | `JdbcControlledAutomationLineageStore` and concurrency acceptance | `PASS` | Row locks, transactions, unique constraints and exact replay are PostgreSQL-backed. |
| `G2-25` | PostgreSQL nearest-microsecond rounding remains exact | `JdbcControlledAutomationLineageInstantPrecisionIntegrationTest` | `PASS` | `<500ns` rounds down, `>=500ns` rounds up and second carry is tested. |
| `G2-26` | The single permanent Workflow remains complete | `.github/workflows/approval-platform-validation.yml` | `PASS` | One automatic PR/main workflow runs nine physical Jobs. |
| `G2-27` | Four Artifact classes remain independently reconstructable | workflow artifact merge plus shard verifier | `PASS` | Maven, Vben, Mobile and Hygiene Artifacts contain raw logs/reports and exact shard coverage. |
| `G2-28` | No production AI path accesses Flowable internal tables | M6-F P8-G1 source scan | `PASS` | AI production modules contain no Flowable dependency or `ACT_*` reference. |
| `G2-29` | No `Provider -> Command` path exists | M6-F P8-G1 authority scan | `PASS` | AI production modules do not depend on application command services. |
| `G2-30` | Browser/Mobile cannot manufacture trusted permission | `approval-client-boundary.test.mjs` | `PASS` | Client overlays cannot send `X-Approval-Trusted-Permissions` or direct tenant-management authority. |

Scenario result: `30 / 30 PASS`.

## 3. Integrated safety conclusions

### Tenant and Secret isolation

Connector routing, Connector credential material, AI Runtime binding, AI usage/history and durable evidence retain separate server-owned contracts. Tenant identifiers are used only in their owning boundary and are hashed/redacted where durable evidence requires it. Connector Secret Material is not a field in AI evidence; AI model/Prompt metadata is not a field in Connector credential material.

### Authority chain

The permanent authority chain remains:

`AI advisory -> typed non-executable proposal -> fresh server policy/precondition evaluation -> fresh authorization preview -> explicit human confirmation -> existing application command service -> immutable audited result`

The repository currently stops before the final command-service step because the whitelist is empty, P5 is skipped and Production Reauthentication is unavailable.

`Provider -> direct command` remains prohibited.

### At-most-one and UNKNOWN

AI Assistance performs at most one Provider attempt. Post-provider task revalidation occurs before durable evidence construction. UNKNOWN does not retry. Evidence-store conflict/unavailability does not issue another Provider request. Controlled Automation lineage records zero-or-one command attempts but no command binding currently exists.

### Persistence and upgrade

The composed Flyway topology reaches unique V50, including Java V38 and separate V49/V50 locations. Clean and historical PostgreSQL paths validate without execution side effects. V49/V50 enforce tenant/hashes/state/append-only/CAS constraints. Real PostgreSQL tests cover locks, replay, concurrency and nearest-microsecond rounding.

### Operations and clients

Governance endpoints remain tenant-scoped, GET-only and `no-store`. Incident readiness is evidence and manual response guidance, not an executor. Web/Mobile require explicit user action, display advisory/unverified/human-review state and expose no trusted permission, Provider mutation or automation command.

## 4. Issue #82 Parent Closure evaluation

| Parent closure question | G2 finding | Decision |
| --- | --- | --- |
| Does required production behavior still depend on deterministic mocks? | M6-B event delivery and portions of Connector operations remain contract/test-only; M6-E has a real adapter but no customer production authorization/rehearsal. | `PARENT_CLOSURE_BLOCKER` |
| Is AI bound to an executable application command? | No; this is intentionally blocked. | `PARENT_CLOSURE_BLOCKER` |
| Is a real Provider gate present? | Adapter exists and is default-disabled, but customer environment/egress/on-call/release approval is not proven. | `PARENT_CLOSURE_BLOCKER` |
| Is durable evidence present? | Yes, V49/V50 evidence is durable and tested. | `PASS` |
| Is retention complete? | Retention due posture exists; operated tombstone execution does not. | `PARENT_CLOSURE_BLOCKER` |
| Does M6-F have a qualifying valuable Action? | No; whitelist is empty and P5 skipped. | `PARENT_CLOSURE_BLOCKER` |
| Does any command bypass application services? | No bypass exists. | `PASS` |
| Are Operations complete for production? | Read-only evidence is complete; operated ownership, billing/cost, durable control timeline and production runbooks/sign-offs are incomplete. | `PARENT_CLOSURE_BLOCKER` |
| Is Rollback complete? | Manual non-executable review exists; no operated rollback executor. | `PARENT_CLOSURE_BLOCKER` |
| Is Incident Response complete? | Fault/rehearsal evidence exists; operated response/notification/retention execution is incomplete. | `PARENT_CLOSURE_BLOCKER` |
| Is Upgrade complete? | Clean and historical V50 paths pass. | `PASS` |
| Are PC/Mobile semantics complete for accepted scope? | Yes, advisory-only and explicit-click parity passes. | `PASS` |
| Is Security evidence complete? | Code boundaries pass, but dedicated GitHub alert inventory and dependency applicability/reachability evidence remain unavailable. | `PRODUCTION_READINESS_BLOCKER` |
| Are Reviews closed? | No current M6-G Review blocker at the G2 input gate. Historical PR #68 metadata is separately disclosed. | `PASS` |

## 5. Production Readiness blocker inventory

G2 does not authorize implementation of these gaps:

- M6-A B01–B20 operated production Connector gates;
- durable production Event subscription/delivery runtime;
- customer production Provider authorization, environment, egress, on-call and release rehearsal;
- operated retention tombstone execution;
- Action Whitelist `EMPTY_PENDING_EXISTING_COMMAND_AUDIT`;
- `P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND`;
- Production Reauthentication unavailable;
- no qualifying executable Application Command;
- no operated automatic Retry, Rollback, Notification or Retention;
- no actual Provider billing or durable cost history;
- no durable Circuit/Control Health time-series;
- no Canary, rollout, deployment or traffic mutation;
- incomplete dedicated GitHub security-alert and dependency-applicability evidence.

These are product/operational gates, not hidden M6-G implementation tasks.

## 6. G2 gate decision

No integrated scenario exposes a new Product Bug, Security Bug, Tenant leak, authorization bypass, replay/CAS defect, PostgreSQL defect, client authority defect or Review blocker.

The existing capabilities compose safely for their accepted, non-promoted scope.

```text
M6_G_END_TO_END_ACCEPTANCE_PASSED
M6_PRODUCTION_READINESS_BLOCKED
UNRESOLVED_CORRECTABLE_DEFECTS=0
NO_NEW_M6_PRODUCT_CAPABILITY
NO_PRODUCTION_PROMOTION
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
ISSUE_13_REMAINS_OPEN
ISSUE_14_REMAINS_OPEN
READY_MERGE_ISSUE_CLOSURE_PROHIBITED
AI_IS_NOT_AN_OPERATOR
```

This decision becomes the permanent G2 evidence only after the exact G2 Head passes one natural Batch B PR Workflow with all nine Jobs and four independently verified Artifacts. G3, Ready, merge and Issue closure remain prohibited until then.
