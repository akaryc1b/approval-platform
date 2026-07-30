# SDK diagnostics, configuration provenance and adapter audit v1

## Scope

This contract defines deterministic fake configuration loading, configuration provenance, diagnostic redaction and reference-only adapter audit events. It does not read environment variables, files, secret managers or network configuration, and it does not persist diagnostics or audit events.

## Contract version

- `contractVersion` is `1`; every unknown version fails closed.
- The only source kind implemented in this safe slice is `fixture`.
- Duplicate configuration keys are rejected.
- Configuration load time is supplied explicitly; no production clock is used.

## Configuration provenance

Each configuration entry is declared `public` or `sensitive`.

- Public values may be returned to the deterministic conformance harness.
- Sensitive values are never returned from `ResolvedConfiguration`.
- Sensitive values are represented only by a deterministic reference containing source ID, key and a truncated SHA-256 value digest.
- Provenance records source ID, revision, load time, public/sensitive key names and a SHA-256 content digest.
- The provenance digest covers sorted key names, classification and value digests; raw values are absent.

## Diagnostic redaction

Diagnostics contain code, severity, message, string context, redaction count and provenance digest.

- Explicit sensitive literals are removed from messages and non-sensitive context values.
- Sensitive key names such as authorization, token, password, secret, private key, certificate and credential material are fully redacted.
- Exception diagnostics expose a stable exception type and generic message only; raw exception message and stack are never emitted.
- A final invariant check rejects any output that still contains a registered sensitive literal.

## Adapter audit event

Audit events contain reference identities only:

- event ID/type;
- logical endpoint and operation;
- request and trace IDs;
- binding and authentication-context IDs;
- outcome and reason code;
- explicit occurrence time;
- configuration provenance digest.

Audit events contain no tenant, operator, permission snapshot, audit reference, credential reference, credential lease or usable credential material. `InMemoryAdapterAuditSink` is deterministic test-only storage and is not a production persistence implementation.

## Fixture

`fixtures/diagnostics-audit-v1.json` includes deliberately sensitive test literals marked `DO-NOT-LEAK`. Java and TypeScript must produce the same provenance digest, sensitive references, redacted diagnostic and audit event while proving those literals never appear in output.
