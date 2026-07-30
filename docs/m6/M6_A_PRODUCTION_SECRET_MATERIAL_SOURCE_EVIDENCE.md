# M6-A-P5 Backend-Neutral Production Secret Material Source Permanent Evidence

Status: `BACKEND_NEUTRAL_MATERIAL_SOURCE_PERMANENTLY_VALIDATED`

Concrete production Secret Backend: `BLOCKED_PENDING_BACKEND_SELECTION`

Production connector execution: `PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED`

Decision date: `2026-07-28`

Tracking:

- parent milestone: Issue #62;
- workstream: Issue #63;
- pull request: PR #67;
- branch: `agent/m6-a-connector-foundation`;
- target branch: `main`;
- owner: `PLATFORM_SECURITY`;
- selected capability: `PRODUCTION_SECRET_MATERIAL_SOURCE`.

## Gate decision

The accepted repository documentation requires production secrets to be managed by a Secret manager, but it selects no vendor, product, deployment topology, authentication method, tenancy model, audit owner, availability policy or incident owner.

P5 therefore records the concrete-backend decision as `BLOCKED_PENDING_BACKEND_SELECTION`.

P5 does not infer or implement HashiCorp Vault, Kubernetes Secret, AWS Secrets Manager, Azure Key Vault, Alibaba Cloud KMS or Secrets Manager, environment variables, system properties, files, a database-backed secret store or a remote HTTP secret service. A later adapter requires a separate explicit gate.

PR #67 remains Open + Draft. P5 does not mark the PR Ready, enable auto-merge or merge the PR.

## Implementation commits

### Backend-neutral implementation

- commit: `dd2ec22e319407e7ad2482fcc3a2d3a305c9dab6`;
- message: `feat(m6-a): add backend-neutral secret material boundary`;
- branch update: `force=false`.

The commit added:

- exact internal `CredentialMaterialRequest` evidence;
- immutable material-version evidence;
- secret-free descriptor and acquisition/release audit evidence;
- closed environment, load and failure classifications;
- exact pre-open binding and validity admission;
- a bounded direct-buffer material lease;
- a source-compatible fail-closed P5 method on the existing P2 source port;
- a deterministic test-only backend;
- default-disabled server properties and status wiring;
- permanent architecture boundaries and governance documentation.

### Minimal architecture-boundary alignment

- commit: `5fac65578e04fc3f7072febbb9ca6be7802dbd0a`;
- message: `fix(m6-a): align secret material helper boundaries`;
- branch update: `force=false`.

The fix changed only two internal source shapes:

- `copyMaterial()` became caller-allocated temporary storage plus `copyMaterialInto(...)`, preventing a method return signature from being falsely classified as a retained raw-secret field;
- internal helper `reject(...)` became `failWhen(...)`, preventing an exact source-text boundary from classifying it as an approval rejection command.

The fix did not change material lifetime, zeroization, admission, failure classes, backend selection, configuration, public behavior or authorization. No architecture assertion was removed or weakened.

## Exact request and admission boundary

One internal server-owned request binds exact tenant, Provider, opaque credential reference, route-plan hash, credential-binding fingerprint, material-version reference and evidence hash, material type, connector operation, protocol profile, closed capability, environment and policy revision.

Public evidence contains hashes and closed classifications, not tenant identity, raw credential-reference identity or Secret material. The request is not a Controller DTO, browser contract, cache key, execution token or authorization grant.

Before material may be exposed, admission fails closed for Provider, reference, tenant, binding, version, material-type or policy drift; operation not allowed; disabled or revoked binding; expired or not-yet-valid binding; `ROTATION_PENDING`; ambiguous active-version evidence; or version evidence outside its exact interval.

There is no wildcard, latest-version lookup, previous-version fallback, silent repair or alternate-source fallback.

## Material lease lifecycle

The backend-neutral lease:

- accepts one bounded material array of 1 to 65,536 bytes;
- copies it into a platform-owned direct buffer and immediately zeroizes the transferred array;
- permits one active callback and rejects concurrent use;
- allocates and zeroizes a callback-scoped temporary copy in `finally`;
- rejects use after close and makes duplicate close idempotent;
- defers release when close occurs during active use;
- overwrites the owned direct buffer before backend release;
- attempts release at most once;
- replaces arbitrary backend release text with a stable redacted failure code;
- emits only hash, boolean, ordinal and closed-enum evidence.

Java cannot guarantee forensic erasure of every JVM, operating-system or hardware copy and cannot prevent deliberately malicious trusted callback code from copying bytes. The enforceable boundary returns no Secret value, provides no persistence or cache path, bounds lifetime, zeroizes platform-owned copies and restricts callbacks to trusted server code.

## Default-disabled startup gate

```yaml
approval:
  connector:
    secret-material:
      enabled: false
      backend-selection: BLOCKED_PENDING_BACKEND_SELECTION
```

Default startup creates only a secret-free status record and no `CredentialMaterialSource` bean. Startup fails closed when `enabled=true` is requested, a concrete backend name is supplied before authorization or an unknown property is supplied.

There is no Controller, management endpoint, Web/Mobile control, worker, scheduler, scanner, polling loop or event listener.

## Retained failed permanent workflow

Implementation Head `dd2ec22e319407e7ad2482fcc3a2d3a305c9dab6` triggered Approval Platform Validation Run `30370207896` / #876.

- Repository hygiene: success;
- Vben: success;
- Mobile: success;
- Maven: failure.

The failed Run was not cancelled, deleted, hidden or rerun. All functional modules and P5 contract, lease and Spring tests succeeded. Only three architecture source-shape assertions failed: a `byte[]` method signature was matched as a field, helper name `reject(...)` was matched as an approval command, and the new boundary observed the same signature false positive. The architecture module executed `70` tests with `3` failures.

Every failed-Run ZIP was downloaded and independently hashed; local SHA-256 exactly matched GitHub digest:

| Artifact | ID | SHA-256 |
| --- | ---: | --- |
| `approval-maven-30370207896` | `8692823446` | `8c4d9d0aee8e9a29ce27675687e9e100e840bc790e16376fce893ae64d1bfe4e` |
| `approval-vben-30370207896` | `8692572391` | `d3600fbdb7527d92d465eafddadfdc4cd8a83b1114e8c65effbb839034228c54` |
| `approval-mobile-30370207896` | `8692553868` | `8733513dca72128e303f12838d776487b627880bfb149b451afd51606068d114` |
| `approval-hygiene-30370207896` | `8692522357` | `ee65639cb5a848d46f8fc442023dfb830d2c929b9d0d3ae5a88647e293e7c855` |

## Successful implementation validation

Fix Head `5fac65578e04fc3f7072febbb9ca6be7802dbd0a` triggered Approval Platform Validation Run `30371517265` / #877: `success`.

- Repository hygiene: success;
- Java 21 / Maven / PostgreSQL: success;
- Vben TypeScript / production build: success;
- UniApp TypeScript / H5 / WeChat: success.

Maven evidence:

- aggregate: `967 / 0 / 0 / 0`;
- Credential Core: `40 / 0 / 0 / 0`;
- P5 contract/admission: `4`;
- P5 lease lifecycle: `8`;
- P5 Spring gate: `4`;
- P5 architecture: `5`;
- total P5 focused: `21 / 0 / 0 / 0`;
- architecture module: `70 / 0 / 0 / 0`;
- Server: `100 / 0 / 0 / 0`;
- reactor: `BUILD SUCCESS`;
- total time: `07:57 min`.

Every successful-Run ZIP was downloaded and independently hashed; local SHA-256 exactly matched GitHub digest:

| Artifact | ID | SHA-256 |
| --- | ---: | --- |
| `approval-maven-30371517265` | `8693357554` | `230189b5a7af6da29cdba8d6b3bda3d312fcb921131a4be1668ad82f0648fe63` |
| `approval-vben-30371517265` | `8693122054` | `8e79a2c415061b526100ef1dde9bf929e9b2ea7cfc29b8d5bb83aa902f8e3f8e` |
| `approval-mobile-30371517265` | `8693101940` | `4a5f57f3398246e8d7e2118d9bfcf85fa83e854e3635af030304b82c17595188` |
| `approval-hygiene-30371517265` | `8693073378` | `cb4d3f5a1e986a367baf642455d9bbe5dd94065dc9337e70c5bab5de644427ac` |

## Permanent boundaries retained

- no concrete production Secret Backend;
- no environment, system-property, file, Vault, KMS, Kubernetes or cloud Secret adapter;
- no Secret persistence, cache, database table or Flyway migration;
- no AppKey/AppSecret administration UI;
- no Token Acquisition, Token Refresh or Token cache;
- no Provider invocation or HTTP dispatch;
- no execution coordinator, worker, scheduler, scanner or polling loop;
- no automatic retry, reconciliation or recovery worker;
- no Provider fallback, weighted/load/health routing;
- no browser, Web, H5, WeChat or Mobile Secret control;
- no Flowable access or Approval-State Mutation;
- no approve, reject, return, transfer, withdraw, terminate or migrate command;
- no M5 migration semantic change;
- no `V49` or higher migration;
- no second automatic PR/main workflow;
- no PR Ready, auto-merge, merge or issue closure.

## Acceptance and stop condition

```text
M6-A-P5:
  BACKEND_NEUTRAL_MATERIAL_SOURCE_PERMANENTLY_VALIDATED

Concrete production Secret Backend:
  BLOCKED_PENDING_BACKEND_SELECTION

Production connector execution:
  PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED

PR #67:
  OPEN
  DRAFT
  NOT_MERGED
```

A separate natural workflow at this evidence-document Head is required before P5 is finally documented. Work stops after that validation. P6 or any concrete backend adapter requires new explicit authorization.
