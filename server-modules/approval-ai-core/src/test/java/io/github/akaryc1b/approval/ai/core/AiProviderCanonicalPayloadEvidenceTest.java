package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiProviderCanonicalPayloadEvidenceTest {

    @Test
    void canonicalHashIsStableAcrossFieldOrdering() {
        List<AiProviderCanonicalPayloadEvidence.FieldEvidence> fields =
            AiProviderTransportTestFixtures.fields();
        AiProviderCanonicalPayloadEvidence first = canonical(fields);
        AiProviderCanonicalPayloadEvidence second = canonical(
            List.of(fields.get(1), fields.get(0))
        );

        assertEquals(first.canonicalPayloadHash(), second.canonicalPayloadHash());
        assertEquals(48, first.totalCanonicalBytes());
        assertFalse(first.rawPayloadStored());
    }

    @Test
    void duplicateJsonPointersFailClosed() {
        AiProviderCanonicalPayloadEvidence.FieldEvidence first =
            AiProviderTransportTestFixtures.fields().get(0);
        assertThrows(
            IllegalArgumentException.class,
            () -> canonical(List.of(first, first))
        );
    }

    @Test
    void confidentialFieldsRequireRedaction() {
        AiProviderCanonicalPayloadEvidence.FieldEvidence unsafe =
            new AiProviderCanonicalPayloadEvidence.FieldEvidence(
                "/supplier",
                AiProviderCanonicalPayloadEvidence.ValueType.STRING_HASH,
                AiProviderTransportTestFixtures.hash('a'),
                10,
                AiProviderPayloadCanonicalizationPolicy.DataClassification.CONFIDENTIAL,
                false
            );
        assertThrows(
            IllegalArgumentException.class,
            () -> canonical(List.of(unsafe))
        );
    }

    @Test
    void signingInputIsDeterministicAndDoesNotComputeSignature() {
        AiProviderCanonicalPayloadEvidence payload = canonical(
            AiProviderTransportTestFixtures.fields()
        );
        AiProviderSigningInputEvidence first = signing(payload, Set.of(
            "x-timestamp",
            "content-type"
        ));
        AiProviderSigningInputEvidence second = signing(payload, Set.of(
            "content-type",
            "x-timestamp"
        ));

        assertEquals(first.signingInputHash(), second.signingInputHash());
        assertFalse(first.signatureComputed());
        assertFalse(first.secretMaterialAccessed());
    }

    @Test
    void signingInputRejectsSensitiveHeaderNames() {
        AiProviderCanonicalPayloadEvidence payload = canonical(
            AiProviderTransportTestFixtures.fields()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> signing(payload, Set.of("authorization"))
        );
    }

    private static AiProviderCanonicalPayloadEvidence canonical(
        List<AiProviderCanonicalPayloadEvidence.FieldEvidence> fields
    ) {
        return AiProviderCanonicalPayloadEvidence.canonicalize(
            AiProviderTransportTestFixtures.policy(),
            AiProviderTransportTestFixtures.provider(),
            AiCapability.APPROVAL_SUMMARY,
            AiProviderTransportTestFixtures.hash('1'),
            fields
        );
    }

    private static AiProviderSigningInputEvidence signing(
        AiProviderCanonicalPayloadEvidence payload,
        Set<String> headers
    ) {
        return AiProviderSigningInputEvidence.create(
            AiProviderTransportTestFixtures.provider(),
            AiProviderSigningInputEvidence.Method.POST,
            "provider-a/1/endpoint-a",
            "mapper-a/1",
            "validator-a/1",
            payload.requestSchemaHash(),
            payload.canonicalPayloadHash(),
            AiProviderTransportTestFixtures.hash('c'),
            1_700_000_000L,
            headers,
            AiProviderSigningInputEvidence.Scheme.HMAC_SHA256_REFERENCE_ONLY
        );
    }
}
