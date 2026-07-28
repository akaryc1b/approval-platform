# M5-E1 — Read-only Operations Visibility Permanent Evidence

## Governance result

- M5-E1: `COMPLETE / PERMANENTLY_VALIDATED`
- Production migration execution: `NOT_AUTHORIZED`
- E1 provides visibility only. It does not provide execute, retry, force, rollback, reconcile, cancel, Kill Switch mutation or other migration command.
- PR #58 remains Open + Draft. Issues #13, #14 and #56 remain Open.
- M6 PRs #67–#70 remain independent and were not modified.

## Validated implementation head

- Head: `9a352f8168a89355f519939cdefdf646d2aadd12`
- Workflow: `Approval Platform Validation`
- Run ID: `30319103973`
- Run number: `#788`
- Result: `success`
- Repository hygiene: success
- Java 21 / Maven / PostgreSQL: success
- Vben TypeScript / production build: success
- UniApp TypeScript / H5 / WeChat: success

## Read-only server boundary

E1 exposes exactly four GET handlers over one tenant-scoped query:

- summary;
- bounded plan list;
- exact plan detail;
- bounded selected-instance evidence list.

The same server-owned read capability protects two client surfaces:

- `/api/approval/management/process-instance-operations` for the management Web application;
- `/api/approval/mobile/process-instance-operations` for authorized Mobile operations users.

Both paths require `MIGRATION_OPERATIONS_READ` with tenant scope. Participant responsibility receives no such capability. The Controller has no request body and no POST, PUT, PATCH or DELETE mapping.

## Persistent evidence and accuracy

`JdbcApprovalMigrationOperationsQuery` reads only durable M5 evidence, including plan, intent, attempt, verification, reconciliation, orchestration, completion and binding-conflict records. It performs no JDBC update and has no Flowable dependency or `ACT_*` access.

The query is deterministic and bounded:

- maximum page size: 200;
- plan ordering: creation time and plan ID;
- selected-instance ordering: sealed sequence number;
- exact tenant filters are applied to summary, list, detail and instance evidence;
- cross-tenant plan detail and instance reads fail closed;
- page metadata must exactly match returned rows and total counts.

A plan without a D8 aggregate revision is not reported as resolved. It exposes zero success/failure aggregate evidence and reports every selected instance as unresolved. Any contradictory pre-aggregation aggregate evidence is rejected.

## Web and Mobile visibility

The Web and Mobile clients use governed transports and GET requests only. Neither client sends command idempotency/reason headers or trusted permission authority.

Both clients preserve evidence completeness:

- the Web plan and instance tables are paginated and expose total counts;
- the Web instance dialog explicitly shows the current evidence range;
- the Mobile plan and instance views show displayed count versus total count;
- Mobile provides bounded load-more operations for remaining evidence;
- Mobile never calls a `/management` endpoint.

No client renders an execute, retry, force-success, rollback, reconcile or cancel action.

## Security and privacy boundary

- Tenant and operator authority are supplied by the established server identity and management-permission chain.
- Clients cannot supply permission evidence, authoritative status, aggregate result, completion result or engine identity.
- Query responses contain bounded identifiers and evidence hashes but do not return raw migration payload JSON, operation reasons, credentials, tokens, variable values, attachments or Flowable internal data.
- Stable error mapping is read-only and redacted.
- No second workflow, scheduler, polling worker, public command route or Flyway migration was added for E1.

## Tests

Maven aggregate:

- tests: `675`
- failures: `0`
- errors: `0`
- skipped: `0`

Focused E1 tests:

- `JdbcApprovalMigrationOperationsQueryIntegrationTest`: `2/2`;
- `ApprovalMigrationOperationsResponsibilityTest`: `2/2`;
- `ApprovalMigrationOperationsEndpointContractTest`: `3/3`;
- total: `7/7`.

Permanent Node governance:

- E1 operations visibility boundary: `5/5`;
- all M5 Node groups: `91/91`.

## Artifact integrity

All downloaded ZIP SHA-256 values exactly matched GitHub artifact digests.

| Artifact | ID | GitHub digest / downloaded ZIP SHA-256 |
| --- | ---: | --- |
| `approval-maven-30319103973` | `8673433962` | `a2be52874b531e88ff7eb87f6d77e4360dfe8fb7f5c41b5dda6d34fb0ffb2bd4` — exact match |
| `approval-vben-30319103973` | `8673327702` | `d920a1d2250f779f2e2d490715f94d9ee647e58543b6300bd502a6f2816ee4b0` — exact match |
| `approval-mobile-30319103973` | `8673320384` | `f8121f9158dd829fa2325becb4f1b197664710c1ae6d11c4826b01c292d34b00` — exact match |
| `approval-hygiene-30319103973` | `8673303753` | `6a3310d84e21abf8b90997b376dd7620a23c8cd6349ba90ec0822d485438e41f` — exact match |

## Retained validation lineage

No failed run was rerun, deleted, hidden or rewritten. Runs cancelled by the workflow concurrency rule remain part of the branch history and were not treated as successful evidence.

- Runs #753 and #755 exposed the old M4 path heuristic after the new read-only visibility route was first added. The E1 route was separated from migration command paths rather than weakening the M4 boundary.
- Runs #765 and #769 exposed an invalid Mobile transport import and the established prohibition on Mobile `/management` calls. The final design uses the governed Mobile transport and a dedicated Mobile read path protected by the same server capability.
- Run #784 exposed a missing final newline in the E1 JDBC integration test.
- Run #785 passed Web, Mobile and Hygiene and exposed missing final newlines in the Controller and endpoint contract test.
- Run #788 is the first final E1 implementation head with all four permanent jobs successful.

## Explicitly blocked

E1 does not provide or authorize:

- execute, retry, force, rollback, reconcile, cancel or Kill Switch mutation endpoints;
- browser or Mobile execution controls;
- automatic migration or automatic `UNKNOWN` retry;
- direct Flowable internal-table access;
- background polling or resident migration workers;
- production execution;
- M5-E2 executable operations;
- M5-F2 production fault exercises;
- M5-G merge readiness;
- Ready, auto-merge, merge or issue closure.

## Next gate

The next authorized slice is M5-F1 fault, security and observability acceptance foundation. It must remain bounded, non-executable and must not authorize production migration execution.
