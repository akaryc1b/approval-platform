# M6-A-P6 DingTalk Token Lifecycle Permanent Evidence

Status: `DINGTALK_TOKEN_LIFECYCLE_IMPLEMENTED_DEFAULT_DISABLED / IMPLEMENTATION_PERMANENTLY_VALIDATED`

Production connector execution: `PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED`

Decision date: `2026-07-29`

Tracking:

- parent milestone: Issue #62;
- workstream: Issue #63;
- pull request: PR #67;
- branch: `agent/m6-a-connector-foundation`;
- target branch: `main`;
- owner: `PLATFORM_SECURITY`;
- selected capability: `TOKEN_LIFECYCLE`.

## Scope and prerequisites

P6 was developed only after M6-A-R0 and P5 permanent validation. The verified
pre-P6 state was:

- `main`: `1d425581d0548c6b15487d58ce47774b29f1073a`;
- P5 documented Head: `3ba5e2eecee61b3aab8bc1fd89ddab4a241a36e1`;
- relation to `main`: ahead `82`, behind `0`;
- PR #67: Open + Draft + mergeable;
- reviews and unresolved review threads: none;
- concrete production Secret Backend:
  `BLOCKED_PENDING_BACKEND_SELECTION`;
- Flyway continuous through `V48`, with no `V49` or higher migration;
- only automatic PR/main workflow:
  `.github/workflows/approval-platform-validation.yml`.

P6 does not alter the accepted P3 DingTalk HTTP transport. Token acquisition and
refresh are isolated in a new module so the P3 transport remains Token-lifecycle
free.

## Implementation commit

- commit: `18396bdb70e9441aa3e0c413d96c2d504c73ad60`;
- message: `feat(m6-a): add default-disabled DingTalk Token lifecycle`;
- parent: `3ba5e2eecee61b3aab8bc1fd89ddab4a241a36e1`;
- branch update: `force=false`;
- changed files: `31`;
- no rebase, squash, amend, reset, force push or history rewrite.

The commit adds the independent module:

`server-modules/approval-connector-dingtalk-token`

Its compile dependencies are limited to credential core and routing core. It has
no Spring, HTTP client, persistence, Flowable, integration-JDBC, worker,
scheduler or retry-framework dependency.

## Exact server-owned request

One `DingTalkTokenRequest` binds:

- trusted tenant identity;
- immutable route-plan and route-definition hashes;
- Provider, capability, connector operation, API family and transport profile;
- application-credential reference hash, binding fingerprint and exact version;
- credential and Token policy versions;
- Kill Switch revision;
- non-production environment classification.

The request rejects production material before endpoint access. Public evidence
contains hashes, versions, times, ordinals and closed classifications; it contains
no tenant identifier, raw credential reference, AppKey, AppSecret or Token.

## On-demand lifecycle

The coordinator is synchronous and caller-driven. It starts no background thread
and performs no scheduled refresh.

Before cache hit, acquisition, refresh and single-flight join it revalidates:

1. coordinator state and exact Token policy version;
2. Kill Switch decision and revision;
3. the immutable route through the P4 revalidator;
4. exact credential catalog descriptor and state;
5. tenant, Provider, material type, binding, version and policy identity;
6. P5 material admission and validity.

A Token before its refresh threshold produces `CACHE_HIT`. At or after the
threshold, refresh occurs only when requested. Concurrent requests for one exact
cache key share one bounded single-flight future. A second endpoint attempt is
not started by a joining caller.

Credential rotation changes the cache identity and zeroizes the superseded family
entry. A stale previous credential version cannot fall back. Revocation, disabled,
expired, not-yet-valid or rotation-pending state, Kill Switch changes and route
drift invalidate the family and fail closed.

The cache is bounded, process-local and non-persistent. It is not a distributed
Token store, durable recovery mechanism or execution authorization.

## Material lifetime and redaction

P6 consumes application credentials only through the P5 material lease. AppKey
and AppSecret temporary arrays are zeroized after endpoint callback completion or
failure.

The endpoint port returns Token bytes through a callback. The coordinator takes
ownership into a direct buffer and immediately zeroizes the supplied array.
Issued `DingTalkAccessTokenLease` instances:

- use a dedicated direct buffer;
- expose material only through a scoped callback copy;
- zeroize callback copies in `finally`;
- reject concurrent use and use after close;
- support idempotent repeated close;
- defer close during active use;
- zeroize retained buffers on invalidation and coordinator shutdown.

Exceptions expose only closed low-cardinality failure codes and discard arbitrary
endpoint/backend text.

## Default-disabled application gate

```yaml
approval:
  connector:
    dingtalk-token:
      enabled: false
      policy-version: dingtalk-token-policy-v1
      refresh-before-expiry: 5m
      minimum-validity: 30s
      maximum-lifetime: 2h
      single-flight-wait: 5s
      maximum-entries: 256
```

Disabled startup creates no Token policy, route gate or coordinator. Enabling the
gate without every server-owned dependency fails startup. The repository supplies
no real DingTalk Token endpoint, concrete production Secret Backend or production
Kill Switch implementation.

## Deterministic validation

Local Java 21 validation used `-Xlint:all -Werror`. Production classes, module
tests, Spring tests and architecture tests compiled successfully. A deterministic
non-network smoke executed initial acquisition, cache hit, on-demand refresh,
lease zeroization and close behavior, ending with:

`P6_SMOKE_OK calls=2`

GitHub Actions implementation Run `30414206815` / #879 at Head
`18396bdb70e9441aa3e0c413d96c2d504c73ad60` completed successfully:

- Repository hygiene: success;
- Java 21 / Maven / PostgreSQL: success;
- Vben TypeScript / production build: success;
- UniApp TypeScript / H5 / WeChat: success.

Maven evidence:

- aggregate: `993 / 0 / 0 / 0`;
- P6 Token contract: `5 / 0 / 0 / 0`;
- P6 coordinator lifecycle: `8 / 0 / 0 / 0`;
- P6 Token lease: `4 / 0 / 0 / 0`;
- P6 Token module total: `17 / 0 / 0 / 0`;
- P6 Spring gate: `3 / 0 / 0 / 0`;
- P6 architecture: `6 / 0 / 0 / 0`;
- P6 focused total: `26 / 0 / 0 / 0`;
- architecture module: `76 / 0 / 0 / 0`;
- Server: `103 / 0 / 0 / 0`;
- reactor: `BUILD SUCCESS`;
- total time: `08:03 min`.

Every Run #879 artifact ZIP was downloaded and independently hashed. Local
SHA-256 exactly matched the GitHub digest:

| Artifact | ID | SHA-256 |
| --- | ---: | --- |
| `approval-maven-30414206815` | `8709665348` | `87fbbd29e62580967694cec01cc3405566c274eed06fb98a9e38f54bd4b87cf9` |
| `approval-vben-30414206815` | `8709556528` | `e54b0cae6f6f56e3e842eb17a01d90233cedce88426b0d42a2ced5355e0b9745` |
| `approval-mobile-30414206815` | `8709540750` | `00596b8e770ce9999b7afc272b1b739c5fa1d7cc160deb9b821f06e2b830d23b` |
| `approval-hygiene-30414206815` | `8709531711` | `4b30844ce30c13ff8fc8c28aa53d45ecdae57a3b43752d6a17987ece910fa440` |

No P6 validation failure was hidden, cancelled or rerun. The first natural P6
implementation Run succeeded.

## Permanent boundaries retained

- no real DingTalk Token endpoint;
- no concrete production Secret Backend;
- no production AppKey, AppSecret, Token or customer endpoint;
- no production connector invocation or P7 dispatch;
- no Token persistence, database table or Flyway migration;
- no V49 or higher migration;
- no distributed Token cache or recovery process;
- no Worker, Scheduler, scanner, polling loop or background refresh;
- no Automatic Retry or post-attempt fallback;
- no previous credential or Token version fallback;
- no public management, Web or Mobile Token controls;
- no Flowable access or Approval-State Mutation;
- no approve, reject, transfer, withdraw, terminate or migrate command;
- no M5 semantic change;
- no second automatic workflow;
- no PR Ready, auto-merge, merge or issue closure.

## Acceptance and stop condition

```text
M6-A-P6:
  DINGTALK_TOKEN_LIFECYCLE_IMPLEMENTED_DEFAULT_DISABLED
  IMPLEMENTATION_PERMANENTLY_VALIDATED

P7:
  BLOCKED

Production connector execution:
  PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED

PR #67:
  OPEN
  DRAFT
  NOT_MERGED
```

A separate natural workflow at this evidence-document Head is required before P6
is finally documented. Work stops after that validation and does not enter P7.
