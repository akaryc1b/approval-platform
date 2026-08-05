# M6-F P0 — Existing Command Audit

Status: `P0_RESULT_A_NO_QUALIFYING_EXISTING_COMMAND`

Permanent conclusion: `AI_IS_NOT_AN_OPERATOR`

## 1. Exact audit baseline

- repository: `akaryc1b/approval-platform`
- `main`: `492a428627d3be707d5723350506302ca04841b0`
- M6-F branch before P0: `agent/m6-f-controlled-automation-and-ai-governance`
- accepted R0 documented Head: `0306acc6c9dc43c7c85c5f1c99ce272a71448a89`
- accepted R0 final Run: `30973167769` / #1272 — success
- PR: #88 — Open + Draft
- highest Flyway migration: `V49`
- automatic PR/main workflows: exactly one, `.github/workflows/approval-platform-validation.yml`
- Action Whitelist entering P0: `EMPTY_PENDING_EXISTING_COMMAND_AUDIT`

P0 adds no execution path, Provider capability, application command, Flyway migration, Queue, Worker, Scheduler or second workflow.

## 2. Audit method

The audit covered the existing application services and controllers that mutate business state or produce durable side effects, including:

- purchase-payment publish/start/task actions;
- withdraw, transfer, retrieve, reject and resubmit actions;
- urge, copy, message read and read-all actions;
- notification preferences, read state and replay;
- comment create/edit/delete and attachment binding;
- delegation and handover;
- task collaboration and participant decisions;
- design/form/template DRAFT creation and lifecycle actions;
- release publish/deploy/activate/disposition;
- SLA execution and governed replay;
- consistency and operational-failure replay;
- M5 migration execution/reconciliation operations;
- M6-A connector invocation;
- M6-E advisory generation.

Read-only query, preview, diagnostics and advisory operations were reviewed but are not executable Action candidates because they intentionally have no business mutation.

## 3. Qualification criteria

Each candidate was evaluated against the mandatory twenty-point gate.

| # | Criterion |
| ---: | --- |
| C1 | real and explainable business value |
| C2 | already exists on current `main`; not invented by M6-F |
| C3 | executed through an application service |
| C4 | tenant is server-owned |
| C5 | operator comes from authenticated server context |
| C6 | explicit permission/capability exists |
| C7 | resource-level authorization exists |
| C8 | expected state/version is supplied or derived exactly |
| C9 | CAS or equivalent stale-state rejection exists |
| C10 | idempotency contract exists and binds the payload |
| C11 | immutable audit contract exists |
| C12 | external or duplicate side effects are absent or bounded safely |
| C13 | duplicate notification/write risk is controlled |
| C14 | rollback, compensation or durable UNKNOWN behavior is defined |
| C15 | no direct Flowable table or engine bypass |
| C16 | no direct connector command bypass |
| C17 | does not decide or advance approval/process state |
| C18 | separation-of-duties can be evaluated freshly |
| C19 | suitable for explicit human confirmation and risk display |
| C20 | every authority, policy and resource precondition can be freshly revalidated before execution |

Legend:

- `Y`: present and suitable;
- `P`: partial or local control only; insufficient for whitelist admission;
- `N`: absent;
- `X`: permanently excluded category.

A candidate is qualified only when all mandatory controls are `Y`, the Action is low-risk, and an existing reusable reauthentication mechanism is available.

## 4. Detailed qualification matrix

| Candidate | C1 | C2 | C3 | C4 | C5 | C6 | C7 | C8 | C9 | C10 | C11 | C12 | C13 | C14 | C15 | C16 | C17 | C18 | C19 | C20 | Decision |
| --- | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: | --- |
| message mark-read | Y | Y | Y | Y | Y | N | Y | N | P | N | P | Y | Y | P | Y | Y | Y | N | Y | P | not qualified |
| message read-all | Y | Y | Y | Y | Y | N | Y | N | P | N | P | Y | Y | P | Y | Y | Y | N | Y | P | not qualified |
| notification mark-read | Y | Y | Y | Y | Y | N | Y | N | P | N | N | Y | Y | P | Y | Y | Y | N | Y | P | not qualified |
| notification read-all | Y | Y | Y | Y | Y | N | Y | N | P | N | N | Y | Y | P | Y | Y | Y | N | Y | P | not qualified |
| notification preferences update | Y | Y | Y | Y | Y | N | Y | Y | Y | N | N | Y | P | P | Y | Y | Y | N | Y | P | not qualified |
| instance urge | Y | Y | Y | Y | Y | N | Y | N | P | Y | Y | P | P | P | Y | Y | Y | N | Y | P | not qualified |
| instance copy | Y | Y | Y | Y | Y | N | Y | N | P | Y | Y | P | P | P | Y | Y | Y | N | Y | P | not qualified |
| comment create | Y | Y | Y | Y | Y | N | Y | N | N | Y | Y | P | P | P | Y | Y | Y | N | Y | P | not qualified |
| comment edit | Y | Y | Y | Y | Y | N | Y | Y | Y | Y | Y | P | P | P | Y | Y | Y | N | Y | P | not qualified |
| comment delete/tombstone | Y | Y | Y | Y | Y | N | Y | Y | Y | Y | Y | Y | Y | P | Y | Y | Y | N | Y | P | not qualified |
| process-template DRAFT creation | Y | Y | Y | Y | Y | Y | Y | P | P | Y | Y | Y | Y | P | Y | Y | Y | P | P | P | not qualified; high-risk `TRANSFER` gate and no reauthentication |
| delegation create/revoke | Y | Y | Y | Y | Y | N | P | N | P | Y | Y | P | P | P | Y | Y | N | N | P | P | excluded; assignment authority |
| task collaboration create/add/remove/decide | Y | Y | Y | Y | Y | N | Y | P | P | Y | Y | P | P | P | Y | Y | N | N | P | P | excluded; can gate or affect process outcome |
| operational-failure replay | Y | Y | Y | Y | Y | Y | Y | P | P | Y | Y | N | P | P | Y | N | P | P | P | P | excluded; replay and connector/Outbox side effects |
| approval task transitions | Y | Y | Y | Y | Y | P | Y | Y | Y | Y | Y | N | P | P | Y | Y | N | P | P | P | permanently excluded |
| release/SLA/migration lifecycle actions | Y | Y | Y | Y | Y | Y | Y | P | P | Y | Y | N | P | P | Y | P | N | P | P | P | permanently excluded |
| M6-A connector invocation | Y | Y | Y | Y | Y | Y | Y | P | P | P | Y | N | P | P | Y | N | Y | P | P | P | excluded; direct connector command is prohibited |
| M6-E advisory generation | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | Y | N | Y | not an Action; intentionally advisory and non-executable |

## 5. Candidate findings

### 5.1 Message read receipt and read-all

`ApprovalMessageService.markRead` and `markAllRead` are real low-risk participant operations and use recipient scoping. First-read evidence is audited, and repeated reads are effectively idempotent at the store level.

They are not qualified because:

- the controller exposes no explicit permission/capability requirement;
- no `Idempotency-Key` contract binds these requests;
- no expected message version or compare-and-set evidence is supplied;
- read-all has no exact bounded target-set evidence for a Proposal;
- no fresh whitelist/policy/separation-of-duties evaluation contract exists;
- no reusable reauthentication evidence exists.

### 5.2 Notification read state

`ApprovalNotificationService.markRead` and `markAllRead` are tenant/recipient scoped and low-risk.

They are not qualified because they have no explicit permission, payload-bound idempotency, immutable command audit, expected version, reauthentication or durable execution lineage.

### 5.3 Notification preference update

`ApprovalNotificationService.updatePreferences` has a real expected-version check and persistence CAS.

It is not qualified because it lacks a command idempotency contract, immutable audit event, explicit permission, reauthentication, separation-of-duties and M6-F durable UNKNOWN/lineage semantics.

### 5.4 Urge

`ApprovalMessageService.urge` has real business value, uses `IdempotencyGuard`, validates a running instance and current pending assignees, suppresses repeated delivery inside a ten-minute window, and appends audit evidence.

It is not qualified because:

- no explicit permission/capability is required by the endpoint;
- no expected instance/task version is bound to the command;
- a changed idempotency key can reach the message-deduplication layer, so the command contract itself is not a complete CAS boundary;
- it can create multiple notification records and has no durable partial/UNKNOWN classification suitable for controlled automation;
- no reauthentication or fresh separation-of-duties gate exists.

### 5.5 Copy

`ApprovalMessageService.copy` is idempotent, audited and restricts recipients to the immutable approval identity snapshot.

It is not qualified because it lacks explicit permission, expected resource version, complete stale-state protection, reauthentication and durable partial/UNKNOWN semantics for multi-recipient writes.

### 5.6 Comment create

`ApprovalCommentService.comment` is application-service based, payload-idempotent, participant-authorized and audited.

It is not qualified because creation binds no expected instance/comment version, has no explicit permission, and may bind attachments plus create mention messages. These additional durable side effects do not have a separate M6-F UNKNOWN/compensation contract or reauthentication gate.

### 5.7 Comment edit

`ApprovalCommentService.edit` is the closest technical candidate: it has application-service routing, participant/author authorization, expected comment version, CAS, payload idempotency and immutable revision/audit evidence.

It is still not qualified because:

- there is no explicit permission/capability for comment mutation;
- there is no reusable reauthentication or step-up proof;
- edits may bind attachments and emit new mention messages;
- there is no fresh policy/whitelist/separation-of-duties evaluator;
- no durable partial/UNKNOWN outcome contract exists for the combined edit/message side effects.

### 5.8 Comment delete/tombstone

`ApprovalCommentService.delete` has expected version, CAS, idempotency, author checks, immutable revision history and audit evidence.

It is not qualified because it is destructive content mutation without an explicit permission or reusable reauthentication mechanism. The current transaction can roll back local failure, but M6-F requires an explicit final outcome/UNKNOWN lineage rather than inferring safety from one implementation transaction.

### 5.9 Process-template DRAFT creation

`ProcessTemplateManagementController.createDraft` uses an existing governed coordinator, payload bounds, exact preview evidence, idempotency and the explicit high-risk `TRANSFER` permission. It creates only a tenant-local editable DRAFT and does not publish, deploy or activate.

It is not qualified because:

- it is already classified as a high-risk management operation;
- its permission is `TRANSFER`, not a dedicated low-risk automation capability;
- the repository has reason/idempotency/audit governance but no reusable reauthentication evidence;
- the imported package changes design state and is not an appropriate first AI-controlled Action;
- adding a dedicated permission or reauthentication flow would be new command capability, which P0 is prohibited from inventing.

### 5.10 Delegation and handover

Delegation creation/revocation and employee handover alter assignment authority and future task routing. They are transfer-like actions and permanently outside the low-risk Action gate for this round.

### 5.11 Task collaboration

Collaboration creation, participant changes and participant decisions can block, satisfy or make an approval threshold impossible. They influence approval outcome semantics and are therefore excluded.

### 5.12 Operational replay

Operational-failure replay can requeue notification delivery, replay a business Outbox item or launch a new consistency check. It intentionally preserves owning state machines but still creates external or repeated side effects. Automatic/replay execution is not an eligible first Action.

### 5.13 Approval, release, SLA and migration commands

Approve, reject/return, resubmit, transfer, withdraw, retrieve, terminate, publish, deploy, activate, SLA execution/replay and process-instance migration are high-risk or process-state-changing operations. They are permanently excluded without a separate future high-risk gate.

### 5.14 Connector invocation and AI advisory generation

M6-A governed connector invocation is read-only but remains a connector call; M6-F permanently prohibits direct connector commands.

M6-E generation is intentionally advisory. It produces evidence and a human-review result, not a business command, and cannot be placed on the Action Whitelist.

## 6. Repository-wide reauthentication finding

The current identity model provides:

- server-authenticated `ApprovalPrincipal`;
- tenant/operator ownership;
- authority and responsibility sets;
- account status;
- session expiry;
- high-risk management reason, idempotency and authorization-audit evidence.

The audited repository does not provide a reusable command-bound reauthentication or step-up evidence contract that can be bound to proposal, operator, tenant, resource, action, parameters, expected state, policy, whitelist and expiry.

A normal unexpired session is not reauthentication. A reason header, idempotency key or audit record is not reauthentication. P0 must not fabricate a weak replacement.

This finding alone blocks P5-A even if another candidate were otherwise acceptable.

## 7. Formal P0 result

P0 result is **A — no candidate qualifies**.

The Action Whitelist remains exactly:

`EMPTY_PENDING_EXISTING_COMMAND_AUDIT`

Consequences:

- no Action type is authorized;
- no Provider output can be mapped to a command;
- P5-A is skipped and remains prohibited;
- no new permission, command, credential, reauthentication flow or execution adapter is invented;
- P1-P4 may continue only as typed, non-executing governance foundations;
- M6-F cannot be described as complete;
- Issue #81 remains Open;
- M6-G Issue #82 remains blocked.

`AI_IS_NOT_AN_OPERATOR`
