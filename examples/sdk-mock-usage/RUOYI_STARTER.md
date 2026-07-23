# RuoYi starter binding blueprint

This blueprint applies to both planned RuoYi 5 and RuoYi 6 host starters without adding a RuoYi dependency to the framework-neutral SDK.

The starter should provide a Spring-managed server profile and delegate pure compatibility negotiation to the SDK. RuoYi or Sa-Token authentication context must remain outside `ClientProfile`; tenant, operator, permissions and audit evidence are resolved after authentication on the server.

The starter must not:

- accept trusted identity or permission evidence from the SDK body;
- infer support from an unknown manifest, event schema or Webhook protocol;
- enable a required capability after its sunset;
- embed a production token, endpoint or signing key in code;
- add event persistence, subscription tables or a delivery worker as part of compatibility negotiation.

Until the real transport gate is accepted, starter tests bind `ApprovalSdk.MockTransport` and deterministic compatibility fixtures only.
