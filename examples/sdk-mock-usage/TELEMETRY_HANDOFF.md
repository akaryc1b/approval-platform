# Telemetry signal and audit handoff blueprint

This blueprint is deterministic and fake-exporter-only. It contains no production telemetry backend, queue, endpoint address or credential.

A conforming host test should:

1. construct a telemetry policy with explicit signal-name, attribute-key and attribute-value allowlists;
2. create reference-only signals from logical endpoint/correlation references and provenance digests;
3. verify canonical aggregation, signal and batch digests across Java and TypeScript;
4. export an ordered batch atomically to `ScriptedTelemetryExporter`;
5. prove capacity, duplicate and scripted failures append no partial telemetry;
6. create an audit handoff envelope only from `AuditCompletenessProof`;
7. retain pending state across NACK, timeout-like and scripted failure outcomes;
8. atomically move pending to acknowledged on ACK;
9. return the original acknowledgement for an identical duplicate handoff;
10. fail closed on conflicting handoff identity or incomplete proof.

Production OpenTelemetry, metrics/logging backends, brokers, queues and audit storage require later acceptance gates.
