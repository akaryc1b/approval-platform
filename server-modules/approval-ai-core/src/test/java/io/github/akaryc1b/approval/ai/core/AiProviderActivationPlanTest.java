package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiProviderActivationPlanTest {

    @Test
    void reviewReadyPlanRemainsNonExecutableAndHasNoLease() {
        AiEndpointTrustAssessment endpoint = trustedEndpoint();
        AiProviderKillSwitch killSwitch = killSwitch(
            AiProviderKillSwitch.State.FAULT_DRILL_ONLY
        );
        AiProviderActivationPlan plan = AiProviderActivationPlan.assemble(
            "provider-a-plan",
            "1",
            completeReview(endpoint.assessmentHash(), killSwitch.evidenceHash()),
            killSwitch,
            lease(AiProviderActivationLease.State.NOT_GRANTED),
            endpoint
        );

        assertEquals(AiProviderActivationPlan.Status.REVIEW_READY, plan.status());
        assertEquals(AiProviderActivationPlan.Mode.NON_EXECUTABLE_REVIEW_ONLY, plan.mode());
        assertFalse(plan.leaseGranted());
        assertFalse(plan.secretResolutionAuthorized());
        assertFalse(plan.networkAccessAuthorized());
        assertFalse(plan.providerInvocationAuthorized());
        assertFalse(plan.applyAuthorized());
        assertFalse(plan.productionEnablementAuthorized());
    }

    @Test
    void disabledKillSwitchOrDeniedLeaseBlocksPlan() {
        AiEndpointTrustAssessment endpoint = trustedEndpoint();
        AiProviderKillSwitch disabledSwitch = killSwitch(AiProviderKillSwitch.State.DISABLED);
        AiProviderActivationPlan disabled = AiProviderActivationPlan.assemble(
            "provider-a-plan",
            "1",
            completeReview(endpoint.assessmentHash(), disabledSwitch.evidenceHash()),
            disabledSwitch,
            lease(AiProviderActivationLease.State.NOT_GRANTED),
            endpoint
        );
        AiProviderKillSwitch reviewSwitch = killSwitch(
            AiProviderKillSwitch.State.FAULT_DRILL_ONLY
        );
        AiProviderActivationPlan denied = AiProviderActivationPlan.assemble(
            "provider-a-plan",
            "1",
            completeReview(endpoint.assessmentHash(), reviewSwitch.evidenceHash()),
            reviewSwitch,
            lease(AiProviderActivationLease.State.DENIED),
            endpoint
        );

        assertEquals(AiProviderActivationPlan.Status.BLOCKED, disabled.status());
        assertEquals(AiProviderActivationPlan.Status.BLOCKED, denied.status());
    }

    @Test
    void blockedEndpointTrustPreventsReviewReadyPlan() {
        AiEndpointTrustAssessment blocked = AiEndpointTrustAssessment.assess(
            endpointPolicy(),
            new AiDnsResolutionEvidence(
                "provider-a/1/endpoint-a",
                "api.provider.example",
                Set.of(),
                AiDnsResolutionEvidence.Status.REBINDING_DETECTED,
                0,
                "4".repeat(64),
                false,
                false
            ),
            new AiTlsPeerEvidence(
                "provider-a/1/endpoint-a",
                "api.provider.example",
                "2".repeat(64),
                AiTlsPeerEvidence.Status.CHAIN_HOST_AND_PIN_MATCHED,
                false,
                "5".repeat(64),
                false,
                false
            )
        );
        AiProviderKillSwitch killSwitch = killSwitch(
            AiProviderKillSwitch.State.FAULT_DRILL_ONLY
        );
        AiProviderActivationPlan plan = AiProviderActivationPlan.assemble(
            "provider-a-plan",
            "1",
            completeReview(blocked.assessmentHash(), killSwitch.evidenceHash()),
            killSwitch,
            lease(AiProviderActivationLease.State.NOT_GRANTED),
            blocked
        );

        assertEquals(AiProviderActivationPlan.Status.BLOCKED, plan.status());
    }

    @Test
    void killSwitchLeaseAndPlanRejectExecutionAuthority() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiProviderKillSwitch(
                "provider-a-switch",
                provider(),
                1,
                AiProviderKillSwitch.State.FAULT_DRILL_ONLY,
                "FAULT_DRILL",
                "7".repeat(64),
                true,
                false,
                false
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiProviderActivationLease(
                "provider-a-lease",
                provider(),
                "1".repeat(64),
                AiProviderActivationLease.State.NOT_GRANTED,
                "REVIEW_ONLY",
                "8".repeat(64),
                false,
                true,
                false,
                false,
                false
            )
        );
    }

    private static AiProviderActivationReviewBundle completeReview(
        String endpointTrustHash,
        String killSwitchHash
    ) {
        return AiProviderActivationReviewBundle.create(
            "provider-a-review",
            "1",
            provider(),
            "1".repeat(64),
            "2".repeat(64),
            "3".repeat(64),
            "4".repeat(64),
            endpointTrustHash,
            "6".repeat(64),
            killSwitchHash,
            List.of(
                new AiProviderActivationReviewBundle.ReviewerApproval(
                    "security-reviewer",
                    AiProviderActivationReviewBundle.Role.SECURITY,
                    AiProviderActivationReviewBundle.Decision.APPROVED,
                    "a".repeat(64)
                ),
                new AiProviderActivationReviewBundle.ReviewerApproval(
                    "platform-reviewer",
                    AiProviderActivationReviewBundle.Role.PLATFORM,
                    AiProviderActivationReviewBundle.Decision.APPROVED,
                    "b".repeat(64)
                )
            )
        );
    }

    private static AiProviderKillSwitch killSwitch(AiProviderKillSwitch.State state) {
        return new AiProviderKillSwitch(
            "provider-a-switch",
            provider(),
            1,
            state,
            "FAULT_DRILL",
            "7".repeat(64),
            false,
            false,
            false
        );
    }

    private static AiProviderActivationLease lease(AiProviderActivationLease.State state) {
        return new AiProviderActivationLease(
            "provider-a-lease",
            provider(),
            "1".repeat(64),
            state,
            "REVIEW_ONLY",
            "8".repeat(64),
            false,
            false,
            false,
            false,
            false
        );
    }

    private static AiEndpointTrustAssessment trustedEndpoint() {
        return AiEndpointTrustAssessment.assess(
            endpointPolicy(),
            new AiDnsResolutionEvidence(
                "provider-a/1/endpoint-a",
                "api.provider.example",
                Set.of("1".repeat(64)),
                AiDnsResolutionEvidence.Status.PUBLIC_SET_MATCHED,
                60,
                "4".repeat(64),
                false,
                false
            ),
            new AiTlsPeerEvidence(
                "provider-a/1/endpoint-a",
                "api.provider.example",
                "2".repeat(64),
                AiTlsPeerEvidence.Status.CHAIN_HOST_AND_PIN_MATCHED,
                false,
                "5".repeat(64),
                false,
                false
            )
        );
    }

    private static AiEndpointTrustPolicy endpointPolicy() {
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

    private static AiVersionReferences.ProviderVersion provider() {
        return new AiVersionReferences.ProviderVersion("provider-a", "1");
    }
}
