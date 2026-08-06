# M6-F P7 Adversarial / Fault / Concurrency Acceptance Matrix

Status: `P7_R0_MATRIX_DEFINED_PENDING_DOCUMENTED_HEAD_VALIDATION`

This document is the P7-R0 audit and execution contract for M6-F. It records the exact pre-P7 baseline, the current test evidence, every required Threat / Fault / Race scenario, the missing coverage, and the only permitted target slice. It does **not** claim that P7-A, P7-B, P7-C or P7-D has passed.

## 1. Exact rebaseline before P7-R0

| Item | Re-read result |
| --- | --- |
| Repository | `akaryc1b/approval-platform` |
| Target branch | `main` |
| Exact `main` SHA | `492a428627d3be707d5723350506302ca04841b0` |
| PR | `#88 — M6-F: controlled automation and AI governance` |
| PR branch | `agent/m6-f-controlled-automation-and-ai-governance` |
| Exact pre-P7 Head | `8bad9d6b7ae82b8d27c2af69a1b43f126d8a34ce` |
| PR state | Open / Draft / not merged |
| Mergeability | mergeable; no merge conflict observed |
| Compare | ahead `110`, behind `0`; merge base equals current `main` |
| Main drift | none |
| Review submissions | none |
| `REQUEST_CHANGES` | none |
| Unresolved review threads | none |
| Issue #81 | Open |
| Issue #82 | Open and still blocked by #81 post-main closure |
| Issue #62 | Open |
| Issue #13 | Open |
| Issue #14 | Open |
| PR #83 | Merged / Closed; unchanged |
| Automatic PR/main workflow | only `.github/workflows/approval-platform-validation.yml` |
| Auto-merge | disabled; no auto-merge configured for PR #88 |
| Highest governed migration | unique `V50`; V49 and V50 ownership preserved; no V51 |

If `main` changes after this document is committed, P7 must stop and perform the separately governed Merge Commit rebaseline before any P7-A implementation.

## 2. Permanent authority and scope freeze

The following boundary is immutable throughout P7:

`AI advisory -> typed non-executable proposal -> fresh server policy/precondition evaluation -> fresh authorization preview -> explicit human confirmation -> existing application command service -> immutable audited result`

`Provider -> direct command` is prohibited.

`AI_IS_NOT_AN_OPERATOR`

Current Action Whitelist:

`EMPTY_PENDING_EXISTING_COMMAND_AUDIT`

Current P5 decision:

`P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND`

P7 must not invent a command, test-only production action, command adapter or executable UI merely to make M6-F appear complete. No approve, reject/return, transfer, withdraw, terminate, migrate, template activation, Provider activation, permission mutation, Secret mutation, direct Flowable command, `ACT_*` access, arbitrary HTTP/SQL/Shell/script, connector command, Queue, Worker, Scheduler, listener, polling, automatic retry, automatic rollback, automatic notification or autonomous execution is permitted.

P7-R0 adds no product capability. P7-A through P7-D may add tests and may correct production code only when a deterministic failing test proves a real security, correctness or consistency defect. Any correction must be minimal, independently committed and permanently revalidated.

## 3. Baseline permanent evidence

The exact pre-P7 Head was permanently validated by natural `pull_request` Run `31064702512` / #1299 with all nine jobs successful.

| Evidence | Rebuilt result |
| --- | ---: |
| Maven core | `1387 / 0 failures / 0 errors / 0 skipped` |
| Persistence JDBC | `304 / 0 / 0 / 0` |
| Aggregate | `1691 / 0 / 0 / 0` |
| AI SPI | `12 / 12` |
| AI Core | `191 / 191` |
| OpenAI | `73 / 73` |
| Application | `233 / 233` |
| Architecture | `159 / 159` |
| Server | `232 / 232` |
| Permanent M6 transport boundary | `114 / 114` |
| JDBC selected classes | `75` |
| JDBC Surefire report classes | `74` |
| Abstract selected classes without report | `1` |
| Duplicate JDBC selections | `0` |
| Non-abstract selected classes without report | `0` |

Baseline artifacts were independently downloaded and locally hashed:

| Artifact | ID | Local bytes | Local SHA-256 | Expires |
| --- | ---: | ---: | --- | --- |
| Maven | `8953453545` | `337258` | `9fb9e436deadbee6a217d9cbae2692486424198bc83735c32878a306d37bdfd7` | `2026-11-04T02:07:17Z` |
| Vben | `8953434504` | `18865` | `697534d2f43f6832395d6224cfa9f92f7b7760577aea894d733ff574a5bd23ca` | `2026-11-04T02:07:17Z` |
| Mobile | `8953423547` | `9789` | `053293bfda41ca582864fb9d209f1f5499a87cb6d255ea608942b88afb02111d` | `2026-11-04T02:07:17Z` |
| Hygiene | `8953408425` | `13448` | `d739f465270bf171d5fd07649f81d9aa56db999331d61dfb47258723ead59e7e` | `2026-11-04T02:07:17Z` |

These numbers are the P7 starting point, not P7 acceptance evidence.

## 4. Audited existing test surface

### 4.1 Focused current coverage

| Area | Existing focused evidence | Current conclusion |
| --- | --- | --- |
| Typed Proposal | `ControlledAutomationProposalFactoryTest` — 8 tests | closed types, explicit trigger, expiry, empty whitelist and non-executable shape are covered; broad injection matrix is not |
| Fresh governance | `ControlledAutomationGovernanceEvaluatorTest` — 8 tests | tenant/operator, policy, permission, state/version, SOD, source evidence and whitelist drift fail closed; concurrency and HTTP identity attacks are not |
| Confirmation | `ControlledAutomationConfirmationServiceTest` — 6 tests | explicit click, exact binding, expiry and unavailable reauthentication are covered; concurrent/replay races are not |
| Lineage contract | `ControlledAutomationLineageStoreContractTest` — 5 tests | hash-only terminal model, cancellation and UNKNOWN no-retry rules are covered |
| Durable lineage | `JdbcControlledAutomationLineageStoreIntegrationTest` — 7 PostgreSQL tests | sequential replay/conflict, tenant/operator scope, one terminal race, cancellation, UNKNOWN and append-only protection are covered; full fault/race matrix is not |
| Durable history | `ApprovalAssistanceGovernanceHistoryQueryTest` — 4 tests; `JdbcApprovalAssistanceGovernanceHistoryQueryIntegrationTest` — 2 PostgreSQL tests | window and normal aggregation semantics are covered; persistence failure, overflow and concurrent Tombstone cases are not |
| OpenAI runtime | 16 classes / 73 tests | failure enum mapping, HTTP failure redaction, kill switch, admission, basic rate/cost/circuit, sender/codec/framing and sequential usage are covered; post-dispatch ambiguity and deterministic concurrency are incomplete |
| Server governance API | 16 focused classes / 52 tests | tenant READ annotations, direct canonical tenant/Instant checks, `no-store`, GET annotations and contract invariants are covered; full HTTP duplicate header/query/body/method pollution is not |
| Architecture | 10 M6-F classes / 20 focused tests | no command/provider/secret/worker/scheduler and shared-runtime boundaries are covered; P7-specific test/production parity audit remains required |
| Web / Mobile | production builds plus Node boundary assertions | UI is disabled, advisory and non-executable; no executable interaction is exposed; no new UI capability is planned in P7 |
| Node boundary | permanent `m6-ai-transport-review-boundary.test.mjs` — 114 tests | static authority, migration and workflow boundaries are enforced; a P7 coverage-presence boundary may be added only with the P7 test slices |

### 4.2 Evidence codes used below

| Code | Existing evidence |
| --- | --- |
| `E1` | canonical tenant direct-controller checks and tenant READ annotations |
| `E2` | canonical Instant / HistoryWindow validation and normal history aggregation |
| `E3` | Proposal factory closed schema, expiry, explicit trigger and empty whitelist |
| `E4` | Fresh governance evaluator tenant/operator/policy/permission/state/SOD/evidence checks |
| `E5` | Confirmation exact binding, explicit click, expiry and unavailable reauthentication |
| `E6` | Lineage contract plus PostgreSQL replay/CAS/cancellation/UNKNOWN evidence |
| `E7` | Governance plan/controller GET-only and non-executable flags |
| `E8` | Control/usage/history/incident contract hash and component-binding checks |
| `E9` | OpenAI production fault matrix, secure sender, codec and framing tests |
| `E10` | Admission, rate, cost and basic CircuitBreaker tests |
| `E11` | Process-local usage ledger/accounting tests |
| `E12` | PostgreSQL durable history normal aggregation tests |
| `E13` | Architecture and permanent Node authority boundaries |
| `E14` | Web/Mobile disabled confirmation boundary and successful builds |

Coverage codes:

- `F` — current focused test already exercises the exact scenario, but it must still remain green in P7.
- `P` — current evidence covers only part of the scenario; the named missing test is mandatory.
- `M` — no exact deterministic test was found; the named test is mandatory.

Other columns:

- `PG`: requires real PostgreSQL.
- `Prod`: exercises real production semantics, even when dependencies are controlled/fake and no external Provider or Secret is used.
- `Posture`: `FC` fail closed before side effect; `IR` exact idempotent replay without duplicate event/state; `OW` one winner with deterministic conflicts; `U` terminal UNKNOWN with no retry; `RO` read-only/no mutation.
- `UNK`: whether a legitimate post-dispatch ambiguity may produce `UNKNOWN`.
- `Fix`: `D` means production correction is allowed only after a deterministic test proves a real defect. No speculative redesign is allowed.

Planned target codes:

| Target | Planned test surface |
| --- | --- |
| `S-HTTP` | real Spring HTTP binding/security tests for governance endpoints |
| `CORE` | AI Core Proposal / evaluation / confirmation / hash adversarial tests |
| `OPENAI` | controlled Transport, Credential, Admission, Circuit and Usage tests |
| `JDBC-H` | PostgreSQL durable history fault/consistency tests |
| `JDBC-L` | PostgreSQL lineage replay/CAS/fault/concurrency tests |
| `INC` | composite incident-readiness and snapshot-race tests |
| `ARCH` | architecture and permanent Node boundary assertions |
| `UI` | Web/Mobile static semantics and builds only; no executable interaction |

## 5. P7-A — Threat and permission matrix

### 5.1 Tenant and Header attacks

| ID | Scenario | Cover | Exact missing coverage | Target | PG | Prod | Posture | UNK | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A-H01 | missing `X-Tenant-Id` | P/E1 | real HTTP missing-header result and zero source calls | S-HTTP | N | Y | FC | N | D |
| A-H02 | empty Tenant | P/E1 | real HTTP empty-header case | S-HTTP | N | Y | FC | N | D |
| A-H03 | whitespace-only Tenant | F/E1 | retain exact direct and HTTP assertion | S-HTTP | N | Y | FC | N | D |
| A-H04 | leading/trailing whitespace | P/E1 | both leading and trailing HTTP cases | S-HTTP | N | Y | FC | N | D |
| A-H05 | overlong Tenant | M | bounded header length and zero downstream calls | S-HTTP | N | Y | FC | N | D |
| A-H06 | newline in Tenant | M | HTTP parser plus canonical validator rejection | S-HTTP | N | Y | FC | N | D |
| A-H07 | Unicode control character | M | canonical validator rejection | S-HTTP | N | Y | FC | N | D |
| A-H08 | duplicate header with same value | M | explicit duplicate-header policy | S-HTTP | N | Y | FC | N | D |
| A-H09 | conflicting duplicate headers | M | conflict rejection before authorization/source | S-HTTP | N | Y | FC | N | D |
| A-H10 | non-canonical Tenant | P/E1 | complete canonical grammar matrix | S-HTTP | N | Y | FC | N | D |
| A-H11 | read another Tenant | P/E4/E12 | full controller/source cross-tenant isolation | S-HTTP/JDBC-H | Y | Y | FC | N | D |
| A-H12 | replace Tenant Hash | P/E4/E8 | exact forged tenant-hash object/controller case | CORE/INC | N | Y | FC | N | D |
| A-H13 | inject other-tenant Evidence Hash | P/E4/E8 | cross-tenant component hash injection | CORE/INC | N | Y | FC | N | D |
| A-H14 | no management READ permission | P/E1 | real security-interceptor denial and zero source calls | S-HTTP | N | Y | FC | N | D |
| A-H15 | wrong Resource Scope | P/E1 | real principal/resource-scope denial | S-HTTP | N | Y | FC | N | D |
| A-H16 | denial creates Runtime Binding | P/E13 | call-count assertion proves zero Binding creation | S-HTTP/ARCH | N | Y | FC | N | D |
| A-H17 | denial reads Secret/calls Provider/writes DB | P/E13 | controlled spies prove all zero | S-HTTP/ARCH | N | Y | FC | N | D |

### 5.2 Time-window attacks

| ID | Scenario | Cover | Exact missing coverage | Target | PG | Prod | Posture | UNK | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A-T01 | non-canonical Instant | P/E2 | complete malformed corpus through HTTP | S-HTTP | N | Y | FC | N | D |
| A-T02 | non-`Z` offset | M | reject equivalent `+00:00` and non-zero offsets | S-HTTP | N | Y | FC | N | D |
| A-T03 | non-canonical fractional precision | P/E2 | zero, excessive and normalized fraction cases | S-HTTP | N | Y | FC | N | D |
| A-T04 | `from == to` | P/E2 | exact constructor and HTTP case | CORE/S-HTTP | N | Y | FC | N | D |
| A-T05 | `from > to` | P/E2 | exact constructor and HTTP case | CORE/S-HTTP | N | Y | FC | N | D |
| A-T06 | window over 31 days | P/E2 | exact boundary plus one-nanosecond-over case | CORE/S-HTTP | N | Y | FC | N | D |
| A-T07 | beyond maximum lookback | P/E2 | 3,650-day boundary and over-boundary case | CORE/S-HTTP | N | Y | FC | N | D |
| A-T08 | `to > observedAt` | P/E2/E8 | exact snapshot observation binding | CORE/INC | N | Y | FC | N | D |
| A-T09 | extreme past | M | parser/range rejection without query | S-HTTP | N | Y | FC | N | D |
| A-T10 | extreme future | M | parser/range rejection without query | S-HTTP | N | Y | FC | N | D |
| A-T11 | empty `from` | M | real HTTP empty query value | S-HTTP | N | Y | FC | N | D |
| A-T12 | empty `to` | M | real HTTP empty query value | S-HTTP | N | Y | FC | N | D |
| A-T13 | overlong time value | M | bounded query parsing | S-HTTP | N | Y | FC | N | D |
| A-T14 | newline/control in time value | M | parser rejection | S-HTTP | N | Y | FC | N | D |
| A-T15 | duplicate `from` | M | reject duplicate query parameter | S-HTTP | N | Y | FC | N | D |
| A-T16 | duplicate `to` | M | reject duplicate query parameter | S-HTTP | N | Y | FC | N | D |
| A-T17 | query parameter pollution | M | unknown/repeated mixed parameter corpus | S-HTTP | N | Y | FC | N | D |
| A-T18 | invalid window performs partial History query or mutation | P/E2/E12 | spy/PG assertion proves zero partial query/write | S-HTTP/JDBC-H | Y | Y | FC | N | D |

### 5.3 Proposal and Confirmation adversarial input

| ID | Scenario | Cover | Exact missing coverage | Target | PG | Prod | Posture | UNK | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A-P01 | forged Proposal ID | P/E5/E6 | mismatched persisted identity and confirmation binding | CORE/JDBC-L | Y | Y | FC | N | D |
| A-P02 | forged Tenant | F/E4/E5 | retain and add persisted-lineage case | CORE/JDBC-L | Y | Y | FC | N | D |
| A-P03 | forged Operator | F/E4/E5 | retain and add persisted-lineage case | CORE/JDBC-L | Y | Y | FC | N | D |
| A-P04 | forged Subject Hash | P/E4 | explicit source subject-hash mismatch | CORE | N | Y | FC | N | D |
| A-P05 | forged Resource Hash | F/E4 | retain exact drift case | CORE | N | Y | FC | N | D |
| A-P06 | change Expected State | F/E4 | retain exact stale-state case | CORE | N | Y | FC | N | D |
| A-P07 | change Expected Version | F/E4 | retain exact stale-version case | CORE | N | Y | FC | N | D |
| A-P08 | change Expiry | P/E3/E5 | post-creation expiry/hash tamper case | CORE | N | Y | FC | N | D |
| A-P09 | replay Confirmation | P/E5/E6 | exact persisted confirmation replay and no duplicate event | CORE/JDBC-L | Y | Y | IR | N | D |
| A-P10 | replace Confirmation Evidence | P/E5/E6 | persisted evidence-hash mismatch | CORE/JDBC-L | Y | Y | FC | N | D |
| A-P11 | advisory text disguised as command | P/E3/E13 | adversarial text corpus remains data only | CORE/ARCH | N | Y | FC | N | D |
| A-P12 | Prompt Injection asks `approve` | P/E3/E13 | explicit corpus and zero command spy | CORE/ARCH | N | Y | FC | N | D |
| A-P13 | Prompt Injection asks `reject` | P/E3/E13 | explicit corpus and zero command spy | CORE/ARCH | N | Y | FC | N | D |
| A-P14 | Prompt Injection asks HTTP call | P/E3/E13 | explicit corpus and no network type | CORE/ARCH | N | Y | FC | N | D |
| A-P15 | SQL injection | P/E3/E13 | variants beyond current simple string | CORE/ARCH | N | Y | FC | N | D |
| A-P16 | Shell injection | P/E3/E13 | shell/metacharacter corpus | CORE/ARCH | N | Y | FC | N | D |
| A-P17 | Flowable command injection | P/E13 | explicit command-name corpus and no Flowable dependency | CORE/ARCH | N | Y | FC | N | D |
| A-P18 | Connector command injection | P/E13 | explicit connector corpus and no connector invocation | CORE/ARCH | N | Y | FC | N | D |
| A-P19 | unknown Action type | F/E3/E4 | retain empty/closed whitelist behavior | CORE | N | Y | FC | N | D |
| A-P20 | expired Proposal | F/E3/E4 | retain exact expiry behavior | CORE | N | Y | FC | N | D |
| A-P21 | stale Proposal | F/E4 | retain state/version/policy drift behavior | CORE | N | Y | FC | N | D |
| A-P22 | force confirmation with empty whitelist | P/E3/E4/E5 | end-to-end core composition with zero command | CORE/ARCH | N | Y | FC | N | D |
| A-P23 | bypass Production Reauthentication | F/E5 | retain unavailable verifier and no evidence allocation | CORE | N | Y | FC | N | D |

### 5.4 Governance API attacks

| ID | Scenario | Cover | Exact missing coverage | Target | PG | Prod | Posture | UNK | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A-G01 | unknown Change Plan operation | P/E7 | real HTTP enum-binding rejection | S-HTTP | N | Y | FC | N | D |
| A-G02 | Provider parameter injection | M | unknown parameter rejected/ignored by explicit policy with zero source drift | S-HTTP | N | Y | FC | N | D |
| A-G03 | Model parameter injection | M | same | S-HTTP | N | Y | FC | N | D |
| A-G04 | Prompt parameter injection | M | same | S-HTTP | N | Y | FC | N | D |
| A-G05 | Policy parameter injection | M | same | S-HTTP | N | Y | FC | N | D |
| A-G06 | Secret parameter injection | M | same and no Secret read | S-HTTP | N | Y | FC | N | D |
| A-G07 | Traffic parameter injection | M | same; planned traffic remains zero | S-HTTP | N | Y | FC | N | D |
| A-G08 | Deployment parameter injection | M | same; deployment remains false | S-HTTP | N | Y | FC | N | D |
| A-G09 | Command parameter injection | M | same; command remains false | S-HTTP | N | Y | FC | N | D |
| A-G10 | request-body injection | M | GET body is rejected/ignored by explicit tested policy | S-HTTP | N | Y | FC | N | D |
| A-G11 | POST | P/E7 | real HTTP 405 and zero source calls | S-HTTP | N | Y | FC | N | D |
| A-G12 | PUT | P/E7 | real HTTP 405 and zero source calls | S-HTTP | N | Y | FC | N | D |
| A-G13 | PATCH | P/E7 | real HTTP 405 and zero source calls | S-HTTP | N | Y | FC | N | D |
| A-G14 | DELETE | P/E7 | real HTTP 405 and zero source calls | S-HTTP | N | Y | FC | N | D |
| A-G15 | duplicate operation parameter | M | reject ambiguous operation | S-HTTP | N | Y | FC | N | D |
| A-G16 | overlong parameter | M | bounded rejection | S-HTTP | N | Y | FC | N | D |
| A-G17 | Content-Type confusion | M | form/json/text cases cannot create body authority | S-HTTP | N | Y | FC | N | D |
| A-G18 | Method Override header | M | override cannot turn request into mutation | S-HTTP | N | Y | FC | N | D |
| A-G19 | successful read changes apply/traffic/deployment/mutation/command flags | F/E7/E8 | retain all false and planned traffic zero | S-HTTP | N | Y | RO | N | D |

### 5.5 Evidence Hash attacks

| ID | Scenario | Cover | Exact missing coverage | Target | PG | Prod | Posture | UNK | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A-E01 | uppercase SHA-256 | P/E3/E6/E8 | canonical lowercase hash corpus across contracts | CORE/INC | N | Y | FC | N | D |
| A-E02 | non-64-length hash | P/E3/E6/E8 | complete length boundaries | CORE/INC | N | Y | FC | N | D |
| A-E03 | non-hex hash | P/E3/E6/E8 | complete invalid character corpus | CORE/INC | N | Y | FC | N | D |
| A-E04 | replace Snapshot Hash | P/E8 | exact composite replacement case | INC | N | Y | FC | N | D |
| A-E05 | replace Control Hash | P/E8 | exact composite replacement case | INC | N | Y | FC | N | D |
| A-E06 | replace Usage Hash | P/E8 | exact composite replacement case | INC | N | Y | FC | N | D |
| A-E07 | replace History Hash | P/E8 | exact composite replacement case | INC | N | Y | FC | N | D |
| A-E08 | replace Rollback Hash | P/E8 | exact composite replacement case | INC | N | Y | FC | N | D |
| A-E09 | Runtime State inconsistent | P/E8 | every incompatible state pairing | INC | N | Y | FC | N | D |
| A-E10 | Snapshot observation time inconsistent | P/E8 | exact time mismatch cases | INC | N | Y | FC | N | D |
| A-E11 | History observation time inconsistent | P/E8 | exact time mismatch cases | INC | N | Y | FC | N | D |
| A-E12 | change Incident Signal and reuse old hash | M | reconstruction/tamper case | INC | N | Y | FC | N | D |
| A-E13 | change Operator Steps and reuse old hash | M | reconstruction/tamper case | INC | N | Y | FC | N | D |
| A-E14 | change Blocker and reuse old hash | M | reconstruction/tamper case | INC | N | Y | FC | N | D |
| A-E15 | controller/source receives forged view | P/E8/E13 | source fails; controller never returns forged view; no compensation | INC/S-HTTP | N | Y | FC | N | D |

## 6. P7-B — Fault and degradation matrix

### 6.1 Provider / Transport faults

| ID | Scenario | Cover | Exact missing coverage | Target | PG | Prod | Posture | UNK | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| B-P01 | DNS failure | F/E9 | retain controlled sender/provider mapping | OPENAI | N | Y | FC | N | D |
| B-P02 | TLS certificate validation failure | F/E9 | retain chain/hostname/expiry variants | OPENAI | N | Y | FC | N | D |
| B-P03 | connect timeout | P/E9 | exact controlled connect-timeout path | OPENAI | N | Y | FC | N | D |
| B-P04 | read timeout | P/E9 | exact controlled post-connect timeout path | OPENAI | N | Y | FC/U | Y | D |
| B-P05 | unexpected HTTP status | F/E9 | retain 401/403/429/500/503 and expand closed set | OPENAI | N | Y | FC | N | D |
| B-P06 | malformed JSON | F/E9 | retain decoder classification and body redaction | OPENAI | N | Y | FC | N | D |
| B-P07 | oversized response | F/E9 | retain exact boundary and over-boundary | OPENAI | N | Y | FC | N | D |
| B-P08 | output schema mismatch | P/E9 | complete semantic mismatch corpus | OPENAI | N | Y | FC | N | D |
| B-P09 | connection drops after Provider send | M | deterministic transport marks terminal ambiguity | OPENAI | N | Y | U | Y | D |
| B-P10 | response parse is uncertain | P/E9 | distinguish trusted reject from post-dispatch UNKNOWN | OPENAI | N | Y | FC/U | Y | D |
| B-P11 | credential acquisition failure | P/E9 | exact production source failure with no material leak | OPENAI | N | Y | FC | N | D |
| B-P12 | Secret expired | P/E9/E10 | exact window boundary | OPENAI | N | Y | FC | N | D |
| B-P13 | Secret not yet effective | P/E9/E10 | exact window boundary | OPENAI | N | Y | FC | N | D |
| B-P14 | Cost Policy expired | F/E10 | retain exact window boundary | OPENAI | N | Y | FC | N | D |
| B-P15 | Cost Policy not yet effective | P/E10 | add future-effective boundary | OPENAI | N | Y | FC | N | D |
| B-P16 | Kill Switch denies admission | F/E10 | retain zero dispatch/usage | OPENAI | N | Y | FC | N | D |
| B-P17 | request cost exceeds maximum | F/E10 | retain exact max and overflow-safe over-max | OPENAI | N | Y | FC | N | D |
| B-P18 | rate admission fails | F/E10/E11 | retain zero usage and zero dispatch | OPENAI | N | Y | FC | N | D |
| B-P19 | pre-dispatch failure records Usage | P/E11 | explicit zero-record assertions for every pre-dispatch fault | OPENAI | N | Y | FC | N | D |
| B-P20 | failure leaks Secret/body/request id | P/E9 | unified redaction assertions across every failure | OPENAI/ARCH | N | Y | FC | N | D |
| B-P21 | automatic retry after any fault | F/E9/E13 | retain exactly one attempt and non-retryable outcome | OPENAI/ARCH | N | Y | FC/U | Y | D |

### 6.2 Circuit Breaker faults

| ID | Scenario | Cover | Exact missing coverage | Target | PG | Prod | Posture | UNK | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| B-C01 | consecutive failures reach threshold | P/E10 | exact threshold-minus-one/threshold matrix | OPENAI | N | Y | FC | N | D |
| B-C02 | `CLOSED -> OPEN` | F/E10 | retain transition | OPENAI | N | Y | FC | N | D |
| B-C03 | OPEN rejects admission | F/E10 | retain no dispatch/usage | OPENAI | N | Y | FC | N | D |
| B-C04 | OPEN window expires | F/E10 | retain controlled-clock boundary | OPENAI | N | Y | FC | N | D |
| B-C05 | `OPEN -> HALF_OPEN` | F/E10 | retain transition | OPENAI | N | Y | FC | N | D |
| B-C06 | concurrent HALF_OPEN probes | P/E10 | deterministic barrier proves one probe only | OPENAI | N | Y | OW | N | D |
| B-C07 | HALF_OPEN success closes | F/E10 | retain and verify generation | OPENAI | N | Y | FC | N | D |
| B-C08 | HALF_OPEN failure reopens | M | exact failure transition | OPENAI | N | Y | FC | N | D |
| B-C09 | generation monotonicity | P/E8/E10 | all transition/race generations | OPENAI/INC | N | Y | FC | N | D |
| B-C10 | Snapshot matches state transition | P/E8 | concurrent state/snapshot exactness | OPENAI/INC | N | Y | FC | N | D |
| B-C11 | Incident OPEN is `INCIDENT_BLOCKED` | F/E8 | retain exact signal/steps | INC | N | Y | RO | N | D |
| B-C12 | Incident HALF_OPEN is `INCIDENT_BLOCKED` | P/E8 | explicit HALF_OPEN readiness case | INC | N | Y | RO | N | D |
| B-C13 | second Circuit or bypass is created | P/E13 | identity/count architecture assertion | ARCH | N | Y | FC | N | D |
| B-C14 | control snapshot acquires permit/calls Provider | P/E13 | controlled call-count test remains zero | OPENAI/ARCH | N | Y | RO | N | D |

### 6.3 Rate and Usage faults

| ID | Scenario | Cover | Exact missing coverage | Target | PG | Prod | Posture | UNK | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| B-U01 | Tenant saturation | F/E10/E11 | retain exact boundary | OPENAI | N | Y | FC/RO | N | D |
| B-U02 | Global saturation | F/E10/E11 | retain exact boundary without exact global disclosure | OPENAI | N | Y | FC/RO | N | D |
| B-U03 | failed admission records Usage | F/E10/E11 | retain zero record | OPENAI | N | Y | FC | N | D |
| B-U04 | cancellation before dispatch records Usage | F/E10/E11 | retain zero record | OPENAI | N | Y | FC | N | D |
| B-U05 | terminal outcome double-counts | P/E11 | success/failure/unknown repeated record matrix | OPENAI | N | Y | FC | Y | D |
| B-U06 | dispatch delayed across window | P/E11 | original `RatePermit.windowStart` retained | OPENAI | N | Y | RO | N | D |
| B-U07 | Ledger tenant-capacity limit | P/E10/E11 | exact max and over-capacity behavior | OPENAI | N | Y | FC | N | D |
| B-U08 | request counter overflow | M | checked arithmetic near maximum | OPENAI | N | Y | FC | N | D |
| B-U09 | cost upper-bound overflow | M | checked arithmetic near maximum | OPENAI | N | Y | FC | N | D |
| B-U10 | expose exact global count | F/E11 | retain equality-under-hidden-global test | OPENAI | N | Y | FC | N | D |
| B-U11 | expose other-tenant usage | F/E11 | retain tenant-specific snapshots and redaction | OPENAI | N | Y | FC | N | D |
| B-U12 | Global Saturation exposes more than boolean | P/E8/E11 | API serialization assertion | S-HTTP/OPENAI | N | Y | FC | N | D |
| B-U13 | Evidence Hash includes exact global count | F/E11 | retain hash equivalence test | OPENAI | N | Y | FC | N | D |

### 6.4 Durable History and database faults

| ID | Scenario | Cover | Exact missing coverage | Target | PG | Prod | Posture | UNK | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| B-H01 | V49 query fails | M | forced SQL failure and no partial view | JDBC-H | Y | Y | FC | N | D |
| B-H02 | database connection fails | M | unavailable DataSource classification | JDBC-H | Y | Y | FC | N | D |
| B-H03 | transaction timeout | M | deterministic transaction timeout | JDBC-H | Y | Y | FC | N | D |
| B-H04 | read-only transaction exception | M | injected exception with rollback/no write | JDBC-H | Y | Y | FC | N | D |
| B-H05 | unknown Outcome | P/E2/E12 | corrupt/unknown closed-enum row rejection | JDBC-H | Y | Y | FC | N | D |
| B-H06 | unknown Use Case | P/E2/E12 | corrupt/unknown closed-enum row rejection | JDBC-H | Y | Y | FC | N | D |
| B-H07 | `ACTIVE + TOMBSTONED != total` | P/E2 | constructor/source invariant | CORE/JDBC-H | Y | Y | FC | N | D |
| B-H08 | Attempt / Invocation inconsistent | P/E2 | invariant and corrupt-row case | CORE/JDBC-H | Y | Y | FC | N | D |
| B-H09 | Use Case aggregate inconsistent | P/E2 | invariant and corrupt-row case | CORE/JDBC-H | Y | Y | FC | N | D |
| B-H10 | Advisory aggregate inconsistent | P/E2 | invariant and corrupt-row case | CORE/JDBC-H | Y | Y | FC | N | D |
| B-H11 | Retention aggregate inconsistent | P/E2 | invariant and corrupt-row case | CORE/JDBC-H | Y | Y | FC | N | D |
| B-H12 | reversed range reaches JDBC | P/E2 | prove query is never invoked | CORE/JDBC-H | Y | Y | FC | N | D |
| B-H13 | aggregate overflow | M | checked arithmetic / oversized aggregate simulation | CORE/JDBC-H | Y | Y | FC | N | D |
| B-H14 | concurrent Tombstone during query | M | controlled two-transaction race | JDBC-H | Y | Y | RO | N | D |
| B-H15 | `REPEATABLE_READ` consistency | P/E12 | deterministic two-transaction snapshot proof | JDBC-H | Y | Y | RO | N | D |
| B-H16 | query failure triggers repair write | M | SQL audit proves zero repair/Tombstone writes | JDBC-H/ARCH | Y | Y | FC | N | D |
| B-H17 | query failure starts Worker/Scheduler | P/E13 | retain no worker/scheduler and call-count zero | ARCH | N | Y | FC | N | D |

### 6.5 P4 Lineage faults

| ID | Scenario | Cover | Exact missing coverage | Target | PG | Prod | Posture | UNK | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| B-L01 | Registration transaction fails | M | forced transaction rollback leaves no state/event | JDBC-L | Y | Y | FC | N | D |
| B-L02 | Event insert fails | M | deferred/trigger failure leaves no partial state | JDBC-L | Y | Y | FC | N | D |
| B-L03 | State update fails | M | event/state atomic rollback | JDBC-L | Y | Y | FC | N | D |
| B-L04 | Event / State inconsistent | P/E6 | deferred constraint plus explicit commit-failure case | JDBC-L | Y | Y | FC | N | D |
| B-L05 | CAS fails | F/E6 | retain and expand exact disposition matrix | JDBC-L | Y | Y | FC | N | D |
| B-L06 | terminal commit outcome uncertain | M | deterministic commit-ambiguity maps to UNKNOWN/no retry | JDBC-L | Y | Y | U | Y | D |
| B-L07 | UNKNOWN automatically retries | F/E6 | retain exact prohibition | CORE/JDBC-L | Y | Y | U | Y | D |
| B-L08 | PARTIAL maps to SUCCESS | P/E6 | explicit persisted PARTIAL assertion | CORE/JDBC-L | Y | Y | FC | N | D |
| B-L09 | CANCELLED has command attempt | F/E6 | retain zero-attempt constraint | CORE/JDBC-L | Y | Y | FC | N | D |
| B-L10 | non-cancel terminal has not exactly one attempt | F/E6 | expand every terminal status | CORE/JDBC-L | Y | Y | FC/U | Y | D |
| B-L11 | fault creates duplicate event/state | P/E6 | transaction/replay count assertions | JDBC-L | Y | Y | IR/FC | N | D |

## 7. P7-C — Deterministic concurrency and Replay matrix

All concurrency tests must use controlled synchronization (`ExecutorService`, `CountDownLatch`, `CyclicBarrier`, controlled Clock, controlled Transport/Credential Source, PostgreSQL row locks and CAS). `Thread.sleep`, random probability, external Provider calls and real Secrets are not acceptable as the primary synchronization mechanism.

### 7.1 Proposal registration concurrency

| ID | Scenario | Cover | Exact missing coverage | Target | PG | Prod | Posture | UNK | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| C-R01 | same idempotency key / same payload | P/E6 | simultaneous registration | JDBC-L | Y | Y | IR | N | D |
| C-R02 | same key / different payload | P/E6 | simultaneous conflict | JDBC-L | Y | Y | FC | N | D |
| C-R03 | same Proposal concurrently registered | P/E6 | many-thread exact identity race | JDBC-L | Y | Y | IR | N | D |
| C-R04 | different Tenants same external ID | P/E6 | simultaneous tenant isolation | JDBC-L | Y | Y | OW | N | D |
| C-R05 | Replay vs Conflict race | M | barrier-controlled mixed payload race | JDBC-L | Y | Y | IR/FC | N | D |
| C-R06 | duplicate Event | P/E6 | exact event count under all registration races | JDBC-L | Y | Y | IR | N | D |
| C-R07 | hash collision protection | M | same key hash / different evidence-payload simulation | CORE/JDBC-L | Y | Y | FC | N | D |

### 7.2 Confirmation concurrency

| ID | Scenario | Cover | Exact missing coverage | Target | PG | Prod | Posture | UNK | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| C-F01 | two operators confirm simultaneously | M | exact authorization/identity race | CORE/JDBC-L | Y | Y | OW | N | D |
| C-F02 | same operator confirms twice | M | persisted replay/no duplicate event | CORE/JDBC-L | Y | Y | IR | N | D |
| C-F03 | Confirmation vs Expiry | M | controlled Clock/barrier | CORE/JDBC-L | Y | Y | OW | N | D |
| C-F04 | Confirmation vs Cancellation | M | one terminal winner | CORE/JDBC-L | Y | Y | OW | N | D |
| C-F05 | Confirmation vs re-evaluation | M | fresh evaluation winner/stale result | CORE/JDBC-L | Y | Y | OW | N | D |
| C-F06 | Confirmation vs stale version | M | version change race | CORE/JDBC-L | Y | Y | OW | N | D |
| C-F07 | Confirmation vs policy change | M | policy-generation race | CORE/JDBC-L | Y | Y | OW | N | D |

### 7.3 CAS and terminal races

| ID | Scenario | Cover | Exact missing coverage | Target | PG | Prod | Posture | UNK | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| C-T01 | SUCCESS vs FAILED | F/E6 | retain exact current race | JDBC-L | Y | Y | OW | N | D |
| C-T02 | SUCCESS vs UNKNOWN | M | deterministic race | JDBC-L | Y | Y | OW/U | Y | D |
| C-T03 | FAILED vs PARTIAL | M | deterministic race | JDBC-L | Y | Y | OW | N | D |
| C-T04 | CANCELLED vs SUCCESS | M | deterministic race and attempt invariant | JDBC-L | Y | Y | OW | N | D |
| C-T05 | UNKNOWN vs Retry | P/E6 | simultaneous retry attempt rejected | JDBC-L | Y | Y | U | Y | D |
| C-T06 | two terminal commits | P/E6 | all terminal pairs parameterized | JDBC-L | Y | Y | OW | Y | D |
| C-T07 | concurrent Event Sequence | M | unique ordered append-only events | JDBC-L | Y | Y | OW | N | D |
| C-T08 | Replay after row-lock release | M | deterministic wait/replay | JDBC-L | Y | Y | IR | N | D |
| C-T09 | terminal winner uniqueness | P/E6 | assert state/event/attempt across all races | JDBC-L | Y | Y | OW | Y | D |

### 7.4 Usage Ledger concurrency

| ID | Scenario | Cover | Exact missing coverage | Target | PG | Prod | Posture | UNK | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| C-U01 | same Tenant multi-thread dispatch | M | barrier-controlled limit race | OPENAI | N | Y | OW | N | D |
| C-U02 | multiple Tenants dispatch concurrently | M | tenant/global exact limits | OPENAI | N | Y | OW | N | D |
| C-U03 | Tenant saturation boundary | P/E11 | simultaneous last-slot race | OPENAI | N | Y | OW | N | D |
| C-U04 | Global saturation boundary | P/E11 | simultaneous global last-slot race | OPENAI | N | Y | OW | N | D |
| C-U05 | cross-window concurrency | M | controlled Clock with original permit window | OPENAI | N | Y | OW | N | D |
| C-U06 | Snapshot vs Record | M | no torn/read-leaking snapshot | OPENAI | N | Y | RO | N | D |
| C-U07 | maximum Tenant capacity | P/E11 | simultaneous creation at capacity | OPENAI | N | Y | FC/OW | N | D |
| C-U08 | four-window retention boundary | M | exact bounded eviction with controlled Clock | OPENAI | N | Y | RO | N | D |
| C-U09 | duplicate `markDispatched` | M | exactly-once usage commit | OPENAI | N | Y | IR | N | D |
| C-U10 | original Rate Window ownership | P/E11 | dispatch after boundary retains original window | OPENAI | N | Y | RO | N | D |

### 7.5 Circuit concurrency

| ID | Scenario | Cover | Exact missing coverage | Target | PG | Prod | Posture | UNK | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| C-C01 | many threads hit failure threshold | M | exact single OPEN transition | OPENAI | N | Y | OW | N | D |
| C-C02 | concurrent OPEN admission | M | all rejected, no permit/dispatch | OPENAI | N | Y | FC | N | D |
| C-C03 | controlled HALF_OPEN probe | P/E10 | many-thread one-probe proof | OPENAI | N | Y | OW | N | D |
| C-C04 | Success vs Failure probe race | M | one accepted probe result only | OPENAI | N | Y | OW | N | D |
| C-C05 | Snapshot vs Transition | M | state/generation pair is self-consistent | OPENAI/INC | N | Y | RO | N | D |
| C-C06 | generation monotonicity under race | M | strictly non-decreasing observations | OPENAI | N | Y | OW | N | D |
| C-C07 | multiple HALF_OPEN winners | P/E10 | explicit impossible-winner-count assertion | OPENAI | N | Y | OW | N | D |

### 7.6 Composite Snapshot races

| ID | Scenario | Cover | Exact missing coverage | Target | PG | Prod | Posture | UNK | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| C-S01 | Snapshot and Control from different cycles | P/E8 | exact mismatched observation/generation | INC | N | Y | FC | N | D |
| C-S02 | Snapshot and Usage inconsistent | P/E8 | exact limits/window mismatch | INC | N | Y | FC | N | D |
| C-S03 | Snapshot and History inconsistent | P/E8 | exact snapshot/window/hash mismatch | INC | N | Y | FC | N | D |
| C-S04 | Snapshot and Rollback inconsistent | P/E8 | exact snapshot/runtime mismatch | INC | N | Y | FC | N | D |
| C-S05 | Runtime state changes during composition | M | controlled source changes between reads | INC | N | Y | FC | N | D |
| C-S06 | Runtime changes after History query | M | controlled post-query change | INC/JDBC-H | Y | Y | FC | N | D |
| C-S07 | Circuit changes during composition | M | controlled generation/state change | INC/OPENAI | N | Y | FC | N | D |
| C-S08 | component hash replaced | P/E8 | every component replacement parameterized | INC | N | Y | FC | N | D |
| C-S09 | retry splices different components | M | retry cannot retain mixed prior components | INC | N | Y | FC | N | D |
| C-S10 | retry chooses healthier snapshots | M | no cherry-picking across observations | INC | N | Y | FC | N | D |

## 8. P7-D — Incident / Rollback rehearsal matrix

P7-D is a manual-readiness and evidence rehearsal. It must not implement automatic incident response or rollback.

| ID | Scenario | Cover | Exact missing coverage | Target | PG | Prod | Expected evidence | Fix |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| D-01 | Runtime not configured | F/E8 | retain full operator-step and zero-call rehearsal | INC/ARCH | N | Y | `RUNTIME_NOT_CONFIGURED`, `ALREADY_DISABLED`, no Provider/Binding/release | D |
| D-02 | healthy advisory Runtime | F/E8/E14 | retain empty whitelist and reauth-unavailable evidence | INC/UI | N | Y | `OBSERVATION_READY_ADVISORY_ONLY`; read-only monitoring only | D |
| D-03 | Circuit OPEN | F/E8 | retain explicit non-executable rollback plan | INC | N | Y | `INCIDENT_BLOCKED`; no retry/rollback/notification | D |
| D-04 | Circuit HALF_OPEN | P/E8 | explicit HALF_OPEN rehearsal and no active probe | INC/OPENAI | N | Y | `INCIDENT_BLOCKED`; no traffic recovery | D |
| D-05 | Tenant Rate Saturation | F/E8/E11 | separate tenant-only rehearsal | INC/OPENAI | N | Y | `INCIDENT_BLOCKED`; no reset/limit change/Provider call | D |
| D-06 | Global Rate Saturation | P/E8/E11 | separate redaction rehearsal | INC/OPENAI | N | Y | `INCIDENT_BLOCKED`; boolean saturation only | D |
| D-07 | historical version drift | F/E8/E12 | retain operator-step assertions | INC/JDBC-H | Y | Y | `ACTION_REQUIRED`; no old-version restore/config mutation | D |
| D-08 | Retention due | F/E8/E12 | retain explicit no-Tombstone/no-Scheduler assertion | INC/JDBC-H/ARCH | Y | Y | `ACTION_REQUIRED`; manual Tombstone review only | D |
| D-09 | Provider post-send UNKNOWN | M | compose terminal UNKNOWN lineage into rehearsal | INC/OPENAI/JDBC-L | Y | Y | stays `UNKNOWN`; no second request, command or retry | D |

## 9. Deterministic execution design

P7-A through P7-D must use the following test design:

1. HTTP adversarial cases use the real Spring request binding, security interceptor and controller stack with controlled sources that count every invocation.
2. Provider and Secret faults use controlled `OpenAiResponsesTransportPort`, sender dependencies, controlled Credential Material Source and fixed Clock. No public network, customer account, paid call or real Secret is allowed.
3. Concurrent in-memory controls use `ExecutorService`, `CountDownLatch` or `CyclicBarrier`. Time transitions use a controllable Clock. A timeout is only a deadlock guard, never the primary race trigger.
4. Durable races use real PostgreSQL transactions, row locks, CAS and separately coordinated connections. They must assert state, event count, revision, attempts and replay disposition after every race.
5. No scenario may be accepted solely from a mock that bypasses the production class under test.
6. No test may weaken an assertion, skip a Job, convert fail-closed to fail-open or broaden the Action Whitelist to pass.
7. Every first failed permanent Run and available failure Artifact must remain visible. Corrections are append-only and independently committed.

## 10. Migration decision

P7 begins with migration ceiling `V50`.

- Prefer no new migration.
- Do not modify V49 or V50.
- Do not create Incident, Queue, Worker or Scheduler tables.
- Do not persist raw Prompt, Provider request/response, Secret or other-tenant details.
- If a deterministic P7 test proves a database security defect that cannot be corrected safely within the V49/V50 code path, stop the stage and produce a separate migration design before any V51 is created.

## 11. Stage gates derived from this matrix

### P7-A exit

Every A-H, A-T, A-P, A-G and A-E row must have an exact test or an explicitly justified equivalent test, all zero-side-effect assertions must pass, and PR #88 must remain Open + Draft.

### P7-B exit

Every B-P, B-C, B-U, B-H and B-L row must be exercised with controlled faults. No success may be fabricated, no Secret/body may leak, and post-dispatch ambiguity must remain terminal UNKNOWN without retry.

### P7-C exit

Every C-R, C-F, C-T, C-U, C-C and C-S race must be deterministic. Exactly-one-winner, exact replay, event/state consistency, bounded usage and single HALF_OPEN probe invariants must be proven.

### P7-D exit

All nine incident scenarios must be rehearsed and recorded in `docs/m6/M6_F_P7_ADVERSARIAL_FAULT_CONCURRENCY_ACCEPTANCE.md`. The document must include exact failed/success Runs, Jobs, Artifacts, rebuilt test totals, Review state, Action Whitelist, P5 decision and honest limitations.

### P7 Gate

P8 is prohibited until every P7 stage has an independent final successful permanent Run and independently verified Maven, Vben, Mobile and Hygiene artifacts, all failed Runs are retained, no Review blocker exists, `main` is current, V50 remains governed, the Action Whitelist remains empty, P5-A remains skipped, and no command/retry/rollback/notification/Provider/Secret expansion exists.

Only then may the explicit conclusion `P7_GATE_PASSED` be recorded.

## 12. P7-R0 conclusion

This audit found substantial baseline coverage, but it also found material missing exact tests in real HTTP ambiguity handling, post-dispatch UNKNOWN, database fault injection, full lineage/confirmation races, concurrent Usage/Circuit behavior and composite snapshot races. Therefore existing P6 tests must not be represented as P7 acceptance.

P7-R0 authorizes only the staged implementation of the missing tests and defect-only corrections defined above. It does not authorize P8, Ready, auto-merge, merge, Issue closure or M6-G.

The exact documented-Head permanent Run, nine Job IDs, four independently verified Artifacts and final P7-R0 PR comment are recorded after this commit without changing this matrix, avoiding a self-referential evidence commit.

`P7_R0_SCOPE_FROZEN`

`P7_A_NOT_STARTED`

`AI_IS_NOT_AN_OPERATOR`
