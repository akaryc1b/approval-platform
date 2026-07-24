package io.github.akaryc1b.approval.ai.core;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AiEndpointTrustAssessmentTest {

    @Test
    void exactPinnedEvidenceIsTrustedForReviewOnly() {
        AiEndpointTrustAssessment assessment = AiEndpointTrustAssessment.assess(
            policy(),
            dns(AiDnsResolutionEvidence.Status.PUBLIC_SET_MATCHED, Set.of("1".repeat(64)), 60),
            tls(AiTlsPeerEvidence.Status.CHAIN_HOST_AND_PIN_MATCHED, "2".repeat(64), false)
        );

        assertEquals(AiEndpointTrustAssessment.Status.TRUSTED_FOR_REVIEW, assessment.status());
        assertFalse(assessment.networkCallAttempted());
        assertFalse(assessment.productionEnablementAuthorized());
    }

    @Test
    void dnsRebindingAndPrivateAddressEvidenceFailClosed() {
        AiEndpointTrustAssessment rebinding = AiEndpointTrustAssessment.assess(
            policy(),
            dns(AiDnsResolutionEvidence.Status.REBINDING_DETECTED, Set.of(), 0),
            tls(AiTlsPeerEvidence.Status.CHAIN_HOST_AND_PIN_MATCHED, "2".repeat(64), false)
        );
        AiEndpointTrustAssessment privateAddress = AiEndpointTrustAssessment.assess(
            policy(),
            dns(AiDnsResolutionEvidence.Status.PRIVATE_OR_LOCAL_ADDRESS, Set.of(), 0),
            tls(AiTlsPeerEvidence.Status.CHAIN_HOST_AND_PIN_MATCHED, "2".repeat(64), false)
        );

        assertEquals(AiEndpointTrustAssessment.Status.BLOCKED, rebinding.status());
        assertEquals(AiEndpointTrustAssessment.Status.BLOCKED, privateAddress.status());
    }

    @Test
    void redirectAndCertificatePinMismatchFailClosed() {
        AiEndpointTrustAssessment redirect = AiEndpointTrustAssessment.assess(
            policy(),
            dns(AiDnsResolutionEvidence.Status.PUBLIC_SET_MATCHED, Set.of("1".repeat(64)), 60),
            tls(AiTlsPeerEvidence.Status.REDIRECT_OBSERVED, "2".repeat(64), true)
        );
        AiEndpointTrustAssessment pinMismatch = AiEndpointTrustAssessment.assess(
            policy(),
            dns(AiDnsResolutionEvidence.Status.PUBLIC_SET_MATCHED, Set.of("1".repeat(64)), 60),
            tls(AiTlsPeerEvidence.Status.PIN_MISMATCH, "3".repeat(64), false)
        );

        assertEquals(AiEndpointTrustAssessment.Status.BLOCKED, redirect.status());
        assertEquals(AiEndpointTrustAssessment.Status.BLOCKED, pinMismatch.status());
    }

    @Test
    void evidenceAndPolicyChangesAffectDeterministicAssessmentHash() {
        AiEndpointTrustAssessment first = AiEndpointTrustAssessment.assess(
            policy(),
            dns(AiDnsResolutionEvidence.Status.PUBLIC_SET_MATCHED, Set.of("1".repeat(64)), 60),
            tls(AiTlsPeerEvidence.Status.CHAIN_HOST_AND_PIN_MATCHED, "2".repeat(64), false)
        );
        AiEndpointTrustAssessment second = AiEndpointTrustAssessment.assess(
            policy(),
            dns(AiDnsResolutionEvidence.Status.PUBLIC_SET_MATCHED, Set.of("1".repeat(64)), 90),
            tls(AiTlsPeerEvidence.Status.CHAIN_HOST_AND_PIN_MATCHED, "2".repeat(64), false)
        );

        assertNotEquals(first.assessmentHash(), second.assessmentHash());
    }

    private static AiEndpointTrustPolicy policy() {
        return new AiEndpointTrustPolicy(
            "provider-a-endpoint",
            "1",
            "provider-a/1/endpoint-a",
            "api.provider.example",
            Set.of("1".repeat(64)),
            Set.of("2".repeat(64)),
            300,
            false,
            false,
            false,
            false,
            false
        );
    }

    private static AiDnsResolutionEvidence dns(
        AiDnsResolutionEvidence.Status status,
        Set<String> hashes,
        int ttl
    ) {
        return new AiDnsResolutionEvidence(
            "provider-a/1/endpoint-a",
            "api.provider.example",
            hashes,
            status,
            ttl,
            "4".repeat(64),
            false,
            false
        );
    }

    private static AiTlsPeerEvidence tls(
        AiTlsPeerEvidence.Status status,
        String pin,
        boolean redirect
    ) {
        return new AiTlsPeerEvidence(
            "provider-a/1/endpoint-a",
            "api.provider.example",
            pin,
            status,
            redirect,
            "5".repeat(64),
            false,
            false
        );
    }
}
