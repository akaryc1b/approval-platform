package io.github.akaryc1b.approval.sdk.v1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AggregationReconciliationFixtureSupport {
    private AggregationReconciliationFixtureSupport() {
    }

    static String fixtureJson() throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(
                "contracts/sdk/v1/fixtures/aggregation-reconciliation-v1.json"
            );
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate);
            }
            directory = directory.getParent();
        }
        throw new IOException("Unable to locate aggregation/reconciliation fixture");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> fixture() throws IOException {
        return (Map<String, Object>) CanonicalJson.parse(fixtureJson());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Map<String, Object> parent, String field) {
        return (Map<String, Object>) parent.get(field);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> objects(Map<String, Object> parent, String field) {
        return ((List<Object>) parent.get(field)).stream()
            .map(value -> (Map<String, Object>) value)
            .toList();
    }

    static SdkTelemetryAggregationV1.TelemetryAggregationPolicy policy(
        Map<String, Object> fixture
    ) {
        Map<String, Object> raw = object(fixture, "aggregationPolicy");
        return new SdkTelemetryAggregationV1.TelemetryAggregationPolicy(
            (String) raw.get("contractVersion"),
            ((Number) raw.get("windowSizeOrdinals")).intValue(),
            ((Number) raw.get("deduplicationWindowOrdinals")).intValue(),
            ((Number) raw.get("maxActiveWindows")).intValue(),
            ((Number) raw.get("maxAggregationIdentities")).intValue(),
            ((Number) raw.get("maxTrackedSignalDigests")).intValue()
        );
    }

    static List<SdkTelemetrySignalV1.ReferenceTelemetrySignal> signals(
        Map<String, Object> fixture
    ) {
        List<SdkTelemetrySignalV1.ReferenceTelemetrySignal> output = new ArrayList<>();
        for (Map<String, Object> raw : objects(fixture, "telemetrySignals")) {
            output.add(new SdkTelemetrySignalV1.ReferenceTelemetrySignal(
                (String) raw.get("contractVersion"),
                (String) raw.get("signalId"),
                (String) raw.get("signalName"),
                SdkTelemetrySignalV1.TelemetrySignalKind.valueOf(
                    ((String) raw.get("signalKind")).toUpperCase()
                ),
                (String) raw.get("endpointId"),
                (String) raw.get("operation"),
                (String) raw.get("requestId"),
                (String) raw.get("traceId"),
                (String) raw.get("bindingId"),
                (String) raw.get("authenticationContextId"),
                (String) raw.get("outcome"),
                ((Number) raw.get("quantity")).longValue(),
                ((Number) raw.get("ordinal")).longValue(),
                (String) raw.get("provenanceDigest"),
                stringMap(raw, "attributes"),
                (String) raw.get("aggregationIdentityDigest"),
                (String) raw.get("signalDigest")
            ));
        }
        return output;
    }

    static SdkAuditHandoffV1.AuditHandoffEnvelope envelope(Map<String, Object> fixture) {
        Map<String, Object> raw = object(fixture, "auditHandoffEnvelope");
        return new SdkAuditHandoffV1.AuditHandoffEnvelope(
            (String) raw.get("contractVersion"),
            (String) raw.get("handoffId"),
            (String) raw.get("destinationReference"),
            ((Number) raw.get("handoffOrdinal")).longValue(),
            (String) raw.get("auditBatchDigest"),
            (String) raw.get("auditIdentityDigest"),
            ((Number) raw.get("recordCount")).intValue(),
            ((Number) raw.get("attemptCount")).intValue(),
            (String) raw.get("firstEventId"),
            (String) raw.get("terminalEventId"),
            (String) raw.get("envelopeDigest")
        );
    }

    static SdkAuditHandoffV1.AuditHandoffAcknowledgement acknowledgement(
        Map<String, Object> fixture
    ) {
        Map<String, Object> raw = object(fixture, "auditHandoffAcknowledgement");
        return new SdkAuditHandoffV1.AuditHandoffAcknowledgement(
            (String) raw.get("contractVersion"),
            (String) raw.get("acknowledgementId"),
            (String) raw.get("handoffId"),
            (String) raw.get("destinationReference"),
            (String) raw.get("envelopeDigest"),
            (String) raw.get("auditBatchDigest"),
            ((Number) raw.get("deliveryAttempt")).intValue(),
            ((Number) raw.get("acknowledgedOrdinal")).longValue()
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Map<String, Object> parent, String field) {
        Map<String, String> output = new LinkedHashMap<>();
        ((Map<String, Object>) parent.get(field)).forEach(
            (key, value) -> output.put(key, (String) value)
        );
        return output;
    }
}
