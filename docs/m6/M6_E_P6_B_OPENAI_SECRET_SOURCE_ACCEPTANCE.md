# M6-E P6-B OpenAI Secret Source Acceptance

Status: `M6_E_P6_B_FORMALLY_ACCEPTED_PENDING_DOCUMENTED_HEAD_VALIDATION`

Date: `2026-08-03`

Tracking:

- parent milestone: Issue #62;
- roadmap rebaseline: Issue #78, closed / completed;
- workstream: Issue #80;
- Pull Request: #83;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- target: `main`;
- exact base/current main: `fcf031da9e6e04b15a1255044021a7fdd6637421`.

This record accepts only the P6-B server-owned OpenAI environment Secret source and its exact
callback-scoped credential-lease conformance. It does not authorize a request encoder, response
decoder, HTTP sender, DNS lookup, TLS connection, Provider bean, runtime route, Provider invocation,
public invocation endpoint, production Prompt, Provider-derived P4 write, migration, approval command
or automation command.

## 1. P6-A entry gate

P6-B began only after P6-A completed its independent documented-Head acceptance.

Accepted P6-A evidence:

- exact documented Head: `f40c170f8e4d0e55627d0234d1831c3fdc3617f0`;
- permanent Run: `30777095281` / run number `1057`;
- conclusion: `success`;
- all four permanent jobs: `success`;
- all four documented-Head artifacts: independently downloaded and SHA-256 matched;
- Maven aggregate: `1477 / 0 / 0 / 0`;
- P6-A permanent Provider-profile gates: `2 / 2`;
- combined repository hygiene: `19 / 19`;
- PR #83 remained Open + Draft;
- P6-B alone was authorized.

## 2. Exact P6-B binding

P6-B adds one production Secret-material source:

`OpenAiEnvironmentCredentialMaterialSource`

The source accepts only this exact server-owned profile:

- provider key: `openai-responses`;
- credential reference ID: `openai-api-key`;
- protocol profile: `OPENAI_RESPONSES_V1`;
- capability: `APPROVAL_ASSISTANCE`;
- connector operation: `AI_ADVISORY_GENERATE`;
- material type: `API_KEY`;
- environment: `PRODUCTION`;
- material variable: `OPENAI_API_KEY`;
- non-secret version variable: `OPENAI_API_KEY_VERSION`.

The admitted request binds tenant, credential reference, route-plan hash, credential-binding hash,
version reference, version-evidence hash, policy revision and the complete request-evidence hash.
Any mismatch fails closed before Secret acquisition.

## 3. Secret acquisition ordering

The accepted source enforces this order:

1. validate the exact request profile and evidence;
2. validate the expected credential effective interval;
3. acquire the source-local single-active-lease fence;
4. read `OPENAI_API_KEY_VERSION`;
5. compare the non-secret source version with the admitted version reference;
6. read `OPENAI_API_KEY` as a platform-owned `char[]`;
7. reject missing, empty, oversized or non-printable material;
8. encode using strict UTF-8 error handling;
9. create bounded hash-only source evidence;
10. transfer byte-array ownership to `CredentialMaterialLease.takeOwnership`;
11. zeroize every platform-owned intermediate copy;
12. release the source fence when the lease closes.

Version drift is rejected before Secret material is read. Request drift and invalid credential time
windows are rejected before either environment variable is read.

## 4. Callback-scoped lifecycle

The implementation reuses the accepted connector credential contract rather than creating a second
Secret abstraction.

Accepted lifecycle properties:

- only one active lease per source instance;
- concurrent lease acquisition fails with `CONCURRENT_ACCESS_REJECTED`;
- the lease supplies only a callback-scoped material copy;
- the callback copy is zeroized after callback completion;
- callback failure still closes and releases the lease;
- closing a lease allows a fresh later lease;
- the platform-owned `char[]`, encoded intermediate storage and untransferred `byte[]` are zeroized;
- legacy `openMaterial` material scope is unavailable;
- no material is retained in a source field;
- no caching survives the callback-scoped lease.

## 5. Fail-closed contract

P6-B permanently exercises these closed failures:

- `SOURCE_UNAVAILABLE`;
- `MATERIAL_MALFORMED`;
- `CONCURRENT_ACCESS_REJECTED`;
- `PROVIDER_DRIFT`;
- `TENANT_DRIFT`;
- `REFERENCE_DRIFT`;
- `ROUTE_DRIFT`;
- `BINDING_DRIFT`;
- `VERSION_DRIFT`;
- `MATERIAL_TYPE_DRIFT`;
- `OPERATION_NOT_ALLOWED`;
- `PROTOCOL_DRIFT`;
- `CAPABILITY_DRIFT`;
- `ENVIRONMENT_DRIFT`;
- `POLICY_DRIFT`;
- `CREDENTIAL_NOT_YET_VALID`;
- `CREDENTIAL_EXPIRED`;
- bounded `UNKNOWN` for invalid acquisition ordinals.

No failure includes Secret material or environment-variable names in its message.

## 6. Redaction and evidence boundary

The accepted source exposes only bounded non-secret evidence:

- canonical hashes of admitted binding data;
- a source-evidence hash;
- material length;
- a monotonic acquisition ordinal;
- lease state.

It does not expose or retain:

- API-key text or bytes;
- raw tenant identity;
- raw credential reference;
- Authorization headers;
- Bearer values;
- Provider request or response content;
- customer data;
- Prompt content.

`toString`, descriptors, audit evidence and exceptions remain redaction-safe.

## 7. Deterministic tests

The focused Java class is:

`OpenAiEnvironmentCredentialMaterialSourceTest`

It contains seven deterministic tests covering:

1. callback-scoped copying and zeroization of every platform-owned array;
2. one active lease and fresh acquisition after close;
3. callback failure cleanup;
4. missing, blank, malformed and version-drift failures;
5. exact-request drift rejection before environment access;
6. not-yet-valid and expired version rejection before environment access;
7. redacted evidence/exceptions and disabled legacy scope.

The tests inject `CapturingEnvironment`; they do not call `System.getenv`, read a real Secret, resolve
DNS, open a socket or invoke a Provider.

Permanent Node boundaries are implemented in:

- `scripts/tests/m6-e-p6-openai-provider-audit-boundary.test.mjs`;
- `scripts/tests/m6-e-p6-openai-secret-source-boundary.test.mjs`.

No separate P6-B verification shell script exists. This record cites only tracked files that are
present in the accepted tree.

## 8. Module and runtime isolation

P6-B adds `approval-ai-openai` as a Maven reactor module with only the connector SPI and connector
credential-core dependencies needed for the Secret source.

The module has no dependency on:

- Spring Boot;
- `approval-ai-core`;
- `approval-persistence-jdbc`;
- Flowable;
- an HTTP client.

`apps/server/pom.xml` does not depend on `approval-ai-openai`. Therefore P6-B creates no runtime bean,
route or dispatch path.

The Provider module is permanently checked to contain no:

- `@RestController`;
- orchestration-service coupling;
- durable-evidence-store coupling;
- JDBC or `DataSource` access;
- retained `byte[]` or `char[]` Secret field.

## 9. Zero-transport and zero-invocation boundary

Through P6-B, AI-relevant production source remains prohibited from adding:

- `api.openai.com` endpoint usage;
- `java.net` transport;
- `HttpClient`, `WebClient` or `RestClient`;
- Authorization-header construction;
- Bearer credential construction;
- an approval-assistance POST endpoint;
- a Scheduler;
- Provider invocation or retry;
- Provider-derived durable evidence.

The existing P5 GET endpoint remains read-only and zero-egress. It truthfully reports Provider
unavailability and cannot be converted into a generation endpoint by P6-B.

## 10. Persistence and command boundary

P6-B adds no migration. The unique governed M6-E `V49` remains the repository maximum and no `V50+`
migration exists.

P6-B grants no path to:

- approve;
- reject;
- return;
- transfer;
- withdraw;
- terminate;
- migrate;
- publish;
- activate;
- modify permission or Secret configuration;
- execute arbitrary HTTP, SQL or script content.

AI remains advisory, unverified, human-reviewed and non-operational.

## 11. Compatibility audit

The P6-B implementation required one connector admission compatibility correction:

- `ConnectorExecutionAdmissionAcceptanceContractsTest` now includes the existing generic admission
  contract for `AI_ADVISORY_GENERATE`.

This closes test-matrix coverage for the new operation without changing runtime admission semantics.

The source reuses the existing credential request, version, descriptor, lease, failure and evidence
contracts. It does not fork their lifecycle or weaken existing DingTalk/connector boundaries.

Existing P3 orchestration, P4 durable-evidence and P5 read-only controller types remain legal in their
own modules. Provider-specific coupling checks are scoped to the OpenAI Provider module, while global
transport, invocation, POST, Scheduler and credential-header prohibitions remain repository-wide for
AI-relevant source.

## 12. Hygiene correction audit

Failed permanent runs remain retained as append-only evidence.

The correction sequence addressed only false-positive or incomplete validation rules:

- missing `AI_ADVISORY_GENERATE` generic admission-contract coverage;
- a Controller regex that matched a Javadoc mention;
- a retained-array regex that matched a method return type;
- a multiline whitespace regex that crossed physical lines;
- a Provider Controller scan that incorrectly included the accepted P5 GET controller;
- Provider coupling scans that incorrectly matched the accepted P3/P4 type definitions themselves.

Final rules preserve positive regressions that prove forbidden annotations, retained arrays and
Provider couplings are still detected. No Hygiene gate was removed, bypassed or converted to an
allow-all rule.

## 13. Successful P6-B implementation verification

Exact implementation Head:

`40519efbf5da79ace75304960f043c11d7e764cb`

Permanent workflow:

- Run ID: `30802576969`;
- run number: `1076`;
- event: `pull_request`;
- attempt: `1`;
- conclusion: `success`.

Jobs:

| Job | Job ID | Result |
| --- | ---: | --- |
| Java 21 / Maven / PostgreSQL | `91650417848` | success |
| Vben TypeScript / production build | `91650417815` | success |
| UniApp TypeScript / H5 / WeChat | `91650417846` | success |
| Repository hygiene | `91650417784` | success |

Maven evidence recalculated from `maven-verify.log`:

- aggregate: `1484 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `156 / 156`;
- OpenAI Provider module: `7 / 7`;
- `OpenAiEnvironmentCredentialMaterialSourceTest`: `7 / 7`;
- P5 controller: `3 / 3`;
- Persistence JDBC: `295 / 295`;
- architecture module: `138 / 138`;
- approval-server: `156 / 156`;
- `BUILD SUCCESS`: present;
- all 26 reactor projects: `SUCCESS`;
- total Maven time: `08:46 min`.

Permanent governance evidence:

- M6-E P0-P3 boundary: `9 / 9`;
- P4 durable-evidence boundary: `1 / 1`;
- P5 server boundary: `1 / 1`;
- P5 Web/Mobile boundary: `1 / 1`;
- P6-A exact profile/non-implementation boundary: `2 / 2`;
- P6-B exact Secret source/lifecycle boundary: `2 / 2`;
- combined repository-hygiene entrypoint: `21 / 21`;
- M6 AI foundation: `10 / 10`;
- M6 AI activation review: `6 / 6`;
- M6 AI transport review: `7 / 7`.

## 14. P6-B implementation artifact verification

Every ZIP was independently downloaded and locally SHA-256 hashed. Each local hash exactly matches
GitHub.

| Artifact | ID | Size | GitHub/local SHA-256 | Match |
| --- | ---: | ---: | --- | --- |
| Maven | `8851659776` | `27276` | `64389891110439c9fc873ff913e93ba91e6233ac469b209aef5d69a04b7e55c6` | exact |
| Vben | `8851448775` | `18792` | `c0396abbc6dae097e5075aab2677a566c9ff4595637ae4f4dcec0c0c51642c8c` | exact |
| Mobile | `8851435913` | `9797` | `2f32a3645e020241f4da20dd30e4930ed69d217d954b0e0b06bb07ed80137370` | exact |
| Hygiene | `8851413279` | `9235` | `7976bde8c0dda6b1db1da3b3df8e1314c1a345780508e8b2a7574cbaa63ca864` | exact |

All four artifacts are unexpired and expire at `2026-11-01T09:42:46Z`.

## 15. Review and repository state before this record

Before this acceptance record was committed:

- current main: `fcf031da9e6e04b15a1255044021a7fdd6637421`;
- PR #83: Open + Draft + mergeable;
- branch compare: ahead `119`, behind `0`;
- changed files: `70`;
- requested reviewers: none;
- submitted reviews: two P6-A evidence-only `COMMENTED` records;
- the later P6-A record explicitly states that there is no actionable finding or change request;
- unresolved review threads: none;
- Issue #80: open;
- auto-merge was not enabled;
- no Ready transition or merge authorization exists.

The documented Head created by this record must receive a new natural permanent workflow and four
new independently matched artifacts. Run `30802576969` cannot substitute for documented-Head
validation.

## 16. P6-B formal decision

P6-B is accepted as the exact server-owned OpenAI environment Secret source and callback-scoped lease
conformance slice, subject to the new documented-Head permanent validation.

P6-C remains blocked until:

1. the exact documented-Head Run succeeds;
2. all four documented-Head artifacts are independently SHA-256 matched;
3. Maven and permanent-governance evidence are recalculated;
4. Review, thread and comment checks contain no actionable finding;
5. PR #83 remains Open + Draft;
6. Issue #80 remains open;
7. current main remains unchanged or is merged into the branch using an ordinary Merge Commit and
   fully revalidated;
8. P6-C receives separate explicit authorization.

P6-C is not started or authorized by this document. No request encoder, strict decoder, transport
port, sender or Provider call is introduced here.

`P6_B_OPENAI_SECRET_SOURCE_ACCEPTED`

`CALLBACK_SCOPED_LEASE_AND_ZEROIZATION`

`NO_PROVIDER_TRANSPORT_OR_INVOCATION`

`P6_C_NOT_AUTHORIZED`

`NO_APPROVAL_COMMAND`

`AI_IS_NOT_AN_OPERATOR`
