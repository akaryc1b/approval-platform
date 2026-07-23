# SDK mock usage

This example is intentionally transport-free and contains no token or endpoint.

TypeScript tests create `MockTransport`, send a request containing only operation, payload, request/trace correlation and idempotency key, then return a typed `Result`. Java uses the equivalent `ApprovalSdk.MockTransport` and `ApprovalSdk.DefaultClient` contracts.

Generic REST and RuoYi hosts bind their own authenticated adapter behind `ApprovalTransport`. They must derive tenant, operator, permissions and audit evidence on the server; those values are not accepted from SDK request payloads.
