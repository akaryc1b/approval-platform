# M6-E P6-C OpenAI Responses Codec Acceptance

Status: `M6_E_P6_C_FORMALLY_ACCEPTED_PENDING_DOCUMENTED_HEAD_VALIDATION`

Date: `2026-08-04`

Tracking:

- parent milestone: Issue #62;
- roadmap rebaseline: Issue #78, closed / completed;
- workstream: Issue #80;
- Pull Request: #83;
- branch: `agent/m6-e-governed-ai-approval-assistance`;
- target: `main`;
- exact base/current main: `fcf031da9e6e04b15a1255044021a7fdd6637421`.

This record accepts only the P6-C deterministic OpenAI Responses request encoder, strict response
decoder, bounded transport-port contract and deterministic tests. It does not authorize a concrete
HTTP sender, endpoint connection, DNS resolution, TLS session, SSRF admission, rate or cost gate,
production Provider bean, runtime route, Provider invocation, public generation endpoint,
Provider-derived P4 write, migration, approval command or automation command.

## 1. P6-B entry gate

P6-C began only after P6-B completed its independent documented-Head acceptance and the user
separately authorized P6-C continuation.

Accepted P6-B evidence:

- exact documented Head: `e3e7c924e997ab1fa1490dc7ca316477455432d9`;
- permanent Run: `30803572167` / run number `1077`;
- conclusion: `success`;
- all four permanent jobs: `success`;
- all four documented-Head artifacts: independently downloaded and SHA-256 matched;
- Maven aggregate: `1484 / 0 / 0 / 0`;
- OpenAI Provider module: `7 / 7`;
- combined repository hygiene: `21 / 21`;
- PR #83 remained Open + Draft;
- Issue #80 remained open;
- P6-C alone was authorized.

At P6-C implementation acceptance, current `main` remains
`fcf031da9e6e04b15a1255044021a7fdd6637421`. The branch is ahead `130`, behind `0`.
PR #83 remains Open + Draft + mergeable. Issues #80, #62, #13 and #14 remain open.

## 2. Accepted P6-C scope

P6-C adds four production protocol types in the isolated `approval-ai-openai` module:

1. `OpenAiResponsesProtocol`;
2. `OpenAiResponsesRequestEncoder`;
3. `OpenAiResponsesResponseDecoder`;
4. `OpenAiResponsesTransportPort`.

P6-C adds four deterministic Java test classes:

1. `OpenAiResponsesRequestEncoderTest`;
2. `OpenAiResponsesResponseDecoderTest`;
3. `OpenAiResponsesTransportPortTest`;
4. `OpenAiResponsesCodecHardeningTest`.

P6-C adds two permanent Node boundaries:

- `scripts/tests/m6-e-p6-openai-codec-boundary.test.mjs`;
- `scripts/tests/m6-e-p6-openai-codec-hardening-boundary.test.mjs`.

Both are loaded through the existing `scripts/tests/m3-repository-hygiene.test.mjs` entrypoint. No
second automatic workflow was added.

P6-C adds no Spring component, runtime bean, controller, server dependency, HTTP-client dependency,
JDBC dependency, Flowable dependency, migration, Web source or Mobile source.

## 3. Official protocol recheck

The official OpenAI API reference was rechecked before final P6-C acceptance. The reference confirms
that:

- Responses supports `tool_choice=none`;
- strict Structured Outputs use `text.format.type=json_schema` and `strict=true`;
- the response object contains status, model, output and usage evidence;
- assistant text is represented by an `output_text` content item;
- current response objects may contain optional fields including `max_tool_calls`, `prompt` and
  `top_logprobs`;
- `store=false` is the required stateless data-control posture selected by P6-A;
- background mode is incompatible with the accepted no-polling and no-application-state boundary.

External protocol documentation supports field compatibility only. It grants no deployment,
credential, network or execution authority.

## 4. Exact protocol vocabulary

`OpenAiResponsesProtocol` freezes:

- provider ID: `openai-responses`;
- provider version: `responses-v1`;
- model ID: `gpt-5-mini`;
- model version: `2025-08-07`;
- exact model snapshot: `gpt-5-mini-2025-08-07`;
- output Schema ID: `approval-assistance`;
- output Schema version: `1`;
- response-format name: `approval_assistance_v1`;
- maximum encoded request: `262144` bytes;
- maximum decoded response: `262144` bytes;
- maximum transport response envelope: `524288` bytes;
- maximum server Prompt: `12000` characters;
- maximum structured output text: `240000` characters.

The closed failure vocabulary covers invalid or oversized requests, HTTP rejection, missing or
mismatched request-identifier evidence, oversized or invalid UTF-8 response bodies, malformed or
duplicate JSON, unknown properties, incomplete status, Provider error, refusal, non-exact output,
model/Schema/version mismatch, invalid usage and invalid advisory results.

Protocol exceptions expose only the closed failure classification. They do not include Provider
request or response bodies.

## 5. Deterministic request encoder

The encoder accepts only an exact server-owned `AiProviderRequest`, exact server-owned Prompt
evidence, bounded P2 output limits and a bounded output-token ceiling.

The encoded root request is fixed to:

- model `gpt-5-mini-2025-08-07`;
- `store=false`;
- `background=false`;
- `stream=false`;
- `tools=[]`;
- `tool_choice=none`;
- `truncation=disabled`;
- finite `max_output_tokens` not greater than `16384`;
- one server-owned `instructions` value;
- text-only input;
- strict `approval_assistance_v1` JSON Schema.

The encoder rejects:

- Provider ID or provider-version drift;
- model ID, version or Provider drift;
- knowledge-source material other than `KnowledgeSourceVersion.none()`;
- output-Schema drift;
- Prompt template/version/hash drift;
- Prompt/use-case mismatch;
- an empty Provider-safe field set;
- timeout greater than 15 seconds;
- output-token ceiling greater than 16384;
- null, unsupported, excessive-depth or non-finite Provider-safe values;
- encoded request bodies over 262144 bytes.

Provider-safe fields and nested map keys are deterministically ordered before serialization. The
request body hash is calculated over the exact UTF-8 bytes.

The encoder never serializes:

- tenant identity;
- operator identity;
- task or process-instance identity;
- authorization reference;
- browser-supplied Provider selection;
- Secret material;
- metadata;
- `previous_response_id`;
- conversation state;
- prompt-cache-retention override.

## 6. Strict JSON Schema

The strict output Schema contains only bounded advisory data:

- summary;
- observations;
- risk signals;
- missing materials;
- recommendations;
- evidence references;
- confidence;
- limitations;
- exact versions;
- mandatory human-review and non-authority classifications.

Every object uses:

`additionalProperties=false`

Every object declares all accepted properties as required. Collections retain the accepted P2 item,
evidence and limitation bounds.

P6-C hardening corrected two boolean-Schema defects before acceptance:

- `needsHumanReview` is encoded as a typed boolean constant whose only accepted value is `true`;
- `knowledgeSource.containsCustomerData` is encoded as a typed boolean constant whose only accepted
  value is the exact request-version value, which is `false` for P6-C.

A raw JSON-Schema boolean is not used as a substitute for a boolean-valued property Schema.

The confidence contract remains exactly the accepted P2 contract:

- score `< 0.50`: `LOW`;
- score `>= 0.50` and `< 0.80`: `MEDIUM`;
- score `>= 0.80`: `HIGH`.

The earlier one-third/two-thirds implementation assumption was rejected and replaced before formal
acceptance.

## 7. Strict response decoder

The decoder accepts only:

- HTTP status `200`;
- a non-blank bounded request identifier whose SHA-256 matches admitted evidence;
- a non-empty response body not greater than 262144 bytes;
- strict UTF-8;
- duplicate-property-detecting JSON;
- root object `object=response`;
- root status `completed`;
- no Provider error or incomplete details;
- model exactly `gpt-5-mini-2025-08-07`;
- `store=false`;
- `background=false`;
- no previous response or conversation;
- no Prompt reference, prompt-cache key/retention override, user or safety identifier;
- no tool-call budget;
- `top_logprobs` absent, null or zero;
- metadata absent, null or empty;
- empty tools and `tool_choice=none`;
- strict `approval_assistance_v1` text format;
- exactly one completed assistant message;
- exactly one `output_text` content item;
- empty annotations and no log probabilities;
- exactly one structured advisory payload.

The current official optional response fields `max_tool_calls`, `prompt` and `top_logprobs` are
recognized by the whitelist, but they cannot activate state or tools. A non-null Prompt reference,
non-null prompt-cache retention, non-empty metadata or non-zero top-logprobs value fails closed.

The structured payload rejects:

- unknown or duplicate properties;
- non-advisory authority or assertion status;
- `needsHumanReview=false`;
- exact-version drift;
- over-limit collections or strings;
- duplicate item/evidence IDs;
- evidence for a field outside the exact Provider-safe key set;
- missing, repeated, unresolved or unused evidence;
- non-finite confidence;
- confidence-band mismatch;
- invalid enum values;
- invalid token-usage totals or detail counts.

The decoder constructs a fresh `AiAdvisoryResult` and therefore re-applies the SPI structural
invariants. P2-level request/result validation remains required in the later orchestration/invocation
boundary; decoding does not grant authority.

## 8. Transport port only

`OpenAiResponsesTransportPort` is an interface with no production implementation in P6-C.

Its request contract owns defensive copies of:

- exact request bytes;
- exact request-body SHA-256;
- connect timeout no greater than 2 seconds;
- total timeout no greater than 15 seconds.

Its response contract owns defensive copies of:

- bounded HTTP status;
- request identifier;
- bounded response bytes.

`toString` outputs body length, body hash or request-ID hash only. It does not render request bytes,
response bytes or the raw request identifier.

The only `exchange` implementation in P6-C is a deterministic test lambda. No production class
implements the port and no production source calls `exchange`.

## 9. No sender, invocation or runtime authority

P6-C production source contains no:

- `api.openai.com` endpoint literal;
- `java.net` transport;
- `HttpClient`, `WebClient` or `RestClient`;
- Authorization-header construction;
- Bearer credential construction;
- Secret lease use;
- DNS lookup;
- TLS socket or certificate validation;
- redirect handling;
- rate limiter or cost admission;
- circuit or kill-switch runtime binding;
- Provider bean;
- Spring `@Component` or `@Bean`;
- controller or POST endpoint;
- Provider invocation;
- retry, fallback, polling, Queue, Worker or Scheduler;
- P4 durable-evidence store call;
- JDBC or `DataSource` access;
- Flowable or approval command dependency.

`apps/server/pom.xml` still does not depend on `approval-ai-openai`. The accepted P5 GET endpoint
remains zero-egress and still truthfully reports Provider unavailability.

## 10. Deterministic test evidence

The final OpenAI Provider module contains `23 / 23` passing tests:

| Test class | Result |
| --- | ---: |
| `OpenAiEnvironmentCredentialMaterialSourceTest` | `7 / 7` |
| `OpenAiResponsesCodecHardeningTest` | `3 / 3` |
| `OpenAiResponsesRequestEncoderTest` | `4 / 4` |
| `OpenAiResponsesResponseDecoderTest` | `6 / 6` |
| `OpenAiResponsesTransportPortTest` | `3 / 3` |

The P6-C codec-focused total is `16 / 16`.

Deterministic tests cover:

- canonical byte-for-byte request equality and body hashes;
- exact model, Provider and Prompt profile rejection;
- identity/authority exclusion;
- typed boolean Schema constants;
- P2 confidence thresholds;
- request, token and timeout bounds;
- unsupported and non-finite values;
- completed response decoding;
- request-ID, status, model, Schema, version, usage and evidence drift;
- Provider errors, incomplete results and refusals;
- duplicate, unknown, malformed, invalid UTF-8 and oversized responses;
- current known optional response fields under the stateless profile only;
- defensive copies and redaction-safe `toString`;
- exactly one deterministic test exchange.

Tests do not read `OPENAI_API_KEY`, call `System.getenv`, contact `api.openai.com`, resolve DNS, open a
socket or require a paid/customer Provider account.

## 11. Permanent architecture boundaries

The final combined repository-hygiene entrypoint passes `25 / 25`.

P6-C-specific permanent evidence:

- request encoder and strict decoder exact-profile gate: `1 / 1`;
- no sender/invocation/endpoint/persistence/command gate: `1 / 1`;
- P2-confidence and typed-boolean-Schema hardening gate: `1 / 1`;
- current-known-fields stateless compatibility gate: `1 / 1`.

Retained M6-D permanent evidence also remains green:

- M6 AI foundation: `10 / 10`;
- M6 AI activation review: `6 / 6`;
- M6 AI transport review: `7 / 7`.

The Provider audit allows only the exact accepted P6-B Secret source and P6-C protocol types. It
still rejects a production sender, Controller, orchestration coupling and P4 store coupling.

## 12. Retained append-only validation sequence

The first complete P6-C implementation Head was:

`f6e875191450bc082e516c77359179a9f6ba939b`

Its natural Run `30807893931` / #1080 succeeded. Subsequent source audit identified compatibility and
contract-hardening requirements, so that earlier successful Run was not used as final acceptance
evidence.

Append-only hardening Runs were naturally superseded:

| Head | Run | Run number | Conclusion | Reason |
| --- | ---: | ---: | --- | --- |
| `d78613088105155778511067935ab95dc0225968` | `30867587599` | #1081 | cancelled | later strict-Schema hardening superseded it |
| `e6a452d9cd86b27022bdb984621ea3905a667770` | `30867640594` | #1082 | cancelled | later response-compatibility hardening superseded it |
| `39c231c523e34d7dfe7e910f57a4b3c39795daba` | `30867717795` | #1083 | cancelled | deterministic regressions were still required |
| `43324ad906552b95f267ab764d62fb11f5303668` | `30867780008` | #1084 | cancelled | permanent hardening gate was still required |
| `d882a4d0aa4bd346aaebc6c4f916287d3802132b` | `30867808351` | #1085 | cancelled | combined Hygiene loading was still required |

Final pre-correction candidate Head:

`29d4a4cd16a5c0eaeccdf944af82969806fee3c7`

Natural Run:

- Run ID: `30867825052`;
- run number: `1086`;
- conclusion: `failure`;
- Repository hygiene: success;
- Maven: failure at Checkstyle before tests;
- exact cause: two unused imports in `OpenAiResponsesCodecHardeningTest`;
- affected imports: `ArrayNode` and `StandardCharsets`;
- no production code, protocol behavior or security boundary failed.

Minimal append-only correction:

- commit: `55b3dc0a3937d737be131e08f4425e3a09814355`;
- change: remove only the two unused test imports;
- no production code or permanent boundary changed.

Run #1086 was not rerun, deleted, hidden or used as acceptance evidence.

## 13. Successful P6-C implementation verification

Exact implementation Head:

`55b3dc0a3937d737be131e08f4425e3a09814355`

Permanent workflow:

- Run ID: `30867965877`;
- run number: `1087`;
- event: `pull_request`;
- attempt: `1`;
- conclusion: `success`.

Jobs:

| Job | Job ID | Result |
| --- | ---: | --- |
| Java 21 / Maven / PostgreSQL | `91863776497` | success |
| Vben TypeScript / production build | `91863776962` | success |
| UniApp TypeScript / H5 / WeChat | `91863776522` | success |
| Repository hygiene | `91863776529` | success |

Maven evidence recalculated from `maven-verify.log`:

- aggregate: `1500 / 0 / 0 / 0`;
- AI SPI: `12 / 12`;
- AI Core: `156 / 156`;
- OpenAI Provider module: `23 / 23`;
- P6-B Secret source: `7 / 7`;
- P6-C codec-focused: `16 / 16`;
- Persistence JDBC: `295 / 295`;
- architecture module: `138 / 138`;
- approval-server: `156 / 156`;
- all 26 Maven reactor projects: `SUCCESS`;
- `BUILD SUCCESS`: present;
- total Maven time: `08:30 min`.

Permanent governance evidence:

- combined repository hygiene: `25 / 25`;
- P6-C codec boundaries: `4 / 4`;
- M6 AI foundation: `10 / 10`;
- M6 AI activation review: `6 / 6`;
- M6 AI transport review: `7 / 7`;
- Vben TypeScript and production build: success;
- UniApp TypeScript, H5 and WeChat builds: success.

## 14. P6-C implementation artifact verification

Every ZIP was downloaded and independently SHA-256 hashed. Each local hash exactly matches GitHub.

| Artifact | ID | Size | GitHub/local SHA-256 | Match |
| --- | ---: | ---: | --- | --- |
| Maven | `8876964097` | `27386` | `efd51cb5f5174dd58ff9d61917a9836e4ae0b63cb040322037cc6bc454131c71` | exact |
| Vben | `8876841613` | `18884` | `554c20fd4b275e79440f0ff4ecf2bca4151aa1f0735b6f0d72ea7b9a5cb502c4` | exact |
| Mobile | `8876824964` | `9801` | `d227777b2c8b588d7d703867eec697c407b168ef7942694788db5a44bc94be83` | exact |
| Hygiene | `8876812866` | `9260` | `438ab474344f59ed11f0d3777d0ee20d7fa11c00ad2598995537da84f59c3a4c` | exact |

All are unexpired and expire at `2026-11-02T01:11:13Z`.

## 15. Review and repository state before this record

Before this acceptance record was committed:

- current main: `fcf031da9e6e04b15a1255044021a7fdd6637421`;
- PR #83: Open + Draft + mergeable;
- branch compare: ahead `130`, behind `0`;
- changed files: `81`;
- commits: `130`;
- requested reviewers: none;
- submitted Reviews: two P6-A evidence-only `COMMENTED` records;
- the later P6-A Review explicitly states there is no actionable finding or change request;
- unresolved review threads: none known;
- Issue #80: open;
- auto-merge: not enabled;
- Ready transition: not performed;
- merge authorization: none.

The documented Head created by this record must receive a new natural permanent workflow and four
new independently matched artifacts. Run `30867965877` cannot substitute for documented-Head
validation.

## 16. Explicit absence of P6-D through P7 capability

P6-C introduces no:

- concrete OpenAI sender;
- endpoint admission or actual network connection;
- DNS resolution or rebinding control implementation;
- TLS certificate/hostname verification implementation;
- redirect or SSRF sender implementation;
- API-key lease consumption during transport;
- rate, budget or cost admission implementation;
- production circuit/kill-switch integration;
- server-owned production invocation service;
- production Provider bean or route;
- distinct invocation API;
- production Provider call;
- paid/customer Provider call in CI;
- Provider-derived P4 evidence write;
- new migration or V50+ migration;
- Queue, Worker, Scheduler, polling or retry;
- approve, reject, return, transfer, withdraw, terminate, migrate, publish or activate command;
- M6-F capability.

## 17. P6-C formal decision

P6-C is accepted as the deterministic, stateless and strict OpenAI Responses codec plus a bounded
transport-port contract, subject to the new documented-Head permanent validation.

P6-D remains blocked until:

1. the exact documented-Head Run succeeds;
2. all four documented-Head artifacts are independently SHA-256 matched;
3. Maven, P6-C focused and permanent-governance evidence are recalculated;
4. Review, thread, comment and reaction checks contain no actionable finding;
5. PR #83 remains Open + Draft;
6. Issue #80 remains open;
7. current main remains unchanged or is merged into the branch through an ordinary Merge Commit and
   fully revalidated;
8. P6-D receives separate explicit authorization.

P6-D is not started or authorized by this document. P6-D may later add only the concrete
DNS/TLS/SSRF-enforced sender and exact pre-dispatch rate/cost/circuit/kill-switch admission. It may
not add the server invocation API, P4 integration or approval commands reserved for later gates.

`P6_C_OPENAI_RESPONSES_CODEC_ACCEPTED`

`STRICT_JSON_SCHEMA_AND_P2_CONFIDENCE`

`STATELESS_STORE_FALSE_TOOLS_NONE`

`TRANSPORT_PORT_WITH_NO_SENDER`

`NO_PROVIDER_INVOCATION`

`P6_D_NOT_AUTHORIZED`

`NO_APPROVAL_COMMAND`

`AI_IS_NOT_AN_OPERATOR`
