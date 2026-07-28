# M5-G1 Release Rehearsal and Production Readiness — Permanent Evidence

## Governance result

- M5-G1: `COMPLETE / PERMANENTLY_VALIDATED`
- Rehearsal result: `PASS_IN_ISOLATED_CI`
- Real production migration: `NOT_PERFORMED`
- Production migration execution: `NOT_AUTHORIZED`
- Production credentials/endpoints: `ABSENT`
- Production alerting integration: `NOT_CONFIGURED`
- PR #58 remains Open + Draft pending M5-G2
- Issues #13, #14 and #56 remain Open
- M6 PRs #67–#70 remain independent and unchanged

G1 validates release preparation, safe defaults, schema continuity, read-only Operations, fault/security/observability evidence, operator scenarios, stop-the-line and rollback procedures. It creates no production worker, scheduler, migration dispatch, Canary run, orchestration run, aggregation run or reconciliation run.

## Permanently validated implementation Head

- Head: `144ec0fd0dc0584c5150b28f47d4adae17e2da0d`
- Workflow: `Approval Platform Validation`
- Run ID: `30336903468`
- Run number: `#861`
- Run URL: `https://github.com/akaryc1b/approval-platform/actions/runs/30336903468`
- Conclusion: `success`

All four permanent jobs succeeded:

- Repository hygiene: success
- Java 21 / Maven / PostgreSQL: success
- Vben TypeScript / production build: success
- UniApp TypeScript / H5 / WeChat: success

## Release rehearsal evidence

The accepted runbook contains:

- 18 release preconditions;
- database validation, clean migration and historical upgrade procedures;
- six explicit safe configuration defaults;
- startup no-execution/no-worker/no-scheduler/no-scanner verification;
- deterministic Canary and bounded orchestration parameters;
- E1/E2 API and Web/Mobile read-only verification;
- metric, log and trace redaction verification;
- ten-step UNKNOWN/reconciliation operator procedure;
- stop-the-line, application rollback and incident escalation procedures;
- post-release observation and permanent evidence retention requirements;
- 18 isolated dry-run rehearsal cases;
- 23 auditable production-readiness checklist items;
- 14 operator scenarios.

The rehearsal uses only source inspection, isolated CI, PostgreSQL integration tests, embedded/test Flowable and deterministic fake/test ports. No real production endpoint or data is used.

## Safe default evidence

The base configuration remains:

```text
APPROVAL_MIGRATION_EXECUTION_ENABLED=false
APPROVAL_MIGRATION_WORKER_ENABLED=false
APPROVAL_MIGRATION_ORCHESTRATION_ENABLED=false
APPROVAL_MIGRATION_AGGREGATION_ENABLED=false
APPROVAL_MIGRATION_RECONCILIATION_AUTOMATIC_ENABLED=false
APPROVAL_MIGRATION_KILL_SWITCH_ENABLED=false
```

The one-shot runners fail closed unless their exact required features are explicitly enabled. There is no migration scheduler, polling loop, resident worker or cross-tenant scanner.

## Database rehearsal evidence

- M5-owned migration files remain continuous V33–V48;
- no V49 exists;
- fresh database migration reaches V48;
- historical upgrade cases cover V1, V13, V23, V31 and V36–V47;
- the V27 heavy fixture upgrades 5,000 instances/tasks;
- upgrade assertions preserve platform evidence and leave all M5 execution tables empty;
- no direct Flowable `ACT_*` access exists;
- no Flyway downgrade or repair is used.

## Operations and operator evidence

- exactly seven E1/E2 GET handlers remain under management/mobile read-only prefixes;
- no POST, PUT, PATCH, DELETE or request body exists;
- tenant resource scope and dedicated read permission remain mandatory;
- Web and Mobile use governed GET transports only;
- no diagnostic data is written to browser/session/Mobile storage;
- no execution, retry, rollback, force, reconciliation-start, Kill Switch mutation or feature-flag mutation control exists;
- UNKNOWN remains durable and manual-governed;
- reconciliation remains one bounded read-only observation;
- rollback preserves database evidence and uses no schema downgrade.

## Test statistics

Maven reactor aggregate:

- tests: `704`
- failures: `0`
- errors: `0`
- skipped: `0`

Permanent M5 Node boundaries:

- groups: `14`
- tests: `131`
- pass: `131`
- fail: `0`
- G1 production-readiness boundary: `6 / 6`

Web and Mobile typecheck/build jobs succeeded.

## Artifact integrity

Every artifact ZIP was downloaded and hashed locally. Each local SHA-256 exactly matched the GitHub artifact digest.

| Artifact | Artifact ID | GitHub digest / downloaded ZIP SHA-256 | Result |
| --- | ---: | --- | --- |
| `approval-maven-30336903468` | `8679679396` | `61832d8a3d676057a7afe46d96e600678f0a5c352fbdb02a9a6f333a37019936` | exact match |
| `approval-vben-30336903468` | `8679524082` | `8acc1926ae9e4a7a95b9b524f842f541c06f9fdef680ea6774d849a63af2ab75` | exact match |
| `approval-mobile-30336903468` | `8679503872` | `91569644e87f95fb0e6482d8ca7c6b58a51be629fc84f0952bce8548044b25df` | exact match |
| `approval-hygiene-30336903468` | `8679485129` | `e7a9c857508add925196066039afd2616df01cddee5fe285659af4874052914f` | exact match |

## Retained failed evidence

No failed or cancelled Run was hidden, deleted or treated as successful evidence.

- Run `30336546995` / #859 failed because the first G1 Node test used an invalid template-literal escape while checking checklist markers.
- Run `30336700204` / #860 failed because the static scan incorrectly assumed V1–V32 lived in the M5-owned migration directory. The corrected test statically checks M5-owned V33–V48 and relies on the real fresh/historical Flyway integration suite for V1–V48 continuity.

Both corrections strengthened alignment with actual repository structure without lowering any production-readiness requirement.

## Formal records

- `docs/M5_G1_RELEASE_REHEARSAL_AND_PRODUCTION_READINESS.md`
- `scripts/tests/m5-g1-production-readiness-boundary.test.mjs`
- this permanent evidence file

## Stop condition

M5-G1 is complete and permanently validated. PR #58 must remain Draft until G2 final regression, permanent acceptance evidence, PR/Issue closure and pre-merge checks complete. Production migration execution remains `NOT_AUTHORIZED`.
