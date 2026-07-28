# M5-D4 — Exact Migration Verification Permanent Evidence

## Governance status

- M5-D2: `ACCEPTED / PERMANENTLY_VALIDATED`
- M5-D3: `COMPLETE / PERMANENTLY_VALIDATED`
- M5-D4 implementation: `COMPLETE / PERMANENTLY_VALIDATED`
- M5-D5 through M5-D8: not started by this evidence record
- M5-D overall: `IN_PROGRESS`
- Production migration execution: `NOT_AUTHORIZED`

This record freezes the committed implementation evidence for exact, bounded, server-owned verification after one governed Flowable migration dispatch. It does not update runtime binding, complete a plan, authorize production execution, expose a public execution endpoint, add a Web or Mobile execution control, add a resident scheduler, or retry an ambiguous migration.

## Permanent committed head

- Branch: `agent/m5-governed-process-instance-migration`
- Implementation head: `75ca562754d8a7037e0332c2c57b6d0bad54cdd5`
- Permanent workflow: `.github/workflows/approval-platform-validation.yml`
- Run ID: `30207204719`
- Run number: `#618`
- Run conclusion: `success`
- Run head: `75ca562754d8a7037e0332c2c57b6d0bad54cdd5`

The committed head and run head match exactly.

## Implemented verification boundary

D4 establishes a separate engine readback port and never treats a migration API return as verified platform success.

Implemented components include:

- `ProcessInstanceVerificationPort`: one exact tenant and one exact engine process instance;
- `FlowableProcessInstanceVerificationAdapter`: Flowable 8 public runtime, task, job, timer, subscription and history APIs only;
- `ApprovalMigrationEngineSnapshot`: bounded, redacted, immutable snapshot vocabulary;
- `ApprovalMigrationExactVerification`: deterministic exact classification and evidence hash;
- `ApprovalMigrationExactVerificationService`: short transaction A, transaction-free Flowable read, short transaction B;
- `JdbcApprovalMigrationExactVerificationStore`: server-owned context, exact replay, changed-payload conflict, atomic evidence plus audit;
- internal default-disabled one-shot verification runner;
- Flyway `V43__create_exact_migration_verification_evidence.sql`.

No production code reads or writes any Flowable `ACT_*` table or depends on a Flowable implementation class.

## Bounded snapshot evidence

The snapshot is limited to:

- runtime presence and exact process-definition/deployment identity;
- active activity IDs;
- execution identities hashed and bound through public unfinished historic-activity definition evidence;
- active task definition keys and process-definition identity;
- executable, timer, suspended and dead-letter job type/state evidence;
- relevant event-subscription activity and definition evidence;
- allowlisted variable hashes only;
- bounded identity-link hashes only;
- history presence, historic process-definition identity and end time;
- bounded delete reason;
- bounded historic task evidence;
- deterministic snapshot hash;
- explicit truncation indicator.

Credentials, tokens, secret values, attachment bytes, arbitrary serialized objects, unfiltered variable values, unbounded identity links and unbounded history are not recorded. Unsupported variable types and any `limit + 1` overflow set truncation and prohibit exact verification success.

## Deterministic classification

The closed D4 classification vocabulary is:

- `EXACT_TARGET_RUNTIME`
- `EXACT_SOURCE_RUNTIME`
- `SOURCE_HISTORY_TERMINAL`
- `TARGET_HISTORY_TERMINAL`
- `MIXED_SOURCE_TARGET_EVIDENCE`
- `MISSING_NO_EVIDENCE`
- `STALE_OR_CONTRADICTORY_EVIDENCE`
- `TRUNCATED_MANUAL_REVIEW_REQUIRED`
- `READ_FAILURE_RECONCILIATION_REQUIRED`
- `INCOMPLETE_RECONCILIATION_REQUIRED`

Exact target requires all observed runtime, execution/activity, task, job, subscription and active history definition evidence to be target-bound, complete and untruncated. A target process instance with any source-bound job, timer, task, execution/activity or subscription evidence is not exact target verified.

## Transaction and replay semantics

The verification service uses:

```text
short platform transaction A
  lock exact attempt, request and engine outcome
  require VERIFYING and one returned engine call
  validate tenant, attempt, request, outcome and server-owned identity
  create deterministic verification request hash
commit A

no platform transaction
  read one exact Flowable instance through public APIs

short platform transaction B
  revalidate the same attempt/request/outcome lineage
  append immutable exact verification evidence
  append audit in the same transaction
  retain VERIFYING only for exact target, pending D5 binding CAS
  move non-exact results to reconciliation-required state
commit B
```

Exact request replay returns the existing immutable verification and performs no second Flowable read. Reusing the verification request identity with changed payload fails closed. Audit failure rolls back the verification state and evidence append.

D4 does not modify `ap_process_runtime_binding`; exact runtime-binding CAS remains exclusively D5 scope.

## Flyway and PostgreSQL evidence

Flyway is continuous through V43:

- V41: immutable engine request/outcome evidence;
- V42: request/outcome guard binding to immutable attempt payload identity;
- V43: exact verification request and evidence tables, lineage constraints, deterministic replay identity, and append-only guards.

V1 through V42 remain unchanged. Before assigning V43, PR #67 through PR #70 were rechecked and contained no Flyway migration.

Permanent PostgreSQL testing covers:

- exact request/outcome/attempt lineage;
- tenant isolation;
- request hash and evidence hash integrity;
- exact replay;
- changed-payload replay rejection;
- append-only update/delete rejection;
- audit atomicity;
- upgrade from fresh, V1, V13, V23, V31, V36 through V42;
- 5,000-row historical upgrade evidence preservation.

## Permanent test totals

Run #618 Maven aggregate:

- tests: `613`
- failures: `0`
- errors: `0`
- skipped: `0`

Focused D4 Maven tests:

- `ApprovalMigrationExactVerificationTest`: 9/9
- `FlowableExactMigrationVerificationAdapterTest`: 4/4
- `ApprovalMigrationExactVerificationServiceTest`: 4/4
- `JdbcApprovalMigrationExactVerificationGuardIntegrationTest`: 1/1
- focused D4 total: `18/18`

Permanent M5 Node governance groups in the hygiene artifact:

- 39/39
- 8/8
- 2/2
- 7/7
- D4 exact-verification boundary: 7/7
- aggregate M5 Node boundary total: `63/63`

All four permanent jobs succeeded:

- Repository hygiene: success
- Java 21 / Maven / PostgreSQL: success
- Vben TypeScript / production build: success
- UniApp TypeScript / H5 / WeChat: success

## Artifact digest verification

Each artifact ZIP was downloaded independently. Its local SHA-256 exactly matched the GitHub artifact digest.

| Artifact | Artifact ID | GitHub and local SHA-256 |
| --- | ---: | --- |
| Maven/PostgreSQL | `8633464274` | `3f6b6794b7bbd180f92cadde0143c1dee11792eed55ffae60493673353a2d870` |
| Vben | `8633422664` | `7ab96138273f5855bf899f71d2f481b9f54fc741c1dcbd80c18124e0fd124361` |
| Mobile | `8633417783` | `5f413a4f32609414c82eb0286b53d13979c92ba88b50a6e964f7a9eb5290d734` |
| Repository hygiene | `8633409797` | `afc3ff492a55de7de337bf33cbb2a9babfc8952480c5afe20182ba9bf9060137` |

## Retained failed evidence and minimal corrections

The following failed runs remain retained:

- Run `30197063417` / #612: stale Node `ACT_*` regular expressions and an effectively-final Java lambda error;
- Run `30197146431` / #613: unused imports and the same stale Node boundary assertions;
- Run `30206936063` / #616: missing final newline after the import cleanup;
- Run `30207033859` / #617: public `Execution` does not expose process-definition identity.

Corrections were ordinary append-only commits. No run was deleted, hidden, cancelled to conceal failure, or repaired by rewriting branch history. The final public-API correction combines runtime execution identity with unfinished historic-activity identity and definition evidence; it does not cast to a Flowable implementation class or query an `ACT_*` table.

## Retained safety boundary

D4 permanently proves:

- engine API return is not verified success;
- verification is server generated and tenant scoped;
- exact target requires complete untruncated target-only evidence;
- Flowable reads occur outside platform database transactions;
- evidence and audit commit atomically;
- runtime binding is unchanged in D4;
- no public execute or verification endpoint exists;
- no Web or Mobile execution control exists;
- no resident scheduler exists;
- no definition-wide migration exists;
- no automatic retry of `UNKNOWN` exists;
- no force-success or fabricated rollback exists;
- execution, worker and automatic reconciliation remain disabled by default;
- production migration execution remains `NOT_AUTHORIZED`.

## Next gate

D5 may begin only after this documentation head itself receives a successful committed-head permanent workflow run. D5 is limited to exact-target runtime-binding CAS, immutable binding/completion evidence and reconciliation on CAS conflict. D6 remains blocked until D5 is separately implemented and permanently validated.
