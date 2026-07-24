package io.github.akaryc1b.approval.sdk.v1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CheckpointEscalationFixtureSupport {
    private CheckpointEscalationFixtureSupport() {
    }

    static String fixtureJson() throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(
                "contracts/sdk/v1/fixtures/checkpoint-escalation-v1.json"
            );
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate);
            }
            directory = directory.getParent();
        }
        throw new IOException("Unable to locate checkpoint/escalation fixture");
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

    static SdkAggregateExportCheckpointV1.AggregateExportCheckpointPolicy checkpointPolicy(
        Map<String, Object> fixture
    ) {
        Map<String, Object> raw = object(fixture, "aggregateCheckpointPolicy");
        return new SdkAggregateExportCheckpointV1.AggregateExportCheckpointPolicy(
            (String) raw.get("contractVersion"),
            number(raw, "maxSnapshotsPerCheckpoint").intValue(),
            number(raw, "maxStoredCheckpoints").intValue()
        );
    }

    static SdkAggregateExportCheckpointV1.AggregateExportCheckpointInput checkpointInput(
        Map<String, Object> fixture
    ) {
        Map<String, Object> raw = object(fixture, "aggregateCheckpoint");
        return checkpointInput(fixture, raw);
    }

    static SdkAggregateExportCheckpointV1.AggregateExportCheckpointInput checkpointInput(
        Map<String, Object> fixture,
        Map<String, Object> raw
    ) {
        return new SdkAggregateExportCheckpointV1.AggregateExportCheckpointInput(
            (String) raw.get("contractVersion"),
            (String) raw.get("streamReference"),
            number(raw, "checkpointOrdinal").longValue(),
            (String) raw.get("previousCheckpointDigest"),
            snapshots(fixture),
            strings(raw, "exportedSnapshotDigests")
        );
    }

    static List<SdkTelemetryAggregationV1.TelemetryAggregationSnapshot> snapshots(
        Map<String, Object> fixture
    ) {
        List<SdkTelemetryAggregationV1.TelemetryAggregationSnapshot> output =
            new ArrayList<>();
        for (Map<String, Object> raw : objects(fixture, "aggregationSnapshots")) {
            output.add(snapshot(raw));
        }
        return List.copyOf(output);
    }

    static SdkTelemetryAggregationV1.TelemetryAggregationSnapshot snapshot(
        Map<String, Object> raw
    ) {
        return new SdkTelemetryAggregationV1.TelemetryAggregationSnapshot(
            (String) raw.get("contractVersion"),
            number(raw, "windowStartOrdinal").longValue(),
            number(raw, "windowEndOrdinalExclusive").longValue(),
            (String) raw.get("aggregationIdentityDigest"),
            (String) raw.get("signalName"),
            SdkTelemetrySignalV1.TelemetrySignalKind.valueOf(
                ((String) raw.get("signalKind")).toUpperCase()
            ),
            (String) raw.get("endpointId"),
            (String) raw.get("operation"),
            (String) raw.get("outcome"),
            (String) raw.get("provenanceDigest"),
            stringMap(raw, "attributes"),
            number(raw, "totalQuantity").longValue(),
            number(raw, "signalCount").intValue(),
            (String) raw.get("firstSignalId"),
            (String) raw.get("lastSignalId"),
            number(raw, "firstOrdinal").longValue(),
            number(raw, "lastOrdinal").longValue(),
            (String) raw.get("snapshotDigest")
        );
    }

    static SdkReconciliationEscalationV1.ReconciliationEscalationPolicy escalationPolicy(
        Map<String, Object> fixture
    ) {
        Map<String, Object> raw = object(fixture, "escalationPolicy");
        return new SdkReconciliationEscalationV1.ReconciliationEscalationPolicy(
            (String) raw.get("contractVersion"),
            number(raw, "observeAfterOrdinals").longValue(),
            number(raw, "investigateAfterOrdinals").longValue(),
            number(raw, "blockAfterOrdinals").longValue(),
            number(raw, "maxStoredProofs").intValue()
        );
    }

    static SdkAuditHandoffReconciliationV1.AuditHandoffReconciliationProof proof(
        Map<String, Object> fixture,
        String name
    ) {
        Map<String, Object> proofs = object(fixture, "reconciliationProofs");
        Map<String, Object> raw = object(proofs, name);
        return new SdkAuditHandoffReconciliationV1.AuditHandoffReconciliationProof(
            (String) raw.get("contractVersion"),
            (String) raw.get("handoffId"),
            SdkAuditHandoffReconciliationV1.ExpectedHandoffState.valueOf(
                ((String) raw.get("expectedState")).toUpperCase()
            ),
            SdkAuditHandoffReconciliationV1.HandoffReconciliationClassification.valueOf(
                ((String) raw.get("classification")).toUpperCase()
            ),
            (Boolean) raw.get("safeToFinalize"),
            number(raw, "reconciliationOrdinal").longValue(),
            (String) raw.get("envelopeDigest"),
            (String) raw.get("pendingEnvelopeDigest"),
            (String) raw.get("acknowledgementId"),
            (String) raw.get("acknowledgementEnvelopeDigest"),
            (String) raw.get("evidenceDigest"),
            (String) raw.get("proofDigest")
        );
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Map<String, Object> parent, String field) {
        return ((List<Object>) parent.get(field)).stream().map(String.class::cast).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Map<String, Object> parent, String field) {
        Map<String, String> output = new LinkedHashMap<>();
        ((Map<String, Object>) parent.get(field)).forEach(
            (key, value) -> output.put(key, (String) value)
        );
        return output;
    }

    private static Number number(Map<String, Object> parent, String field) {
        return (Number) parent.get(field);
    }
}
