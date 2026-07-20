# M3 Collaboration, Delegation and Notification Governance

## Status

M3 is active on `agent/m3-collaboration-delegation-and-notification-hardening` after M2 was merged and the permanent validation workflow on `main` was confirmed green.

The M3 pull request must remain open and in Draft state during implementation. It must target `main` directly and must not be stacked on an M2 branch.

## Scope baseline

M3 will harden collaboration, delegation and notification behavior across backend, PC and mobile clients. The implementation scope includes:

- sequential and parallel countersign, any-sign, vote and weighted approval policies;
- add-sign, remove-sign, delegation and assisted handling with explicit authority boundaries;
- proxy rules, employee-departure transfer and durable process handover;
- comment governance, notification-channel governance and user notification preferences;
- tenant isolation, idempotency, concurrency safety, immutable audit evidence and stable authorization failures;
- consistent PC, H5 and WeChat semantics backed by server-authoritative APIs;
- permanent committed-head CI coverage with no PR-only helper, self-committing workflow or temporary payload.

## Delivery guardrails

- Preserve existing M1 and M2 runtime compatibility unless a migration is explicitly documented and tested.
- Do not read or mutate Flowable tables directly; platform-owned projections and application ports remain authoritative.
- Keep identity, delegation and notification decisions deterministic and auditable.
- Keep notification delivery separate from business callback Outbox responsibilities.
- Add database constraints, integration coverage and cross-platform contract tests with each increment.
- Do not mark the M3 pull request ready, merge it, close it or retarget it during active development.

## Increment A — delegation policy foundation

The first M3 increment establishes a server-authoritative proxy-rule lifecycle without changing existing task assignment behavior yet.

Completed capabilities:

- tenant-owned delegation rules with `ALL` and definition-specific scopes;
- self-service create, list and revoke APIs under `/api/approval/delegations`;
- durable request idempotency for create and revoke commands;
- principal-scoped PostgreSQL advisory serialization for overlap-safe writes;
- same-scope time-window overlap rejection while allowing definition-specific rules to override a global rule;
- deterministic effective-rule resolution with definition-specific precedence;
- self-delegation, invalid time windows, durations longer than 366 days and unauthorized revocation rejection;
- immutable `DELEGATION_RULE_CREATED` and `DELEGATION_RULE_REVOKED` audit evidence;
- stable delegation-specific 400, 404, 409 and 500 API responses;
- Flyway V14 schema constraints and resolution/query indexes;
- PostgreSQL/Testcontainers coverage for idempotency, tenant isolation, overlap rejection, precedence, revocation and fallback.

Implementation head `df242607fef1db2d01af5230c35408f89ffc7b26` passed permanent workflow run `29712802784`:

- repository hygiene: passed;
- Java 21 / Maven / PostgreSQL: passed;
- `JdbcApprovalDelegationIntegrationTest`: 3 tests, 0 failures, 0 errors, 0 skipped;
- Vben TypeScript and production build: passed;
- UniApp TypeScript, H5 and WeChat builds: passed.

Final documented head `d4308b0e6d4ab281aaff484d4d486fd2ad0db74d` passed permanent committed-head workflow run `29713024085` with the same four jobs green.

## Increment B — delegated task assignment and responsibility evidence

The second increment binds active delegation rules into the real workflow-engine task lifecycle while retaining platform-owned responsibility evidence.

Completed capabilities:

- an `ApprovalEngine` decorator that resolves delegation whenever a new active user task becomes visible;
- real Flowable task transfer through the existing engine SPI, without direct Flowable table reads or writes;
- definition-specific rules taking precedence over global rules at assignment time;
- initiator revision tasks explicitly excluded from automatic delegation;
- restart-safe definition resolution from the platform-owned approval instance projection;
- Flyway V15 task-delegation evidence storing the original principal, effective delegate, rule, scope and lifecycle status;
- assignment terminal states synchronized for completion, manual transfer, retrieve and instance termination;
- immutable `TASK_DELEGATED` audit evidence preserving the original principal as the responsibility identity;
- participant-authorized `GET /api/approval/tasks/{taskId}/delegation` responsibility query;
- PC delegation management page and route with create, list, include-revoked and revoke actions;
- H5/WeChat shared delegation management page and a personal-center entry;
- PC and mobile task details showing original principal, actual delegate, rule scope and automatic-delegation timeline events;
- PostgreSQL/Testcontainers coverage for one-time assignment, completion evidence, manual-transfer supersession, restart resolution and revision-task exclusion.

Implementation head `030d6d2bbc13c3ef6c5ed9050aa4f8a22e319919` passed permanent workflow run `29713811312`; `JdbcDelegatingApprovalEngineIntegrationTest` executed 3 tests with no failures, errors or skips.

Cross-platform head `c4cd5e235d65c49f2a258ba949e94b0522406b8c` passed permanent workflow run `29714577565` after a real mobile type-check failure was fixed from the downloaded Actions log:

- repository hygiene: passed;
- Java 21 / Maven / PostgreSQL: passed;
- Vben TypeScript and production build: passed;
- UniApp TypeScript, H5 and WeChat builds: passed.

## Increment C — governed identities and employee handover

The third increment removes free-text identity entry from delegation commands and adds durable employee-departure handover across current and future approval tasks.

Completed capabilities:

- a connector-backed approval identity directory using exact `source`, `objectType` and external identity values;
- deterministic identity mismatch, missing-user, inactive-user and connector-unavailable failures;
- separate candidate searches for active delegates/successors and inactive-or-active departing principals;
- PC, H5 and WeChat delegation creation using selected organization identities instead of raw user ID text;
- delegation creation rejecting identities that no longer exist or are inactive;
- management-protected employee handover create, list and revoke APIs using `approval.management.transfer`;
- Flyway V16 principal-handover and task-handover evidence with one active handover per tenant/principal;
- immediate transfer of all current pending tasks to the successor;
- existing tasks already handled by a temporary delegate included in formal handover and their old delegation evidence marked `SUPERSEDED`;
- formal employee handover taking precedence over temporary delegation for future tasks;
- future initiator-revision tasks assigned to the successor while preserving task-specific resubmit authority only;
- completion, manual transfer, retrieve and process termination synchronizing handover assignment terminal states;
- immutable `EMPLOYEE_HANDOVER_CREATED`, `EMPLOYEE_HANDOVER_REVOKED` and `TASK_HANDOVER_ASSIGNED` audit evidence;
- timeline reads supporting platform task IDs and engine task IDs for responsibility events;
- a PC employee-handover governance page with exact departing-user and active-successor selection, immediate-transfer confirmation, history and revoke actions.

Implementation head `81e5b31ec20c816e13f2861546df8431a70a8532` passed permanent workflow run `29718536112`:

- repository hygiene: passed;
- Java 21 / Maven / PostgreSQL: passed;
- `JdbcApprovalHandoverIntegrationTest`: 4 tests, 0 failures, 0 errors, 0 skipped;
- Vben TypeScript and production build: passed;
- UniApp TypeScript, H5 and WeChat builds: passed.

## Increment D — governed dynamic add-sign and remove-sign

The fourth increment attaches a platform-owned collaboration policy to an existing pending task while leaving the original Flowable task and responsibility owner unchanged.

Completed capabilities:

- exact active connector identities for every add-sign participant, with duplicate resolved-user and current-owner rejection;
- `ALL` collaboration requiring every remaining participant to approve and `ANY` collaboration completing after the first approval;
- any participant rejection placing the collaboration in `REJECTED` state and forcing the parent approval task to use its rejection action;
- parent-task approval blocked until collaboration is satisfied, with task claim and collaboration creation serialized by the same PostgreSQL advisory lock;
- transfer and retrieve blocked while collaboration is active;
- process withdrawal canceling the active collaboration and all remaining participant work;
- remove-sign allowed only for pending participants before any collaboration decision and never for the final pending participant;
- initiator-revision tasks excluded from dynamic add-sign;
- durable idempotency for collaboration creation, participant decision and remove-sign commands;
- Flyway V17 policy and participant evidence with one active policy per tenant/task, exact identity evidence and constrained terminal metadata;
- immutable `TASK_COLLABORATION_CREATED`, `TASK_COLLABORATOR_REMOVED` and `TASK_COLLABORATION_PARTICIPANT_DECIDED` audit evidence;
- participant-authorized collaboration task queries and owner/initiator/participant policy visibility;
- PC `加签协作` workspace for selecting a real pending task, exact organization identities, `ALL`/`ANY` mode, remove-sign and participant decisions;
- shared H5/WeChat `加签协作` workspace and personal-center entry using the same server-authoritative APIs and semantics.

Backend implementation head `8801c38984647a11287dd39f46842cd02e3be77b` passed permanent workflow run `29720189256`.

Cross-platform head `6195341eea242651b783ddfdfc1a98d6065a0601` passed permanent workflow run `29720696299`:

- repository hygiene: passed;
- Java 21 / Maven / PostgreSQL: passed;
- `JdbcApprovalTaskCollaborationIntegrationTest`: 3 tests, 0 failures, 0 errors, 0 skipped;
- Vben TypeScript and production build: passed;
- UniApp TypeScript, H5 and WeChat builds: passed.

The next increment will continue M3 with governed vote and weighted approval policies, followed by notification-channel preferences and delivery governance.

## Initial validation baseline

M3 starts from `main` merge commit `4e468f9f049b52cb20855872cddf44eaa237501b`, which contains the complete M2 delivery and the permanent `.github/workflows/approval-platform-validation.yml` workflow.
