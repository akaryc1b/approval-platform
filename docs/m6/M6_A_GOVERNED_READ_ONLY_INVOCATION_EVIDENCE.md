# M6-A-P7 Governed Read-Only Invocation Permanent Evidence

Status: `GOVERNED_READ_ONLY_INVOCATION_IMPLEMENTED_DEFAULT_DISABLED`

Validation status at this document commit: `IMPLEMENTATION_RUN_VALIDATED_PENDING_DOCUMENTED_HEAD_RUN`

Production connector execution: `PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED`

Tracking:

- parent milestone: Issue #62;
- workstream: Issue #63;
- pull request: PR #67;
- branch: `agent/m6-a-connector-foundation`;
- target branch: `main`.

## Verified preflight baseline

Before implementation, the repository was re-read from GitHub rather than trusted from the
handoff alone:

- `main`: `1d425581d0548c6b15487d58ce47774b29f1073a`;
- P6 documented Head: `3a98467084b7dae159e586ae8f870e660464f6a4`;
- relation: ahead `84`, behind `0`;
- PR #67: Open, Draft, unmerged, mergeable, no requested reviewers, submitted reviews or
  unresolved review threads;
- repository auto-merge remained disabled;
- Issues #62, #63, #13 and #14 remained Open;
- PR #68, #69 and #70 Heads matched the frozen handoff values;
- Flyway remained continuous through `V48` with no `V49` or higher migration;
- `.github/workflows/approval-platform-validation.yml` remained the only automatic
  `pull_request` / `push` workflow;
- P6 Run `30414994006` / #880 remained successful and its four artifacts remained
  unexpired with exact digest matches;
- P5 and P6 formal documents remained present, and the P6 lifecycle document blob was
  unchanged from its implementation Head.

No R0 rebaseline was required because `behind = 0`.

## Implementation commit

- commit: `6712964e58dec021cc8729ad8aa64cf473fc7af3`;
- message: `feat(m6-a): add governed read-only invocation coordinator`;
- parent: `3a98467084b7dae159e586ae8f870e660464f6a4`;
- ref update: ordinary fast-forward with `force=false`;
- no rebase, squash, amend, reset, force push or history rewrite.

The implementation adds one new module:

`server-modules/approval-connector-invocation-core`

It composes exact P4 routing and revalidation with P6 Token acquisition and one closed,
Token-bound, read-only dispatch seam. The repository contains no production implementation
of that seam. The Spring gate is literal default disabled and does not auto-create a fake,
real Token endpoint, concrete Secret Backend or production transport adapter.

## Closed safety behavior

The implementation and tests prove:

- only the accepted DingTalk organization-user lookup and DingTalk-user identity-resolution
  matrix is admitted;
- trusted tenant authority is supplied outside the client request DTO;
- Kill Switch evaluation occurs before Token acquisition and again after Token acquisition;
- route and credential binding are revalidated before and after Token acquisition;
- credential-version, Token evidence and route-plan drift fail before transport;
- every pre-dispatch failure keeps transport count at zero;
- a successful path dispatches exactly once;
- timeout, transport exception, null response and indeterminate transport result become
  `UNKNOWN_AFTER_DISPATCH` without automatic retry;
- the P6 Token lease is always closed and the scoped Token copy is zeroized;
- the P5 material lease used by P6 is released;
- result authority is permanently `readOnly = true`,
  `approvalStateMutationAuthorized = false`, and
  `productionExecutionAuthorized = false`;
- no persistence, transaction, Controller mutation, Flowable mutation, Worker, Scheduler,
  listener, queue, retry, recovery or reconciliation implementation is added.

## Natural implementation workflow

Workflow: `Approval Platform Validation`

- Run ID: `30426115014`;
- run number: `#881`;
- Head: `6712964e58dec021cc8729ad8aa64cf473fc7af3`;
- trigger: natural branch push from the implementation commit;
- result: **success**;
- no manual rerun, cancellation, deletion or hiding.

| Job | Job ID | Result |
| --- | ---: | --- |
| Repository hygiene | `90492840985` | success |
| Java 21 / Maven / PostgreSQL | `90492840978` | success |
| Vben TypeScript / production build | `90492840930` | success |
| UniApp TypeScript / H5 / WeChat | `90492840931` | success |

## Maven verification

- aggregate: `1032 / 0 / 0 / 0`;
- P7 coordinator: `32 / 0 / 0 / 0`;
- P7 Spring fail-closed gate: `4 / 0 / 0 / 0`;
- P7 architecture boundaries: `3 / 0 / 0 / 0`;
- P7 focused total: `39 / 0 / 0 / 0`;
- architecture module: `79 / 0 / 0 / 0`;
- Server: `107 / 0 / 0 / 0`;
- reactor: `BUILD SUCCESS`.

## Implementation artifacts and exact SHA-256

Each ZIP was downloaded from GitHub Actions and hashed locally. Every local ZIP SHA-256
exactly matched the GitHub artifact digest.

| Artifact | ID | GitHub digest / downloaded ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30426115014` | `8713806802` | `ca96a5d4ca9e03d1185a4ea776825d4998925c7ba32f15b763ce3f62839c46c5` — exact match |
| `approval-vben-30426115014` | `8713685914` | `ad25859b59eaadfa032d9c4ba454274db0a3303fa5088b3e648292dceaf3c33b` — exact match |
| `approval-mobile-30426115014` | `8713680974` | `94100d7775f288fbaf396e06c5376036a4e5c79a8e376c82cd52c4e0b436d7e8` — exact match |
| `approval-hygiene-30426115014` | `8713658560` | `af64d11c7d8dd4940005b654682558d58f11ff489331bd3107784eccebf4652f` — exact match |

All four artifacts were unexpired when downloaded.

## Secret and authority review

The P7 tests inspect evidence JSON and `toString()` output and reject raw tenant,
credential-reference, application-secret, Token, Authorization, Bearer and Cookie fixture
material. Targeted scans of the four implementation artifacts found no P7 synthetic
Secret value, Token value, route credential reference, application credential reference,
Bearer value or DingTalk access-token header value.

The repository already contains unrelated legacy management and mobile verification logs
that print pre-existing synthetic tenant fixtures. P7 did not create those log statements,
and this evidence therefore does not make a false repository-wide zero-match claim. P7's
own evidence contract remains hash-only and its focused leakage tests passed.

## Permanent architecture boundaries

The natural workflow passed executable architecture checks proving:

- Flyway highest version remains `V48`;
- no `V49` or higher migration exists;
- only one automatic PR/main workflow exists;
- the invocation core has no JDBC, transaction, persistence, Flowable or Web dependency;
- production invocation source contains no mutation Controller, Worker, Scheduler,
  automatic retry, replay, recovery or reconciliation path;
- no arbitrary endpoint, host or URL enters the P7 request/dispatch contract.

## Documented Head gate

This file is intentionally committed separately after the implementation Run and artifact
verification. P7 is not permanently validated until the commit containing this document
receives a second natural, complete successful workflow and all four documented-Head ZIPs
are downloaded and verified against their GitHub digests.

The final documented-Head Run, artifact IDs and hashes are appended by the immutable commit
state itself and recorded in the PR #67 and Issue #63 formal status comments after that Run
succeeds. No P8 work begins before that gate closes.

## Retained blockers

The following remain explicitly blocked:

- concrete production Secret Backend selection and implementation;
- real DingTalk Token endpoint adapter;
- production Token-bound dispatch adapter;
- production AppKey, AppSecret, Access Token or customer endpoint;
- production credential provisioning and egress ownership;
- distributed Token lifecycle;
- durable invocation audit or persistence;
- unknown-after-dispatch reconciliation;
- Worker, Scheduler, background refresh, automatic retry or Provider fallback;
- Approval-State Mutation;
- production connector execution authorization;
- PR Ready, auto-merge, merge or Issue closure.

`PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED`
