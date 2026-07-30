package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiProviderTransportFixtureObservation;
import io.github.akaryc1b.approval.ai.spi.AiProviderTransportMappingRequest;
import io.github.akaryc1b.approval.ai.spi.AiProviderTransportMappingResult;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.time.Duration;
import java.util.List;
import java.util.Set;

final class AiProviderTransportTestFixtures {

    private AiProviderTransportTestFixtures() {
    }

    static AiVersionReferences.ProviderVersion provider() {
        return new AiVersionReferences.ProviderVersion("provider-a", "1");
    }

    static AiProviderTransportMappingRequest request(boolean cancellationRequested) {
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
            Duration.ofSeconds(5),
            cancellationRequested,
            false,
            false,
            false,
            false,
            false
        );
    }

    static AiProviderTransportMappingResult mapped(
        AiProviderTransportMappingRequest request
    ) {
        return new DeterministicProviderTransportMapper(
            DeterministicProviderTransportMapper.Mode.MAPPED,
            hash('e')
        ).map(request);
    }

    static AiProviderTransportFixtureObservation observation(
        AiProviderTransportFixtureObservation.Shape shape,
        String schemaHash
    ) {
        return new AiProviderTransportFixtureObservation(
            provider(),
            "mapper-a/1",
            schemaHash,
            shape,
            shape == AiProviderTransportFixtureObservation.Shape.EMPTY_BODY ? 0 : 256,
            hash('9'),
            shape == AiProviderTransportFixtureObservation.Shape.TIMEOUT,
            shape == AiProviderTransportFixtureObservation.Shape.CANCELLED,
            false,
            false,
            false,
            false
        );
    }

    static AiProviderPayloadCanonicalizationPolicy policy() {
        return new AiProviderPayloadCanonicalizationPolicy(
            "canonical-policy",
            "1",
            10,
            4096,
            Set.of(AiProviderPayloadCanonicalizationPolicy.DataClassification.values()),
            true,
            false,
            false,
            false,
            false,
            false
        );
    }

    static List<AiProviderCanonicalPayloadEvidence.FieldEvidence> fields() {
        return List.of(
            new AiProviderCanonicalPayloadEvidence.FieldEvidence(
                "/amount",
                AiProviderCanonicalPayloadEvidence.ValueType.NUMBER_HASH,
                hash('a'),
                16,
                AiProviderPayloadCanonicalizationPolicy.DataClassification.INTERNAL,
                false
            ),
            new AiProviderCanonicalPayloadEvidence.FieldEvidence(
                "/supplier",
                AiProviderCanonicalPayloadEvidence.ValueType.STRING_HASH,
                hash('b'),
                32,
                AiProviderPayloadCanonicalizationPolicy.DataClassification.CONFIDENTIAL,
                true
            )
        );
    }

    static List<AiProviderTransportAcceptanceChecklist.GateEvidence> completeGates() {
        return List.of(
            gate(
                "CANONICAL_PAYLOAD_DETERMINISTIC",
                AiProviderTransportAcceptanceChecklist.Category.PLATFORM
            ),
            gate(
                "SECRET_MATERIAL_ABSENT",
                AiProviderTransportAcceptanceChecklist.Category.SECURITY
            ),
            gate(
                "NETWORK_DISPATCH_PROHIBITED",
                AiProviderTransportAcceptanceChecklist.Category.SECURITY
            ),
            gate(
                "MALFORMED_RESPONSE_FAILS_CLOSED",
                AiProviderTransportAcceptanceChecklist.Category.PLATFORM
            ),
            gate(
                "SCHEMA_DRIFT_FAILS_CLOSED",
                AiProviderTransportAcceptanceChecklist.Category.PLATFORM
            ),
            gate(
                "TIMEOUT_CANCELLATION_FAILS_CLOSED",
                AiProviderTransportAcceptanceChecklist.Category.OPERATIONS
            ),
            gate(
                "REDACTION_AUDIT_COMPLETE",
                AiProviderTransportAcceptanceChecklist.Category.SECURITY
            ),
            gate(
                "TWO_PERSON_REVIEW_BOUND",
                AiProviderTransportAcceptanceChecklist.Category.OPERATIONS
            )
        );
    }

    static AiProviderTransportAcceptanceChecklist.GateEvidence gate(
        String gateId,
        AiProviderTransportAcceptanceChecklist.Category category
    ) {
        return new AiProviderTransportAcceptanceChecklist.GateEvidence(
            gateId,
            category,
            AiProviderTransportAcceptanceChecklist.Decision.PASSED,
            hash((char) ('a' + Math.abs(gateId.hashCode() % 6)))
        );
    }

    static String hash(char character) {
        return Character.toString(character).repeat(64);
    }
}
