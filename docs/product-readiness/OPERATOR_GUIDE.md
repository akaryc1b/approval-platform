# Local Demo Operator Guide

## Normal lifecycle

```bash
pnpm demo:quickstart:plan
pnpm demo:quickstart
```

The first command is read-only. The second command owns the whole local runtime and must be left attached until the user has finished inspecting the demo.

## Owned resources

```text
Compose project: approval-platform-demo
PostgreSQL port: 5432 inside Compose
Redis port: 6379 inside Compose
backend port: 8080
PC port: 5777
H5 port: 9000
evidence root: .runtime/quick-start/
```

The Quick Start removes its containers, network and disposable PostgreSQL volume during cleanup. It also verifies that ports 5432, 5777, 6379, 8080 and 9000 are available before reporting cleanup success.

## Preflight

```bash
pnpm demo:preflight
```

The preflight checks the repository contracts and active Java, Maven, Node, pnpm, Docker and Compose versions. Resolve failures before retrying; do not bypass the check.

## Runtime observation

The command prints the ready duration, URLs, tenant, business key, actors and evidence directory. Useful read-only checks are:

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
docker compose --project-name approval-platform-demo \
  -f deploy/compose/docker-compose.yml ps
```

Per-run logs are retained as `backend.log`, `pc.log` and `h5.log` inside the evidence directory. Browser trace and screenshots are also retained.

## Stop

Use `Ctrl-C` in the owning terminal. Do not separately kill Docker or Java first. The orchestrator sends a bounded graceful termination, escalates when necessary, removes disposable infrastructure, checks port release and writes `cleanup-evidence.json`.

## Recovery from an interrupted terminal

Confirm that no other local demo is using the same Compose project, then run:

```bash
node scripts/product-readiness/demo-backend.mjs reset --confirm-local-data-loss
```

If a port remains occupied, identify the unrelated owner rather than killing an arbitrary process. The Quick Start does not use unbounded waits or silently ignore cleanup errors.

## Acceptance evidence

A valid clean run must contain source identity, environment, health, PC/H5 browser screenshots, startup timing and cleanup evidence. Two run IDs are required on the same commit and tree. Any failed run resets the consecutive-run ledger.

The permanent CI form is path-scoped through the existing `Approval Platform Validation` Workflow. No additional automatic Workflow is authorized.

## Operational non-claims

This local procedure is not a production installation, backup/restore exercise, disaster-recovery rehearsal, capacity test, RPO/RTO measurement, MySQL 8.4 validation or Release artifact.
