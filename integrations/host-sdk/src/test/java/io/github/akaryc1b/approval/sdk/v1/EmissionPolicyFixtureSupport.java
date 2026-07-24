package io.github.akaryc1b.approval.sdk.v1;

import io.github.akaryc1b.approval.sdk.v1.SdkAuditCompletenessV1.AuditCompletenessPolicy;
import io.github.akaryc1b.approval.sdk.v1.SdkAuditCompletenessV1.AuditEmissionRecord;
import io.github.akaryc1b.approval.sdk.v1.SdkAuditCompletenessV1.AuditPhase;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticEmissionV1.DiagnosticEmissionPolicy;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticEmissionV1.DiagnosticEmissionRequest;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.AdapterAuditEvent;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.AuditOutcome;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.DiagnosticSeverity;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.SafeDiagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EmissionPolicyFixtureSupport {
    private EmissionPolicyFixtureSupport() {
    }

    static String fixtureJson() throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("contracts/sdk/v1/fixtures/emission-policy-v1.json");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate);
            }
            directory = directory.getParent();
        }
        throw new IOException("Unable to locate emission policy fixture");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> fixture() throws IOException {
        return (Map<String, Object>) CanonicalJson.parse(fixtureJson());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Map<String, Object> parent, String field) {
        return (Map<String, Object>) parent.get(field);
    }

    static DiagnosticEmissionPolicy diagnosticPolicy(Map<String, Object> fixture) {
        Map<String, Object> raw = object(fixture, "policy");
        return new DiagnosticEmissionPolicy(
            (String) raw.get("contractVersion"),
            DiagnosticSeverity.valueOf(((String) raw.get("minimumSeverity")).toUpperCase()),
            ((Number) raw.get("sampleNumerator")).intValue(),
            ((Number) raw.get("sampleDenominator")).intValue(),
            (String) raw.get("sampleSalt"),
            ((Number) raw.get("deduplicationWindowOrdinals")).longValue(),
            ((Number) raw.get("deduplicationCapacity")).intValue()
        );
    }

    @SuppressWarnings("unchecked")
    static SafeDiagnostic diagnostic(Map<String, Object> fixture) {
        Map<String, Object> raw = object(fixture, "diagnostic");
        Map<String, String> context = new LinkedHashMap<>();
        ((Map<String, Object>) raw.get("context")).forEach(
            (key, value) -> context.put(key, (String) value)
        );
        return new SafeDiagnostic(
            (String) raw.get("contractVersion"),
            (String) raw.get("code"),
            DiagnosticSeverity.valueOf(((String) raw.get("severity")).toUpperCase()),
            (String) raw.get("message"),
            context,
            ((Number) raw.get("redactionCount")).intValue(),
            (String) raw.get("provenanceDigest")
        );
    }

    static DiagnosticEmissionRequest emissionRequest(
        Map<String, Object> fixture,
        SafeDiagnostic diagnostic
    ) {
        Map<String, Object> raw = object(fixture, "emission");
        return new DiagnosticEmissionRequest(
            (String) raw.get("contractVersion"),
            (String) raw.get("emissionId"),
            (String) raw.get("sampleKey"),
            ((Number) raw.get("ordinal")).longValue(),
            diagnostic
        );
    }

    @SuppressWarnings("unchecked")
    static AuditCompletenessPolicy auditPolicy(Map<String, Object> fixture) {
        Map<String, Object> raw = object(fixture, "auditPolicy");
        List<String> terminal = ((List<Object>) raw.get("terminalEventTypes"))
            .stream()
            .map(String.class::cast)
            .toList();
        return new AuditCompletenessPolicy(
            (String) raw.get("contractVersion"),
            ((Number) raw.get("expectedAttemptCount")).intValue(),
            (String) raw.get("startedEventType"),
            (String) raw.get("attemptEventType"),
            terminal
        );
    }

    @SuppressWarnings("unchecked")
    static List<AuditEmissionRecord> auditRecords(Map<String, Object> fixture) {
        List<AuditEmissionRecord> output = new ArrayList<>();
        for (Object value : (List<Object>) fixture.get("auditRecords")) {
            Map<String, Object> raw = (Map<String, Object>) value;
            Map<String, Object> event = (Map<String, Object>) raw.get("event");
            output.add(new AuditEmissionRecord(
                (String) raw.get("contractVersion"),
                ((Number) raw.get("sequence")).intValue(),
                AuditPhase.valueOf(((String) raw.get("phase")).toUpperCase()),
                new AdapterAuditEvent(
                    (String) event.get("contractVersion"),
                    (String) event.get("eventId"),
                    (String) event.get("eventType"),
                    (String) event.get("endpointId"),
                    (String) event.get("operation"),
                    (String) event.get("requestId"),
                    (String) event.get("traceId"),
                    (String) event.get("bindingId"),
                    (String) event.get("authenticationContextId"),
                    AuditOutcome.valueOf(((String) event.get("outcome")).toUpperCase()),
                    (String) event.get("reasonCode"),
                    ((Number) event.get("occurredAtEpochSeconds")).longValue(),
                    (String) event.get("provenanceDigest")
                )
            ));
        }
        return output;
    }
}
