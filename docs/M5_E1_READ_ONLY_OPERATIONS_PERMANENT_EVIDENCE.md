# M5-E1 Read-Only Operations Visibility Permanent Evidence

M5-E1 implementation status: `COMPLETE_CANDIDATE / PERMANENTLY_VALIDATED`

M5 overall remains `IN_PROGRESS`. M5-E2 executable commands, M5-F2 production-grade exercises,
M5-G merge readiness and production migration execution remain blocked.

PR #58 remains Open + Draft. Issues #13, #14 and #56 remain Open. M6 PRs #67 through #70 remain
independent and were not modified by this work.

## Scope

M5-E1 adds tenant-scoped, read-only Operations visibility over the immutable D1 through D8 migration
evidence.

The accepted surfaces are:

- tenant operations summary;
- bounded migration-plan list;
- one exact plan detail;
- bounded canonical selected-instance evidence list;
- a read-only Web management view;
- a read-only Mobile operations view.

M5-E1 does not:

- consume or authorize a migration plan;
- create, claim, renew or take over an Attempt;
- call Flowable;
- dispatch, retry or redispatch migration;
- run verification or reconciliation;
- mutate runtime binding or approval projection;
- append D1-D8 execution evidence;
- activate or bypass the Kill Switch;
- provide rollback, compensation, force-success or cancellation semantics;
- authorize production execution.

## Server API

`ApprovalMigrationOperationsController` exposes exactly four `GET` handlers through two explicit
read-only prefixes:

```text
/api/approval/management/process-instance-operations
/api/approval/mobile/process-instance-operations
```

Handlers:

```text
GET /summary
GET /plans
GET /plans/{planId}
GET /plans/{planId}/instances
```

There is no `POST`, `PUT`, `PATCH`, `DELETE`, request body or executable operation in the controller.
Both prefixes use the same server-side authorization, tenant isolation, query contract and error
handling.

The caller can provide only:

- authenticated tenant context;
- exact plan identity for detail/instance reads;
- closed plan/aggregate filters;
- a bounded page limit and offset.

The caller cannot provide or override:

- Attempt status or revision;
- engine outcome;
- verification or reconciliation classification;
- exact completion or conflict state;
- aggregate status or terminal outcome;
- selected, succeeded, failed or unresolved counts;
- Canary, orchestration or Kill Switch state;
- evidence hashes or timestamps.

## Dedicated read-only capability

E1 uses one dedicated requirement:

```text
approval.management.migration.operations.read
```

The capability is not reason-required and grants no write authority.

The enterprise responsibility matrix permits tenant-scoped visibility to:

- Platform Admin;
- Tenant Admin;
- Process Publisher;
- Auditor;
- Operations.

It does not grant the capability to:

- Process Designer;
- Department Approval Admin;
- Data Archive Admin;
- Connector Admin;
- Participant.

Cross-tenant resolution fails closed. Browser and Mobile clients cannot forge trusted management
permission headers.

## Bounded durable query model

`JdbcApprovalMigrationOperationsQuery` reads only platform-owned durable evidence from V33 through
V48.

Plan visibility includes stable identity, release versions, sealed selected count, plan/intent state,
latest aggregate revision/state, exact-success count, terminal-failure count, unresolved count,
Canary/orchestration/pause/Kill Switch state and completion time.

Plan detail adds bounded hash references and request/audit correlation. It does not return payload JSON,
operation reason text, raw Flowable state or internal exception material.

Instance visibility follows the sealed plan sequence and includes:

- sequence and approval-instance identity;
- Canary membership;
- latest Attempt identity/status/revision/outcome;
- exact verification classification;
- reconciliation status/disposition;
- exact completion or binding-conflict indicator;
- bounded evidence-hash references and latest evidence time.

The query boundary:

- always requires tenant identity;
- uses an exact plan id for plan/detail instance reads;
- limits every page to 1 through 200 rows;
- rejects negative offsets;
- orders plans by `created_at desc, plan_id desc`;
- orders instances by sealed `sequence_no`;
- returns not-found without exposing another tenant's resource;
- contains no JDBC update, insert or delete;
- contains no Flowable API or `ACT_*` access.

Before the first D8 aggregate exists, exact success and terminal failure are zero and unresolved count
is the sealed selected-instance count. A plan cannot expose partial aggregate evidence without an
aggregate revision.

## Web visibility

The Vben view is available at:

```text
/approval/process-instance-operations
```

It uses the host-side `approval:ops:view` route hint and the governed approval transport.

The page shows:

- tenant summary counts;
- plan status, aggregate status and revision;
- exact success, terminal failure and unresolved counts;
- pause reason and latest aggregation time;
- bounded plan hash/audit references;
- canonical per-instance Attempt, verification, reconciliation and completion evidence.

The page explicitly states that it has no execute, retry, rollback, force-success or reconciliation
command. It sends no idempotency or command headers and uses only GET requests.

## Mobile visibility

The UniApp view uses the dedicated Mobile prefix:

```text
/approval/mobile/process-instance-operations
```

The Mobile overlay does not reference tenant management endpoints. It uses the governed
`mobileApprovalRequest` transport and provides summary, plan and canonical instance evidence only.

The page is linked from the profile surface and explicitly states that it is read-only. It has no
management mutation, trusted-permission header, direct `uni.request` call, command header or execution
control.

## Flyway and workflow ownership

M5-E1 adds no Flyway migration. The continuous migration sequence remains V1 through V48, with M5
owning V33 through V48. No V49 is allocated.

The repository still has exactly one automatic PR/main workflow:

```text
.github/workflows/approval-platform-validation.yml
```

No E1-specific workflow, temporary patch workflow, scheduler or resident worker was added.

## Focused validation

Committed-head implementation validation:

- workflow: `Approval Platform Validation`;
- Run ID: `30319103973`;
- run number: `#788`;
- Head: `9a352f8168a89355f519939cdefdf646d2aadd12`;
- result: `success`;
- all four jobs succeeded.

Maven evidence:

- aggregate: `675 / 0 / 0 / 0`;
- Approval Domain: `61 / 0 / 0 / 0`;
- Approval Engine Flowable: `48 / 0 / 0 / 0`;
- Approval Application: `161 / 0 / 0 / 0`;
- Approval Persistence JDBC: `279 / 0 / 0 / 0`;
- Approval Server: `69 / 0 / 0 / 0`;
- E1 JDBC tenant/filter/paging scenarios: `2 / 2`;
- E1 responsibility scenarios: `2 / 2`;
- E1 endpoint contract scenarios: `3 / 3`.

Node/client evidence:

- all permanent M5 Node boundary groups: `91 / 91`;
- E1 permanent architecture/governance boundary: `5 / 5`;
- Web client security boundary: `10 / 10`;
- Repository hygiene: success.

Client build evidence:

- Vben type-check: success;
- Vben production build: success;
- UniApp type-check: success;
- UniApp H5 build: success;
- WeChat Mini Program build: success.

## Artifact integrity

All four Run #788 Artifact ZIPs were downloaded. Their local SHA-256 values exactly matched GitHub's
recorded digests:

| Artifact | ID | SHA-256 |
| --- | ---: | --- |
| `approval-maven-30319103973` | `8673433962` | `a2be52874b531e88ff7eb87f6d77e4360dfe8fb7f5c41b5dda6d34fb0ffb2bd4` |
| `approval-vben-30319103973` | `8673327702` | `d920a1d2250f779f2e2d490715f94d9ee647e58543b6300bd502a6f2816ee4b0` |
| `approval-mobile-30319103973` | `8673320384` | `f8121f9158dd829fa2325becb4f1b197664710c1ae6d11c4826b01c292d34b00` |
| `approval-hygiene-30319103973` | `8673303753` | `6a3310d84e21abf8b90997b376dd7620a23c8cd6349ba90ec0822d485438e41f` |

## Retained failure and cancellation lineage

No failed or cancelled Run was rerun, deleted, hidden or rewritten.

Retained E1 development evidence includes:

- Run #755: the legacy M4 path guard rejected the first operations route;
- Run #769: the legacy client boundary rejected Mobile management-path visibility;
- Run #770: the Mobile client used the Web transport function name;
- Run #772: the first dual-prefix boundary and Mobile management-isolation assertions disagreed;
- Runs #775, #776 and #777: automatically cancelled by later fast-forward commits;
- Run #779: one query-contract file lacked its final newline;
- Run #783: automatically cancelled by a later fast-forward commit;
- Run #784: one JDBC integration-test file lacked its final newline;
- Run #785: two Server files lacked final newlines after all PostgreSQL tests had passed;
- Run #786: automatically cancelled by equivalent follow-up commits.

The final implementation Run #788 is independent and complete.

## Explicit absences

M5-E1 provides or authorizes none of the following:

- M5-E2 executable Operations commands;
- migration execute, retry, force, rollback, cancel or reconcile endpoints;
- automatic retry or second dispatch of `UNKNOWN`;
- Flowable internal-table access;
- runtime-binding or approval-projection mutation;
- browser or Mobile execution controls;
- a scheduler, polling loop or cross-tenant scan;
- V49 or M6 database ownership;
- production migration execution;
- M5-F2 production-grade fault exercise;
- M5-G merge readiness;
- Ready-for-review, auto-merge, merge or issue closure.

## Documentation-head validation

This evidence file is committed after implementation Run #788. Its committed Head must receive a new
full `Approval Platform Validation` success before M5-E1 is accepted as a final documented gate. The
result is recorded in PR #58 without rewriting this implementation evidence.
