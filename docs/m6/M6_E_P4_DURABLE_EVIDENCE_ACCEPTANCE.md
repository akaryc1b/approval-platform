# M6-E P4 Durable Evidence Acceptance

Status: `M6_E_P4_FORMALLY_ACCEPTED_PENDING_DOCUMENTED_HEAD_VALIDATION`

Date: `2026-08-01`

Tracking:

- parent milestone: Issue #62;
- roadmap rebaseline: Issue #78, closed / completed;
- workstream: Issue #80;
- Pull Request: #83;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- target: `main`;
- exact base/current main: `fcf031da9e6e04b15a1255044021a7fdd6637421`.

This record accepts only M6-E P4 durable minimal approval-assistance evidence. It does not
authorize P5 API/UI exposure, a real production Provider, production Prompt/customer knowledge,
runtime Secret material, network egress, automation, an executable action or any approval command.

## 1. P3 entry gate

P4 began only after P3 completed its independent documented-Head acceptance.

Accepted P3 evidence:

- exact documented Head: `968f1281885f10eae7324c0626d13020c64eb8b1`;
- permanent Run: `30629560198` / run number `1002`;
- conclusion: `success`;
- all four permanent jobs: `success`;
- all four artifacts: independently downloaded and SHA-256 matched;
- Maven aggregate: `1452 / 0 / 0 / 0`;
- AI Core: `148 / 148`;
- P1 focused Java: `22 / 22`;
- P2 focused Java: `20 / 20`;
- P3 focused Java: `14 / 14`;
- M6-E P0-P3 architecture boundary: `9 / 9`;
- no actionable Review finding;
- PR #83 remained Open + Draft.

At P4 acceptance, current `main` remains
`fcf031da9e6e04b15a1255044021a7fdd6637421`. The M6-E branch is ahead of main and behind zero.
Issues #80, #62, #13 and #14 remain open.

## 2. P4 accepted scope

P4 adds one durable-minimal-evidence contract and one internal JDBC persistence adapter:

- `ApprovalAssistanceDurableEvidence`;
- `ApprovalAssistanceDurableEvidenceStore`;
- `JdbcApprovalAssistanceDurableEvidenceStore`.

P4 adds exactly one Flyway migration:

`V49__create_ai_approval_assistance_durable_evidence.sql`

P4 adds exactly three tables:

1. `ap_ai_approval_assistance_evidence`;
2. `ap_ai_approval_assistance_evidence_state`;
3. `ap_ai_approval_assistance_evidence_event`.

The repository has no V50 or later migration. M5-owned migration semantics remain frozen through
V48; the only accepted V49 is the exact M6-E durable-evidence migration above.

P4 adds no Spring bean registration, controller, application-service binding, browser source,
mobile source, public endpoint, Provider adapter, Secret source, network client, Worker or
Scheduler.

## 3. Hash-only evidence contract

The durable record stores canonical SHA-256 evidence instead of raw approval or Provider content.

Stored hashes bind:

- the exact P3 request evidence;
- subject evidence;
- authorized resource evidence;
- the exact P1 Provider-safe projection;
- the P3 execution evidence;
- the selected route when an invocation started;
- complete Provider/model/Prompt/policy/output-Schema versions;
- the final advisory or failure outcome;
- the canonical P4 durable record itself.

The P1 projection hash includes the canonical hash of each final Provider-safe value. The P4
record therefore proves the exact minimized Provider-safe input without retaining the original
value.

The outcome hash includes the complete advisory or failure output before persistence. P4 therefore
proves the exact result without retaining summary, observation, risk, recommendation, limitation
or failure text.

The durable evidence contract rejects:

- malformed or non-SHA-256 hashes;
- mismatched P2/P3 execution evidence;
- mismatched Provider/model/Prompt/policy/output-Schema versions;
- customer knowledge metadata;
- more than one Provider attempt;
- invocation-started/attempt-count mismatch;
- retry or post-invocation fallback evidence;
- a started invocation without exact route evidence;
- manufactured advisory metadata on a failure;
- missing advisory metadata on a success/low-confidence result;
- invalid confidence values;
- recorded time preceding request time;
- zero, negative or greater-than-ten-year retention;
- any directly constructed record whose final canonical evidence hash does not match.

## 4. No raw sensitive payload persistence

P4 intentionally stores no:

- Form field value;
- Prompt body;
- Provider request body;
- Provider response body;
- summary;
- observation text;
- risk text;
- missing-material reason;
- recommendation text;
- limitation text;
- failure message;
- attachment content;
- raw tenant/operator/resource identity beyond the tenant key and opaque hash evidence;
- JSON, JSONB, TEXT, BYTEA or generic payload column.

The schema and permanent boundary tests reject raw, payload, body, content and other generic text
or binary persistence columns.

## 5. Tenant isolation

Every durable table is tenant scoped. Evidence identity is the composite:

`(tenant_id, evidence_id)`

State and event rows use composite tenant/evidence foreign keys. A record with the same evidence ID
in another tenant is an independent record. Cross-tenant reads, replays, state transitions and
tombstones are not accepted.

No browser, mobile client or Provider supplies the authoritative tenant key for the store. P4 is an
internal persistence port only; caller authorization remains a required later application/API gate.

## 6. Atomic immutable store protocol

A first store transaction creates, in this order:

1. immutable evidence;
2. append-only `STORED` event at revision `1`;
3. `ACTIVE` state at revision `1`.

The three writes are one transaction. A deferred state/event constraint verifies at commit that the
state and append-only event match exactly.

Exact replay returns `REPLAYED` without creating a second evidence, event or state row.

P4 returns `CONFLICT` when:

- the same request identity is paired with another evidence identity;
- the same tenant/evidence identity is paired with different evidence content;
- an existing state/event chain does not match the exact incoming record.

Concurrent exact store produces one `STORED` result and one `REPLAYED` result. It does not produce
two immutable records or two revision-one events.

## 7. Retention and CAS tombstone protocol

P4 does not physically delete evidence. A permitted deletion request creates a tombstone state and
append-only event while preserving the hash-only immutable evidence.

A tombstone command requires:

- exact tenant and evidence ID;
- expected state revision;
- closed delete-reason vocabulary;
- server-owned request time;
- exact SHA-256 deletion-request evidence.

The closed delete reasons are:

- `RETENTION_EXPIRED`;
- `DATA_SUBJECT_REQUEST`;
- `TENANT_POLICY`;
- `SECURITY_INCIDENT`;
- `LEGAL_REQUIREMENT`.

`RETENTION_EXPIRED` cannot tombstone a record before its retention deadline. The other governed
reasons permit an early tombstone but still require exact CAS and append-only evidence.

A successful transition is exactly:

`ACTIVE / revision 1 -> TOMBSTONED / revision 2`

The transaction creates the revision-two event and CAS-updates the state. Exact replay of the same
deletion request returns `REPLAYED`. A stale revision returns `REVISION_CONFLICT`. A different
second deletion request returns `CONFLICT`. A missing tenant/evidence pair returns `NOT_FOUND`.

Concurrent exact tombstone produces one transition and one replay. It does not create duplicate
revision-two events.

## 8. Database integrity and tamper resistance

V49 establishes database-level guards for:

- immutable evidence rows;
- append-only event rows;
- state transition rules;
- revision vocabulary `1` and `2` only;
- state vocabulary `ACTIVE` and `TOMBSTONED` only;
- no retry or post-invocation fallback evidence;
- `KnowledgeSourceVersion.none()` only;
- tombstone time not preceding evidence recorded time;
- event time not preceding evidence recorded time;
- exact predecessor event linkage;
- exact state-to-event matching at commit;
- exact event-to-state matching at commit.

The bidirectional deferred constraints reject:

- state without a matching event;
- event without a matching state;
- an event with the wrong predecessor;
- revision/state/event mismatch;
- time inversion;
- partial state/event commits.

Permanent PostgreSQL tests also prove that direct update or physical deletion of evidence, state or
event history is rejected.

## 9. Internal JDBC boundary

`JdbcApprovalAssistanceDurableEvidenceStore` implements only the framework-free P4 store port.

The adapter uses short Spring transactions for:

- `store`;
- `tombstone`;
- tenant-scoped read.

It contains no:

- controller annotation;
- scheduled task;
- Flowable dependency;
- HTTP client;
- Secret material access;
- approval command;
- automatic cleanup loop;
- cross-tenant query.

`apps/server` does not instantiate or expose the P4 store. P5 must pass an independent API and
authorization gate before any runtime binding.

## 10. Retained failed and superseded Runs

P4 retained all natural failed or superseded Runs. None was rerun, deleted, hidden or used as final
acceptance evidence.

The retained sequence includes compatibility failures where older M5/M6-A/M6-B/M6-C tests treated
V48 as a permanent repository ceiling. Corrections were intentionally narrow:

- each prior milestone remains frozen to the migration versions it owns;
- only the exact M6-E V49 file is allowed;
- all other V49 files and every V50+ migration remain prohibited;
- no previous production capability or schema semantics were changed.

Run `30693393912` / #1032 at exact Head
`e0613dbf5e8256a726811f681b047754da48167e` is retained as the final failed candidate:

- Vben: success;
- Mobile: success;
- Maven continued through the full reactor;
- Repository hygiene: failure;
- failure cause: the new P4 permanent test expected a lower-case wording while production code used
  `Provider attempts must be zero or one...`;
- the failure was a test-expression mismatch, not a production-code defect.

The append-only correction commit changed only the permanent test regular expression to be
case-insensitive. Run #1032 was not rerun.

## 11. Successful P4 implementation verification

Exact implementation Head:

`8e190d0910716eafbb1d2a717a8d2e9db63255a3`

Permanent workflow:

- Run ID: `30693642836`;
- run number: `1033`;
- event: `pull_request`;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- attempt: `1`;
- conclusion: `success`.

Jobs:

| Job | Job ID | Result |
| --- | ---: | --- |
| Java 21 / Maven / PostgreSQL | `91352674245` | success |
| Vben TypeScript / production build | `91352674235` | success |
| UniApp TypeScript / H5 / WeChat | `91352674264` | success |
| Repository hygiene | `91352674252` | success |

Maven evidence recalculated from `maven-verify.log`:

- aggregate: `1474 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `156 / 156`;
- P1 focused Java: `22 / 22`;
- P2 focused Java: `20 / 20`;
- P3 focused Java: `14 / 14`;
- P4 Core: `8 / 8`;
- P4 PostgreSQL: `14 / 14`;
- Persistence JDBC: `295 / 295`;
- architecture module: `138 / 138`;
- ArchUnit `ModuleBoundariesTest`: `10 / 10`;
- `BUILD SUCCESS`: present;
- full reactor result: every module `SUCCESS`.

Permanent governance evidence from the Repository hygiene job:

- M6-E P0-P3 authority boundary: `9 / 9`;
- P4 durable-evidence boundary: `1 / 1`;
- combined repository-hygiene entrypoint: `15 / 15`;
- existing M6-D foundation boundary: `10 / 10`;
- existing M6-D activation boundary: `6 / 6`;
- existing M6-D transport boundary: `7 / 7`.

## 12. P4 implementation artifact verification

Every ZIP was independently downloaded and locally SHA-256 hashed. Each local SHA-256 exactly
matches the GitHub artifact digest.

| Artifact | ID | Size | GitHub/local SHA-256 | Match |
| --- | ---: | ---: | --- | --- |
| Maven | `8816594891` | `26905` | `0503891c72733b20d740f2b5385ee716451fe3ab8ef8721f901298c888e56247` | exact |
| Vben | `8816539245` | `18871` | `52edd543685550e9d15b37044c54b4b895f6c2dc187753913e96c317ff9fca41` | exact |
| Mobile | `8816531955` | `9787` | `d440eb0792530e0de75ad2b6bc02a5ed33946adfe1bc825ce7752ddc64a80f91` | exact |
| Hygiene | `8816523512` | `9214` | `b2b70aa0a1607baec135d22f1cca1fd02b87a4bc03d4838c28f2fdbafe5d77a0` | exact |

All four artifacts are unexpired and expire at `2026-10-30T09:23:13Z`.

## 13. Review and repository state before this record

Before this acceptance record was committed:

- current main: `fcf031da9e6e04b15a1255044021a7fdd6637421`;
- PR #83: Open + Draft + mergeable;
- branch compare: ahead `76`, behind `0`;
- changed files: `44`;
- submitted reviews: none;
- requested reviewers: none;
- unresolved review threads: none;
- PR reactions: none;
- existing PR comments contain only P0-P3 acceptance evidence and no actionable finding;
- Issues #80, #62, #13 and #14: open;
- auto-merge was not enabled.

The documented Head created by this record must receive a new natural permanent workflow and four
new independently matched artifacts. Run `30693642836` cannot substitute for documented-Head
validation.

## 14. Explicit absence of P5-P7 capability

P4 introduces no:

- public approval-assistance API;
- PC or Mobile AI experience;
- application-service binding to the P4 store;
- browser-manufactured or Provider-manufactured tenant/operator/permission/audit authority;
- real Provider or production Provider adapter;
- production Prompt content or customer knowledge;
- RAG, embedding or vector storage;
- runtime Secret material or network egress;
- paid/customer Provider call in CI;
- AI Queue, Worker, Scheduler, listener, polling or autonomous retry;
- automation proposal or executable action;
- approve, reject, return, transfer, withdraw, terminate, migrate, publish or activate command;
- M6-F capability.

## 15. P4 formal decision

M6-E P4 is accepted as tenant-safe, hash-only, append-only and CAS-governed durable minimal
approval-assistance evidence, subject to the new documented-Head permanent validation.

P5 may begin only after:

1. the exact documented Head Run succeeds;
2. all four documented-Head artifacts are independently SHA-256 matched;
3. Maven aggregate, AI Core, P1-P4 focused and PostgreSQL evidence are recalculated;
4. Review, thread, comment and reaction checks contain no actionable finding;
5. PR #83 remains Open + Draft;
6. Issues #80, #62, #13 and #14 remain in their required state;
7. current main is unchanged or is merged into the branch through an ordinary Merge Commit and
   fully revalidated.

P5 may add only a read-only, tenant-scoped approval-assistance API and PC/Mobile presentation. It
must reuse server-owned authorization and the accepted P1-P4 contracts, clearly label every result
as advisory/unverified/human-reviewed, prevent clients from choosing Provider/routes/versions or
manufacturing authority, and expose no approval command or automation path.

`M6_E_P4_ACCEPTED_NOT_PUBLICLY_EXPOSED`

`DURABLE_MINIMAL_EVIDENCE_HASH_ONLY`

`TENANT_SCOPED_APPEND_ONLY_CAS`

`NO_RAW_PROVIDER_INPUT_OR_OUTPUT`

`ADVISORY_NOT_AUTHORITY`

`AI_IS_NOT_AN_OPERATOR`

`PROVIDER_TO_DIRECT_COMMAND_PROHIBITED`
