# M5-E2 Advanced Read-Only Operations Diagnostics — Permanent Evidence

## Scope and authorization

M5-E2 adds tenant-scoped, authorization-protected, GET-only diagnostics over platform-owned migration evidence. It does not authorize production execution, issue migration commands, invoke Flowable migration, start workers, start reconciliation, modify kill switches, or introduce a scheduler.

Production execution remains `NOT_AUTHORIZED`.

## Accepted implementation head

- Branch: `agent/m5-governed-process-instance-migration`
- Accepted implementation head: `7a33a9512f219cb6a4ea73c6d66da60f4d814b76`
- Base observed before implementation: `main@d769722cf7dd5418739a91ad4c45ca1a1c147502`
- Pull Request: #58, Open / Draft at acceptance time
- Flyway: V1–V48; no V49 added

## Delivered read-only contract

The existing Web and Mobile operations prefixes now expose:

1. `GET /plans/{planId}/diagnostics`
2. `GET /plans/{planId}/diagnostics/instances`
3. `GET /plans/{planId}/instances/{instanceId}/diagnostics`

The list contract is bounded by:

- 1-based page;
- `pageSize` 1–100;
- page 1–10000;
- fixed sort enum;
- closed attempt-status, failure-class and reconciliation-state enums;
- explicit-offset ISO-8601 timestamps;
- maximum 31-day time range;
- duplicate, unknown, blank and overlong query-parameter rejection;
- SQL ordering selected only through a fixed server-side switch.

The query implementation reads only platform tables prefixed `ap_`. It does not access Flowable `ACT_*` tables and does not call a migration service. Lease and fencing owners are returned only as irreversible truncated SHA-256 references.

## Web and Mobile acceptance

- Web adds a dedicated diagnostics view with bounded filtering, state distribution, canary/orchestration boundaries, kill-switch observation, aggregation/completion evidence and stable instance timelines.
- Mobile uses small-screen cards and bounded previous/next paging rather than compressing a desktop table.
- Neither client contains migration execution, retry, reconciliation-start, kill-switch mutation, feature-flag mutation or bulk-command controls.
- E1 API models and endpoints remain available, preserving backward compatibility.

## Permanent validation

- Workflow: `.github/workflows/approval-platform-validation.yml`
- Run ID: `30332557323`
- Run number: `819`
- Head: `7a33a9512f219cb6a4ea73c6d66da60f4d814b76`
- Result: `success`

Jobs:

| Job | Result |
| --- | --- |
| Repository hygiene | success |
| Java 21 / Maven / PostgreSQL | success |
| Vben TypeScript / production build | success |
| UniApp TypeScript / H5 / WeChat | success |

Validation totals:

- Maven tests: 688
- Maven failures: 0
- Maven errors: 0
- Maven skipped: 0
- M5 Node boundary tests: 123
- M5 Node failures: 0
- M5 Node skipped: 0
- Web typecheck/build: success
- Mobile typecheck/H5/WeChat builds: success

## Artifact integrity

The GitHub artifact digest and locally calculated SHA-256 matched for every retained artifact:

| Artifact ID | Name | SHA-256 |
| --- | --- | --- |
| `8678046933` | `approval-maven-30332557323` | `614dc44ca5a03ccb3d4f1988326a8e1a8ec766fe0c6a13f15d83170a75b4f679` |
| `8677934228` | `approval-vben-30332557323` | `1aa8b9c594c277b20ba5f02764d37a929f272ca485fc21bcf0c53a5db0f693cc` |
| `8677917139` | `approval-mobile-30332557323` | `0e97ed543da31b3b9cb666849a062d2f4890b1425453e8995eed580197904986` |
| `8677902853` | `approval-hygiene-30332557323` | `617b3bcbe5778dc82fee4129cc5cb1fcd535d673b77ada3c55eacd6bcbbb6037` |

## Preserved failure evidence

Two non-success validation runs were retained and were not represented as acceptance evidence:

1. Run `30331707644` / #814: repository-hygiene failure caused by the E1 static test matching the read-only enum `RETRYABLE_FAILURE` as though it were a retry command. The scan was corrected to target actual command methods, command paths and non-GET methods while retaining the command prohibition.
2. Run `30331891071` / #815: Maven failure because PostgreSQL JDBC could not infer a SQL type for a directly bound `Instant`. The request contract now retains explicit `OffsetDateTime`, and the PostgreSQL integration test verifies the bounded time filter.

No test was removed, skipped or weakened to obtain the successful run.

## Safety invariants

At M5-E2 acceptance:

- execution default remains disabled;
- worker default remains disabled;
- orchestration default remains disabled;
- automatic reconciliation default remains disabled;
- UNKNOWN remains durable and is not automatically retried;
- diagnostics do not write state or invoke engine/worker/reconciliation;
- tenant and authorization boundaries are mandatory;
- metrics retain closed low-cardinality labels;
- no V49 exists;
- no second permanent automatic workflow exists;
- no M6 branch, PR, protocol or file was modified;
- production execution remains `NOT_AUTHORIZED`.
