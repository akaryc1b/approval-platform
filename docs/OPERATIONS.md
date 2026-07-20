# Operations Runbook

## Purpose

This runbook covers the production operation of the standalone Approval Platform: startup, database migration, management authorization, health checks, metrics, audit evidence, deployment failures, effective-release changes, artifact transfer, backup and recovery.

## Production prerequisites

- Java 21 runtime or the project container image;
- PostgreSQL at a version validated by the deployment team; PostgreSQL 16 is used by CI integration tests;
- Flowable schema and platform Flyway migrations from the same application release;
- authenticated ingress or host security adapter;
- durable database backups and a tested restore procedure;
- restricted access to Actuator and management APIs.

Do not expose the service directly to the public network when using trusted-header authorization.

## Required environment

### Database

```text
APPROVAL_DB_URL=jdbc:postgresql://database:5432/approval
APPROVAL_DB_USERNAME=approval
APPROVAL_DB_PASSWORD=<secret>
```

Use a secret manager. Do not place database passwords in source control, container images, Helm values committed to Git or application logs.

### Management authorization

Production defaults:

```text
APPROVAL_MANAGEMENT_PERMISSIONS_ENFORCED=true
APPROVAL_MANAGEMENT_PERMISSION_SOURCE=principal
APPROVAL_MANAGEMENT_TRUSTED_HEADER=X-Approval-Trusted-Permissions
```

Supported sources:

- `principal`: authenticated Servlet principal and canonical container roles;
- `trusted-header`: authenticated edge gateway injects the canonical authority list.

When using `trusted-header`, the gateway must strip every inbound copy of the configured header, block direct service access, authenticate the caller and inject tenant/operator context from trusted identity data.

The Spring `local` profile disables management authorization for standalone development. Never use the local profile in production.

### Generic connector

The generic connector is disabled by default. When enabled, configure its base URI, callback URI, key ID and secret through environment variables and restrict outbound network access to the required host.

## Startup and migration

1. Take or verify a recent database backup before a schema-changing release.
2. Start one application instance with the new release and allow Flyway to validate/apply platform migrations.
3. Keep Flowable `database-schema-update` disabled in production. Engine schema changes must be planned and reviewed, not performed implicitly by every instance.
4. Verify the application reaches readiness before adding it to service.
5. Roll out remaining stateless API instances.

Flyway migrations are immutable. Never rewrite a migration already applied to an environment. Add a new migration for every schema change.

## Health and graceful shutdown

The server enables Spring Boot health probes and graceful shutdown.

Check:

- liveness: application process and core runtime are alive;
- readiness: application is ready to receive traffic;
- database connectivity and migration state;
- connector-specific health where enabled.

Before terminating an instance, remove it from load balancing, allow in-flight requests to finish and use the configured graceful shutdown path. Long-running connector dispatch or engine work must remain restart-safe through persisted state, leases and idempotency.

## Metrics

Actuator exposes `health`, `info`, `metrics` and `prometheus`. Restrict these endpoints to the monitoring network.

Management authorization metrics:

### `approval.management.authorization`

Tags:

- `requirement`: `read`, `design`, `publish`, `deploy`, `activate`, `transfer`;
- `outcome`: `allowed`, `denied`, `bypassed`.

Suggested alerts:

- any `bypassed` count in a production environment;
- sustained or sudden increase in `denied`;
- absence of expected `allowed` traffic after a deployment.

### `approval.management.request.duration`

Tags:

- `requirement`;
- `outcome`: `success`, `client_error`, `server_error`.

Suggested alerts:

- rising `server_error` rate;
- p95/p99 latency regression for `publish`, `deploy` or `activate`;
- activation or deployment requests exceeding the operational timeout budget.

Metric labels deliberately exclude tenant, operator, definition key, path, request ID and hashes to prevent unbounded time-series cardinality.

## Logs

Authorization denial log event:

```text
event=APPROVAL_MANAGEMENT_ACCESS_DENIED
```

Fields are bounded and sanitized:

- tenant ID;
- operator/principal ID;
- request ID;
- required canonical authority;
- denial reason.

The log never includes request bodies, query values, full headers, supplied authority sets, Approval DSL, Form data, BPMN or transfer payloads.

Use `requestId` and `traceId` to correlate application logs with transactional audit events. Avoid adding full artifact JSON/XML to diagnostic logging.

## Transactional audit evidence

State-changing operations append audit records in the same application transaction as platform state. Important actions include:

| Action | Expected event |
| --- | --- |
| Publish Form Package | `FORM_PACKAGE_PUBLISHED` |
| Publish Approval Release Package | `APPROVAL_RELEASE_PACKAGE_PUBLISHED` |
| Deployment succeeds | `APPROVAL_RELEASE_DEPLOYED` |
| Deployment fails | `APPROVAL_RELEASE_DEPLOYMENT_FAILED` |
| Activate release | `APPROVAL_RELEASE_ACTIVATED` |
| Roll back release | `APPROVAL_RELEASE_ROLLED_BACK` |
| Import Approval artifact | `APPROVAL_DESIGN_DRAFT_IMPORTED` |

Audit attributes contain bounded identifiers, versions, hashes, preflight identity, warning acknowledgement, deployment target and engine identifiers as appropriate. Full Form values, DSL, BPMN, DMN and transfer payloads are not audit attributes.

A successful operational change without its expected audit event is a release-blocking integrity issue.

## Release procedure

### Publish

1. Save the latest design draft and record its revision.
2. Run publication preflight against the exact draft revision and target versions.
3. Resolve every blocking error.
4. Review and acknowledge the exact current warning-code set.
5. Publish using the returned `preflightHash`.
6. Verify the immutable Definition, compiled artifact, Form Package binding and Release Package hashes.
7. Verify `APPROVAL_RELEASE_PACKAGE_PUBLISHED` audit evidence.

A changed draft, Form Package, target version or warning set invalidates the preflight identity.

### Deploy

1. Select the immutable Release Package.
2. Run deployment preflight for the target.
3. Acknowledge the exact current warning set.
4. Deploy explicitly.
5. Verify deployment status, attempt count, engine deployment ID, engine definition ID and engine version.
6. Verify either `APPROVAL_RELEASE_DEPLOYED` or `APPROVAL_RELEASE_DEPLOYMENT_FAILED`.

Deployment is idempotent for the same immutable Release Package. A failed deployment remains persisted and requires an explicit retry after the cause is corrected.

### Activate

1. Confirm the target Release Package is successfully deployed and its package hash matches the deployment projection.
2. Read the current effective-release revision.
3. Supply an operational reason and expected revision.
4. Activate the target release.
5. Verify effective-release identity and `APPROVAL_RELEASE_ACTIVATED` audit evidence.

New instances use the exact effective Release Package and engine definition identity. Existing instances retain their original version snapshot.

### Roll back

1. Confirm the rollback target was previously effective and remains successfully deployed.
2. Read the current effective-release revision.
3. Record the incident/change reason.
4. Execute rollback with the expected revision.
5. Verify `APPROVAL_RELEASE_ROLLED_BACK` and the new effective-release projection.

Rollback changes only future starts. It does not migrate or rewrite existing instances.

## Incident runbooks

### Management request returns 403

1. Confirm `APPROVAL_MANAGEMENT_PERMISSIONS_ENFORCED` is true.
2. Confirm the endpoint's required capability.
3. In principal mode, verify an authenticated principal exists and the canonical role is mapped.
4. In trusted-header mode, verify the gateway stripped external headers and injected the bounded canonical list.
5. Check `approval.management.authorization{outcome="denied"}` and the denial log event.
6. Do not fix the incident by disabling authorization in production.

### Deployment remains `PENDING`

1. Check whether an application or engine operation was interrupted.
2. Confirm no concurrent deployment attempt is active.
3. Inspect the deployment projection and request/audit correlation IDs.
4. Do not edit the row manually or deploy directly through Flowable.
5. Use an approved recovery procedure or a new explicit retry after state reconciliation.

### Deployment is `FAILED`

1. Inspect bounded `lastErrorCode` and `lastErrorMessage`.
2. Verify engine connectivity and BPMN compatibility.
3. Re-run deployment preflight; do not reuse a stale hash.
4. Correct the cause and retry explicitly.
5. Verify attempt count increments and a new audit event is written.

### Publication preflight conflict

1. Reload the draft and exact Form Package.
2. Verify draft revision, target Definition/Release versions and content hashes.
3. Re-run preflight and review the current warnings.
4. Publish only with the new preflight identity.

### Activation conflict

1. Reload the current effective release and revision.
2. Verify the target deployment is `DEPLOYED` and package hashes match.
3. Check whether another operator activated a release concurrently.
4. Re-evaluate the target and repeat with the current revision.

### Artifact import rejected

1. Confirm the request is a single uncompressed JSON document within the 2 MiB limit.
2. Verify format/version, payload/envelope hashes and safe resource names.
3. Verify the target tenant has the exact local Form Package requested.
4. Correct the source or target and retry with a new idempotency key where appropriate.
5. A rejected import must create no partial draft, version, deployment or activation state.

## Backup and recovery

Back up PostgreSQL using a consistent method that captures:

- platform domain/projection tables;
- immutable DSL/Form/UI/compiler/Release artifacts;
- Flowable engine tables;
- audit, idempotency, Outbox and connector state.

Platform and Flowable tables must be restored from the same recovery point. Restoring only one side can break exact instance/version binding.

After restore:

1. run Flyway validation;
2. verify immutable hashes for a sample of Form and Release Packages;
3. verify current effective releases reference existing successful deployments;
4. verify engine definition IDs referenced by effective and instance snapshots exist;
5. run read-only version-center and preflight checks;
6. do not automatically redeploy or reactivate every release.

Test restore procedures regularly. A backup that has never been restored is not a validated backup.

## Data retention and cleanup

Never delete immutable versions referenced by:

- an effective release;
- an activation-history record;
- a runtime instance version snapshot;
- an audit or deployment projection required by retention policy.

Cleanup jobs must operate on platform-owned tables and respect tenant boundaries. Do not purge Flowable history independently from the platform's instance/audit retention policy.

## Scaling

The API is designed for horizontal scaling with PostgreSQL-backed locks, revision CAS, idempotency and durable projections. Before adding instances:

- confirm all nodes use the same application version and compiler version;
- share the same database and connector configuration;
- keep clocks synchronized;
- ensure only trusted internal networks can reach database, Actuator and connector credentials;
- observe database connection capacity and Flowable async executor behavior.

## Upgrade and rollback of the application

Before an upgrade, follow `docs/COMPATIBILITY.md` and run the full committed-head validation matrix. Preserve the previous application image and database backup.

Application rollback is safe only when the previous binary understands every migration and protocol already written by the newer release. If a migration is not backward compatible, restore or execute the documented forward-fix procedure instead of starting an older binary against the upgraded schema.
