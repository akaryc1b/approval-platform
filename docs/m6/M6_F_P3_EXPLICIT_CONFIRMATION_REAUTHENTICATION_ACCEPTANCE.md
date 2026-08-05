# M6-F P3 — Explicit Human Confirmation and Reauthentication Boundary

Status: `P3_ACCEPTED_NON_EXECUTING / REAUTHENTICATION_UNAVAILABLE`

Permanent conclusion: `AI_IS_NOT_AN_OPERATOR`

## 1. P3 prerequisite state

- Pull Request #88 remains Open + Draft;
- Action Whitelist remains `EMPTY_PENDING_EXISTING_COMMAND_AUDIT`;
- P5-A remains `P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND`;
- P1 Proposal authority remains `NON_EXECUTABLE_PROPOSAL`;
- P2 evaluation authority remains `READ_ONLY_NON_EXECUTING_PREVIEW`;
- highest Flyway migration at the P3 final Head remains `V49`;
- P3 adds no migration.

P3 establishes an explicit human-confirmation contract, but intentionally provides no production
command admission. The repository contains no reusable command-bound step-up authentication
implementation, so the production-safe verifier is `UNAVAILABLE` and blocks confirmation.

## 2. Reauthentication contract

`ControlledAutomationReauthenticationVerifier` is a server-owned boundary that accepts only:

- authenticated server request context;
- the exact non-executable Proposal;
- one exact short-lived reauthentication challenge.

The challenge binds its ID, Proposal/evaluation hash, closed method classification, issuance time
and expiry. Verification can be only `ACCEPTED`, `EXPIRED`, `FAILED` or `UNAVAILABLE`.

The contract contains no password value, TOTP value, session credential, bearer token, Secret,
permission token or reusable confirmation token. The production-safe implementation returns
`UNAVAILABLE` and no evidence.

## 3. Explicit human intent boundary

`ControlledAutomationConfirmationService` accepts only `EXPLICIT_CLICK`.

Page load, keyboard Enter, timer/countdown, retry and Tab change fail before reauthentication and
confirmation-ID allocation. The service also rejects Proposal/evaluation binding mismatch,
tenant/operator mismatch, inactive or expired Proposal, non-eligible evaluation, whitelist drift,
challenge mismatch, expired challenge, unavailable/failed reauthentication and timestamps outside
the exact challenge/evaluation window.

## 4. Non-executable confirmation evidence

A test-only accepted verifier can create short-lived hash-only evidence containing confirmation and
Proposal IDs, tenant/operator/source/resource evidence hashes, canonical Action type, typed parameter
hash, whitelist/policy versions, evaluation and reauthentication evidence, challenge ID, timestamps,
`singleUseRequired = true`, `authority = NON_EXECUTABLE_CONFIRMATION`,
`commandAdmitted = false` and an immutable evidence hash.

P3 does not persist or consume this evidence. P4 must provide durable single-use, CAS and replay
protection before any future admission path can exist. Confirmation success is not command success.
No application command service is referenced or called.

## 5. PC and Mobile safety presentation

PC and Mobile display the same controlled-automation boundary:

- AI advisory is not a system decision;
- `AI_IS_NOT_AN_OPERATOR`;
- current Action is `NOT_AUTHORIZED`;
- target resource and typed parameters are absent;
- whitelist is `EMPTY_PENDING_EXISTING_COMMAND_AUDIT`;
- authorization preview is `ACTION_NOT_WHITELISTED`;
- reauthentication is `UNAVAILABLE`;
- confirmation is visibly disabled;
- page load, refresh, Tab change, Enter, timer and retry never confirm or execute;
- future confirmation would still require final fresh server validation;
- confirmation success would still not equal command success.

The panels expose no confirmation API call and no click, Enter, submit, timer or retry handler.

## 6. Architecture and attack boundaries

Unit, architecture and permanent Node tests prove unavailable reauthentication blocks confirmation
without ID allocation, non-click intents never call the verifier, forged identity and binding drift
fail closed, accepted test evidence remains non-executable, no credential or command payload exists,
and no Provider, connector, Flowable, persistence, HTTP, Queue, Worker, Scheduler or command
dependency is introduced.

## 7. Final permanent validation

Exact final P3 Head:

`9e56071b27d47de7be821345290ac74ed485648a`

Permanent workflow Run:

- Run ID: `30976346987`
- conclusion: `success`
- all nine physical jobs: `success`

Final artifacts:

| Group | Artifact ID | Size | SHA-256 |
|---|---:|---:|---|
| Maven | `8918431554` | `328264` | `e8d8297d19a1406dfce192e5fbe5db67902bccc1ad6ccf136ad582a02395f500` |
| Vben | `8918416672` | `18904` | `123fd7ba77cf77828788de7e6f9430d9bbaf1c518a2297b4114cba440b91dbf2` |
| Mobile | `8918402981` | `9807` | `e4db8def13e636c333c4d42dae219d0ec85bbbc61cdd8e1bf2691917b468c873` |
| Hygiene | `8918388838` | `13136` | `a9ab55690a6ce3f179d720dbd740c36364c8b25b887c5ccb3ec157160f9d09b1` |

P3 acceptance is complete. The failed predecessor Run `30975999159` remains visible and records the
Checkstyle-only comparator formatting failure; it does not represent a product-logic regression.

## 8. Explicit blockers and exclusions

P3 does not implement production password/MFA/host step-up, challenge persistence, confirmation
persistence, Proposal lifecycle mutation, command admission/execution, idempotency, CAS, replay,
concurrency persistence, partial/UNKNOWN handling, Provider/connector/Flowable execution, P5-A,
P6/P7/P8, M6-G, Ready, Merge or Issue closure.

The exact blocker remains `REAUTHENTICATION_UNAVAILABLE`.

P4 may add durable Proposal/confirmation lineage, CAS, idempotency, replay, concurrency and UNKNOWN
semantics. It must not enable a business command while the whitelist remains empty and
reauthentication remains unavailable.

`AI_IS_NOT_AN_OPERATOR`
