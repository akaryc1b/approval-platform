# Approval Platform Quick Start Candidate

```text
QUICK_START_STATUS=BASELINE_NOT_YET_10_MINUTE_VERIFIED
BACKEND_LOCAL_START_STATUS=DOCUMENTED_NOT_YET_TIMED
PC_CLIENT_SCENARIO_STATUS=NOT_YET_VERIFIED
H5_CLIENT_SCENARIO_STATUS=NOT_YET_VERIFIED
WECHAT_MINI_PROGRAM_SCENARIO_STATUS=NOT_YET_VERIFIED
PURCHASE_PAYMENT_GOLDEN_PATH_STATUS=NOT_YET_VERIFIED
```

This is a source-based candidate path for the Demo and Guides work in Issue #107. It deliberately does **not** claim that a new user can already start a complete product demo within 10 minutes.

The current repository provides PostgreSQL/Redis Compose infrastructure, an executable Spring Boot application, a local profile, and PC/H5/WeChat development or build commands. It does not yet provide a versioned release bundle, deterministic demo identities/data, or a verified cross-client purchase-payment walkthrough.

## Supported candidate environment

Use a clean macOS or Linux environment with:

- Java 21;
- Maven 3.9.6 or newer;
- Node 22.18+ within the 22.x line, or Node 24.x;
- pnpm 10 (the repository currently declares pnpm 10.33.4);
- Docker Engine or Docker Desktop with Docker Compose v2;
- enough local memory and disk for Maven, the retained upstream frontends, PostgreSQL and Redis.

WeChat runtime verification additionally requires the supported WeChat Developer Tools and a separately documented test identity/app configuration. The repository does not yet claim that setup as complete.

## 1. Run the read-only preflight

From the repository root:

```bash
node scripts/product-readiness/demo-preflight.mjs
```

For machine-readable output:

```bash
node scripts/product-readiness/demo-preflight.mjs --json
```

The preflight checks repository contracts and local tool versions. It does not start containers, mutate a database, call an external Provider, or prove a user scenario.

Expected terminal marker after all checks pass:

```text
DEMO_PREFLIGHT_PASSED
QUICK_START_10_MINUTES_NOT_EXECUTED
```

## 2. Start local infrastructure

The following uses only the repository's local Compose baseline:

```bash
docker compose -f deploy/compose/docker-compose.yml up -d postgres redis
docker compose -f deploy/compose/docker-compose.yml ps
```

Do not use production credentials in this path. The checked-in values are local demonstration defaults only.

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

This command is intentionally separate from formal acceptance. Skipping test execution here is for startup preparation only; it is not a substitute for the permanent validation workflow.

## 5. Start the executable backend with the explicit local profile

In the same shell that loaded the database variables:

```bash
mvn -B -ntp -f apps/server/pom.xml spring-boot:run \
  -Dspring-boot.run.profiles=local
```

The base configuration keeps Flowable schema updates disabled. The explicit `local` profile permits local schema bootstrap and uses local-header identity while retaining management-permission enforcement. Never activate this profile as a production default.

## 6. Verify bounded backend health

In another terminal:

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
```

The candidate backend-start outcome requires an HTTP success response containing:

```json
{"status":"UP"}
```

Only after this is reproduced and retained against an exact commit may the narrower marker `BACKEND_LOCAL_START_VERIFIED` be used. This still does not prove login, form submission, approval, Connector delivery or cross-client consistency.

## 7. Existing client development entrypoints

These commands expose current development/build entrypoints. They are not yet a unified product demo.

### PC

```bash
pnpm install --frozen-lockfile
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

A successful development server or build is not `CROSS_CLIENT_SCENARIO_PASSED`. The Golden Path work must still bind PC, H5 and WeChat to the same tenant, purchase request, tasks, audit identifiers and final state.

## 8. Stop or reset local infrastructure

Stop while preserving the PostgreSQL volume:

```bash
docker compose -f deploy/compose/docker-compose.yml down
```

Destroy the local demo database only when an explicit clean reset is intended:

```bash
docker compose -f deploy/compose/docker-compose.yml down -v
```

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
PC_ENTRY_RESULT=
DEMO_USER_RESULT=
PURCHASE_REQUEST_ID=
```

The 10-minute outcome cannot pass until an unfamiliar user can start the documented release/demo environment, sign in with published demo identities, and open the preloaded purchase-payment scenario within 600 seconds.

## Known blockers before a real 10-minute claim

- no prebuilt, versioned demo release bundle or image set is documented here;
- no deterministic demo tenant, ordinary user, approvers, administrator and purchase-payment seed are delivered here;
- no verified PC/H5/WeChat authentication and API-environment walkthrough is delivered here;
- no retained screenshot set or unedited timed recording exists here;
- no complete purchase-payment golden path or Connector outage/recovery evidence exists here.

These gaps must remain visible instead of being replaced by a build-success claim.
