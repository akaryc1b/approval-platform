# M6-F P1 — Typed Non-Executable Proposal Acceptance

Status: `P1_IMPLEMENTED / FINAL_HEAD_VALIDATION_REQUIRED`

Permanent conclusion: `AI_IS_NOT_AN_OPERATOR`

## 1. P1 baseline and prerequisite

- repository baseline: `main@492a428627d3be707d5723350506302ca04841b0`
- M6-F branch: `agent/m6-f-controlled-automation-and-ai-governance`
- Pull Request: #88 — must remain Open + Draft
- P0 result: `P0_RESULT_A_NO_QUALIFYING_EXISTING_COMMAND`
- Action Whitelist: `EMPTY_PENDING_EXISTING_COMMAND_AUDIT`
- P5-A: `P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND`
- highest migration entering P1: `V49`
- P1 adds no migration

P1 defines a closed Proposal contract and pure server-side factory. It does not create an executable production Action and does not weaken the empty-whitelist decision.

## 2. Implemented contracts

### 2.1 Server-owned Action Whitelist view

`ControlledAutomationActionWhitelist` provides only the server-owned snapshot contract required for Proposal validation:

- exact whitelist version;
- exact canonical Action lookup;
- target resource type;
- closed typed parameter schema;
- risk classification;
- server-owned side-effect summary;
- mandatory reauthentication classification.

The production-safe empty implementation resolves no Action. A resolved definition is metadata only and grants no command authority.

### 2.2 Closed typed Proposal

`ControlledAutomationProposal` carries:

- `proposalId`;
- tenant evidence hash;
- authenticated operator evidence hash;
- source M6-E evidence ID and hash;
- exact Provider/model/Prompt/knowledge/policy/output-schema version references;
- canonical server-whitelisted Action type;
- bounded typed parameters and their server/user source classification;
- target resource type and hash-only ID evidence;
- exact expected resource state and version;
- target resource observation and evidence hash;
- whitelist version;
- policy version and evidence hash;
- risk classification;
- server-owned human-readable side-effect summary;
- creation time and expiry;
- closed lifecycle status;
- `requiresHumanConfirmation = true`;
- mandatory reauthentication requirement;
- immutable canonical lineage hash;
- `authority = NON_EXECUTABLE_PROPOSAL`.

The Proposal is not a command, credential, permission token, reusable confirmation token, operator identity, audit identity or execution capability.

### 2.3 Closed parameter protocol

Parameter values are restricted to bounded typed forms:

- boolean;
- enum from the exact server schema;
- bounded identifier;
- instant;
- integer;
- bounded non-executable text.

Parameter sources are restricted to:

- current server resource state;
- explicit user input;
- closed server mapping.

There is no `AI_OUTPUT` parameter source. Parameter names cannot carry URL, HTTP, SQL, script, expression, module, class, Secret, token, credential, password or key semantics. Text cannot carry URL, SQL, script or executable-expression material.

## 3. Explicit creation boundary

`ControlledAutomationProposalFactory` is a pure, side-effect-free server factory.

Creation succeeds only when all of the following are true:

1. the trigger is exactly `EXPLICIT_USER_ACTION`;
2. expiry is future and no more than fifteen minutes;
3. the server-owned whitelist resolves the exact canonical Action;
4. the Action is low risk;
5. target resource type matches the Action definition;
6. parameter names, count, types and enum values match the exact closed schema;
7. tenant and operator evidence are computed from trusted `AiServerRequestContext`;
8. the Proposal ID is allocated only after every fail-closed gate passes.

The following triggers fail before Proposal ID allocation:

- advisory callback;
- page load;
- listener;
- polling;
- Provider callback;
- schedule;
- Webhook.

With the current production whitelist, creation returns `ACTION_NOT_WHITELISTED` and no Proposal.

## 4. Hash and provenance boundary

P1 uses domain-separated, length-framed SHA-256 evidence for:

- tenant identity;
- operator identity;
- target resource expected-state evidence;
- exact source version references;
- complete Proposal lineage.

The lineage binds Proposal ID, identity evidence, source evidence, versions, Action, parameter names/types/values/sources, resource evidence, whitelist, policy, risk, side effects, timestamps, lifecycle, human-confirmation requirement, reauthentication requirement and non-executable authority.

Raw tenant/operator values are not stored in the Proposal. Raw Prompt, hidden input, Provider output, Secret and credential material have no field in the contract.

## 5. Lifecycle boundary

The closed lifecycle contains:

- `PROPOSED`;
- `INELIGIBLE`;
- `ELIGIBLE`;
- `EXPIRED`;
- `STALE`;
- `CANCELLED`;
- `CONFIRMED`.

P1 creates only `PROPOSED`. It contains no `EXECUTING`, `EXECUTED`, `SUCCEEDED`, `FAILED` or `UNKNOWN` execution state. Execution-related state remains unavailable because P5-A is blocked.

## 6. Test-only fixture boundary

Unit tests use one explicit `TEST_ONLY_NON_EXECUTABLE_ACTION` whitelist fixture to prove the closed Proposal contract. That fixture:

- exists only in test source;
- has no application-service binding;
- has no controller or runtime configuration;
- cannot execute or mutate business state;
- does not change the formal Action Whitelist decision;
- cannot be used to claim P5-A or M6-F completion.

The production empty-whitelist test proves no Proposal ID is allocated and no Proposal is returned.

## 7. Architecture and permanent boundaries

Architecture and permanent boundary tests prove:

- controlled-automation foundation has no application, persistence, connector, engine, network, JDBC, Spring or Flowable dependency;
- no Provider call exists in P1;
- no application command service is referenced;
- no Queue, Worker, Scheduler, polling or callback execution path exists;
- no executable or credential payload field exists;
- the migration upper bound remains `V49`;
- the existing automatic workflow remains unique;
- the P0 empty-whitelist decision remains exact.

## 8. Explicit exclusions

P1 does not implement:

- Proposal persistence;
- Eligibility or Authorization Preview;
- policy, role, permission or resource re-read;
- confirmation endpoint or UI;
- reauthentication implementation;
- command admission;
- application command execution;
- retry, replay, compensation or UNKNOWN handling;
- Provider, connector, Flowable or arbitrary HTTP/SQL/script execution;
- P5-A, P6, P7, P8, M6-G, Ready, Merge or Issue closure.

P2 may add only fresh, read-only, zero-side-effect governance evaluation. The Action Whitelist remains empty and every production evaluation must therefore fail `ACTION_NOT_WHITELISTED`.

`AI_IS_NOT_AN_OPERATOR`
