# M6-B G4 Post-Merge Acceptance Correction Evidence

Status: `CORRECTION_IMPLEMENTATION_VALIDATED_PENDING_DOCUMENTED_HEAD_VALIDATION`

Production SDK/event execution: `NOT_AUTHORIZED`

## Purpose

PR #68 completed M6-B Formal Acceptance and was merged with Merge Commit
`eebfad58628f12cb684320b098ae70d81dbc88c9`. An automatic review arrived after
that merge and identified three actionable correctness and security findings on
the accepted SDK Head:

1. nonce replay reservations were removed when `expiry == now`, while the
   verifier still accepted the exact upper clock-skew boundary;
2. the replay identity used delimiter concatenation and could alias distinct
   `(keyReference, nonce)` pairs;
3. JavaScript UTC parsing accepted regex-valid but calendar-invalid dates after
   `Date.parse` normalization.

Issue #64 remains Open. This record covers only the bounded correction in Draft
PR #74 and does not reopen or modify PR #68.

## Correction chain

- accepted M6-B Merge Commit on `main`:
  `eebfad58628f12cb684320b098ae70d81dbc88c9`;
- correction branch:
  `agent/m6-b-g4-acceptance-corrections`;
- TypeScript replay and event-time correction:
  `423905283e33d7c2546767fede84db9708634179`;
- TypeScript compatibility-time correction:
  `bc84276eabb7ccf288e3070b3deb8835eced3734`;
- Java replay correction:
  `84c9864e1fc1231114f8a64cec4643a2ac28bfae`;
- TypeScript correction tests:
  `5351b369ef73564e8eaab8b9cbc94ab16fc5b1ff`;
- Java correction tests and implementation-validation Head:
  `989e8e7744a341b7a83555e40a24ea88af1a25dc`;
- Draft correction PR: #74;
- tracking Issue: #64;
- original accepted PR: #68.

All branch updates were append-only fast-forwards. No rebase, amend, squash,
force push or history rewrite was used.

## Bounded implementation

### Replay boundary

Java and TypeScript now remove a replay reservation only when its expiry is
strictly earlier than the current instant. A reservation remains active at the
exact accepted upper clock-skew boundary, so the first verified request is not
re-verifiable during that boundary second.

### Collision-free replay identity

TypeScript uses a canonical tuple encoding for `(keyReference, nonce)`. Java uses
an immutable `ReplayKey` record. Neither implementation relies on a delimiter
that can appear in either input.

### Calendar-valid UTC parsing

The TypeScript event and compatibility validators now bind the parsed UTC year,
month, day, hour, minute and second back to the supplied components. Values such
as `2025-02-30T00:00:00Z` fail closed instead of being normalized into March.

## Scope audit

The correction changes exactly five implementation/test files before this
record was added:

- `packages/approval-sdk/src/index.ts`;
- `packages/approval-sdk/src/compatibility.ts`;
- `packages/approval-sdk/test/acceptance-corrections.test.mjs`;
- `integrations/host-sdk/src/main/java/io/github/akaryc1b/approval/sdk/v1/SignedWebhookVerifier.java`;
- `integrations/host-sdk/src/test/java/io/github/akaryc1b/approval/sdk/v1/SignedWebhookReplayBoundaryTest.java`.

The implementation correction was ahead five and behind zero relative to
`eebfad58628f12cb684320b098ae70d81dbc88c9`, with 192 additions and 9
deletions. It introduces no new product capability, real endpoint, network
client, usable credential, production configuration, event/subscription
persistence, Outbox ownership, durable aggregation/checkpoint/escalation store,
broker, queue, worker, listener, scheduler, production clock, Flowable command,
approval-state mutation, Flyway migration or workflow.

## Implementation-head permanent validation

The implementation Head naturally triggered Approval Platform Validation Run
`30517518542` / #900.

Result: `success`.

Jobs:

- Repository hygiene: success;
- Java 21 / Maven / PostgreSQL: success;
- Vben TypeScript / production build: success;
- UniApp TypeScript / H5 / WeChat: success.

Test and build evidence:

- Maven aggregate: `1200 / 0 / 0 / 0`;
- host SDK: `75 / 0 / 0 / 0`;
- Java correction tests: `2 / 0 / 0 / 0`;
- TypeScript SDK and permanent boundaries: `88 / 88`;
- TypeScript correction tests: `4 / 4`;
- Maven reactor: `BUILD SUCCESS`;
- Vben type-check and production build: success;
- UniApp type-check, H5 build and WeChat build: success.

The correction tests prove:

- replay rejection at the exact accepted upper clock-skew boundary;
- distinct delimiter-bearing replay identities remain distinct;
- calendar-invalid event timestamps fail closed;
- calendar-invalid support-window and deprecation timestamps fail closed.

## Implementation-head artifacts

Every artifact was downloaded independently. Each downloaded ZIP SHA-256
exactly matched the GitHub artifact digest.

| Artifact | ID | GitHub digest / downloaded ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30517518542` | `8749587918` | `3afccadf8bbece28394c8fd4479089ddab158b7d214a7a5d46f5516ad4acd05c` — exact match |
| `approval-vben-30517518542` | `8749477596` | `b032957b3267ec34ab2cf0f9a1fc2e39c8c5b0962753bcdd6dd127d582880abb` — exact match |
| `approval-mobile-30517518542` | `8749461472` | `c0215ebbc9e35f193e4d332a1da8917d6a15dff97a47e983b4735f6aebf104fb` — exact match |
| `approval-hygiene-30517518542` | `8749449464` | `0b0fdf2325e96984c99de2cf635ba76fa8f18a6479a785b050eacd1f44a615b0` — exact match |

## Parallel and governance boundaries

At the implementation-validation gate:

- PR #69 remained Open + Draft at Head
  `72acb3ba18602c09c28bfe08b58f8b91e6efe6e4`;
- PR #70 remained Open + Draft at Head
  `9d588215e869c8f1332c0bc1a2809fbd235c2efa`;
- PR #74 remained Open + Draft, mergeable, with no requested reviewers or
  unresolved review threads;
- Issue #64 remained Open;
- the correction added no migration and no workflow;
- `.github/workflows/approval-platform-validation.yml` remained the only
  automatic PR/main workflow.

The three original PR #68 review threads remain unresolved as historical review
records. This correction does not silently dismiss or resolve them.

## Documented-head gate

The commit containing this evidence document must trigger a new natural complete
workflow. That Run must:

- execute all four jobs successfully;
- retain Maven aggregate `1200 / 0 / 0 / 0`;
- retain host SDK `75 / 0 / 0 / 0`;
- retain TypeScript SDK and permanent boundaries `88 / 88`;
- execute the Java and TypeScript correction tests successfully;
- produce four downloadable artifacts whose local ZIP SHA-256 values exactly
  match their GitHub digests.

Only the independently validated evidence-document Head may be marked Ready.
Ready must then be held long enough to inspect any newly triggered review. Merge
is authorized only with a Merge Commit and an exact expected Head after all
instantaneous gates are rechecked.

Issue #64 closes only after the resulting `main` Merge Commit completes another
natural permanent validation and all four post-merge artifacts are independently
hash-verified.

## Disposition

```text
M6-B safe slices 1-9:
  FORMALLY_ACCEPTED

Original PR #68 Merge Commit:
  MERGED

Post-merge review findings:
  CORRECTED_IN_PR_74

Correction implementation Head:
  PERMANENTLY_VALIDATED

Correction evidence-document Head:
  VALIDATION_REQUIRED

Production network transport:
  NOT_AUTHORIZED

Durable event delivery:
  NOT_AUTHORIZED

Approval-state mutation:
  NOT_AUTHORIZED
```
