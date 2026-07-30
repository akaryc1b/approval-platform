# M6-B Final Acceptance Evidence

Status: `FORMAL_ACCEPTANCE_EVIDENCE_RECORDED_PENDING_DOCUMENTED_HEAD_VALIDATION`

Production SDK/event execution: `NOT_AUTHORIZED`

## Accepted record chain

- post-M6-A `main`: `ebe7cb1ef92cb835810146f3120bd23ea94c586a`;
- normal rebaseline merge commit:
  `c68f3482dea6116a12c5a0e601f288531ee7e05d`;
- bounded compatibility fix:
  `47db2fc86fa2e2fa2067f6f1e427265cdb7dde7b`;
- R0 evidence commit:
  `fd14b66d41acf8e3b0370b0ac32e73ad3c1d1217`;
- Formal Acceptance commit:
  `19b42a65d36061eff5960c36f0e1707e53967395`;
- PR: #68;
- Issue: #64;
- parent milestone: #62.

The Formal Acceptance decision is bounded to the deterministic Java/TypeScript
SDK and event ecosystem safe slices 1-9 described in
`docs/m6/M6_B_FINAL_ACCEPTANCE.md`. It does not authorize a real network,
production credentials, durable delivery or reconciliation persistence, a broker,
worker, scheduler, approval mutation, production enablement or an M6-B-owned
migration.

## Formal Acceptance implementation-head validation

The Formal Acceptance commit naturally triggered Approval Platform Validation Run
`30515081900` / #897 at Head
`19b42a65d36061eff5960c36f0e1707e53967395`.

Result: `success`.

Jobs:

- Repository hygiene: success;
- Java 21 / Maven / PostgreSQL: success;
- Vben TypeScript / production build: success;
- UniApp TypeScript / H5 / WeChat: success.

Test and build evidence:

- Maven aggregate: `1198 / 0 / 0 / 0`;
- host SDK: `73 / 0 / 0 / 0`;
- TypeScript SDK and permanent boundary suite: `84 / 84`;
- Maven reactor: `BUILD SUCCESS`;
- Vben type-check and production build: success;
- UniApp type-check, H5 build and WeChat build: success.

## Formal Acceptance implementation-head artifacts

Every artifact was downloaded independently. Each downloaded ZIP SHA-256 exactly
matched the GitHub artifact digest.

| Artifact | ID | GitHub digest / downloaded ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30515081900` | `8748678074` | `3f6b8b8026bb8c7f26ce6d222838ffdd107235dfd9d9047f913d67be03e02778` — exact match |
| `approval-vben-30515081900` | `8748572405` | `e57b1a7eec69a5dec84037086e46342d64fab96af6d5fccdd9d95441cc59c101` — exact match |
| `approval-mobile-30515081900` | `8748558637` | `040a78e5c48d87f200e62171cb26f9ada309903b77082a351bb1660708b2bde1` — exact match |
| `approval-hygiene-30515081900` | `8748546065` | `c23f9f11e074c1ce0a3d6f137ea63dcd1d4fbb4f3d7a89aefb6304c4ca2a4098` — exact match |

## Final scope and boundary audit

Before this evidence record was created:

- current `main`: `ebe7cb1ef92cb835810146f3120bd23ea94c586a`;
- Formal Acceptance Head:
  `19b42a65d36061eff5960c36f0e1707e53967395`;
- relation to current `main`: ahead `24`, behind `0`;
- exact net changed files: `113`;
- PR #68: Open + Draft, unmerged and mergeable;
- requested reviewers, reviews and unresolved review threads: none;
- auto-merge: disabled;
- Issues #62, #64, #13 and #14: Open;
- PR #69 Head: `72acb3ba18602c09c28bfe08b58f8b91e6efe6e4`;
- PR #70 Head: `9d588215e869c8f1332c0bc1a2809fbd235c2efa`;
- Flyway: `V48`, no `V49` or higher;
- only automatic PR/main workflow:
  `.github/workflows/approval-platform-validation.yml`.

The exact current-main-to-Head diff remains limited to the accepted M6-B
SDK/event contracts, fixtures, deterministic implementations, tests, examples,
documentation and root SDK test wiring. It adds no migration, real endpoint,
network client, usable credential, persistent subscription, event store, Outbox,
checkpoint/escalation/reconciliation store, broker, queue, worker, scheduler,
production clock, Flowable authority or approval mutation.

## Documented-head gate

The commit containing this evidence document must trigger another natural complete
workflow. That later Run must:

- execute all four jobs successfully;
- retain Maven aggregate `1198 / 0 / 0 / 0`;
- retain host SDK `73 / 0 / 0 / 0`;
- retain TypeScript SDK and boundary suite `84 / 84`;
- produce four downloadable artifacts whose local SHA-256 values exactly match
  their GitHub digests.

Only the evidence-document Head, not the preceding Formal Acceptance Head, may be
used for the final Ready and Merge Commit gate. The gate must be re-evaluated
immediately before Ready and again immediately before merge.

## Acceptance disposition

```text
M6-B safe slices 1-9:
  FORMALLY_ACCEPTED

Formal Acceptance implementation Head:
  PERMANENTLY_VALIDATED

Evidence-document Head:
  VALIDATION_REQUIRED

Production network transport:
  NOT_AUTHORIZED

Durable event delivery:
  NOT_AUTHORIZED

Approval-state mutation:
  NOT_AUTHORIZED
```
