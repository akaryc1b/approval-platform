# SDK mock usage

This example is intentionally transport-free and contains no token or endpoint address.

TypeScript tests create `MockTransport`, send a request containing only operation, payload, request/trace correlation and idempotency key, then return a typed `Result`. Java uses the equivalent `ApprovalSdk.MockTransport` and `ApprovalSdk.DefaultClient` contracts.

Compatibility negotiation, bounded transport policy and server-side security binding occur before any future real adapter execution. The blueprints are:

- [Generic REST host](GENERIC_REST_HOST.md)
- [RuoYi starter](RUOYI_STARTER.md)
- [Adapter conformance](ADAPTER_CONFORMANCE.md)
- [Security binding](SECURITY_BINDING.md)

Hosts derive tenant, operator, permissions and audit evidence on the server. Those values are not accepted from SDK request, compatibility profile, transport policy, endpoint descriptor or adapter response metadata.
