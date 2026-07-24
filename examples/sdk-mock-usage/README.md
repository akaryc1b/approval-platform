# SDK mock usage

This example is intentionally transport-free and contains no token or endpoint.

TypeScript tests create `MockTransport`, send a request containing only operation, payload, request/trace correlation and idempotency key, then return a typed `Result`. Java uses the equivalent `ApprovalSdk.MockTransport` and `ApprovalSdk.DefaultClient` contracts.

Compatibility negotiation, bounded transport policy evaluation, logical security binding, diagnostic/audit redaction, deterministic emission policy and reference-only telemetry/audit handoff occur before any future real adapter execution. The credential-free blueprints are:

- [Generic REST host](GENERIC_REST_HOST.md)
- [RuoYi starter](RUOYI_STARTER.md)
- [Adapter conformance](ADAPTER_CONFORMANCE.md)
- [Security binding](SECURITY_BINDING.md)
- [Diagnostic redaction and audit](DIAGNOSTIC_REDACTION.md)
- [Diagnostic emission and audit completeness](EMISSION_POLICY.md)
- [Telemetry signal and audit handoff](TELEMETRY_HANDOFF.md)

Hosts derive tenant, operator, permissions and audit evidence on the server. Those values are not accepted from SDK request, compatibility profile, transport policy, logical endpoint, diagnostic, emission decision, telemetry signal, adapter audit metadata or handoff acknowledgement.
