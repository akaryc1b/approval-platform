# Operations Runbook

## Document status

This living runbook describes the accepted M4 production baseline.

- Accepted `main`: `58efb4255394fe3911700719669c4423a3ab212e`
- M4 PR: `#55`, merged and closed
- Flyway chain: V1–V32
- Final permanent validation: Run `29986751894`, `success`
- Permanent workflow: `.github/workflows/approval-platform-validation.yml`

See [`M4_FINAL_ACCEPTANCE.md`](M4_FINAL_ACCEPTANCE.md) for acceptance lineage and immutable evidence links.

## Purpose

This runbook covers production identity, startup, database migration, management authorization, health checks, metrics, release lifecycle, SLA execution, governed replay, detect-only migration assessment, backup and recovery.

Real process-instance migration execution is not part of M4 and must not be enabled or simulated through direct Flowable database access.

## Production prerequisites

- Java 21 runtime or the approved application image;
- PostgreSQL at the validated deployment version; PostgreSQL 16 is used by integration tests;
- platform Flyway migrations and application binaries from the same release;
- reviewed Flowable schema compatibility with automatic schema update disabled;
- a trusted authentication integration capable of establishing `ApprovalPrincipal`;
- restricted access to management APIs and Actuator;
- durable database backups with a tested restore procedure;
- configured connector secrets in a secret manager;
- synchronized host clocks for lease and evidence timestamps.

## Production identity

### Required mode

Base configuration is principal-backed:

```yaml
approval:
  security:
    identity:
      mode: ${APPROVAL_IDENTITY_MODE:principal}
```

Production must use `principal` mode. A trusted upstream authentication adapter installs an `ApprovalPrincipal` containing authoritative tenant, operator, bounded authorities, account state, session expiry and server-resolved responsibility evidence.

If no trusted principal is available, the application fails closed with an authentication error. It must not invent a default tenant or operator.

### Local development mode

`local-headers` is an explicit local/test adapter only. Startup must fail if it is selected outside a local or test profile.

Do not use local header identity in production. Browser, H5, WeChat and Mobile code must not be treated as trusted sources of:

- tenant or operator;
- management permission or enterprise role;
- audit-chain reference;
- worker or lease owner;
- authoritative due time;
- release package or runtime binding;
- Flowable deployment or definition identity.

### Management authorization

Management handlers declare a closed capability and resource scope. Keep permission enforcement enabled in production.

High-risk operations require, before handler execution:

- an allowed server-side authorization decision;
- `X-Approval-Operation-Reason` with normalized bounded text;
- a valid `Idempotency-Key`;
- server-owned requestId and traceId;
- successfully persisted governance audit evidence.

Audit failure is a service-unavailable condition and must fail closed. Do not temporarily bypass authorization or audit to resolve an incident.

## Database configuration

Example environment variables:

```text
APPROVAL_DB_URL=jdbc:postgresql://database:5432/approval
APPROVAL_DB_USERNAME=approval
APPROVAL_DB_PASSWORD=<secret>
```

Use a secret manager. Never commit database passwords, connector secrets, tokens or production identity configuration.

## Startup and Flyway migration

1. Verify a recent recoverable backup.
2. Verify the application artifact and expected Flyway range.
3. Start one controlled application instance.
4. Allow Flyway to validate and apply platform migrations.
5. Keep Flowable `database-schema-update` disabled in production.
6. Verify readiness, database connectivity and migration state.
7. Verify principal-backed authentication and management authorization.
8. Add the instance to service.
9. Roll out remaining stateless API and worker instances.

Flyway migrations are immutable. Never edit a migration already merged or applied. Add a new migration for every schema change.

For the accepted M4 release, the chain is continuous through:

- V28: versioned work calendars;
- V29: immutable SLA policies;
- V30: SLA instances and responsibility history;
- V31: SLA execution intents, attempts and replay;
- V32: process release lifecycle and runtime binding.

## Health and graceful shutdown

Actuator exposes health, info, metrics and Prometheus endpoints. Restrict them to the monitoring network.

Check:

- application liveness and readiness;
- database connectivity and Flyway validation;
- trusted identity integration;
- connector health where enabled;
- SLA worker queue and lease behavior;
- release lifecycle and runtime-binding error rates.

Before terminating an instance:

1. remove it from load balancing;
2. stop new worker claims;
3. allow in-flight HTTP requests and short database transactions to finish;
4. allow active external dispatch calls to return or expire;
5. preserve claimed work for lease-expiry recovery;
6. perform graceful shutdown.

Do not hold shutdown waiting on an unbounded external call. Durable intent, attempt and lease evidence must make recovery explicit.

## Release operations

### Publish

1. Save the exact design draft revision.
2. Run publication preflight against exact definition and form versions.
3. Resolve blocking findings.
4. Review and acknowledge the current warning-code set.
5. Publish using the exact preflight identity.
6. Verify immutable Definition, Form Package and Approval Release Package hashes.
7. Verify publication audit evidence.

A changed draft, form package, target version or warning set invalidates the old preflight evidence.

### Deploy

1. Select the immutable Release Package.
2. Run deployment preflight for the target environment.
3. Acknowledge the exact current warning set.
4. Deploy explicitly through the platform.
5. Verify platform deployment status and attempt evidence.
6. Verify exact engine deployment ID, definition ID and engine version.
7. Verify deployment success or failure audit evidence.

Do not deploy directly through Flowable and then manually patch platform records.

### Activate

1. Confirm the target release is `PUBLISHED` and successfully deployed.
2. Confirm package and deployment hashes match.
3. Read the current effective-release revision.
4. supply a bounded operational reason and idempotency key;
5. activate the target release through the governed lifecycle endpoint;
6. verify the target lifecycle is `ACTIVE`;
7. verify the prior active release was disposed according to the closed lifecycle;
8. verify effective-release projection, activation history and audit evidence.

New instances use the exact `ACTIVE` release. Existing instances keep their original runtime binding.

### Deprecate and retire

- Deprecate an active release only through the governed disposition path.
- Retire only from an allowed lifecycle state.
- `RETIRED` is terminal.
- Never edit the immutable Release Package to represent lifecycle changes.
- Do not retire evidence still required by active or retained runtime bindings without an approved retention strategy.

### Reactivate a previously active release

A governed `DEPRECATED -> ACTIVE` path may restore a previously active, still-valid release for future starts. This changes the effective release only.

It is not process-instance migration and does not rewrite existing instances.

## Runtime-binding operations

For each release-bound instance, the platform retains exact release, package, schema, compiler, deployment and engine-definition evidence.

Operational checks:

- new starts must resolve one exact `ACTIVE` release;
- release and deployment hashes must match;
- runtime binding must be persisted with platform instance/task/audit/idempotency evidence;
- reads and replays must validate binding against the platform projection;
- missing binding or evidence mismatch must fail closed;
- legacy unbound instances may remain readable but cannot fabricate release evidence.

Do not repair a binding by directly editing platform or Flowable tables. Investigate the originating start transaction, audit evidence and external-engine boundary.

## SLA operations

### Calendar and policy management

- publish immutable calendar versions before activation;
- bind working-time policies to an exact published calendar version and hash;
- do not modify published policy or calendar content in place;
- use server-calculated natural-time or working-time deadlines;
- preserve time-zone and DST interpretation evidence.

### SLA execution worker

Worker behavior:

1. discover a bounded tenant set;
2. claim a bounded per-tenant batch using PostgreSQL locking;
3. record server-owned worker and lease evidence;
4. commit the claim transaction;
5. execute connector or governed action outside the database transaction;
6. persist one append-only attempt;
7. use CAS and lease-owner checks to record the outcome;
8. schedule bounded backoff or enter `DEAD` based on server-side retry classification.

Only due `READY`, due `RETRY_WAIT` or expired `CLAIMED` intents are runnable. `SUCCEEDED`, `DEAD` and `CANCELLED` are terminal.

Unsupported automatic actions must fail closed rather than report success. Automatic approve, reject or transfer must not use a direct database shortcut or invented operator.

### DEAD replay

Replay is permitted only for `DEAD` intent evidence and only through the governed management capability.

Replay:

- does not modify the source intent or attempts;
- creates a replacement intent;
- appends immutable replay evidence;
- preserves original error evidence;
- requires reason, idempotency, requestId, traceId and audit evidence;
- returns existing evidence for an exact idempotent replay;
- rejects conflicting key reuse.

## Detect-only process migration assessment

The only accepted migration-related operation is the dry-run assessment endpoint and Web report.

It may read:

- source and target release lifecycle;
- immutable package and deployment evidence;
- published definition topology;
- runtime bindings;
- platform instance and active task projections.

It produces:

- report status;
- global findings;
- per-instance decision and active task keys;
- `bindingEvidenceHash`;
- deterministic `reportHash`;
- paging and completeness evidence.

It must not:

- call a Flowable migration command;
- change instance or task state;
- change runtime binding;
- create an execution plan;
- execute, force, apply or rollback migration;
- automatically retry anything.

A report can become stale immediately after generation. Always treat it as observed evidence, not authorization or an executable command.

## Metrics

Keep all metric tag sets closed and low-cardinality.

### Management authorization

`approval.management.authorization`

Typical tags:

- requirement;
- outcome;
- decision;
- role;
- resource scope.

### Management request duration

`approval.management.request.duration`

Typical tags:

- requirement;
- outcome.

### SLA worker

`approval.sla.execution.worker`

Allowed tags:

- action;
- result;
- failure_class.

### Release lifecycle

`approval.release.lifecycle.operation`

Use only the closed lifecycle action/result/failure categories defined by the implementation.

### Migration assessment

`approval.release.migration.assessment`

Use only closed result and failure categories. Do not tag with definition, release, report or instance identity.

### Runtime binding validation

`approval.runtime.binding.validation`

Allowed tags:

- result: `success`, `failure`, `not_required`;
- failure_class: `none`, `missing_binding`, `evidence_mismatch`.

Never use tenant, operator, user, request, trace, instance, task, definition, release, worker, lease owner, hash, reason or arbitrary error text as a metric tag.

## Logs and tracing

Use requestId and traceId to correlate:

- authentication and authorization decisions;
- audit evidence;
- release lifecycle operations;
- runtime-binding validation;
- SLA intent and attempt evidence;
- connector and external-engine calls;
- migration assessment reports.

Log bounded identifiers and stable error codes. Do not log full Approval DSL, Form values, BPMN/DMN, credentials, complete authority sets or arbitrary request bodies.

## Incident runbooks

### Authentication required in production

1. verify `APPROVAL_IDENTITY_MODE=principal`;
2. verify the trusted authentication adapter created `ApprovalPrincipal`;
3. verify account state and session expiry;
4. verify ingress cannot bypass the adapter;
5. do not switch production to local headers.

### Management request returns 403

1. verify the principal has the required capability through the central responsibility resolver;
2. verify tenant or department resource scope;
3. verify operation reason and idempotency key for high-risk operations;
4. verify audit persistence is available;
5. inspect bounded authorization logs and metrics;
6. do not disable enforcement as a workaround.

### SLA intent remains CLAIMED

1. inspect lease deadline, version and worker evidence;
2. confirm the original worker is not still committing an outcome;
3. allow expired-lease recovery through the normal claim path;
4. verify stale-worker CAS prevents late outcome commit;
5. do not update the row manually.

### SLA intent becomes DEAD

1. inspect append-only attempts and bounded failure evidence;
2. determine whether the action adapter is configured and safe;
3. correct the root cause;
4. use governed replay with a new reason and idempotency key;
5. verify the replacement intent and immutable replay evidence;
6. never delete the source intent or attempts.

### Release activation conflict

1. reload target lifecycle and current effective release;
2. verify deployment and package evidence;
3. check whether another operator advanced the revision;
4. reassess the intended target;
5. repeat with current revision and new governed command evidence.

### Runtime binding validation fails

1. correlate instance, start audit, requestId and traceId;
2. compare platform instance projection and binding evidence;
3. verify exact release package and deployment identity;
4. determine whether the instance is legacy/unbound or release-bound/inconsistent;
5. do not fabricate missing evidence or edit Flowable tables;
6. escalate for controlled reconciliation and root-cause analysis.

### Migration assessment is BLOCKED or PARTIAL

1. inspect global and per-instance findings;
2. verify target release lifecycle and deployment;
3. verify source binding and active task projections;
4. request complete paging if a complete report is required;
5. regenerate after authoritative state changes;
6. do not interpret `READY` as permission to execute a migration.

## Backup and recovery

Back up PostgreSQL consistently so platform and Flowable data share the same recovery point.

Include:

- domain and projection tables;
- immutable DSL/Form/UI/compiler/Release artifacts;
- work calendar and SLA policy versions;
- SLA instances, intents, attempts and replay evidence;
- release lifecycle, deployment, activation and runtime-binding evidence;
- audit, idempotency, Outbox and connector state;
- Flowable engine tables managed by Flowable.

After restore:

1. run Flyway validation;
2. verify the V1–V32 schema state;
3. sample immutable package, policy, calendar and binding hashes;
4. verify active releases reference valid deployed evidence;
5. verify engine definition identities referenced by bindings exist;
6. inspect runnable SLA queues and expired leases;
7. run read-only version-center, consistency and migration-assessment checks as appropriate;
8. do not automatically redeploy, reactivate, replay or migrate all records.

A backup is not validated until restore has been tested.

## Retention and cleanup

Never delete evidence referenced by:

- an effective or historical release lifecycle;
- an activation-history record;
- a runtime binding;
- an active or retained approval instance;
- an SLA instance, execution attempt or replay;
- an audit or idempotency retention policy.

Cleanup jobs operate on platform-owned tables with tenant predicates and reviewed retention rules. Do not purge Flowable history independently from platform evidence retention.

## Upgrade and application rollback

Before upgrade:

1. review application, Flyway and Flowable compatibility;
2. run the committed-head permanent validation workflow;
3. preserve the previous image and a tested backup;
4. verify all nodes will use one application and compiler version;
5. review new worker and lifecycle configuration.

Application binary rollback is safe only when the previous binary understands all schema and protocol evidence already written by the newer release. Otherwise use restore or an approved forward-fix procedure.

Application rollback does not imply release rollback or process-instance migration rollback.

## Permanent operational prohibitions

- no direct production access to Flowable `ACT_*` tables from application or repair code;
- no browser-supplied authority, tenant, operator, audit, worker, lease or engine identity;
- no long database transaction around external connector or engine calls;
- no deletion or overwrite of original attempts, replay, audit or binding evidence;
- no blind retry of an unknown external-engine outcome;
- no representation of detect-only migration assessment as execution;
- no temporary workflow used to bypass `.github/workflows/approval-platform-validation.yml`.
