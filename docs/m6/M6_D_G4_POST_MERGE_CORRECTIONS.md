# M6-D G4 Post-Merge Review Corrections

Status: `DRAFT_CORRECTION_IN_PROGRESS`

## Baseline

- repository: `akaryc1b/approval-platform`
- source Pull Request: #70
- source documented Head: `8a62d3c8037ad5720e30b6918153750dd591c6e5`
- M6-D Merge Commit / correction base: `21c086e57bc5814d8083076550d9fda71adabb4a`
- natural `push -> main` Run: `30542735901`
- correction branch: `agent/m6-d-g4-post-merge-review-corrections`
- tracked Issue: #66

Run `30542735901` is bound to branch `main` and Head `21c086e57bc5814d8083076550d9fda71adabb4a`. Java/Maven, Vben, UniApp and Repository hygiene all completed successfully. Maven aggregate is `1389 / 0 / 0 / 0`. The four main artifact ZIP SHA-256 values independently match the GitHub digests.

## Actionable post-merge findings

Codex submitted five unresolved findings after PR #70 merged:

1. **Circuit generation safety (P1):** a stale successful completion from a CLOSED permit must not close a circuit opened by another concurrent completion.
2. **Deployment protocol capability comparison (P1):** capability and provider metadata changes for an existing protocol-profile key must produce deployment change evidence and require human review.
3. **Activation review hash verification (P2):** the canonical `AiProviderActivationReviewBundle` constructor must recompute and reject a mismatched `bundleHash`.
4. **Audit hash framing (P2):** audit identity and request hash inputs must use unambiguous length framing rather than a delimiter accepted inside values.
5. **Provider nesting limits (P2):** route preflight must enforce provider `maximumCollectionSize` and `maximumDepth` in addition to character limits.

Each finding is treated as actionable. Each correction must remain bounded, append-only and covered by focused regression tests.

## Permanent scope boundary

This correction introduces no:

- real Provider adapter or HTTP client;
- DNS, TLS or network egress;
- runtime Secret material or signature calculation;
- production credential, Prompt or customer knowledge;
- attachment extraction, RAG, embeddings or vector database;
- AI persistence, durable state, Outbox, Queue, Worker or Scheduler;
- participant or management AI endpoint;
- AI-driven approval decision or process command;
- executable activation or transport acceptance;
- Flyway migration;
- second automatic workflow;
- M6-E or M6-F capability.

## Closure gates

The correction cannot merge until:

- all five findings are fixed and their review threads are answered and resolved;
- the exact correction Head has a complete permanent workflow success;
- all four correction artifact ZIPs independently match GitHub SHA-256 digests;
- Maven aggregate and M6-D focused counts are recalculated;
- the PR passes Ready and final merge instantaneous gates;
- final integration uses a Merge Commit with exact expected Head.

Issue #66 remains Open after correction-PR merge until the natural correction `push -> main` Run, four main artifacts, final Maven/focused evidence and final Review closure are complete.
