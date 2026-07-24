# Security binding blueprint

This blueprint is deterministic and transport-free. It contains no endpoint address or usable credential.

A binding conformance test should:

1. select a server-owned logical endpoint descriptor;
2. reject unsupported operations before authentication resolution;
3. issue authentication context from request/trace correlation on the server;
4. acquire a short-lived credential-reference lease bound to endpoint, context and operation;
5. open the scripted adapter exactly once;
6. preserve request, correlation, context and lease identity across every retry attempt;
7. close the adapter and release the lease exactly once;
8. reject expired context, expired lease and binding mismatch fail-closed.

Tenant, operator, permission snapshot and audit reference exist only in the server-issued context. Credential leases contain reference and binding evidence only, never credential material.
