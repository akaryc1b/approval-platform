# Approval Platform Quick Start Candidate

```text
QUICK_START_STATUS=BASELINE_NOT_YET_10_MINUTE_VERIFIED
DEMO_BACKEND_ONE_COMMAND_STATUS=IMPLEMENTED_NOT_CLEAN_MACHINE_TIMED
BACKEND_LOCAL_START_STATUS=VERIFIED_IN_EPHEMERAL_CI
DETERMINISTIC_DEMO_SEED_STATUS=IMPLEMENTED_LOCAL_OPT_IN
DETERMINISTIC_DEMO_SEED_IMPLEMENTED
PC_CLIENT_SCENARIO_STATUS=NOT_YET_VERIFIED
H5_CLIENT_SCENARIO_STATUS=NOT_YET_VERIFIED
WECHAT_MINI_PROGRAM_SCENARIO_STATUS=NOT_YET_VERIFIED
PURCHASE_PAYMENT_GOLDEN_PATH_STATUS=RUNTIME_START_SLICE_VERIFIED_FULL_E2E_NOT_EXECUTED
```

This is the source-based candidate path for the Demo and Guides work in Issue #107. It deliberately does **not** claim that a new user can start a complete product demo within 10 minutes.

The repository now provides a single non-destructive backend entry command. It runs the workstation preflight, starts isolated PostgreSQL and Redis containers, waits for both services, builds the Maven reactor, starts the real Spring Boot application with the explicit local seed, waits for Actuator `UP` and the deterministic seed marker, and then remains attached to the backend process.

The orchestration command is permanently checked for exact command order, local-only configuration, shell-free process invocation, explicit data-loss confirmation and honest non-claims. The underlying backend/seed path is separately verified by the permanent PostgreSQL integration test. The complete command has not yet been timed from a clean unfamiliar-user environment and therefore is not `QUICK_START_10_MINUTES_PASSED`.

## Supported candidate environment

Use a clean macOS or Linux environment with:

- Java 21;
- Maven 3.9.6 or newer;
- Node 22.18+ within the 22.x line, or Node 24.x;
- pnpm 10 (the repository currently declares pnpm 10.33.4);
- Docker Engine or Docker Desktop with Docker Compose v2;
- enough local memory and disk for Maven, retained upstream frontends, PostgreSQL and Redis.

WeChat runtime verification additionally requires supported WeChat Developer Tools and a separately documented test identity/app configuration. The repository does not claim that setup as complete.

## 1. Run the read-only preflight

From the repository root:

```bash
pnpm demo:preflight
```

For machine-readable output:

```bash
node scripts/product-readiness/demo-preflight.mjs --json
```

Repository contracts can be checked separately without implying workstation readiness:

```bash
node scripts/product-readiness/demo-preflight.mjs --repository-only --json
pnpm demo:scenario:check
pnpm demo:seed:check
```

The restricted preflight reports `DEMO_REPOSITORY_CONTRACT_PASSED`; the full preflight may report `DEMO_PREFLIGHT_PASSED`. The preflight and Node checks do not start containers, mutate a database, call an external Provider or prove a user scenario.

## 2. Inspect the exact startup plan without executing it

```bash
pnpm demo:backend:plan
```

The JSON plan is read-only and must report:

```text
destructive=false
QUICK_START_10_MINUTES_NOT_EXECUTED
PURCHASE_APPROVAL_E2E_NOT_EXECUTED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED
CROSS_CLIENT_RUNTIME_NOT_EXECUTED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
```

It also shows the exact preflight, Compose, Maven, health and seed sequence used by the real command.

## 3. Start the real local backend and deterministic seed with one command

```bash
pnpm demo:backend:start
```

The command performs this bounded sequence:

```text
read-only workstation preflight
-> isolated Compose project approval-platform-demo
-> PostgreSQL 16 and Redis 7.4 readiness
-> mvn -B -ntp -DskipTests install
-> real Spring Boot server with the local profile
-> explicit APPROVAL_DEMO_PURCHASE_PAYMENT_ENABLED=true
-> Actuator HTTP status UP
-> PURCHASE_PAYMENT_DEMO_SEED_APPLIED
```

The command reads only the three local database variables from `.env.example` and deliberately overrides matching database variables inherited from the shell. This prevents an accidental local demo run from reusing an unrelated database target. The seed remains false in the checked-in environment example and is enabled only for the child backend process.

The command stays attached so the backend log remains visible. A successful run prints:

```text
DEMO_BACKEND_ONE_COMMAND_STARTED
BACKEND_LOCAL_START_VERIFIED
PURCHASE_PAYMENT_DEMO_SEED_APPLIED ... instanceId=...
QUICK_START_10_MINUTES_NOT_EXECUTED
PURCHASE_APPROVAL_E2E_NOT_EXECUTED
```

Press `Ctrl-C` to stop the attached backend process. PostgreSQL and Redis remain running until the explicit stop command in step 6.

## 4. Verify and inspect the deterministic runtime slice

In another terminal:

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
```

The backend-start outcome requires an HTTP success response containing:

```json
{"status":"UP"}
```

Copy the `instanceId` from the `PURCHASE_PAYMENT_DEMO_SEED_APPLIED` log line, then inspect the seeded request:

```bash
curl -fsS \
  -H 'X-Tenant-Id: demo-purchase-payment' \
  -H 'X-Operator-Id: demo-admin' \
  -H 'X-Request-Id: demo-inspect-instance-v1' \
  -H 'X-Trace-Id: demo-inspect-instance-v1' \
  http://127.0.0.1:8080/api/approval/instances/INSTANCE_ID
```

Inspect the first assigned task:

```bash
curl -fsS \
  -H 'X-Tenant-Id: demo-purchase-payment' \
  -H 'X-Operator-Id: demo-manager' \
  -H 'X-Request-Id: demo-inspect-manager-task-v1' \
  -H 'X-Trace-Id: demo-inspect-manager-task-v1' \
  http://127.0.0.1:8080/api/approval/tasks/pending
```

The verified boundary stops with one pending `managerApproval` task assigned to `demo-manager`. Do not label it `PURCHASE_APPROVAL_E2E_PASSED`.

## 5. Existing client development entrypoints

These commands expose current development/build entrypoints. They are not yet a unified product demo.

The repository currently has no root `pnpm-lock.yaml`, so this path must not begin with a root frozen-lockfile install. Retained frontend bootstrap commands own their install semantics.

### PC

```bash
pnpm web:install
pnpm web:dev
```

### H5

```bash
pnpm mobile:install
pnpm mobile:dev:h5
```

### WeChat Mini Program build

```bash
pnpm mobile:build:weixin
```

A successful development server or build is not `CROSS_CLIENT_SCENARIO_PASSED`. PC, H5 and WeChat must still operate on the same seeded tenant, request, tasks, audit identifiers and final state.

## 6. Stop or explicitly reset local infrastructure

After stopping the attached backend with `Ctrl-C`, stop PostgreSQL and Redis while preserving the PostgreSQL volume:

```bash
pnpm demo:backend:stop
```

Destroy the disposable local demo volume only when an explicit clean reset is intended:

```bash
node scripts/product-readiness/demo-backend.mjs reset --confirm-local-data-loss
```

Running `reset` without the confirmation flag exits before any Docker command. Reset is performed through disposable local infrastructure, not by editing platform or Flowable tables.

## Manual expansion for troubleshooting

The one-command launcher owns the normal candidate path. The equivalent lower-level operations are retained only for diagnosis:

```bash
docker compose --project-name approval-platform-demo \
  -f deploy/compose/docker-compose.yml up -d postgres redis
mvn -B -ntp -DskipTests install
APPROVAL_DEMO_PURCHASE_PAYMENT_ENABLED=true \
mvn -B -ntp -f apps/server/pom.xml spring-boot:run \
  -Dspring-boot.run.profiles=local
```

The base configuration keeps Flowable schema updates disabled. The explicit `local` profile permits local schema bootstrap, uses local-header identity, retains management-permission enforcement, and enables the seed only when the environment switch is true. Never activate this profile or seed as a production default.

## Timed evidence template

A future clean-machine run must retain at least:

```text
COMMIT_SHA=
STARTED_AT_UTC=
COMPLETED_AT_UTC=
ELAPSED_SECONDS=
OS_AND_VERSION=
CPU=
MEMORY=
JAVA_VERSION=
MAVEN_VERSION=
NODE_VERSION=
PNPM_VERSION=
DOCKER_VERSION=
COMPOSE_VERSION=
BACKEND_HEALTH_RESULT=
DEMO_SEED_RESULT=
PC_ENTRY_RESULT=
DEMO_USER_RESULT=
PURCHASE_REQUEST_ID=
```

The 10-minute outcome cannot pass until an unfamiliar user can start the documented environment, open the deterministic purchase-payment request and reach the published outcome within 600 seconds.

## Known blockers before a real 10-minute claim

- no prebuilt, versioned demo release bundle or image set is documented here;
- no verified PC/H5/WeChat authentication and API-environment walkthrough is delivered here;
- no retained screenshot set or unedited timed recording exists here;
- no complete purchase-payment approval sequence, attachment-binding proof or Connector outage/recovery evidence exists here;
- no shared demo environment has been seeded.

These gaps must remain visible instead of being replaced by a build, one-command launcher or first-task success claim.

## Current non-claims

```text
SHARED_DEMO_ENVIRONMENT_SEED_NOT_APPLIED
QUICK_START_10_MINUTES_NOT_EXECUTED
PURCHASE_APPROVAL_E2E_NOT_EXECUTED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED
CROSS_CLIENT_RUNTIME_NOT_EXECUTED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
```

The static `pnpm demo:scenario:check` command still prints `DETERMINISTIC_DEMO_SEED_NOT_APPLIED` because that command itself is read-only; it is not the runtime seed.
