# Generic REST host binding blueprint

This blueprint is intentionally transport-free. It contains no URL, token, credential or HTTP client.

A Generic REST host adapter should:

1. construct its server-owned `ServerProfile` from deployed contract metadata;
2. map an untrusted client compatibility body only to `ClientProfile` fields;
3. call `SdkCompatibilityV1.negotiate` or `negotiateCompatibility` before accepting SDK operations;
4. expose the selected event schema, Webhook protocol, enabled capabilities and warnings;
5. derive tenant, operator, permissions and audit evidence from authenticated server context;
6. bind the accepted operation to a bounded transport policy;
7. validate response mapping and retry traces with the scripted conformance adapter;
8. keep endpoint resolution, authentication, clock, scheduler and real network execution outside this safe slice.

Unknown manifest or policy versions, missing required capabilities, expired support windows, operation mismatch and exhausted request budgets fail closed. Optional unknown capabilities never become trusted server capabilities.
