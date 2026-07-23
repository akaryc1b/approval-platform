# SDK mock usage

This example is intentionally transport-free and contains no token or endpoint.

TypeScript tests create `MockTransport`, send a request containing only operation, payload, request/trace correlation and idempotency key, then return a typed `Result`. Java uses the equivalent `ApprovalSdk.MockTransport` and `ApprovalSdk.DefaultClient` contracts.

The second safe slice adds pure compatibility negotiation before mock operation execution. Generic REST and RuoYi binding blueprints are documented in:

- [Generic REST host](GENERIC_REST_HOST.md)
- [RuoYi starter](RUOYI_STARTER.md)

Hosts derive tenant, operator, permissions and audit evidence on the server. Those values are not accepted from SDK request or compatibility payloads.
