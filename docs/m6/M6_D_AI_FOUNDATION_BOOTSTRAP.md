# M6-D AI Foundation Bootstrap

Status: `BOOTSTRAP_ONLY`

Tracking:

- parent milestone: Issue #62
- workstream: Issue #66
- branch: `agent/m6-d-ai-foundation`
- target branch: `main`
- starting main: `906a4fcf784f22a329102a423fe9b4ab0ba1bdc4`

## Parallel milestone boundary

M6-D is allowed to develop in parallel with M5 and remains independent from Issue #56 and Draft PR #58. AI output is advisory and cannot become a trusted approval or migration command.

## First safe slice

The first implementation slice may add:

- AI Provider SPI and capability descriptors;
- model, prompt, knowledge-source and policy version contracts;
- structured advisory output with provenance, confidence and limitations;
- field-permission enforcement, masking and data-minimization utilities;
- tenant-isolation and provider-input boundary contracts;
- timeout, disable, circuit-breaker and safe fallback semantics;
- deterministic mock provider and evaluation fixtures;
- prompt-injection, sensitive-data leakage and unauthorized-retrieval tests;
- audit contracts linking provider configuration, output and human decision;
- low-cardinality cost, latency and outcome metrics.

## Blocked until a later gate

- real AI provider network calls;
- production provider credentials, private prompts or customer knowledge data;
- any `V33` migration;
- AI approval, rejection, transfer, withdrawal, termination or migration actions;
- AI-manufactured tenant, operator, authority, audit, worker, lease or engine identity;
- representing AI output as verified fact or authoritative decision;
- changes to M5 migration source or PR #58;
- a second permanent GitHub Actions workflow.

## Shared-core coordination

Changes to field permissions, authorization, audit, structured errors, observability or runtime command handling require explicit merge-order coordination. Later `main` changes are incorporated with merge commits only; no rebase or force push.

## Bootstrap acceptance

This bootstrap commit creates scope and safety boundaries only. It introduces no production provider, approval automation, persistence, workflow change or M5 modification.