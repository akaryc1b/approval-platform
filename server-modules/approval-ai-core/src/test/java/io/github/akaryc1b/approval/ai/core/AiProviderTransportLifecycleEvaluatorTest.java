package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiProviderTransportFixtureObservation;
import io.github.akaryc1b.approval.ai.spi.AiProviderTransportMappingRequest;
import io.github.akaryc1b.approval.ai.spi.AiProviderTransportMappingResult;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AiProviderTransportLifecycleEvaluatorTest {

    @Test
    void structuredValidFixtureIsReadyForOfflineAssertion() {
        AiProviderTransportMappingRequest request = request(false);
        AiProviderTransportLifecycleReport report = evaluate(
            request,
            mapped(request),
            observation(
                AiProviderTransportFixtureObservation.Shape.STRUCTURED_VALID,
                request.responseSchemaHash()
            )
        );

        assertEquals(
            AiProviderTransportLifecycleReport.Status.READY_FOR_OFFLINE_ASSERTION,
            report.status()
        );
        assertEquals(AiProviderTransportLifecycleReport.FailureClass.NONE, report.failureClass());
        assertFalse(report.networkDispatchAttempted());
    }

    @Test
    void malformedJsonFailsClosedWithoutRetry() {
        AiProviderTransportMappingRequest request = request(false);
        AiProviderTransportLifecycleReport report = evaluate(
            request,
            mapped(request),
            observation(AiProviderTransportFixtureObservation.Shape.MALFORMED_JSON, null)
        );

        assertEquals(
            AiProviderTransportLifecycleReport.FailureClass.MALFORMED_JSON,
            report.failureClass()
        );
        assertFalse(report.retryAuthorized());
        assertFalse(report.postInvocationFallbackAuthorized());
    }

    @Test
    void schemaDriftFailsClosed() {
        AiProviderTransportMappingRequest request = request(false);
        AiProviderTransportLifecycleReport report = evaluate(
            request,
            mapped(request),
            observation(
                AiProviderTransportFixtureObservation.Shape.STRUCTURED_VALID,
                AiProviderTransportTestFixtures.hash('8')
            )
        );

        assertEquals(
            AiProviderTransportLifecycleReport.FailureClass.RESPONSE_SCHEMA_MISMATCH,
            report.failureClass()
        );
        assertEquals(
            AiProviderTransportLifecycleReport.Status.RESPONSE_REJECTED,
            report.status()
        );
    }

    @Test
    void cancellationStopsBeforeDispatch() {
        AiProviderTransportMappingRequest request = request(true);
        AiProviderTransportMappingResult mapping =
            new DeterministicProviderTransportMapper(
                DeterministicProviderTransportMapper.Mode.REJECTED,
                AiProviderTransportTestFixtures.hash('e')
            ).map(request);
        AiProviderTransportLifecycleReport report = evaluate(
            request,
            mapping,
            observation(AiProviderTransportFixtureObservation.Shape.CANCELLED, null)
        );

        assertEquals(
            AiProviderTransportLifecycleReport.Status.CANCELLED_BEFORE_DISPATCH,
            report.status()
        );
        assertFalse(report.providerInvocationAttempted());
    }

    @Test
    void timeoutStopsBeforeDispatch() {
        AiProviderTransportMappingRequest request = request(false);
        AiProviderTransportLifecycleReport report = evaluate(
            request,
            mapped(request),
            observation(AiProviderTransportFixtureObservation.Shape.TIMEOUT, null)
        );

        assertEquals(
            AiProviderTransportLifecycleReport.Status.TIMED_OUT_BEFORE_DISPATCH,
            report.status()
        );
        assertEquals(
            AiProviderTransportLifecycleReport.FailureClass.TIMEOUT_OBSERVED,
            report.failureClass()
        );
    }

    @Test
    void rejectedMappingBlocksLifecycle() {
        AiProviderTransportMappingRequest request = request(false);
        AiProviderTransportMappingResult mapping =
            new DeterministicProviderTransportMapper(
                DeterministicProviderTransportMapper.Mode.REJECTED,
                AiProviderTransportTestFixtures.hash('e')
            ).map(request);
        AiProviderTransportLifecycleReport report = evaluate(
            request,
            mapping,
            observation(AiProviderTransportFixtureObservation.Shape.UNKNOWN, null)
        );

        assertEquals(AiProviderTransportLifecycleReport.Status.BLOCKED, report.status());
        assertEquals(
            AiProviderTransportLifecycleReport.FailureClass.MAPPING_REJECTED,
            report.failureClass()
        );
    }

    @Test
    void transportAuditStoresOnlyHashesAndStableCodes() {
        AiProviderTransportMappingRequest request = request(false);
        AiProviderTransportMappingResult mapping = mapped(request);
        AiProviderTransportFixtureObservation observation = observation(
            AiProviderTransportFixtureObservation.Shape.MALFORMED_JSON,
            null
        );
        AiProviderTransportLifecycleReport lifecycle = evaluate(
            request,
            mapping,
            observation
        );
        AiProviderTransportAuditEvidence audit = AiProviderTransportAuditEvidence.create(
            request,
            mapping,
            lifecycle,
            observation,
            List.of("AI_TRANSPORT_MALFORMED_JSON")
        );

        assertFalse(audit.rawRequestStored());
        assertFalse(audit.rawResponseStored());
        assertFalse(audit.headerValuesStored());
        assertFalse(audit.secretMaterialStored());
        assertFalse(audit.networkPayloadStored());
    }

    private static AiProviderTransportLifecycleReport evaluate(
        AiProviderTransportMappingRequest request,
        AiProviderTransportMappingResult mapping,
        AiProviderTransportFixtureObservation observation
    ) {
        return new AiProviderTransportLifecycleEvaluator().evaluate(
            request,
            mapping,
            observation
        );
    }

    private static AiProviderTransportMappingRequest request(boolean cancellation) {
        return AiProviderTransportTestFixtures.request(cancellation);
    }

    private static AiProviderTransportMappingResult mapped(
        AiProviderTransportMappingRequest request
    ) {
        return AiProviderTransportTestFixtures.mapped(request);
    }

    private static AiProviderTransportFixtureObservation observation(
        AiProviderTransportFixtureObservation.Shape shape,
        String schemaHash
    ) {
        return AiProviderTransportTestFixtures.observation(shape, schemaHash);
    }
}
