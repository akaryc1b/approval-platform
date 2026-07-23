# M4 Final Acceptance

## Acceptance status

M4 is formally accepted and merged into `main`.

- Pull Request: `#55`
- PR state: `Merged / Closed`
- Source branch: `agent/m4-production-readiness-and-enterprise-governance`
- Accepted source head: `2edd83af89b381438932338898387913192c8fbd`
- Target branch before merge: `main` at `72b1f051312e8e4994311e480b55e326df36648d`
- Merge method: merge commit
- Merge commit and current accepted `main`: `58efb4255394fe3911700719669c4423a3ab212e`
- Final permanent validation run: `29986751894`
- Final run conclusion: `success`
- Highest Flyway migration: `V32`
- Permanent workflow: `.github/workflows/approval-platform-validation.yml`
- Merge date: 2026-07-23

No squash or rebase was used. The accepted source commit lineage remains reachable from the merge commit.

This file is the post-merge M4 index. It does not rewrite the phase-specific governance records, which remain immutable acceptance-time evidence.

## Accepted M4 scope

### M4-A — Identity and tenant governance

- server-authenticated `ApprovalPrincipal` owns tenant, operator, authorities, account state and optional session expiry;
- base configuration defaults to principal-backed identity and fails closed when no trusted principal is installed;
- local header identity is restricted to explicit local/test profiles;
- forged tenant, operator and permission headers do not become authoritative;
- cross-tenant claims use non-disclosing not-found behavior;
- requestId and traceId are bounded, sanitized, generated when absent and propagated by the server.

Record: [`M4_IDENTITY_AND_TENANT_GOVERNANCE.md`](M4_IDENTITY_AND_TENANT_GOVERNANCE.md)

### M4-B — Authorization and responsibility governance

- unified responsibility sources cover person, department, position, role and user group;
- management roles, resource scopes and participant business rights remain separate;
- the centralized resolver owns a closed role-to-capability matrix;
- high-risk operations require a bounded reason, governed idempotency key and prior audit evidence;
- audit unavailability fails closed;
- authorization metrics use closed, low-cardinality tags.

Record: [`M4_AUTHORIZATION_AND_RESPONSIBILITY_GOVERNANCE.md`](M4_AUTHORIZATION_AND_RESPONSIBILITY_GOVERNANCE.md)

### M4-C — SLA and work-calendar governance

- tenant-scoped versioned work calendars and immutable calendar snapshots;
- immutable SLA policies bound to exact calendar version and content hash;
- natural-time and working-time calculations with time-zone and DST behavior;
- lifecycle-safe SLA instances, pause/resume/terminal transitions and responsibility history;
- PostgreSQL 16 migration, transaction and index-plan evidence;
- Web management and Mobile participant-safe SLA visibility.

Migrations: `V28`–`V30`

Record: [`M4_SLA_AND_CALENDAR_GOVERNANCE.md`](M4_SLA_AND_CALENDAR_GOVERNANCE.md)

### M4-D — SLA execution and replay governance

- durable execution intent and append-only attempt evidence;
- multi-worker bounded claim, lease, CAS and expired-lease recovery;
- bounded retry/backoff, `DEAD` state and governed replay;
- external connector or action execution occurs outside long database transactions;
- pause, resume, terminal and responsibility-change synchronization shares the existing outer platform transaction;
- Web operations surface exposes queue evidence and governed `DEAD` replay;
- worker metrics use only `action`, `result` and `failure_class`.

Migration: `V31`

Record: [`M4_SLA_EXECUTION_AND_REPLAY_GOVERNANCE.md`](M4_SLA_EXECUTION_AND_REPLAY_GOVERNANCE.md)

### M4-E/F — Process release and migration-assessment governance

- tenant-scoped process release lifecycle: `DRAFT`, `PUBLISHED`, `ACTIVE`, `DEPRECATED`, `RETIRED`;
- immutable Approval Release Package, deployment evidence and activation history;
- at most one `ACTIVE` release for a tenant and definition;
- new release-bound starts use the exact active release;
- existing instances retain immutable runtime binding evidence;
- missing or conflicting release-bound evidence fails closed;
- detect-only migration assessment produces deterministic report and per-instance binding evidence hashes;
- assessment is non-mutating and uses public application ports and platform-owned data;
- a separate Web report page exposes evidence but no migration execution action;
- structured release-governance errors and low-cardinality lifecycle, assessment and runtime-binding metrics.

Migration: `V32`

Record: [`M4_PROCESS_RELEASE_AND_MIGRATION_ASSESSMENT_GOVERNANCE.md`](M4_PROCESS_RELEASE_AND_MIGRATION_ASSESSMENT_GOVERNANCE.md)

## Migration chain

M4 extended the immutable Flyway chain without rewriting prior migrations:

| Migration | Accepted purpose |
| --- | --- |
| V28 | Versioned work calendars |
| V29 | Immutable SLA policies |
| V30 | SLA instances and responsibility history |
| V31 | SLA execution intents, attempts and replay evidence |
| V32 | Process release lifecycle and runtime binding |

The accepted chain is continuous from V1 through V32.

## Final validation evidence

Final frozen source head: `2edd83af89b381438932338898387913192c8fbd`

Permanent Run `29986751894` completed successfully with all four jobs:

- Java 21 / Maven / PostgreSQL;
- Vben TypeScript / production build;
- UniApp TypeScript / H5 / WeChat Mini Program;
- Repository hygiene and permanent governance boundaries.

Final test matrix recorded on the accepted head:

- 16-module Maven reactor: `BUILD SUCCESS`;
- aggregate tests: `477`;
- application tests: `125`;
- PostgreSQL persistence tests: `199`;
- server tests: `64`;
- failures / errors / skipped: `0 / 0 / 0`;
- migration assessment application: `6/6`;
- migration assessment PostgreSQL non-mutation proof: `2/2`;
- lifecycle activation transaction/concurrency: `3/3`;
- runtime binding validation: `4/4`;
- runtime binding production configuration: `1/1`;
- migration upgrade: `6/6`;
- client governance boundary: `10/10`;
- release governance permanent boundary: `5/5`;
- Vben production pipeline: `11/11`;
- UniApp type-check, H5 and WeChat builds: successful.

Final artifacts were downloaded, SHA-256 checked, expanded and inspected:

| Artifact | ID | SHA-256 |
| --- | ---: | --- |
| Maven | `8555413942` | `f0ffbd591c05a4380ce3602aecbca30c14e1c8a77da6f2144858bb24af869783` |
| Vben | `8555345250` | `9d63f235f063d67f2f21d71962a30c946388463d31cc8a819255d592cf5a4acb` |
| Mobile | `8555328276` | `e8f67f1155927ed326ebac021d22b80a245b12b006e26dd1090ac79baf63104c` |
| Hygiene | `8555307684` | `d7f4ce9fc37af2d2e3b7926a4097531c10bb4b38c2797adc6795866cd84aaf45` |

## Frozen governance lineage

The following detailed records remain byte-for-byte frozen:

| Record | Git blob |
| --- | --- |
| `M3_FINAL_ACCEPTANCE.md` | `459c684027e4a08f08655bff3e31721912dc35bc` |
| `M4_IDENTITY_AND_TENANT_GOVERNANCE.md` | `716ecf6503aeaea7a6dbfa5980964a5c4b983619` |
| `M4_AUTHORIZATION_AND_RESPONSIBILITY_GOVERNANCE.md` | `888f07df905726cfb3507d2ae495db3247d6c4fe` |
| `M4_SLA_AND_CALENDAR_GOVERNANCE.md` | `beb098bc6b4ee68c6ca11da0678a76780b72a049` |
| `M4_SLA_EXECUTION_AND_REPLAY_GOVERNANCE.md` | `dc687d073e0352e0b88d96bd8df0f4ee36775b6e` |
| `M4_PROCESS_RELEASE_AND_MIGRATION_ASSESSMENT_GOVERNANCE.md` | `3c78cee75ed1ec3536fc8e26d440592e2038c6f2` |

Acceptance-time statements inside those files are historical evidence. They must not be edited merely because PR #55 is now merged.

## Explicit limitations

M4 does not provide real process-instance migration execution.

The accepted migration assessment:

- observes current authoritative platform state;
- can become stale after generation;
- is not an executable plan;
- does not call an engine migration command;
- does not modify task, instance, release, runtime-binding or Flowable state;
- does not provide execute, force or rollback operations.

M4 has no migration plan, execution intent, attempt, verification, reconciliation or completion persistence model. No official Flowable migration API has been selected and validated for this product boundary. External engine calls cannot share an atomic transaction with the platform database.

## M5 entry gate

M5 must begin with an isolated feasibility stage using the official Flowable public API. Before a safe conclusion is accepted, M5 must not add:

- a real migration execution endpoint;
- a migration worker;
- an automatic retry path for unknown outcomes;
- a browser or Mobile execution action;
- direct Flowable internal-table access;
- a false rollback capability.

The next accepted capability must explicitly model execution outcome, post-call verification and reconciliation for unknown or inconsistent states.

## Repository tracking state

At M4 merge completion, Issues #13 and #14 remained Open. Their issue states do not change or weaken this acceptance record.
