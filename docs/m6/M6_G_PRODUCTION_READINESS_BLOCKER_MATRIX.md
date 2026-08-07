# M6-G Production Readiness Blocker Matrix

Status: `M6_PRODUCTION_READINESS_BLOCKED`

Exact accepted G2 Head: `007c973eeffdc07c94ee46602afb8827be2dc231`

Exact base: `main@0cf6572770953a46fe5b16d15ecdff78cf607855`

Tracking Issue: `#82`

Parent Issue: `#62`

Parallel database blocker: `#91` / Draft PR `#92`

This matrix is a permanent decision record. It does not authorize implementation of a missing product or operated-production capability inside M6-G.

## Classification rules

- `PASS`: accepted behavior is complete for its declared scope and has permanent evidence.
- `NON_BLOCKING_LIMITATION`: an explicit accepted non-goal that does not falsify the declared scope.
- `PRODUCTION_READINESS_BLOCKER`: production evidence, ownership or security evidence is insufficient.
- `PARENT_CLOSURE_BLOCKER`: Issue #62 cannot close while the item remains unresolved.
- `CORRECTABLE_DEFECT`: a defect in already-promised behavior; current count is zero.

## Blocker matrix

| ID | Domain | Exact current state | Classification | M6-G disposition | Parent/Issue decision |
| --- | --- | --- | --- | --- | --- |
| `PRB-01` | Connector production ownership | M6-A B01–B20 remain blocked, including Secret backend, customer endpoint/egress, durable audit, on-call, incident, capacity, retention, security/change approval, kill switch, tenant allowlist, rotation, rate, legal/privacy, release, observability and DR gates. | `PARENT_CLOSURE_BLOCKER` | Record only; no Connector capability is added. | `#82 OPEN`, `#62 OPEN` |
| `PRB-02` | SDK/Event production runtime | Versioned SDK/Event contracts pass, but no durable production subscription, event store, Outbox delivery, broker, Worker, listener, automatic recovery or operated delivery audit exists. | `PARENT_CLOSURE_BLOCKER` | Do not create an Event runtime in M6-G. | `#82 OPEN`, `#62 OPEN` |
| `PRB-03` | Customer Provider gate | A real OpenAI Responses adapter exists and is default-disabled, but customer production authorization, environment/egress/on-call approval and production rehearsal are not proved. | `PARENT_CLOSURE_BLOCKER` | No Provider promotion or customer call. | `#82 OPEN`, `#62 OPEN` |
| `PRB-04` | Retention execution | V49/V50 expose durable retention posture and history, but no operated tombstone executor exists. | `PARENT_CLOSURE_BLOCKER` | Do not add automatic Retention. | `#82 OPEN`, `#62 OPEN` |
| `PRB-05` | Action Whitelist | Exact state is `EMPTY_PENDING_EXISTING_COMMAND_AUDIT`; Action count is zero. | `PARENT_CLOSURE_BLOCKER` | Never invent a demonstration Action. | `#82 OPEN`, `#62 OPEN` |
| `PRB-06` | P5 execution | Exact decision is `P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND`. | `PARENT_CLOSURE_BLOCKER` | P5 remains skipped. | `#82 OPEN`, `#62 OPEN` |
| `PRB-07` | Production Reauthentication | Production Reauthentication is `UNAVAILABLE` and Confirmation fails closed. | `PARENT_CLOSURE_BLOCKER` | No executable Confirmation. | `#82 OPEN`, `#62 OPEN` |
| `PRB-08` | Qualifying Application Command | No pre-existing valuable low-risk command satisfies permission, resource authorization, expected state/version, idempotency, audit, UNKNOWN/compensation, separation-of-duties and reauthentication gates. | `PARENT_CLOSURE_BLOCKER` | No command binding is added. | `#82 OPEN`, `#62 OPEN` |
| `PRB-09` | Rollback execution | Read-only Rollback plans and manual rehearsals exist; no operated Rollback executor exists. | `PARENT_CLOSURE_BLOCKER` | Preserve non-executable review. | `#82 OPEN`, `#62 OPEN` |
| `PRB-10` | Incident response execution | Fault/rehearsal evidence exists, but operated Notification, response, Retention and release controls are incomplete. | `PARENT_CLOSURE_BLOCKER` | No automatic Notification or response executor. | `#82 OPEN`, `#62 OPEN` |
| `PRB-11` | Actual Provider billing | Cost envelopes exist, but actual Provider billing and reconciliation do not. | `PARENT_CLOSURE_BLOCKER` | No billing authority is added. | `#82 OPEN`, `#62 OPEN` |
| `PRB-12` | Durable cost history | Process-local upper-bound usage exists; durable cost history does not. | `PARENT_CLOSURE_BLOCKER` | Record limitation only. | `#82 OPEN`, `#62 OPEN` |
| `PRB-13` | Durable Circuit/Control timeline | Current process-local state is readable; no durable Circuit or Control Health time-series exists. | `PARENT_CLOSURE_BLOCKER` | No new time-series product. | `#82 OPEN`, `#62 OPEN` |
| `PRB-14` | Canary/rollout/traffic mutation | Change plans are non-executable; no Canary, rollout, deployment or traffic mutation exists. | `PARENT_CLOSURE_BLOCKER` | No Production Promotion. | `#82 OPEN`, `#62 OPEN` |
| `PRB-15` | MySQL 8.4 production compatibility | Issue #91 and Draft PR #92 explicitly block #82/#62. Current main is PostgreSQL-only accepted; production-equivalent MySQL migration/JDBC/Flowable/concurrency/fault/operations/CI evidence is incomplete. | `PARENT_CLOSURE_BLOCKER` | Keep parallel and Draft; do not merge or claim it in M6-G. | `#91 OPEN`, `#82 OPEN`, `#62 OPEN` |
| `PRB-16` | Dedicated GitHub alert inventory | The available connector does not expose Code Scanning, Secret Scanning or Dependabot Security Alert inventories. | `PRODUCTION_READINESS_BLOCKER` | No unsupported zero-alert claim. | `#82 OPEN`, `#62 OPEN` |
| `PRB-17` | Dependency applicability | Open Dependabot maintenance PRs exist, but current-graph CVE applicability/reachability is not independently proved. | `PRODUCTION_READINESS_BLOCKER` | Dedicated dependency/security review remains required. | `#82 OPEN`, `#62 OPEN` |
| `PRB-18` | Automatic Retry/fallback | Automatic Retry/fallback is intentionally absent; UNKNOWN remains manual and fail-closed. | `NON_BLOCKING_LIMITATION` | Absence is not treated as a defect and must not be “fixed” in M6-G. | No authority expansion |
| `PRB-19` | Template marketplace/remote components | Marketplace, remote package loading, remote registry and dynamic components are explicit non-goals. | `NON_BLOCKING_LIMITATION` | Do not add them in M6-G. | No authority expansion |
| `PRB-20` | Concurrent explicit AI requests | Independent explicit requests are bounded separately rather than by a distributed pre-dispatch exactly-once reservation. | `NON_BLOCKING_LIMITATION` | Existing at-most-one-per-request semantics remain accepted. | No Queue/Worker |
| `PRB-21` | Accepted authority boundaries | Tenant isolation, no Provider-to-command path, no client-manufactured trusted authority, hash-only evidence, V50 upgrade, PostgreSQL replay/rounding, GET-only governance and explicit advisory UI all pass. | `PASS` | Freeze permanently. | M6-G acceptance may pass |

## Counts

| Classification | Count |
| --- | ---: |
| `PASS` | `1` |
| `NON_BLOCKING_LIMITATION` | `3` |
| `CORRECTABLE_DEFECT` | `0` |
| `PRODUCTION_READINESS_BLOCKER` | `2` |
| `PARENT_CLOSURE_BLOCKER` | `15` |

## Closure consequences

- M6-G Formal Acceptance may pass because the audit, scenarios, failures, corrections and limitations are factual and no Correctable Defect remains.
- M6 is not Production Ready.
- Issue #82 must remain Open after merge because Production Readiness is blocked.
- Parent Issue #62 must remain Open.
- Issue #91 and Draft PR #92 remain Open.
- Issues #13 and #14 remain Open.
- M6-G may not create follow-up product capability, start M7, deploy or promote traffic.

```text
M6_G_ACCEPTANCE_PASSED
M6_PRODUCTION_READINESS_BLOCKED
ISSUE_82_REMAINS_OPEN
ISSUE_62_REMAINS_OPEN
ISSUE_91_REMAINS_OPEN
ISSUE_13_REMAINS_OPEN
ISSUE_14_REMAINS_OPEN
NO_NEW_M6_PRODUCT_CAPABILITY
NO_PRODUCTION_PROMOTION
AI_IS_NOT_AN_OPERATOR
```
