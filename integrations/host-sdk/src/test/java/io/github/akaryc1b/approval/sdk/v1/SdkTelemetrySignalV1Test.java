package io.github.akaryc1b.approval.sdk.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.akaryc1b.approval.sdk.v1.SdkTelemetrySignalV1.ReferenceTelemetrySignal;
import io.github.akaryc1b.approval.sdk.v1.SdkTelemetrySignalV1.ScriptedTelemetryExporter;
import io.github.akaryc1b.approval.sdk.v1.SdkTelemetrySignalV1.TelemetryAttributePolicy;
import io.github.akaryc1b.approval.sdk.v1.SdkTelemetrySignalV1.TelemetrySignalInput;
import io.github.akaryc1b.approval.sdk.v1.SdkTelemetrySignalV1.TelemetrySignalKind;
import io.github.akaryc1b.approval.sdk.v1.SdkTelemetrySignalV1.UnsupportedTelemetryHandoffVersionException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdkTelemetrySignalV1Test {
    @Test
    void sharedFixtureProducesExactReferenceSignalsAndExportProof() throws IOException {
        Map<String, Object> fixture = TelemetryHandoffFixtureSupport.fixture();
        TelemetryAttributePolicy policy = policy(fixture);
        List<ReferenceTelemetrySignal> signals = signals(fixture, policy);
        ScriptedTelemetryExporter exporter = new ScriptedTelemetryExporter(2);
        var result = SdkTelemetrySignalV1.exportTelemetryBatch(policy, signals, exporter);
        Map<String, Object> expected = TelemetryHandoffFixtureSupport.object(
            TelemetryHandoffFixtureSupport.object(fixture, "expectations"),
            "telemetryExport"
        );
        assertEquals(
            CanonicalJson.canonicalizeValue(expected),
            CanonicalJson.canonicalizeValue(
                SdkTelemetrySignalV1.telemetryExportResultMap(result)
            )
        );
        List<Object> expectedSignals = TelemetryHandoffFixtureSupport.list(
            TelemetryHandoffFixtureSupport.object(fixture, "expectations"),
            "telemetrySignals"
        );
        List<Object> actualSignals = signals.stream()
            .map(SdkTelemetrySignalV1::telemetrySignalMap)
            .map(value -> (Object) value)
            .toList();
        assertEquals(
            CanonicalJson.canonicalizeValue(expectedSignals),
            CanonicalJson.canonicalizeValue(actualSignals)
        );
    }

    @Test
    void unknownVersionUnapprovedNameAndForbiddenAttributesFailClosed() throws IOException {
        Map<String, Object> fixture = TelemetryHandoffFixtureSupport.fixture();
        TelemetryAttributePolicy policy = policy(fixture);
        assertThrows(
            UnsupportedTelemetryHandoffVersionException.class,
            () -> new TelemetryAttributePolicy(
                "2",
                policy.allowedSignalNames(),
                policy.allowedAttributeKeys(),
                policy.allowedAttributeValues(),
                policy.maxAttributeCount(),
                policy.maxAttributeValueLength(),
                policy.maxBatchSize()
            )
        );
        TelemetrySignalInput base = signalInputs(fixture).get(0);
        assertThrows(
            IllegalArgumentException.class,
            () -> SdkTelemetrySignalV1.createReferenceTelemetrySignal(
                policy,
                copySignal(base, "approval.unapproved", base.attributes(), base.ordinal())
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new TelemetryAttributePolicy(
                "1",
                policy.allowedSignalNames(),
                List.of("adapterKind", "tenantId"),
                Map.of("adapterKind", List.of("generic-rest"), "tenantId", List.of("x")),
                2,
                64,
                2
            )
        );
    }

    @Test
    void exporterCapacityAndScriptedFailureAreAtomicDegradedOutcomes() throws IOException {
        Map<String, Object> fixture = TelemetryHandoffFixtureSupport.fixture();
        TelemetryAttributePolicy policy = policy(fixture);
        List<ReferenceTelemetrySignal> signals = signals(fixture, policy);
        ScriptedTelemetryExporter capacity = new ScriptedTelemetryExporter(1);
        var capacityResult = SdkTelemetrySignalV1.exportTelemetryBatch(
            policy,
            signals,
            capacity
        );
        assertEquals("telemetry_exporter_capacity", capacityResult.reasonCode());
        assertEquals(0, capacity.size());

        ScriptedTelemetryExporter failed = new ScriptedTelemetryExporter(2, List.of(1));
        var failedResult = SdkTelemetrySignalV1.exportTelemetryBatch(
            policy,
            signals,
            failed
        );
        assertEquals("telemetry_exporter_failed", failedResult.reasonCode());
        assertEquals(0, failed.size());
    }

    @Test
    void duplicateBatchAndOrdinalRegressionDoNotAppendPartialSignals() throws IOException {
        Map<String, Object> fixture = TelemetryHandoffFixtureSupport.fixture();
        TelemetryAttributePolicy policy = policy(fixture);
        List<ReferenceTelemetrySignal> signals = signals(fixture, policy);
        ScriptedTelemetryExporter exporter = new ScriptedTelemetryExporter(4);
        SdkTelemetrySignalV1.exportTelemetryBatch(policy, signals, exporter);
        var duplicate = SdkTelemetrySignalV1.exportTelemetryBatch(
            policy,
            signals,
            exporter
        );
        assertEquals("telemetry_duplicate_batch", duplicate.reasonCode());
        assertEquals(2, exporter.size());
        assertThrows(
            IllegalArgumentException.class,
            () -> SdkTelemetrySignalV1.exportTelemetryBatch(
                policy,
                List.of(signals.get(1), signals.get(0)),
                new ScriptedTelemetryExporter(2)
            )
        );
    }

    static TelemetryAttributePolicy policy(Map<String, Object> fixture) {
        Map<String, Object> raw = TelemetryHandoffFixtureSupport.object(
            fixture,
            "telemetryPolicy"
        );
        return new TelemetryAttributePolicy(
            (String) raw.get("contractVersion"),
            strings(raw, "allowedSignalNames"),
            strings(raw, "allowedAttributeKeys"),
            stringLists(raw, "allowedAttributeValues"),
            ((Number) raw.get("maxAttributeCount")).intValue(),
            ((Number) raw.get("maxAttributeValueLength")).intValue(),
            ((Number) raw.get("maxBatchSize")).intValue()
        );
    }

    static List<ReferenceTelemetrySignal> signals(
        Map<String, Object> fixture,
        TelemetryAttributePolicy policy
    ) {
        return signalInputs(fixture).stream()
            .map(input -> SdkTelemetrySignalV1.createReferenceTelemetrySignal(policy, input))
            .toList();
    }

    @SuppressWarnings("unchecked")
    static List<TelemetrySignalInput> signalInputs(Map<String, Object> fixture) {
        List<TelemetrySignalInput> output = new ArrayList<>();
        for (Object value : TelemetryHandoffFixtureSupport.list(fixture, "telemetrySignals")) {
            Map<String, Object> raw = (Map<String, Object>) value;
            Map<String, String> attributes = new LinkedHashMap<>();
            ((Map<String, Object>) raw.get("attributes")).forEach(
                (key, item) -> attributes.put(key, (String) item)
            );
            output.add(new TelemetrySignalInput(
                (String) raw.get("contractVersion"),
                (String) raw.get("signalId"),
                (String) raw.get("signalName"),
                TelemetrySignalKind.valueOf(
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
                attributes
            ));
        }
        return output;
    }


    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> stringLists(
        Map<String, Object> raw,
        String field
    ) {
        Map<String, List<String>> output = new LinkedHashMap<>();
        ((Map<String, Object>) raw.get(field)).forEach(
            (key, value) -> output.put(
                key,
                ((List<Object>) value).stream().map(String.class::cast).toList()
            )
        );
        return output;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Map<String, Object> raw, String field) {
        return ((List<Object>) raw.get(field)).stream().map(String.class::cast).toList();
    }

    private static TelemetrySignalInput copySignal(
        TelemetrySignalInput base,
        String signalName,
        Map<String, String> attributes,
        long ordinal
    ) {
        return new TelemetrySignalInput(
            base.contractVersion(),
            base.signalId(),
            signalName,
            base.signalKind(),
            base.endpointId(),
            base.operation(),
            base.requestId(),
            base.traceId(),
            base.bindingId(),
            base.authenticationContextId(),
            base.outcome(),
            base.quantity(),
            ordinal,
            base.provenanceDigest(),
            attributes
        );
    }
}
