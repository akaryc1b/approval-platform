# Approval Platform Quick Start Candidate

```text
QUICK_START_STATUS=BASELINE_NOT_YET_10_MINUTE_VERIFIED
BACKEND_LOCAL_START_STATUS=VERIFIED_IN_EPHEMERAL_CI
DETERMINISTIC_DEMO_SEED_STATUS=IMPLEMENTED_LOCAL_OPT_IN
DETERMINISTIC_DEMO_SEED_IMPLEMENTED
PC_CLIENT_SCENARIO_STATUS=NOT_YET_VERIFIED
H5_CLIENT_SCENARIO_STATUS=NOT_YET_VERIFIED
WECHAT_MINI_PROGRAM_SCENARIO_STATUS=NOT_YET_VERIFIED
PURCHASE_PAYMENT_GOLDEN_PATH_STATUS=RUNTIME_START_SLICE_VERIFIED_FULL_E2E_NOT_EXECUTED
```

This is the source-based candidate path for the Demo and Guides work in Issue #107. It deliberately does **not** claim that a new user can start a complete product demo within 10 minutes.

The repository now provides PostgreSQL/Redis Compose infrastructure, an executable Spring Boot application, a local profile, an explicit deterministic purchase-payment seed, and PC/H5/WeChat development or build commands. Permanent Maven validation starts the real backend against ephemeral PostgreSQL and verifies the seed, health, instance read and manager pending-task read. It does not time a clean-machine setup or execute the full approval workflow.

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
node scripts/product-readiness/demo-preflight.mjs
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

## 2. Start local infrastructure

```bash
docker compose -f deploy/compose/docker-compose.yml up -d postgres redis
docker compose -f deploy/compose/docker-compose.yml ps
```

Do not use production credentials in this path. Checked-in values are local demonstration defaults only.

## 3. Load local database variables

For Bash or Zsh:

```bash
set -a
. ./.env.example
set +a
```

The example currently resolves to the local PostgreSQL service on port `5432`.

## 4. Build the Maven reactor

```bash
mvn -B -ntp -DskipTests install
```

Skipping tests here is only startup preparation; it does not replace the permanent workflow.

## 5. Start the executable backend and deterministic local seed

The seed is false by default and requires both the `local` profile and an explicit switch:

```bash
APPROVAL_DEMO_PURCHASE_PAYMENT_ENABLED=true \
mvn -B -ntp -f apps/server/pom.xml spring-boot:run \
  -Dspring-boot.run.profiles=local
```

The base configuration keeps Flowable schema updates disabled. The explicit `local` profile permits local schema bootstrap, uses local-header identity, retains management-permission enforcement, and enables the seed only when the environment switch is true. Never activate this profile or seed as a production default.

Successful seed startup logs:

```text
PURCHASE_PAYMENT_DEMO_SEED_APPLIED
```

The marker includes bounded tenant, business, instance, task and attachment identifiers; it contains no credential or customer data.

## 6. Verify bounded backend health

In another terminal:

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
```

The backend-start outcome requires an HTTP success response containing:

```json
{"status":"UP"}
```

The repository's permanent integration test already verifies this outcome against PostgreSQL Testcontainers, so the narrow marker is now valid:

```text
BACKEND_LOCAL_START_VERIFIED
```

This still does not prove login, task approval, Connector delivery, browser compatibility or cross-client consistency.

## 7. Inspect the deterministic runtime slice

Use the `instanceId` printed by the seed marker or health details:

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

The verified boundary stops here. Do not label it `PURCHASE_APPROVAL_E2E_PASSED`.

## 8. Existing client development entrypoints

These commands expose current development/build entrypoints. They are not yet a unified product demo.

The repository currently has no root `pnpm-lock.yaml`, so this path must not begin with `pnpm install --frozen-lockfile` at the repository root. Retained frontend bootstrap commands own their install semantics.

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

## 9. Stop or reset local infrastructure

Stop while preserving the PostgreSQL volume:

```bash
docker compose -f deploy/compose/docker-compose.yml down
```

Destroy the local demo database only when an explicit clean reset is intended:

```bash
docker compose -f deploy/compose/docker-compose.yml down -v
```

Reset is performed through disposable local infrastructure, not by editing platform or Flowable tables.

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

These gaps must remain visible instead of being replaced by a build or first-task success claim.

## Current non-claims

```text
SHARED_DEMO_ENVIRONMENT_SEED_NOT_APPLIED
PURCHASE_APPROVAL_E2E_NOT_EXECUTED
PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED
PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED
```

The static `pnpm demo:scenario:check` command still prints `DETERMINISTIC_DEMO_SEED_NOT_APPLIED` because that command itself is read-only; it is not the runtime seed.
