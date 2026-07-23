# M6-B SDK and Event Ecosystem Bootstrap

Status: `SAFE_SLICE_4_IMPLEMENTED`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #64
- Draft PR: #68
- branch: `agent/m6-b-sdk-event-ecosystem`
- target branch: `main`
- verified main baseline: `d769722cf7dd5418739a91ad4c45ca1a1c147502`

## Parallel milestone boundary

M6-B develops in parallel with M5 and remains independent from Issue #56 and Draft PR #58. Public SDK contracts expose neither Flowable internals nor unaccepted M5 migration execution commands. Main drift is incorporated by merge commit only; rebase and force push remain prohibited.

## Safe slices 1–3

The accepted foundation already provides:

- versioned event envelope, canonical JSON, payload hash and signed Webhook verification;
- at-least-once, ordering-key, replay, deduplication and idempotent-consumer semantics;
- Java/TypeScript clients, structured errors, correlation, idempotency and mock transport;
- compatibility, capability, deprecation, sunset and support-window governance;
- bounded transport budgets, deterministic retry decisions, structured response mapping and virtual-time conformance adapters;
- permanent boundaries for Flowable, M5 commands, network, persistence, worker, V33 and workflow count.

## Safe slice 4 — adapter contract and security binding

The fourth safe slice adds pure logical endpoint and server-side security binding without resolving an address or usable credential.

### Logical endpoint descriptor

- binding version `1`; unknown versions fail closed;
- logical endpoint ID, adapter kind and audience;
- explicit supported-operation allowlist;
- reference-only credential provider ID, credential ID and credential kind;
- no URL, URI, host, address, route, header or production endpoint value.

### Server-derived authentication context

- resolver input contains endpoint ID, operation, request ID and trace ID only;
- nominal context is issued server-side;
- tenant, operator, permission-snapshot hash and audit reference are not accepted from an SDK request;
- authenticated-at and expires-at form a bounded active interval;
- expired contexts fail before credential acquisition.

### Credential lease binding

- credential providers issue nominal leases with references and binding evidence only;
- no bearer value, private key, certificate bytes, password or credential header enters the contract;
- endpoint, context, operation, provider, credential ID and kind must match;
- deterministic binding ID is shared by Java and TypeScript;
- issued leases are released with completed, binding-failed or adapter-failed reason;
- expired or mismatched leases fail closed before adapter open.

### Adapter lifecycle

- lifecycle is `created -> open -> closed`;
- exchange is prohibited outside open state;
- every attempt preserves the same request, correlation, endpoint, authentication context and lease identity;
- context/lease expiry before an attempt maps to a terminal unauthorized response;
- scripted adapters use no clock, sleep, scheduler, endpoint lookup or network I/O.

## Tests and permanent validation

The shared `adapter-binding-v1.json` fixture validates identical Java and TypeScript endpoint binding, context/lease identity, lifecycle, release reason, transport result and request preservation. Additional tests cover unsupported versions, duplicate operations, unsupported operations, expired context, expired lease, binding mismatch and private/nominal server-issued evidence.

The root `postinstall` gate executes `pnpm sdk:test`; the Maven reactor executes Java tests. The permanent workflow remains `.github/workflows/approval-platform-validation.yml`.

## Still blocked

- real HTTP or other network transport;
- endpoint address or DNS resolution;
- production authentication execution and usable credential material;
- production clock, scheduler or sleep implementation;
- subscription persistence or delivery tables;
- production event store or Outbox ownership changes;
- production delivery/retry worker;
- SDK migration execution commands before M5 acceptance;
- any Flyway migration, including `V33`;
- a second permanent workflow;
- Ready, auto-merge or merge of PR #68.

## Next gate

The next M6-B gate requires explicit acceptance of logical endpoint and security binding before any real endpoint address, authentication executor or network adapter is introduced. Diagnostic redaction, configuration provenance and audit-event contracts should be accepted before production credential material or network execution.
