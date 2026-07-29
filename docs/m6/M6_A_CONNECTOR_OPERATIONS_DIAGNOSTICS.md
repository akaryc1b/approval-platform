# M6-A-P8 Read-Only Connector Operations Diagnostics and Observability

Status: `READ_ONLY_CONNECTOR_OPERATIONS_DIAGNOSTICS_IMPLEMENTED`

Production connector execution: `PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED`

## Decision

P8 adds a default-disabled, GET-only, tenant-scoped diagnostic view over P7's
secret-free invocation evidence. The source is bounded and process-local. It is
not persistence, an audit system, a recovery mechanism, a retry queue, a
reconciliation mechanism, or production execution authorization.

P8 does not add a database table, Flyway migration, worker, scheduler, event
listener, retry, replay, recovery, Token refresh, credential rotation, route
mutation, Kill Switch mutation, Provider fallback, or approval-state mutation.

## Source and retention

`BoundedConnectorOperationsDiagnosticsStore` receives only P7
`InvocationEvidence`; it never receives the raw trusted tenant, request body,
Provider response, credential reference, Secret, Token, Authorization header,
Cookie, host, path, URL, exception message, stack trace, request ID or trace ID.

The store:

- retains at most 1,024 entries by default;
- retains at most 256 entries per tenant hash by default;
- evicts the oldest evidence when a bound is reached;
- is lost on restart;
- cannot authorize execution or recovery;
- returns only tenant-local entries and tenant-local counts.

## Pagination and filters

Pagination is stable newest-first using a captured sequence high-watermark.
Opaque page tokens bind:

- tenant hash;
- exact filter hash;
- high-watermark;
- next sequence boundary;
- a process-local HMAC-SHA-256 integrity proof.

A token cannot be reused for another tenant or another filter set. Restart
rotates the process-local key and invalidates outstanding tokens. No token
contains a raw tenant or grants Provider, credential, Token, endpoint or
operator authority.

Filters are exact and allowlisted. Wildcards, arbitrary search, fuzzy matching
and resource-existence probing are rejected.

## HTTP surface

The only P8 endpoints are:

- `GET /api/approval/management/connector-operations/diagnostics`;
- `GET /api/approval/management/connector-operations/diagnostics/summary`.

Both reuse existing management authentication, trusted tenant injection and the
existing tenant-scoped `OPERATIONAL_FAILURE_READ` permission. Both return
`Cache-Control: no-store`.

There is no POST, PUT, PATCH or DELETE endpoint and no refresh, invalidate,
rotate, retry, replay, recover, clear, enable or disable action.

## Stable API failures

P8 maps only redacted stable classifications:

- 400 invalid query or page token;
- 403 existing management permission denial;
- 404 non-leaking not-found classification;
- 409 snapshot conflict classification;
- 422 response-size bound;
- 503 process-local source unavailable;
- 500 internal diagnostic failure.

Arbitrary exceptions and stack traces are never returned.

## Observability

The Micrometer adapter uses only closed low-cardinality tags:

- Provider;
- connector operation;
- completion outcome;
- stable failure code;
- duration bucket.

Tenant, credential, Token, request, trace, process instance, user and endpoint
identities are prohibited as metric tags. Diagnostics or metrics failure is
best effort and cannot change P7 invocation semantics.

## Explicit non-authority

Every page and summary remains:

```text
processLocal = true
persistent = false
auditSystem = false
recoveryMechanism = false
productionExecutionAuthorized = false
approvalStateMutationAuthorized = false
```

P8 does not close any production readiness blocker.

`PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED`
