# M6-E P7 Final Ready Review Correction Validation Acceptance

## Decision

This append-only record accepts the implementation of the final Ready Review correction for PR #83 at exact implementation Head `1ad630949fb92e9126f9303f3a7457036c9364f8`.

The implementation is accepted only as a validated correction candidate. PR #83 remains Draft and the two final Ready Review threads remain unresolved until the documented Head created after this record completes its own natural permanent workflow and four exact artifacts are independently verified.

## Exact baseline

- repository: `akaryc1b/approval-platform`
- branch: `agent/m6-e-governed-ai-approval-assistance`
- implementation Head: `1ad630949fb92e9126f9303f3a7457036c9364f8`
- synchronized `main`: `b20b5cca68bb6b77e7a51233bc2aee3387b21993`
- compare at implementation validation: ahead `259`, behind `0`
- PR #83: Open, Draft and mergeable
- natural workflow Run: `30911672094` / #1231
- workflow conclusion: success
- workflow attempt: 1
- only automatic PR/main workflow: `.github/workflows/approval-platform-validation.yml`
- highest migration: unique `V49`
- `V50+`: absent

## Review findings corrected

The correction addresses the two actionable findings in Review `PRR_kwDOTbeZ188AAAABIVludg`:

1. strict Responses decoding must admit the selected GPT-5 profile's default `text.verbosity=medium` field without accepting arbitrary response drift;
2. a pending approval task must be revalidated after the bounded external Provider attempt and before P4 evidence construction or storage.

The associated threads remain open pending documented-Head evidence:

- `PRRT_kwDOTbeZ186WUnxy` — exact default response verbosity;
- `PRRT_kwDOTbeZ186WUnx4` — post-Provider pending-task revalidation.

## Exact decoder behavior

The decoder retains a closed schema:

- the `text` object permits only `format` and `verbosity`;
- `verbosity` may be absent for compatible responses and deterministic fixtures;
- when present, `verbosity` must be a textual value exactly equal to `medium`;
- `low`, `high`, non-textual values and all other values fail closed as `SCHEMA_MISMATCH`;
- unknown siblings continue to fail as `UNKNOWN_PROPERTY`;
- all model, version, structured-schema, tool, storage, conversation, usage and request-ID checks remain unchanged.

No request option, model, endpoint, Provider, streaming mode or response-state capability is introduced.

## Exact post-Provider state boundary

The server-owned generation service now performs exactly three pending-task reads:

1. initial trusted pending-task lookup;
2. pre-Provider exact snapshot equality revalidation;
3. post-Provider exact snapshot equality revalidation.

The third read is strictly ordered after the only `orchestrator.execute(request)` call and before P4 durable evidence construction and the only evidence-store call.

If the task is no longer pending or any `PendingTaskDetails` field differs from the initial snapshot, the service returns `STALE_TASK`, performs no evidence write and never retries the Provider.

## Regression evidence

Permanent Java tests prove:

- exact `text.verbosity=medium` decodes successfully;
- an omitted verbosity remains compatible;
- `low`, `high` and non-textual verbosity fail closed;
- a changed task after Provider execution returns `STALE_TASK`;
- a missing pending task after Provider execution returns `STALE_TASK`;
- both stale cases perform one Provider attempt and zero evidence writes;
- unchanged conflict and store-unavailable cases perform three task reads, one Provider attempt and one store attempt;
- no evidence failure causes a second Provider attempt.

Permanent repository boundaries prove:

- exactly three task queries, one Provider execution and one evidence store;
- execution precedes post-Provider validation;
- post-Provider validation precedes evidence construction and storage;
- unique V49 remains the highest migration;
- only one automatic workflow remains;
- no autonomous or approval-command authority is introduced.

## Exact successful workflow

Run `30911672094` / #1231 completed successfully at exact implementation Head `1ad630949fb92e9126f9303f3a7457036c9364f8`.

Successful jobs:

- Java 21 / Maven core;
- Persistence JDBC shard 0;
- Persistence JDBC shard 1;
- Persistence JDBC shard 2;
- Persistence JDBC shard 3;
- Java 21 / Maven / PostgreSQL aggregation;
- Vben TypeScript / production build;
- UniApp TypeScript / H5 / WeChat;
- Repository hygiene.

Independent result reconstruction:

- Maven core: `1266 / 0 / 0 / 0`;
- Persistence JDBC: `295 / 0 / 0 / 0`;
- Maven aggregate: `1561 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `160 / 160`;
- OpenAI: `60 / 60`;
- approval-application: `233 / 233`;
- approval-architecture-tests: `139 / 139`;
- approval-server: `175 / 175`;
- selected JDBC test classes: `73`;
- duplicate shard assignments: `0`;
- M6 transport/P7 permanent boundary: `67 / 67`;
- Web and Mobile: success.

## Exact artifacts

Every artifact was downloaded and independently SHA-256 verified:

| Artifact | ID | Size | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `8893426431` | `327644` | `3bf8294de6a26da0b7dfe56156bcacdd4c25f64a5d7074860b316654945b9a70` |
| Vben | `8893397669` | `18869` | `523c637c0e81d28ab6195f2ee86568edde10867112facfdaf17ec75fb7d91959` |
| Mobile | `8893374179` | `9788` | `63dda182be0ed4570a7697dc006a9e170e14c34a71ef042eada68e375a6d8280` |
| Hygiene | `8893343071` | `11594` | `75f95deb38785acb8a2eb3b3e534fba4e4f501c847167d6aa01399bbe4bbb2a4` |

## Authority and release boundary

- AI remains advisory, unverified and subject to mandatory human review;
- AI is never an operator;
- no approve, reject, return, transfer, withdraw, terminate, migrate, publish or activate command is introduced;
- no direct Flowable or approval mutation path is introduced;
- no second Provider, retry, fallback, redirect following, streaming or previous-response state is introduced;
- no Queue, Worker, Scheduler, listener, polling or autonomous continuation is introduced;
- no automation proposal or executable action is introduced;
- no migration or second automatic workflow is introduced;
- CI performs no live paid or customer Provider request;
- M6-F remains gated.

## Remaining gate

Before replying to or resolving the two final Ready Review threads:

1. create a permanent boundary for this exact validation record;
2. load it through the existing transport-review aggregator;
3. require a natural workflow on the resulting documented Head;
4. independently verify all four documented-Head artifacts;
5. recheck exact `main`, behind zero, mergeability, Reviews, threads, reactions and Issue states.

Only after those gates may the threads receive evidence replies and be resolved. Ready and Merge Commit remain conditional on a new complete real-time gate.

`P7_FINAL_READY_REVIEW_CORRECTION_IMPLEMENTATION_ACCEPTED`

`DOCUMENTED_HEAD_PERMANENT_VALIDATION_REQUIRED`

`FINAL_READY_REVIEW_THREADS_PENDING_EVIDENCE_REPLY`

`PR_REMAINS_DRAFT`

`M6_F_REMAINS_GATED`

`AI_IS_NOT_AN_OPERATOR`
