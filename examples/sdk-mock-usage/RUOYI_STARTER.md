# RuoYi starter binding blueprint

This blueprint applies to planned RuoYi 5 and RuoYi 6 starters without adding a RuoYi or Sa-Token dependency to the framework-neutral SDK.

The starter should provide Spring-managed compatibility, operation-policy and logical-endpoint metadata. RuoYi or Sa-Token authentication creates the server resolver context; the SDK request cannot provide tenant, operator, permission snapshot or audit evidence. A starter-owned credential provider issues only a short-lived lease reference to the adapter binding layer.

The starter must not:

- accept trusted identity or permission evidence from SDK, compatibility, policy, endpoint or adapter response data;
- infer support from an unknown manifest, event schema, Webhook protocol, policy or binding version;
- resolve an endpoint address or usable credential inside the framework-neutral SDK;
- retry a `never` operation or a request without required idempotency;
- use an expired context or credential lease;
- embed a production token, endpoint address or signing key in code;
- add persistence, subscriptions or delivery workers as part of adapter binding.

Until a real transport gate is accepted, starter tests use deterministic compatibility fixtures, logical endpoint descriptors, static server resolvers, reference-only leases and scripted adapters.
