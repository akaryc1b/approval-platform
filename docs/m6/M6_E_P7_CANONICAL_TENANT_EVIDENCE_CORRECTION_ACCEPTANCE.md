# M6-E P7 Canonical Tenant Evidence Correction Acceptance

## Decision

This append-only record accepts the final correction discovered while closing the second Ready Review of PR #83.

The correction does not add a Provider, model, Prompt, endpoint, Secret source, retry, fallback, Queue, Worker, Scheduler, automation proposal, executable action, approval command or Flowable mutation authority. M6-F remains gated.

## Exact implementation evidence

- repository: `akaryc1b/approval-platform`
- branch: `agent/m6-e-governed-ai-approval-assistance`
- implementation Head: `3d14e299fe1a40da1b59d1e32a5512150dd41673`
- current synchronized `main`: `b20b5cca68bb6b77e7a51233bc2aee3387b21993`
- compare at acceptance: ahead `245`, behind `0`
- PR #83: Open, Draft and mergeable
- natural workflow Run: `30908055123` / #1220
- workflow conclusion: success
- automatic workflow count: one
- highest migration: unique `V49`
- `V50+`: absent

## Review findings retained

The second Ready Review remains the source of two actionable findings:

1. every trusted AI tenant carrier must accept the platform maximum of 128 characters and reject 129;
2. the P5 GET contract must accurately expose validated production runtime availability while remaining zero-egress.

The production and client corrections for both findings remain present. Their Review threads remain unresolved until the documented Head receives its own exact successful permanent workflow and artifact verification.

## Correction evidence chain

The failed workflow runs are retained as auditable evidence and were not rerun:

| Run | Exact role | Result | Root cause |
| --- | --- | --- | --- |
| `30906751840` / #1213 | first second-Review candidate | failure | two older static client guards did not admit the new runtime-availability condition |
| `30907139097` / #1215 | guard-corrected candidate | failure | `AiProviderRequest` record component names had been unintentionally changed |
| `30907301380` / #1216 | first accessor correction | failure | the remaining `allowedFields()` contract accessor was still absent |
| `30907683323` / #1217 | restored request-contract candidate | failure | production runtime admission used a non-canonical tenant hash incompatible with credential evidence |
| `30908055123` / #1220 | canonical tenant evidence implementation | success | all physical jobs and aggregate evidence succeeded |

No failed run was rewritten or promoted as acceptance evidence.

## Restored Provider request contract

`AiProviderRequest` is restored to its accepted component and invariant contract:

- `allowedFields` remains the public record component and accessor;
- `inputFields` remains the public record component and accessor;
- input fields remain bounded at 200;
- input field keys remain unique;
- every input key must remain in `allowedFields`;
- existing resource and field bounds and error semantics remain unchanged;
- only the two tenant bounds in `AuthorizedContext` and `AuthorizedResource` advance from 120 to 128.

The correction therefore preserves compatibility instead of introducing a new request shape.

## Canonical tenant evidence

The production runtime factory previously calculated admission evidence as `sha256(tenantId)`, while `CredentialMaterialRequest.tenantHash()` uses the frozen credential canonical form `sha256("tenant\n" + tenantId)`.

`OpenAiResponsesSecureHttpSender` correctly rejected that mismatch before network or Secret access. The fix keeps that fail-closed comparison and changes only the runtime factory to use the established credential canonical form:

```text
sha256("tenant\n" + normalizedTenantId)
```

The exact runtime test now proves:

- a 128-character tenant binds successfully;
- the returned binding hash equals the canonical credential tenant hash;
- the same tenant replays the cached binding;
- a 129-character tenant is rejected;
- no network or live Provider request is required by CI.

## Runtime-aware zero-egress GET

The accepted read contract remains:

- `AVAILABLE` / `AI_ASSISTANCE_AVAILABLE` when the validated runtime factory exists;
- `PROVIDER_NOT_CONFIGURED` / `AI_ASSISTANCE_PROVIDER_REQUIRED` when it does not;
- GET does not bind a tenant runtime;
- GET does not read Secret material;
- GET does not invoke the Provider or network;
- GET does not claim that an advisory result already exists;
- PC and Mobile allow generation only after an explicit user click and only while availability is `AVAILABLE`;
- in-flight duplicate generation remains suppressed.

## Exact successful workflow

Run `30908055123` / #1220 completed successfully at implementation Head `3d14e299fe1a40da1b59d1e32a5512150dd41673`.

Successful physical jobs:

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

- Maven core: `1263 / 0 / 0 / 0`;
- Persistence JDBC: `295 / 0 / 0 / 0`;
- Maven aggregate: `1558 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `160 / 160`;
- OpenAI: `59 / 59`;
- approval-application: `233 / 233`;
- approval-architecture-tests: `139 / 139`;
- approval-server: `173 / 173`;
- selected JDBC test classes: `73`;
- duplicate shard assignments: `0`;
- M6 transport/P7 permanent boundary: `56 / 56`;
- Web and Mobile: success.

## Exact artifacts

Every artifact was downloaded and independently SHA-256 verified:

| Artifact | ID | Size | SHA-256 |
| --- | ---: | ---: | --- |
| Maven | `8891941448` | `328048` | `71e319d9f0b523650586d7993193f09ffecf9ca35bbd41a0f9fbd97926feeeff` |
| Vben | `8891918010` | `18845` | `0634639dbe7b15d666bf61245983fd8a139b87caf9d2e53b315ae760e3801ca1` |
| Mobile | `8891899093` | `9792` | `67dc857003e17d236019506c9794860d81aa4bd3e2d448f0bc4791da93a59604` |
| Hygiene | `8891871139` | `11188` | `6abab67ef480b63585ab5e82a4df541cc1e1a25cc83728fb90e5c95def4ab1b3` |

## Authority and release boundary

- AI remains advisory and unverified;
- human review remains mandatory;
- AI is never an operator;
- no approve, reject, return, transfer, withdraw, terminate, migrate, publish or activate command is introduced;
- no second Provider, retry, fallback, redirect following, streaming or previous-response state is introduced;
- no Queue, Worker, Scheduler, listener, polling or autonomous continuation is introduced;
- no new migration or workflow is introduced;
- CI performs no live paid/customer Provider request;
- PR #83 remains Draft until the documented Head succeeds and both actionable threads are evidence-replied and resolved;
- ordinary Merge Commit remains the only permitted merge method;
- post-main permanent validation and four exact artifacts remain mandatory before Issue #80 can close.

`P7_CANONICAL_TENANT_EVIDENCE_CORRECTION_IMPLEMENTATION_ACCEPTED`

`DOCUMENTED_HEAD_PERMANENT_VALIDATION_REQUIRED`

`SECOND_READY_REVIEW_THREADS_PENDING_EVIDENCE_REPLY`

`PR_REMAINS_DRAFT`

`M6_F_REMAINS_GATED`

`AI_IS_NOT_AN_OPERATOR`
