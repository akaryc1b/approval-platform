# M6-B SDK and Event Ecosystem Bootstrap

Status: `BOOTSTRAP_ONLY`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #64
- branch: `agent/m6-b-sdk-event-ecosystem`
- target branch: `main`
- starting main: `906a4fcf784f22a329102a423fe9b4ab0ba1bdc4`

## Parallel milestone boundary

M6-B is allowed to develop in parallel with M5 and remains independent from Issue #56 and Draft PR #58. Public SDK contracts must not expose Flowable internals or unaccepted M5 migration execution commands.

## First safe slice

The first implementation slice may add:

- versioned public API and event contracts;
- Java and TypeScript SDK abstractions;
- structured errors, correlation and bounded idempotency helpers;
- signed Webhook envelope and verification helpers;
- event ordering, deduplication and replay contracts;
- mock transports and deterministic contract fixtures;
- semantic-version, deprecation and support-window policy;
- credential-free host integration examples;
- unit, compatibility and permanent boundary tests.

## Blocked until a later gate

- subscription persistence or delivery tables;
- any `V33` migration;
- production event delivery worker;
- production secrets, tokens or customer endpoints;
- SDK commands for migration execution before M5 acceptance;
- direct exposure of Flowable APIs or `ACT_*` data;
- client-manufactured tenant, operator, authority or audit evidence;
- a second permanent GitHub Actions workflow.

## Shared-core coordination

Changes to shared REST contracts, authorization, audit, idempotency, Outbox or error infrastructure require explicit merge-order coordination. Later `main` changes are incorporated with merge commits only; no rebase or force push.

## Bootstrap acceptance

This bootstrap commit creates scope and safety boundaries only. It introduces no production SDK transport, event worker, database change, workflow change or M5 modification.