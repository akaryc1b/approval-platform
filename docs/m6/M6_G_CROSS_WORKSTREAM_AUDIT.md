# M6-G G1 — Cross-Workstream Completeness, Security and Compatibility Audit

Status: `M6_G_G1_AUDIT_COMPLETE`

Exact audit base: `main@0cf6572770953a46fe5b16d15ecdff78cf607855`

Branch: `agent/m6-g-overall-formal-acceptance-and-production-readiness`

Tracking Issue: `#82`

Parent Issue: `#62`

## Audit method

G1 audits M6-A through M6-F as one integrated capability. It reuses accepted code, PostgreSQL, boundary, review and Artifact evidence but does not treat a successful historical PR as automatic production approval. The audit searched the current source, accepted documents, migrations, Web/Mobile surfaces, workflow, PR reviews and Issue #82 closure contract.

Classifications are defined in `M6_G_G1_BLOCKER_MATRIX.md`:

- `PASS` — the promised boundary exists and current evidence supports it;
- `NON_BLOCKING_LIMITATION` — an explicit accepted non-goal that does not falsify existing behavior;
- `CORRECTABLE_DEFECT` — a defect in promised behavior that M6-G may fix;
- `PRODUCTION_READINESS_BLOCKER` — evidence or operational readiness is insufficient for a Production Ready declaration;
- `PARENT_CLOSURE_BLOCKER` — Issue #62 cannot close while the capability/operational gate is absent.

No capability is promoted from missing to PASS by wording. No new product capability is authorized by this audit.

## A — M6-A Connector Foundation

### Accepted boundaries

- Connector contracts, provider descriptors, credential references, tenant routing, token lifecycle, transport controls, read-only governed invocation, diagnostics and security/fault/concurrency evidence are present.
- Trusted tenant/operator/credential identity is server-owned. Browser and SDK payloads cannot supply a usable Secret Material value.
- Production Secret Material is abstracted behind a server boundary; the concrete production backend remains disabled with `BLOCKED_PENDING_BACKEND_SELECTION`.
- DingTalk token acquisition/refresh contracts use bounded single-flight and expiry rules, but the production token path is disabled by default.
- Connector invocation is default-disabled and constrained to declared read-only operations. No Connector path is allowed to approve, reject, transfer, withdraw, terminate, migrate or directly mutate an approval.
- Operations diagnostics are read-only, bounded, redacted and default-disabled.
- Faults/timeouts fail closed; no uncertain connector result is converted into approval success.
- Application source does not query or modify Flowable `ACT_*` tables through Connector code.

### Production decision

The M6-A Production Blocker Catalog B01–B20 remains fully `BLOCKED`, including real Secret backend, customer endpoint, production egress, durable audit ownership, on-call ownership, incident playbook, capacity evidence, retention approval, security review, change approval, operated kill switch, tenant allowlist, token rotation, rate policy, provider contract, legal/privacy, release sign-off, observability backend, disaster recovery and mutation separation.

These are `PARENT_CLOSURE_BLOCKER` items, not defects to implement in M6-G.

## B — M6-B SDK and Event Ecosystem

### Accepted boundaries

- Java and TypeScript SDK/event contracts are versioned and consume deterministic canonical fixtures.
- Canonical JSON, signatures, timestamp/nonce rules, replay reservations, idempotency, compatibility manifests, support windows and deprecation policies are cross-language tested.
- PR #74 corrected the accepted upper-bound nonce lifetime, collision-free replay identity and invalid calendar-date rejection.
- Tenant/operator/permission/audit/credential authority cannot be manufactured by public SDK requests.
- Event and Webhook contracts contain reference-only credential identities, not Secret values.
- Deterministic retry policy requires idempotency and is only a contract/test model; no production retry Worker exists.
- No Event-to-command, Event-to-AI automatic invocation or direct Flowable path exists.
- Duplicate/conflicting replay and broken checkpoint/reconciliation evidence fail closed rather than returning success.

### Production decision

Durable production subscription, event store, Outbox delivery, broker, Worker, scheduler, listener, production clock, automatic recovery and delivery audit persistence remain absent and explicitly unauthorized. The existing implementation is a deterministic contract/test ecosystem. This is a `PARENT_CLOSURE_BLOCKER` for a claim that M6 provides a complete production event-delivery ecosystem.

The three unresolved PR #68 thread records are historical metadata only: their defects were corrected by PR #74. They remain a `NON_BLOCKING_LIMITATION`, not a current code defect.

## C — M6-C Template and Component Ecosystem

### Accepted boundaries

- Template Package, Form Package, Form/UI Schema and component descriptor identities are deterministic and version/hash bound.
- Strict JSON/package validation enforces raw wire-size, depth, element, string, number, duplicate-key, unknown-field, path and Unicode limits.
- PR #75 preserves client-error mappings and checks the raw embedded package size before normalization.
- Import preview is side-effect free; governed import creates one tenant-local editable `DRAFT` only.
- Tenant/operator/request/trace/registry/permission evidence is server-owned and cross-tenant reads fail closed.
- Components are data-only descriptors with closed properties and read-only fallback. No JavaScript, arbitrary HTML, expression, URL, remote module or dynamic loader authority is accepted.
- Template import cannot publish, deploy, activate, launch a process, execute M5 migration or call an approval command.
- Web/Mobile consume host-governed Form/UI Schema and field permissions.

### Limitation decision

A marketplace, remote package download, remote registry, dynamic component implementation and production activation are explicit non-goals of the accepted workstream. Their absence is `NON_BLOCKING_LIMITATION` for M6-G acceptance and must not be implemented in this audit.

## D — M6-D AI Foundation

### Accepted boundaries

- Provider-neutral SPI, capabilities, deterministic selection, timeout/cancellation, Circuit Breaker, rate/cost envelopes and kill-switch evidence are isolated from approval commands.
- Provider, model, Prompt metadata, knowledge metadata, policy and output-schema versions are bound into advisory/evidence records.
- Tenant, operator, permission, resource, field permission, masking and data-minimization policy are server-owned and applied before Provider invocation.
- Provider collection/depth limits are checked at preflight and invocation-time selection.
- Circuit generations prevent a stale success from closing a newer OPEN/HALF_OPEN generation.
- Hash inputs use unambiguous framing; activation review recomputes and verifies its evidence hash.
- Provider failures degrade to bounded advisory failure classifications without unsafe post-invocation fallback.
- No Provider-to-command, AI-to-Flowable, AI-to-migration, permission mutation, Secret mutation or traffic mutation path exists.
- Raw customer Prompt/input, raw Provider response and Secret Material are not persisted as M6-D audit evidence.
- Deterministic mock Providers remain test-only.

### Production decision

M6-E later supplied one real OpenAI Responses adapter, so the repository is no longer mock-only. However customer production Provider authorization, environment configuration, operated egress/on-call approval and real production rehearsal are not proven by CI. This remains a `PARENT_CLOSURE_BLOCKER` for overall Production Readiness.

## E — Governed AI Approval Assistance

### Accepted boundaries

- Every successful result remains `ADVISORY`, `UNVERIFIED_ADVISORY`, `needsHumanReview=true`.
- The GET read view reports runtime availability only and performs zero Provider invocation, Secret retrieval or evidence write.
- Generation is a user-explicit POST path. Web and Mobile do not invoke it automatically.
- A request performs at most one Provider attempt and no unsafe retry or post-invocation fallback.
- Server-owned task/context projection binds tenant, operator, authorization, process/task state, exact release, Form Package, Form Schema and UI Schema provenance.
- Field permission, minimization and masking occur before invocation.
- Exact task details are re-read before Provider dispatch and again after Provider completion. Missing or changed task state returns `STALE_TASK`; advisory evidence is not written.
- Post-dispatch unknown outcome records UNKNOWN posture and does not issue a second Provider request.
- V49 stores tenant-scoped hash/provenance evidence, state and append-only events; it stores no raw request, raw response, Prompt body, Secret or advisory text.
- Evidence retention posture is durable and queryable, but retention execution is not automatic.
- The production Runtime is server-owned and default-disabled; Secret Material exists only inside the bounded callback/invocation domain.
- Provider output cannot become an approval decision or command authority.

### Limitations and blockers

Two simultaneous explicit user requests are bounded independently rather than by a distributed pre-dispatch exactly-once reservation. Existing idempotency/evidence constraints still prevent authority expansion, so this is a `NON_BLOCKING_LIMITATION` rather than an observed promised-behavior defect.

An operated retention executor, actual Provider billing, durable cost history and customer production Provider gate remain `PARENT_CLOSURE_BLOCKER` items.

## F — Controlled Automation and AI Governance

### Accepted boundaries

- Proposal construction is typed, bounded and non-executable.
- Fresh governance evaluation covers tenant, operator, permission, authorization, resource identity, state/version, policy, action whitelist, separation of duties, expiry and stale evidence.
- Confirmation is explicit, evidence-bound and non-executable.
- Production Reauthentication is honestly `UNAVAILABLE` and fails closed.
- P4 durable lineage provides hash-only registration, idempotency, row-locked CAS, replay, cancellation, PARTIAL and UNKNOWN semantics.
- PostgreSQL canonicalization matches nearest-microsecond behavior: remainder `<500ns` rounds down, `>=500ns` rounds up, including second carry. Hashing, persistence, time checks and replay compare the same value.
- Action Whitelist remains `EMPTY_PENDING_EXISTING_COMMAND_AUDIT`; Action count is zero.
- P5 remains `P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND`.
- No application command is called, and `Provider -> direct command` remains prohibited.
- No automatic Retry, Rollback, Notification or Retention exists.
- Governance exposes exactly six tenant-scoped `READ`, GET-only, `no-store`, non-mutating endpoints.
- Governance reads do not create Runtime Binding, read raw Secret Material or invoke a Provider.
- One shared server-owned Runtime, Circuit, RateLimiter and Usage Ledger is used; governance does not construct duplicates.
- Incident readiness is read-only evidence plus manual response/rollback review, not an executor.

### Parent closure decision

The empty whitelist, skipped P5, unavailable Production Reauthentication, absence of a qualifying existing Application Command, lack of operated rollback/incident execution and absence of automatic retention are explicit `PARENT_CLOSURE_BLOCKER` items under Issue #82. M6-G must not invent an Action to clear them.

## G — Cross-workstream Authority Audit

Current code and accepted boundaries prohibit:

- `Provider -> Command`;
- AI output becoming approval decision authority;
- Connector to arbitrary command;
- Event to direct command or automatic AI invocation;
- Template to executable code or command;
- Browser/Mobile manufacturing tenant, operator, permission, worker, audit or credential identity;
- AI to Flowable or `ACT_*`;
- AI to process migration;
- AI to permission, Provider, Policy, Secret, deployment or traffic mutation;
- autonomous AI retry or rollback.

No bypass was identified. Cross-workstream authority audit: `PASS`.

## H — Persistence and Migration Audit

- The composed Flyway chain reaches unique V50; V38 is Java-based and V49/V50 are in separate governed locations.
- V49 and V50 each exist exactly once and no V51+ migration exists.
- Clean-install and historical-upgrade integration suites verify the composed path to V50.
- M6-G does not alter historical migrations.
- V49/V50 enforce tenant identity, hashes, state checks, unique/idempotency constraints, append-only event protections, CAS/row-lock behavior and indexes.
- Real PostgreSQL tests cover native timestamps, locks, replay, constraint behavior and nearest-microsecond rounding; acceptance is not H2-only.
- The current main Run selected every Persistence JDBC test class exactly once with no non-abstract omission.

Persistence/migration audit: `PASS`.

## I — Operations and Observability

- Connector diagnostics are bounded/read-only and default-disabled pending production ownership.
- Event operations remain deterministic/reference-only rather than a production delivery console.
- Template operations are preview/create-DRAFT only.
- AI governance snapshot, change plan, control health, usage, history and incident-readiness endpoints are GET-only, tenant-scoped and `no-store`.
- Query windows, headers and instants are bounded/canonical.
- Metrics/evidence avoid tenant/operator/request identifiers as high-cardinality tags and redact Secret/raw payload material.
- Tenant usage does not expose another tenant; global saturation does not expose exact global counters.
- Durable history failure does not perform repair writes.
- Circuit OPEN and HALF_OPEN are not reported as healthy.

Read-only operations correctness: `PASS`. Operated production ownership/rollback/incident automation remains blocked separately.

## J — Web and Mobile Audit

- PC and Mobile use the same advisory status model.
- Neither renders “AI approved” or “confirmation will automatically execute”.
- Assistance requires explicit user interaction and suppresses duplicate in-flight calls.
- UNKNOWN, unavailable, stale and fail-closed outcomes remain visible.
- Controlled-automation confirmation remains disabled/unavailable.
- No UI mutates Provider, Prompt, Policy, Secret, permission, deployment or traffic state.
- Clients do not manufacture trusted tenant/operator/permission/worker identity.
- Reviewed surfaces do not render source TODO/debug comments as product copy.

Web/Mobile audit: `PASS`.

## K — Workflow, Repository and Dependency/Security Audit

- `.github/workflows/approval-platform-validation.yml` remains the sole automatic PR/main workflow.
- No temporary automatic workflow, bypass, broad test skip, empty shard, Artifact reduction, generated payload, patch helper, absolute local path, credential or debug backdoor was identified.
- Workflow permissions remain `contents: read`.
- `workflow_dispatch` exists for diagnosis but is not accepted as PR/main evidence.
- Repository auto-merge is disabled. M6-G has not modified main.
- Final main evidence contains all nine Jobs and four Artifacts.
- Open Dependabot maintenance PRs are `#1`, `#2`, `#3`, `#4`, `#5`, `#6`, `#7`, `#72`, `#73`, `#84`; none is merged into the audited base.
- Those PRs include major/minor upgrades and release-note security fixes, but current-graph vulnerability applicability/reachability has not been independently established.
- Dedicated Code Scanning, Secret Scanning and Dependabot Security Alert inventories are not exposed through the connector used for this audit. Therefore G1 cannot make a zero-alert assertion.

The maintenance backlog itself is `NON_BLOCKING_LIMITATION`. Missing dedicated security-alert inventory and dependency applicability evidence are `PRODUCTION_READINESS_BLOCKER` items. They do not create an unproven code defect classification.

## L — Classification and Gate Decision

The complete classification is in `M6_G_G1_BLOCKER_MATRIX.md`.

Summary:

- accepted authority, isolation, replay, persistence, workflow and UI boundaries: `PASS`;
- explicit accepted non-goals: `NON_BLOCKING_LIMITATION`;
- unresolved promised-behavior defects: none;
- dedicated security inventory/dependency applicability evidence: `PRODUCTION_READINESS_BLOCKER`;
- production Connector/Event/Provider/Retention/Automation/Rollback/Incident and parent closure gates: `PARENT_CLOSURE_BLOCKER`.

```text
UNRESOLVED_CORRECTABLE_DEFECTS=0
M6_G_G1_AUDIT_COMPLETE
M6_PRODUCTION_READINESS_BLOCKED
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
ISSUE_13_REMAINS_OPEN
ISSUE_14_REMAINS_OPEN
NO_NEW_M6_PRODUCT_CAPABILITY
NO_PRODUCTION_PROMOTION
READY_MERGE_ISSUE_CLOSURE_PROHIBITED
AI_IS_NOT_AN_OPERATOR
```

G1 authorizes G2 only after Batch A’s exact Head completes one natural PR Workflow and all nine Jobs/four Artifacts are independently verified. It does not authorize Ready, merge or Issue closure.
