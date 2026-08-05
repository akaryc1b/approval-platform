# M6-F P3 — Explicit Human Confirmation and Reauthentication Boundary

Status: `P3_IMPLEMENTED_NON_EXECUTING / REAUTHENTICATION_UNAVAILABLE / FINAL_HEAD_VALIDATION_REQUIRED`

Permanent conclusion: `AI_IS_NOT_AN_OPERATOR`

## 1. P3 prerequisite state

- Pull Request #88 remains Open + Draft;
- Action Whitelist remains `EMPTY_PENDING_EXISTING_COMMAND_AUDIT`;
- P5-A remains `P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND`;
- P1 Proposal authority remains `NON_EXECUTABLE_PROPOSAL`;
- P2 evaluation authority remains `READ_ONLY_NON_EXECUTING_PREVIEW`;
- highest Flyway migration remains `V49`;
- P3 adds no migration.

P3 establishes an explicit human-confirmation contract, but intentionally provides no production command admission. The repository contains no reusable command-bound step-up authentication implementation, so the production-safe verifier is `UNAVAILABLE` and blocks confirmation.

## 2. Reauthentication contract

`ControlledAutomationReauthenticationVerifier` is a server-owned boundary that accepts only:

- authenticated server request context;
- the exact non-executable Proposal;
- one exact short-lived reauthentication challenge.

The challenge binds:

- challenge ID;
- Proposal and evaluation binding hash;
- closed method classification;
- issuance time;
- expiry.

The verification result can be only:

- `ACCEPTED` with hash-only evidence and verification time;
- `EXPIRED`;
- `FAILED`;
- `UNAVAILABLE`.

The contract contains no password value, TOTP value, session credential, bearer token, Secret, permission token or reusable confirmation token. The default production-safe implementation returns `UNAVAILABLE` and no evidence.

## 3. Explicit human intent boundary

`ControlledAutomationConfirmationService` accepts only `EXPLICIT_CLICK`.

The following intents fail before reauthentication and confirmation-ID allocation:

- page load;
- keyboard Enter;
- timer or countdown;
- retry;
- Tab change.

The service also rejects:

- Proposal/evaluation binding mismatch;
- tenant or operator mismatch;
- inactive or expired Proposal;
- non-eligible or executing evaluation;
- whitelist/evaluation drift;
- challenge binding mismatch;
- expired challenge;
- unavailable, expired or failed reauthentication;
- verification timestamps outside the exact challenge/evaluation window.

## 4. Non-executable confirmation evidence

A test-only accepted verifier can create a short-lived evidence record containing:

- confirmation ID;
- Proposal ID;
- tenant and operator evidence hashes;
- source advisory evidence hash;
- canonical Action type;
- typed parameter hash;
- target resource evidence hash;
- whitelist version;
- policy version;
- evaluation evidence hash;
- reauthentication evidence hash and challenge ID;
- confirmation and expiry timestamps;
- `singleUseRequired = true`;
- `authority = NON_EXECUTABLE_CONFIRMATION`;
- `commandAdmitted = false`;
- immutable confirmation evidence hash.

P3 does not persist or consume this evidence. P4 must provide durable single-use, CAS and replay protection before any future admission path can exist.

Confirmation success is not command success. No application command service is referenced or called.

## 5. PC and Mobile safety presentation

PC and Mobile now display the same controlled-automation boundary alongside the M6-E advisory area:

- AI advisory is not a system decision;
- `AI_IS_NOT_AN_OPERATOR`;
- current Action is `NOT_AUTHORIZED`;
- target resource and typed parameters are absent;
- risk is unavailable;
- complete side-effect summary states that no notification, external call or business-state change occurs;
- expected state/version and expiry are not applicable;
- whitelist is `EMPTY_PENDING_EXISTING_COMMAND_AUDIT`;
- authorization preview is `ACTION_NOT_WHITELISTED`;
- reauthentication is `UNAVAILABLE`;
- confirmation control is visibly disabled;
- page load, refresh, Tab change, Enter, timer and retry never confirm or execute;
- future confirmation would still require final fresh server validation;
- confirmation success would still not equal command success.

The panels expose no confirmation API call and no click, Enter, submit, timer or retry handler.

## 6. Architecture and attack boundaries

Unit, architecture and permanent Node tests prove:

- current unavailable reauthentication blocks confirmation without allocating an ID;
- non-click intents never call the verifier;
- forged operator/tenant and binding changes fail closed;
- expired challenges and failed verification fail closed;
- test-only accepted verification produces only non-executable evidence;
- confirmation evidence carries no credential or command payload;
- no Provider, connector, Flowable, persistence, HTTP, Queue, Worker, Scheduler or command dependency exists;
- PC/Mobile components are integrated and remain disabled with identical semantics.

## 7. Explicit blockers and exclusions

P3 does not implement:

- a production password/MFA/host step-up adapter;
- challenge persistence or one-time consumption;
- confirmation persistence;
- Proposal lifecycle mutation;
- command admission or execution;
- idempotency, CAS, replay or concurrency persistence;
- partial/UNKNOWN result handling;
- Provider, connector, Flowable or arbitrary HTTP/SQL/script execution;
- P5-A, P6, P7, P8, M6-G, Ready, Merge or Issue closure.

The exact blocker remains:

`REAUTHENTICATION_UNAVAILABLE`

P4 may add durable Proposal/confirmation lineage, CAS, idempotency, replay, concurrency and UNKNOWN semantics. It must not enable a business command while the whitelist remains empty and reauthentication remains unavailable.

`AI_IS_NOT_AN_OPERATOR`
