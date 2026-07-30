# M6-B Formal Acceptance

## Decision

M6-B is formally accepted at the implemented, deterministic, fixture-backed and
non-production boundary recorded in this document.

- status: `M6_B_FORMALLY_ACCEPTED_PENDING_FINAL_EVIDENCE`;
- production network transport: `PRODUCTION_NETWORK_TRANSPORT_NOT_AUTHORIZED`;
- usable credential material: `USABLE_CREDENTIAL_MATERIAL_NOT_AUTHORIZED`;
- durable event delivery: `DURABLE_EVENT_DELIVERY_NOT_AUTHORIZED`;
- approval-state mutation: `APPROVAL_STATE_MUTATION_NOT_AUTHORIZED`;
- production enablement: `NOT_GRANTED`.

Formal Acceptance confirms that safe slices 1-9 are complete and eligible for the
final permanent validation and conditional merge-readiness process. It is not a
runtime authority token, production configuration, delivery authorization or
permission to mutate an approval process.

Tracking:

- parent milestone: Issue #62;
- workstream: Issue #64;
- pull request: PR #68;
- branch: `agent/m6-b-sdk-event-ecosystem`;
- target: `main`.

## Audited baseline before this record

The repository was re-read from GitHub before this document was created:

- `main`: `ebe7cb1ef92cb835810146f3120bd23ea94c586a`;
- rebaselined implementation Head: `47db2fc86fa2e2fa2067f6f1e427265cdb7dde7b`;
- validated R0 evidence Head: `fd14b66d41acf8e3b0370b0ac32e73ad3c1d1217`;
- relation to `main`: ahead `23`, behind `0`;
- exact net M6-B changed files: `112`;
- PR #68: Open, Draft, unmerged and mergeable;
- requested reviewers, submitted reviews and unresolved review threads: none;
- repository auto-merge: disabled;
- Issues #62, #64, #13 and #14: Open;
- PR #69 Head: `72acb3ba18602c09c28bfe08b58f8b91e6efe6e4`;
- PR #70 Head: `9d588215e869c8f1332c0bc1a2809fbd235c2efa`;
- Flyway: accepted mainline history continuous through `V48`, with no `V49` or
  higher migration;
- only automatic PR/main workflow:
  `.github/workflows/approval-platform-validation.yml`.

The M6-A merge and the accepted M5 history appear in the PR ancestry. Exact
current-main-to-Head comparison, rather than the historical PR file counter, was
used for scope attribution. The net diff contains only M6-B SDK/event contracts,
fixtures, implementations, tests, examples, documentation and root SDK test
wiring. M5 and M6-A files are inherited unchanged from `main`.

## Formally accepted scope

The accepted M6-B scope consists of these nine safe slices:

1. versioned Java and TypeScript SDK/event foundations, canonical JSON, signed
   Webhook fixtures, at-least-once delivery semantics, replay, deduplication and
   idempotency models;
2. compatibility manifests, semantic-version validation, minimum-client and
   support-window enforcement, capability negotiation, deprecation and sunset
   governance;
3. bounded deterministic transport policy using virtual time, scripted adapters,
   structured response mapping and retry safety tied to idempotency;
4. logical endpoint descriptors, server-issued authentication context,
   reference-only short-lived credential leases and deterministic adapter binding;
5. fake configuration provenance, diagnostic redaction, generic exception
   rendering and reference-only in-memory adapter audit;
6. deterministic diagnostic emission, caller-ordinal deduplication, complete
   started/attempt/terminal audit proof and atomic fake audit batches;
7. reference-only telemetry signals with exact allowlists, deterministic fake
   export and no-loss audit handoff acknowledgement;
8. caller-ordinal aggregation windows, deterministic rollover snapshots and
   reference-only handoff reconciliation with acknowledged-only finalization;
9. complete aggregate export checkpoints, monotonic checkpoint chains,
   deterministic reconciliation escalation and acknowledged-confirmed-only final
   resolution checkpoints.

The implementations are exercisable only through deterministic fixtures, mock or
scripted transports, caller-supplied ordinals and bounded process-local stores.

## Compatibility and cross-language acceptance

Java and TypeScript consume the same versioned fixtures and agree on canonical
identities and digests for event envelopes, signatures, compatibility decisions,
transport traces, adapter binding, diagnostics, audit completeness, telemetry,
handoff acknowledgement, aggregation snapshots, reconciliation proofs, export
checkpoints and escalation/finalization evidence.

Unknown protocol or policy versions fail closed. Unsupported capabilities,
minimum-client violations, sunset policy violations, malformed evidence,
duplicate/conflicting replay, ordinal regression, partial export, broken checkpoint
continuity and unacknowledged finalization are rejected deterministically.

## Security and authority boundary

The reviewed public client requests cannot manufacture trusted tenant, operator,
permission, authority, audit or credential-lease evidence. SDK source exposes no
Flowable `RuntimeService`, `TaskService`, `ProcessMigrationService`, `ACT_*` table
or M5 execute/force/rollback command.

Endpoint descriptors remain logical and contain no URL, URI, host, address,
discovery or route authority. Credential objects are reference-only and contain no
Secret, password, private key, header value, bearer token or credential material.
Diagnostics, telemetry, checkpoints and reconciliation evidence are similarly
reference-only and reject trusted identity or sensitive material.

## Determinism and failure safety

The accepted scope uses no real clock, sleep, scheduled executor, background
listener or polling loop. Capacity exhaustion, scripted store failure, duplicate
replay and conflicting evidence fail closed without partial append, acknowledgement,
checkpoint, finalization or state replacement.

A retry decision can be represented only inside the deterministic policy model and
requires both an idempotent policy and idempotency key. No production retry worker,
network dispatch or uncertain-outcome recovery loop is present.

## Explicitly not authorized or not implemented

Formal Acceptance does not authorize or provide:

- real HTTP or other network transport;
- production endpoint address, DNS, discovery, routing or connection policy;
- production authentication execution or usable credential material;
- environment, file, Vault, KMS, Kubernetes, cloud, database or HTTP configuration
  source;
- production diagnostic logger, OpenTelemetry exporter or metrics backend;
- durable subscription, event store, Outbox, delivery, deduplication, aggregation,
  checkpoint, escalation, reconciliation or audit persistence;
- message broker, audit queue, worker, scheduler, listener, polling loop or
  production clock;
- production delivery, automatic retry, replay, recovery or reconciliation;
- approval-state mutation or approve, reject, return, transfer, withdraw, terminate
  or migrate commands;
- customer production configuration or production enablement;
- an M6-B-owned Flyway migration;
- another automatic workflow.

## Permanent validation lineage

The pre-rebaseline safe-slice implementation lineage remains retained, including
Run `30090513276` / #529 at Head
`330dbdd035e436459ffdedf0d2b0c8e07dac7e6c`. It is historical evidence only and
was not reused as post-M6-A acceptance evidence.

The authoritative rebaseline evidence is
`docs/m6/M6_B_POST_M6_A_REBASELINE_EVIDENCE.md`:

- retained failed merge-head Run: `30513600301` / #894;
- bounded compatibility fix: `47db2fc86fa2e2fa2067f6f1e427265cdb7dde7b`;
- successful post-M6-A implementation Run: `30514030381` / #895;
- Maven aggregate: `1198 / 0 / 0 / 0`;
- host SDK: `73 / 0 / 0 / 0`;
- TypeScript SDK and boundary suite: `84 / 84`;
- all four successful-Run artifacts: downloaded and exact SHA-256 matches.

The R0 evidence document itself was permanently validated at Head
`fd14b66d41acf8e3b0370b0ac32e73ad3c1d1217` by Run `30514552987` / #896:

- all four jobs: success;
- Maven aggregate: `1198 / 0 / 0 / 0`;
- host SDK: `73 / 0 / 0 / 0`;
- TypeScript SDK and boundary suite: `84 / 84`;
- `approval-maven-30514552987`, artifact `8748492725`, SHA-256
  `a08ce7e9df847220d95704086b5e15e28a848b0a1d72fdbadb46f5f3617376ba`;
- `approval-vben-30514552987`, artifact `8748381946`, SHA-256
  `7d5ee02c0fe8b60d9ffd640cdce87e9ba5df0318f9ed540b2dbc8866f14aa4ba`;
- `approval-mobile-30514552987`, artifact `8748367151`, SHA-256
  `90af2b542211d7bd143b2782e7d7ebda880f920badb73996d5d4e8fddd48bcfe`;
- `approval-hygiene-30514552987`, artifact `8748354570`, SHA-256
  `b083cec5b94a37ea5665628a26d1737e61e41656400ba3cba56b74387ceb3469`;
- all four downloaded ZIP hashes exactly matched GitHub artifact digests.

No failed Run was cancelled, deleted, hidden or directly rerun. The replacement
validation was produced naturally by a new minimal commit.

## Repository and parallel-workstream boundaries

- M6-B adds no migration relative to current `main` and owns no M5 or M6-A
  migration, runtime-binding or connector semantics;
- `.github/workflows/approval-platform-validation.yml` remains the single automatic
  PR/main validation workflow;
- PR #69 and PR #70 remain independent Open + Draft workstreams and their Heads
  must not be changed by M6-B acceptance;
- Issues #62, #13 and #14 remain Open;
- Issue #64 may close only after Merge Commit completion and successful `main`
  post-merge permanent validation.

## Final evidence and merge-readiness gate

The commit containing this document and the rebaseline evidence must complete a new
natural full workflow. Its four artifacts must be downloaded and matched exactly.
A separate `M6_B_FINAL_ACCEPTANCE_EVIDENCE.md` commit must then complete another
natural full workflow with another four exact artifact matches.

Only that documented Head may be considered for Ready and Merge Commit, and only
while `main` remains unchanged, behind remains zero, mergeability and checks remain
successful, reviews contain no blocker, auto-merge remains disabled, V48/no-V49 and
the single-workflow boundary remain intact, and PR #69/#70 Heads remain unchanged.

`FORMAL_ACCEPTANCE_DOES_NOT_EQUAL_PRODUCTION_ENABLEMENT`

`PRODUCTION_NETWORK_TRANSPORT_NOT_AUTHORIZED`

`DURABLE_EVENT_DELIVERY_NOT_AUTHORIZED`

`APPROVAL_STATE_MUTATION_NOT_AUTHORIZED`
