# M6-A Production Integration Gate

Status: `OWNERSHIP_GATE_IMPLEMENTED_NO_PRODUCTION_ENABLEMENT`

Decision date: `2026-07-24`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #63
- pull request: PR #67
- branch: `agent/m6-a-connector-foundation`
- target branch: `main`
- verified main baseline: `d769722cf7dd5418739a91ad4c45ca1a1c147502`
- formally accepted foundation head: `3bb9f28e3c72d29413bb494b814cd601ad7da3c7`
- accepted validation run: `30060608341` / run #483
- accepted Maven artifact digest:
  `ba46bfb1ac6b4c516af0f3aa2e3617bedf4c8c5c357306cbf8dcf96c99fa6f41`

## Gate purpose

The M6-A Connector Foundation contract scope is formally accepted. This next isolated stage
defines production-integration ownership and the explicit gates that must exist before any
production behavior is implemented.

This stage does not implement or enable transport, credentials, tenant routing, persistence,
provider execution, retry, recovery, observability export or approval actions.

`READY_FOR_SCOPED_IMPLEMENTATION_REVIEW` means only that every production capability has one
closed owner and one explicit decision. It is not production enablement and is not permission to
invoke a Provider.

PR #67 remains Open + Draft after this gate.

## Existing platform boundaries retained

The repository already contains production-shaped integration infrastructure established before
M6-A:

- `approval-integration-core` owns the existing Inbox, Outbox, idempotency, lease and delivery-state
  contracts;
- `approval-integration-jdbc` owns the existing JDBC persistence for those integration contracts;
- `approval-connector-generic` owns the existing signed Generic REST business-callback transport.

M6-A does not duplicate or reassign these responsibilities. A later provider-specific adapter may
integrate with shared platform capabilities only through a separately reviewed gate.

The existing Generic REST callback is not treated as an implicit DingTalk, Feishu or other Provider
adapter. It does not authorize a new Provider endpoint, credential source, routing policy or worker.

## Production capability ownership matrix

| Capability | Owner | Decision | Current meaning |
| --- | --- | --- | --- |
| Provider transport | connector adapter | `FUTURE_EXPLICIT_GATE` | adapter modules may be proposed later; no network implementation now |
| Credential resolution | platform security | `SHARED_COORDINATION_REQUIRED` | server-owned secret references and resolver lifetime require security review |
| Tenant routing | platform application | `SHARED_COORDINATION_REQUIRED` | tenant/provider configuration ownership is unresolved and remains server-only |
| Execution coordination | platform application | `FUTURE_EXPLICIT_GATE` | any single-attempt coordinator requires a later explicit gate |
| Idempotency | platform integration core | `EXISTING_PLATFORM_BOUNDARY` | reuse existing platform semantics; do not create connector-local authority |
| Persistence | platform integration core | `SHARED_COORDINATION_REQUIRED` | no connector tables or schema ownership are opened |
| Retry policy | platform integration core | `SHARED_COORDINATION_REQUIRED` | existing callback retry does not automatically apply to all Provider operations |
| Reconciliation and recovery | platform application | `FUTURE_EXPLICIT_GATE` | uncertain outcomes remain non-retriable without explicit reconciliation |
| Authorization integration | platform security | `SHARED_COORDINATION_REQUIRED` | connector-local evidence does not replace shared authorization |
| Audit integration | platform audit | `SHARED_COORDINATION_REQUIRED` | no connector-owned audit store or queue is introduced |
| Observability | platform operations | `SHARED_COORDINATION_REQUIRED` | only bounded evidence exists; no exporter or production endpoint is added |
| Approval-state actions | no runtime owner | `BLOCKED` | connectors cannot approve, reject, transfer, withdraw, terminate or migrate |

## Deterministic ownership evidence

`DeterministicConnectorProductionIntegrationGateEvaluator` validates:

- the exact formally accepted foundation anchor;
- the ownership policy version;
- complete and unique coverage of every closed production capability;
- the exact owner, decision and rationale for every capability;
- permanent blocking of approval-state actions.

The evaluator emits sorted deterministic evidence and a SHA-256 evidence hash.

The evidence always reports:

- `productionEnabled() == false`;
- `providerTransportEnabled() == false`;
- `credentialResolutionEnabled() == false`;
- `tenantRoutingEnabled() == false`;
- `persistenceEnabled() == false`;
- `providerExecutionEnabled() == false`;
- `automaticRetryEnabled() == false`;
- `recoveryWorkerEnabled() == false`;
- `schemaChangeAllowed() == false`;
- `approvalStateMutationAllowed() == false`;
- `requiresExplicitCapabilityGate() == true`.

No ownership entry is an endpoint, secret, credential, worker lease, execution token or retry
authorization.

## Explicitly absent

- no real Provider network call;
- no DingTalk or Feishu adapter;
- no production endpoint, customer domain or private address;
- no production credentials, tokens, secrets, secret-store or key-management integration;
- no connector-owned persistence;
- no `V33` or other Flyway migration;
- no connector worker, lease, scheduler or recovery process;
- no provider execution coordinator;
- no automatic execution;
- no automatic retry;
- no health-based routing, implicit fallback, weighted routing or load balancing;
- no audit queue, telemetry exporter or observability endpoint;
- no browser or mobile execution control;
- no direct Flowable access;
- no approval process-state mutation;
- no modification of M5 source or semantics;
- no additional automatic PR/main workflow;
- no PR Ready, auto-merge or merge action.

## Validation scope

The permanent contract tests cover:

- accepted baseline ownership;
- deterministic evidence independent of input order;
- exact formal-acceptance anchor binding;
- fail-closed anchor and policy mismatch;
- duplicate and incomplete capability matrices;
- prohibited approval-state capability opening;
- owner, decision and rationale conflicts;
- immutable ownership evidence;
- existing integration-core ownership;
- future explicit transport, execution and recovery gates;
- shared coordination for credentials, authorization, audit and observability;
- permanent false production-enablement methods.

Permanent architecture tests prove that the evaluator:

- cannot execute an adapter;
- cannot resolve credentials;
- cannot access network or JDBC infrastructure;
- cannot schedule work;
- cannot expose server-owned gate evidence to Web or Mobile clients;
- preserves this document and the existing workflow boundary.

## Next explicit gate

This ownership gate permits only a later scoped implementation review.

A future implementation slice must select exactly one capability, define its module and platform
owner, and preserve all other capability decisions. Opening Provider transport does not
automatically open credentials, persistence, execution, retry, recovery, routing, authorization,
audit, observability or approval actions.

Until a separate explicit authorization is given, all production behavior remains blocked.
