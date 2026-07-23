# M6-B SDK and Event Ecosystem Bootstrap

Status: `SAFE_SLICE_5_IMPLEMENTED`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #64
- Draft PR: #68
- branch: `agent/m6-b-sdk-event-ecosystem`
- target branch: `main`
- verified main baseline: `d769722cf7dd5418739a91ad4c45ca1a1c147502`

## Parallel milestone boundary

M6-B develops in parallel with M5 and remains independent from Issue #56 and Draft PR #58. Public SDK contracts expose neither Flowable internals nor unaccepted M5 migration execution commands. Main drift is incorporated by merge commit only; rebase and force push remain prohibited.

## Safe slice 1 — event and SDK foundation

- versioned event envelope and canonical cross-language JSON;
- payload hashes and deterministic signed Webhook fixture;
- at-least-once, ordering, replay, deduplication and idempotency semantics;
- Java/TypeScript typed clients, structured errors and mock transports.

## Safe slice 2 — compatibility governance

- manifest and semantic-version validation;
- minimum-client and support-window enforcement;
- event-schema, Webhook-protocol and capability negotiation;
- deprecation, sunset and replacement governance.

## Safe slice 3 — transport policy abstraction

- bounded request budget and attempt limit;
- deterministic virtual-time timeout/backoff decisions;
- retry safety tied to idempotency;
- structured response mapping and scripted adapter conformance.

## Safe slice 4 — adapter contract and security binding

- logical endpoint descriptors without addresses;
- server-issued authentication context;
- reference-only short-lived credential leases;
- deterministic adapter lifecycle and binding identity preservation.

## Safe slice 5 — diagnostic redaction, provenance and adapter audit

### Fake configuration source

- diagnostics/audit contract version `1`; unknown versions fail closed;
- only deterministic source kind `fixture` is implemented;
- duplicate configuration keys are rejected;
- public and sensitive entries are explicitly classified;
- no environment, file, vault, secret-manager or network reads exist.

### Configuration provenance

- source ID, revision and explicit load time;
- public/sensitive key-name inventories;
- SHA-256 value digests and canonical content digest;
- sensitive values return only deterministic reference identities;
- raw sensitive values are absent from `ResolvedConfiguration` outputs.

### Diagnostic redaction

- sensitive literals are removed from messages and context values;
- authorization, token, password, secret, private-key, certificate and credential-material keys are fully redacted;
- exception diagnostics emit stable exception type and generic message only;
- raw exception messages and stack traces are never returned;
- a final invariant check rejects leaked registered literals.

### Adapter audit contract

- event, endpoint, operation, request, trace, binding and authentication-context references;
- outcome, reason code, explicit occurrence time and provenance digest;
- no tenant, operator, permission snapshot, raw audit reference, credential lease or secret material;
- deterministic `InMemoryAdapterAuditSink` only, with no production persistence.

## Tests and permanent validation

Java and TypeScript use `diagnostics-audit-v1.json`, whose deliberately sensitive fixture literals contain `DO-NOT-LEAK`. Both implementations must produce the same provenance digest, sensitive references, redacted diagnostic and audit event while proving those literals never appear in output.

The existing root `postinstall` gate executes `pnpm sdk:test`; the Maven reactor executes Java tests. The permanent workflow remains `.github/workflows/approval-platform-validation.yml`.

## Still blocked

- production environment/file/vault configuration source;
- production diagnostic logger or audit persistence;
- real HTTP or other network transport;
- production endpoint address, discovery and routing;
- production authentication executor or usable credential material;
- production clock, scheduler or sleep implementation;
- subscription persistence or delivery tables;
- production event store or Outbox ownership changes;
- production delivery/retry worker;
- SDK migration execution commands before M5 acceptance;
- any Flyway migration, including `V33`;
- a second permanent workflow;
- Ready, auto-merge or merge of PR #68.

## Next gate

The next M6-B gate requires explicit acceptance of diagnostic redaction, configuration provenance and adapter audit contracts before introducing any production configuration source, logger or audit persistence. Real endpoint addresses, credentials and network execution remain separately blocked.
