# M6-F P2 — Fresh Governance Evaluation Acceptance

Status: `P2_IMPLEMENTED / FINAL_HEAD_VALIDATION_REQUIRED`

Permanent conclusion: `AI_IS_NOT_AN_OPERATOR`

## 1. P2 prerequisite state

- M6-F branch: `agent/m6-f-controlled-automation-and-ai-governance`
- Pull Request: #88 — Open + Draft only
- P0 Action Whitelist: `EMPTY_PENDING_EXISTING_COMMAND_AUDIT`
- P1 authority: `NON_EXECUTABLE_PROPOSAL`
- P5-A remains skipped and prohibited
- highest migration entering P2: `V49`
- P2 adds no migration

P2 implements only a read-only Eligibility and Authorization Preview. It cannot execute or admit any business command.

## 2. Fresh snapshot contract

`ControlledAutomationGovernanceSnapshotSource` requires every evaluation to load a fresh server-owned snapshot containing:

- current roles evidence hash;
- current authorization evidence hash;
- current permission decision;
- current resource-level authorization decision;
- current target resource identity, state and version evidence;
- current policy version and evidence hash;
- current policy decision;
- current separation-of-duties decision;
- current feature-gate state;
- current kill-switch state and generation;
- current command-precondition decision;
- current source M6-E evidence presence, identity, hash and integrity decision;
- current Proposal lifecycle status and lineage hash;
- current reauthentication state;
- exact observation timestamp;
- immutable canonical snapshot hash.

No Proposal creation-time authorization or state snapshot is trusted as current authority.

## 3. Evaluation order and fail-closed decisions

`ControlledAutomationGovernanceEvaluator` reloads both the fresh governance snapshot and current server Action Whitelist on every call.

The evaluator fails closed in this order:

1. tenant evidence mismatch;
2. operator evidence mismatch;
3. inactive, cancelled, confirmed or lineage-mismatched Proposal;
4. expired Proposal;
5. missing, mismatched or integrity-invalid source evidence;
6. whitelist version drift, missing Action or Action-definition drift;
7. policy version/hash drift or policy denial;
8. disabled feature or active kill switch;
9. revoked permission or denied resource authorization;
10. resource identity, state or version drift;
11. separation-of-duties denial;
12. failed command preconditions;
13. missing fresh reauthentication;
14. only then `ELIGIBLE`.

Every failure has a stable closed `ReasonCode`.

## 4. Closed decision set

P2 can return only:

- `ELIGIBLE`;
- `INELIGIBLE`;
- `EXPIRED`;
- `STALE`;
- `POLICY_BLOCKED`;
- `AUTHORIZATION_DENIED`;
- `SOURCE_EVIDENCE_INVALID`;
- `ACTION_NOT_WHITELISTED`;
- `REAUTHENTICATION_REQUIRED`.

With the real current empty whitelist, production evaluation must return `ACTION_NOT_WHITELISTED`; no Action can become eligible.

## 5. Read-only output evidence

The result carries:

- decision and stable reason code;
- risk classification;
- complete server-owned side-effect summary;
- expected-versus-current resource identity/state/version comparison evidence;
- current whitelist version;
- current roles and authorization evidence hashes;
- current kill-switch generation;
- exact Proposal lineage hash;
- exact fresh snapshot hash;
- evaluation timestamp;
- immutable evaluation evidence hash;
- `authority = READ_ONLY_NON_EXECUTING_PREVIEW`;
- `businessSideEffectProduced = false`;
- `providerInvoked = false`;
- `connectorInvoked = false`;
- `commandAttempted = false`.

The immutable evidence hash binds every displayed decision, reason, risk, side effect, state comparison, current governance version and non-execution flag.

## 6. Zero-side-effect and architecture boundary

P2 has no dependency on:

- application command services;
- persistence implementations or database mutation;
- Provider invocation;
- connector invocation;
- Flowable services or `ACT_*` tables;
- HTTP clients;
- Queue, Worker, Scheduler, listener, polling or automatic retry.

The snapshot source is a read contract. The evaluator calls only `load`, current-whitelist resolution and pure evidence construction.

## 7. Attack and drift coverage

Unit, architecture and permanent Node tests cover:

- forged tenant;
- forged operator;
- cancelled or lineage-tampered Proposal;
- expired Proposal;
- deleted source evidence;
- mismatched source evidence;
- integrity-invalid source evidence;
- whitelist version drift;
- missing Action;
- Action-definition drift;
- policy drift;
- policy denial;
- disabled feature;
- active kill switch;
- permission revocation;
- resource authorization denial;
- resource identity drift;
- resource state drift;
- resource version drift;
- separation-of-duties denial;
- command-precondition failure;
- reauthentication requirement;
- repeated evaluation loading fresh snapshot and whitelist every time;
- zero business/Provider/connector/command side effects.

## 8. Explicit exclusions

P2 does not implement:

- Proposal persistence;
- confirmation or cancellation mutation;
- PC or Mobile confirmation UI;
- reauthentication mechanism;
- command admission or execution;
- idempotency, CAS or replay persistence;
- partial/UNKNOWN result handling;
- Provider, connector, Flowable or arbitrary HTTP/SQL/script execution;
- P5-A, P6, P7, P8, M6-G, Ready, Merge or Issue closure.

P3 may add only explicit human-confirmation and reauthentication boundaries. Because no reusable reauthentication implementation exists and the whitelist is empty, P3 must remain non-executing and P5-A must remain blocked.

`AI_IS_NOT_AN_OPERATOR`
