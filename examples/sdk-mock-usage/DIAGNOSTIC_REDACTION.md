# Diagnostic redaction and audit blueprint

This blueprint is deterministic and fake-source-only. It does not read environment variables, files, vaults, endpoints or production credentials.

A conforming host test should:

1. load a versioned fixture configuration with explicit public/sensitive classifications;
2. retain public values and convert sensitive values to reference-only identities;
3. attach the configuration provenance digest to diagnostics and adapter audit events;
4. redact sensitive key names and every registered sensitive literal;
5. map exceptions to a generic safe message and stable type without raw message or stack;
6. emit reference-only events to `InMemoryAdapterAuditSink`;
7. assert no tenant, operator, permission evidence, audit reference, credential lease or raw secret appears in output;
8. fail closed on unknown versions, duplicate keys or redaction invariant violations.

A production configuration source, diagnostic logger and audit persistence adapter require later acceptance gates.
