# M6-A-P8 Connector Operations Diagnostics Evidence

## Decision

State at this evidence commit:

- `CONNECTOR_OPERATIONS_DIAGNOSTICS_IMPLEMENTED_DEFAULT_DISABLED`
- implementation validation: `SUCCESS`
- documented-head validation: `PENDING`
- production connector execution: `NOT_AUTHORIZED`
- approval-state mutation: `NOT_AUTHORIZED`
- durable diagnostics, audit-system status and recovery authority: `NOT_PROVIDED`

P7 remains the permanently validated prerequisite at evidence head
`bcd375c3e1a88f0b9e0b48d2254dc6df2dcaa49c`, documented by Run
`30426801370` / #882 (`success`, aggregate `1032/0/0/0`, focused `39/0/0/0`).

## Implementation lineage

- `3cd7fc23a2947e90aaa59c5448cef607f2033417` —
  `feat(m6-a): add read-only connector operations diagnostics`.
- Natural Run `30441174395` / #883 failed only because one Spring test expected one
  observation-sink bean although the intentional design has the bounded store plus a
  `@Primary` Micrometer wrapper. The failed Run was retained and was not cancelled,
  deleted, hidden or manually rerun.
- `5b06e75a714469e6290e2ef1465bb1ac1c5c1f7f` —
  `test(m6-a): correct diagnostics sink bean assertion`; test-only, no production
  source change.
- Every branch update used `force=false`; no rebase, amend, squash or history rewrite.

## Implemented boundary

P8 adds a process-local bounded diagnostics module, HMAC-SHA256 opaque page tokens,
stable high-water pagination, exact allowlisted filters and tenant-hash internal
isolation. Raw tenant values, credentials, Tokens, request/response bodies and endpoint
material are not returned.

Only these management routes are added:

- `GET /api/approval/management/connector-operations/diagnostics`
- `GET /api/approval/management/connector-operations/diagnostics/summary`

They require existing `OPERATIONAL_FAILURE_READ`, use the trusted server tenant context,
and return `Cache-Control: no-store`. Stable redacted handling covers 400, 404, 409,
422, 503 and 500; existing security remains authoritative for 401/403.

`ObservedReadOnlyConnectorInvocationService` delegates exactly once to the unchanged P7
coordinator, records returned secret-free evidence best-effort and returns the exact
coordinator result. Observation failures cannot alter invocation semantics; coordinator
failures are not masked or retried.

Metrics use only closed low-cardinality Provider, operation, outcome, failure and duration
labels. Tenant, user, credential, Token, request, trace and endpoint labels are prohibited.

Configuration is default disabled:

```yaml
approval:
  connector:
    operations-diagnostics:
      enabled: false
```

No persistence, JDBC, Flyway migration, worker, scheduler, listener, retry, replay,
recovery, reconciliation, Token operation, credential rotation or approval mutation is
introduced. P8 remains process-local, non-durable, non-audit and non-production.

## Implementation validation

Natural workflow Run `30442091862` / #884 at head
`5b06e75a714469e6290e2ef1465bb1ac1c5c1f7f` completed `success`:

- Repository hygiene: success
- Java 21 / Maven / PostgreSQL: success
- Vben TypeScript / production build: success
- UniApp TypeScript / H5 / WeChat: success
- reactor: `BUILD SUCCESS`
- aggregate: `1080/0/0/0`
- P8 focused: `48/0/0/0`
  - operations core: 21
  - Server API/configuration/metrics/wrapper: 21
  - architecture boundaries: 6

All successful-Run ZIPs were downloaded; local SHA-256 exactly matched GitHub:

| Artifact | ID | SHA-256 |
| --- | ---: | --- |
| `approval-maven-30442091862` | `8720100016` | `ea0d7614e1569940883949e89534c638d4301cac0467ea937ba7e7107cea5be7` |
| `approval-vben-30442091862` | `8719896831` | `502756e206df4864601c0a24370628694165268ab352a964c9cab4d4fd8f0741` |
| `approval-mobile-30442091862` | `8719884519` | `1164e12118147d80b4b7890bdea0cda6bb7d929d1306bfbe51b891f26b720e4d` |
| `approval-hygiene-30442091862` | `8719860302` | `10d3667123c2b64d2eaf45060b7b9a55db65916cdd83bd08d074d953109c360f` |

## Evidence-commit gate

Immediately before this record:

- `main`: `1d425581d0548c6b15487d58ce47774b29f1073a`
- PR #67 head: `5b06e75a714469e6290e2ef1465bb1ac1c5c1f7f`
- ahead 88, behind 0; Open + Draft + unmerged + mergeable
- Reviews and Threads: none; auto-merge: disabled
- Issues #62/#63/#13/#14: Open
- frozen heads: PR #68 `330dbdd035e436459ffdedf0d2b0c8e07dac7e6c`,
  PR #69 `72acb3ba18602c09c28bfe08b58f8b91e6efe6e4`,
  PR #70 `9d588215e869c8f1332c0bc1a2809fbd235c2efa`
- Flyway highest version: V48; V49 absent
- one automatic PR/main workflow only

P8 becomes `PERMANENTLY_VALIDATED` only after this evidence Head naturally passes all
four jobs and all four documented-head artifacts are downloaded and matched exactly.
This record does not authorize Ready, auto-merge, merge or Issue closure.
