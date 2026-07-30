# M6-A-P7 Governed Read-Only Connector Invocation

Status: `GOVERNED_READ_ONLY_INVOCATION_IMPLEMENTED_DEFAULT_DISABLED`

Production connector execution: `PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED`

Tracking:

- parent milestone: Issue #62;
- workstream: Issue #63;
- pull request: PR #67;
- branch: `agent/m6-a-connector-foundation`;
- target branch: `main`.

## Decision

P7 introduces one synchronous, bounded and default-disabled coordinator for governed
read-only Connector invocation. It composes the accepted P4 route resolver and
revalidator with the accepted P6 Token coordinator, then exposes one closed Token-bound
transport seam. The repository intentionally provides no production implementation of
that seam, so P7 does not create a complete path to real DingTalk.

The coordinator is process-local and contains no JDBC, transaction, Outbox, durable
invocation table, Worker, Scheduler, queue consumer, event listener, polling loop,
automatic retry, fallback or recovery behavior.

## Closed invocation matrix

The coordinator accepts only the two P4 route intents:

| Intent | Capability | Connector operation | Provider operation | API family |
| --- | --- | --- | --- | --- |
| `ORGANIZATION_READ_USER_BY_ID` | `ORGANIZATION` | `ORGANIZATION_READ` | `USER_BY_ID` | `OPEN_API_V1` or `LEGACY_OAPI` |
| `IDENTITY_RESOLVE_DINGTALK_USERID` | `AUTHENTICATION` | `IDENTITY_RESOLVE` | `dingtalk-userid` | `LEGACY_OAPI` |

The request contract cannot carry a Provider, host, endpoint, path, header, Cookie,
credential reference, Token reference or arbitrary operation. The dispatch request is
created only after exact route resolution, two route validations and Token acquisition.
No Provider, route, API-family or credential fallback exists.

## Ordered security gates

One invocation performs the following sequence:

1. reject a closed coordinator;
2. accept a trusted server-owned tenant identity outside the request DTO;
3. resolve one exact P4 route plan;
4. evaluate the invocation Kill Switch and exact revision;
5. revalidate the route and credential binding;
6. create one exact server-owned P6 Token request;
7. acquire one P6 Token lease;
8. evaluate the Kill Switch again after Token acquisition;
9. revalidate the route and credential binding again;
10. bind the Token evidence to the exact route and credential request;
11. dispatch at most once through the injected read-only transport seam;
12. close the Token lease in all paths;
13. emit hash-only bounded evidence;
14. return a typed read-only result or stable failure classification.

Every failure before step 11 has `dispatchCount = 0`. A timeout, null result, transport
exception or unknown transport state after step 11 is classified as
`UNKNOWN_AFTER_DISPATCH`. The coordinator never retries automatically.

## Secret-free evidence

P7 evidence contains only bounded hashes, closed enum values and low-cardinality state:

- tenant and request hashes;
- route plan and route definition hashes;
- route credential reference hash;
- application credential binding fingerprint and version evidence;
- Token evidence hash and outcome;
- transport profile, API family, connector operation and Provider operation;
- gate result, dispatch attempted, dispatch count and completion classification;
- duration bucket, stable failure code and evidence hash.

Evidence and `toString()` methods exclude raw tenant IDs, subject IDs, credential
references, AppKey, AppSecret, Access Token, Authorization, Cookie, request body,
response body, endpoint URL, exception message and stack trace. Provider result values
are returned only to the caller; evidence binds no raw Provider result.

## Token and material lifecycle

P7 uses `DingTalkAccessTokenLease.use(...)`. P6 creates a scoped Token copy for the
single dispatch callback, zeros that copy in `finally`, and P7 closes the lease with
try-with-resources. P6 continues to close and zero the application credential material
lease used for Token acquisition. P7 retains neither lease nor raw material.

## Spring gate

Base configuration is literal default disabled:

```yaml
approval:
  connector:
    invocation:
      enabled: false
      policy-version: connector-invocation-policy-v1
      maximum-request-bytes: 65536
      maximum-response-bytes: 262144
      timeout: 5s
      kill-switch-revision: kill-switch-v1
      token-policy-version: dingtalk-token-policy-v1
```

Unknown properties fail closed. When disabled, no coordinator bean exists. When
enabled, startup requires the route resolver, route revalidator, P6 Token coordinator,
Kill Switch, exact Token-request source, Token-bound read-only dispatch port and Clock.
No fake dependency, real Token endpoint, concrete Secret Backend or production transport
adapter is auto-created.

## Read-only and production authority

Every result permanently declares:

- `readOnly = true`;
- `approvalStateMutationAuthorized = false`;
- `productionExecutionAuthorized = false`.

P7 adds no Controller, mutation endpoint, Flowable command, process-instance write,
task completion, approval decision, organization write or identity write.

## Verification scope

Focused tests cover the closed matrix, tenant isolation, route and credential drift,
Token failure and policy drift, pre/post Token Kill Switch behavior, exactly-once
dispatch, timeout/exception unknown classification, no retry, lease release and
zeroization, evidence redaction, response bounds, concurrent tenant/version isolation,
coordinator close and immutable authority flags.

Architecture tests prohibit persistence, transactions, Flowable, Web Controllers,
Workers, Schedulers, retries, recovery, V49+ and a second automatic PR/main workflow.

## Retained blockers

P7 does not select or implement a concrete production Secret Backend, a real DingTalk
Token endpoint adapter, a production Token-bound dispatch adapter, production
credential provisioning, production egress ownership, distributed Token lifecycle,
durable invocation audit or unknown-after-dispatch reconciliation.

`PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED`
