# M6-A Connector Foundation Bootstrap

Status: `BOOTSTRAP_ONLY`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #63
- branch: `agent/m6-a-connector-foundation`
- target branch: `main`
- starting main: `906a4fcf784f22a329102a423fe9b4ab0ba1bdc4`

## Parallel milestone boundary

M6-A is allowed to develop in parallel with M5. It is independent from Issue #56 and Draft PR #58. This branch must not use the M5 branch as a base, copy M5 migration implementation, or commit connector work to PR #58.

## First safe slice

The first implementation slice may add:

- provider-neutral connector capabilities and descriptors;
- application ports and typed request/result/error contracts;
- signing, nonce, timestamp and replay-defense contracts;
- rate-limit, idempotency, retry and dead-letter classifications;
- deterministic mock adapters and contract fixtures;
- provider evidence and low-cardinality observability boundaries;
- unit, compatibility and permanent boundary tests.

## Blocked until a later gate

- real DingTalk or Feishu network calls;
- production credentials, tokens or customer endpoints;
- connector-owned persistence or Flyway migrations;
- any `V33` migration;
- direct connector mutation of approval process state;
- a connector worker that has not passed external-call and recovery review;
- browser-supplied trusted provider, tenant, operator or signing identity;
- a second permanent GitHub Actions workflow.

## Shared-core coordination

Changes to authorization, audit, idempotency, Outbox, error contracts or observability infrastructure require explicit merge-order coordination with active M5 work. Later `main` changes are incorporated with merge commits only; no rebase or force push.

## Bootstrap acceptance

This bootstrap commit creates scope and safety boundaries only. It introduces no production connector capability, database change, workflow change or M5 modification.