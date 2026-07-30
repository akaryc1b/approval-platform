package io.github.akaryc1b.approval.ai.spi;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiProviderTransportMappingContractTest {

    @Test
    void requestsRejectRawPayloads() {
        assertThrows(
            IllegalArgumentException.class,
            () -> request(true, false, false, Duration.ofSeconds(5))
        );
    }

    @Test
    void requestsRejectDispatchAuthority() {
        assertThrows(
            IllegalArgumentException.class,
            () -> request(false, true, false, Duration.ofSeconds(5))
        );
    }

    @Test
    void mappingResultsRejectNetworkAndRetryClaims() {
        AiProviderTransportMappingRequest request = request(
            false,
            false,
            false,
            Duration.ofSeconds(5)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiProviderTransportMappingResult(
                request.mapperAuthorizationKey(),
                request.providerVersion(),
                AiProviderTransportMappingResult.Status.MAPPED_FOR_OFFLINE_VALIDATION,
                request.requestEvidenceHash(),
                hash('e'),
                request.responseSchemaHash(),
                List.of(),
                false,
                false,
                false,
                true,
                false,
                true,
                false,
                false,
                false
            )
        );
    }

    @Test
    void fixtureObservationsRejectRawBodies() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiProviderTransportFixtureObservation(
                provider(),
                "mapper-a/1",
                hash('2'),
                AiProviderTransportFixtureObservation.Shape.STRUCTURED_VALID,
                10,
                hash('9'),
                false,
                false,
                true,
                false,
                false,
                false
            )
        );
    }

    @Test
    void requestEvidenceHashBindsTimeoutAndCancellation() {
        AiProviderTransportMappingRequest first = request(
            false,
            false,
            false,
            Duration.ofSeconds(5)
        );
        AiProviderTransportMappingRequest same = request(
            false,
            false,
            false,
            Duration.ofSeconds(5)
        );
        AiProviderTransportMappingRequest cancelled = request(
            false,
            false,
            true,
            Duration.ofSeconds(5)
        );
        AiProviderTransportMappingRequest slower = request(
            false,
            false,
            false,
            Duration.ofSeconds(6)
        );
        assertEquals(first.requestEvidenceHash(), same.requestEvidenceHash());
        assertNotEquals(first.requestEvidenceHash(), cancelled.requestEvidenceHash());
        assertNotEquals(first.requestEvidenceHash(), slower.requestEvidenceHash());
    }

    private static AiProviderTransportMappingRequest request(
        boolean rawPayload,
        boolean dispatch,
        boolean cancellation,
        Duration timeout
    ) {
        return new AiProviderTransportMappingRequest(
            "mapper-a",
            "1",
            provider(),
            AiCapability.APPROVAL_SUMMARY,
            "provider-a/1/endpoint-a",
            "validator-a/1",
            hash('1'),
            hash('2'),
            hash('3'),
            hash('4'),
            128,
            2,
            timeout,
            cancellation,
            rawPayload,
            false,
            dispatch,
            false,
            false
        );
    }

    private static AiVersionReferences.ProviderVersion provider() {
        return new AiVersionReferences.ProviderVersion("provider-a", "1");
    }

    private static String hash(char character) {
        return Character.toString(character).repeat(64);
    }
}
