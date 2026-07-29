package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenOutcome;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .CompletionClassification;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .DurationBucket;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .GateResult;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .InvocationEvidence;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .StableFailureCode;
import io.github.akaryc1b.approval.connector.operations.BoundedConnectorOperationsDiagnosticsStore;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts
    .ProviderApiFamily;
import io.github.akaryc1b.approval.connector.routing.TenantConnectorRouteContracts
    .TransportProfile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MicrometerConnectorOperationsObservationSinkTest {

    @Test
    void metricsUseOnlyClosedLowCardinalityTags() {
        BoundedConnectorOperationsDiagnosticsStore store =
            new BoundedConnectorOperationsDiagnosticsStore(8, 8);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MicrometerConnectorOperationsObservationSink sink =
            new MicrometerConnectorOperationsObservationSink(store, meters);
        sink.record(evidence(), Instant.parse("2026-07-29T07:00:00Z"));
        var counter = meters.find(MicrometerConnectorOperationsObservationSink.METRIC).counter();
        assertEquals(1.0, counter.count());
        var tags = counter.getId().getTags().toString();
        assertFalse(tags.contains("tenant-a"));
        assertFalse(tags.contains("request"));
        assertFalse(tags.contains("trace"));
        assertFalse(tags.contains("credential"));
        assertFalse(tags.contains("token"));
        assertFalse(tags.contains("endpoint"));
    }

    @Test
    void unavailableDiagnosticsDoesNotPreventMetricEmission() {
        BoundedConnectorOperationsDiagnosticsStore store =
            new BoundedConnectorOperationsDiagnosticsStore(8, 8);
        store.setAvailable(false);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MicrometerConnectorOperationsObservationSink sink =
            new MicrometerConnectorOperationsObservationSink(store, meters);
        sink.record(evidence(), Instant.parse("2026-07-29T07:00:00Z"));
        assertEquals(
            1.0,
            meters.find(MicrometerConnectorOperationsObservationSink.METRIC).counter().count()
        );
    }

    private static InvocationEvidence evidence() {
        String hash = CanonicalPayloadHash.sha256Utf8("fixture");
        return new InvocationEvidence(
            CanonicalPayloadHash.sha256Utf8("tenant\ntenant-a"),
            hash,
            hash,
            hash,
            hash,
            hash,
            "version-1",
            hash,
            hash,
            DingTalkTokenOutcome.CACHE_HIT,
            TransportProfile.DINGTALK_JAVA21_FIXED_HTTPS_V1,
            ProviderApiFamily.LEGACY_OAPI,
            ConnectorOperation.ORGANIZATION_READ,
            "USER_BY_ID",
            GateResult.ALLOWED,
            true,
            1,
            CompletionClassification.SUCCEEDED,
            DurationBucket.LT_10_MS,
            StableFailureCode.NONE,
            hash
        );
    }
}
