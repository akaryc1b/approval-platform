# RuoYi starter binding blueprint

This blueprint applies to both planned RuoYi 5 and RuoYi 6 host starters without adding a RuoYi dependency to the framework-neutral SDK.

The starter should provide Spring-managed compatibility and operation-policy metadata, while delegating pure negotiation, response classification and retry decisions to the SDK. RuoYi or Sa-Token authentication context must remain outside client profiles and transport policies; tenant, operator, permissions and audit evidence are resolved after authentication on the server.

The starter must not:

- accept trusted identity or permission evidence from an SDK body, compatibility profile or adapter response;
- infer support from an unknown manifest, event schema, Webhook protocol or transport policy version;
- enable a required capability after its sunset;
- retry an operation whose policy is `never` or whose request lacks its idempotency key;
- exceed the total request budget, attempt limit or retry-after boundary;
- embed a production token, endpoint or signing key in code;
- add event persistence, subscription tables or a delivery worker as part of policy evaluation.

Until the real transport gate is accepted, starter tests bind deterministic compatibility fixtures and scripted conformance adapters only.
