package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiProviderActivationReviewBundleTest {

    @Test
    void twoDistinctApprovedReviewersAndRolesCompleteReview() {
        AiProviderActivationReviewBundle bundle = bundle(
            List.of(
                approval("security-reviewer", AiProviderActivationReviewBundle.Role.SECURITY),
                approval("platform-reviewer", AiProviderActivationReviewBundle.Role.PLATFORM)
            )
        );

        assertEquals(
            AiProviderActivationReviewBundle.Status.REVIEW_COMPLETE,
            bundle.status()
        );
        assertFalse(bundle.applyAuthorized());
        assertFalse(bundle.productionEnablementAuthorized());
    }

    @Test
    void duplicateReviewerOrSingleRoleBlocksReview() {
        AiProviderActivationReviewBundle duplicate = bundle(
            List.of(
                approval("same-reviewer", AiProviderActivationReviewBundle.Role.SECURITY),
                approval("same-reviewer", AiProviderActivationReviewBundle.Role.PLATFORM)
            )
        );
        AiProviderActivationReviewBundle oneRole = bundle(
            List.of(
                approval("reviewer-a", AiProviderActivationReviewBundle.Role.SECURITY),
                approval("reviewer-b", AiProviderActivationReviewBundle.Role.SECURITY)
            )
        );

        assertEquals(AiProviderActivationReviewBundle.Status.BLOCKED, duplicate.status());
        assertEquals(AiProviderActivationReviewBundle.Status.BLOCKED, oneRole.status());
    }

    @Test
    void rejectedReviewBlocksBundleAndChangesHash() {
        AiProviderActivationReviewBundle approved = bundle(
            List.of(
                approval("security-reviewer", AiProviderActivationReviewBundle.Role.SECURITY),
                approval("platform-reviewer", AiProviderActivationReviewBundle.Role.PLATFORM)
            )
        );
        AiProviderActivationReviewBundle rejected = bundle(
            List.of(
                approval("security-reviewer", AiProviderActivationReviewBundle.Role.SECURITY),
                new AiProviderActivationReviewBundle.ReviewerApproval(
                    "platform-reviewer",
                    AiProviderActivationReviewBundle.Role.PLATFORM,
                    AiProviderActivationReviewBundle.Decision.REJECTED,
                    "b".repeat(64)
                )
            )
        );

        assertEquals(AiProviderActivationReviewBundle.Status.BLOCKED, rejected.status());
        assertNotEquals(approved.bundleHash(), rejected.bundleHash());
    }

    @Test
    void reviewContractRejectsExecutionAuthority() {
        AiProviderActivationReviewBundle complete = bundle(
            List.of(
                approval("security-reviewer", AiProviderActivationReviewBundle.Role.SECURITY),
                approval("platform-reviewer", AiProviderActivationReviewBundle.Role.PLATFORM)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiProviderActivationReviewBundle(
                complete.bundleId(),
                complete.bundleVersion(),
                complete.providerVersion(),
                complete.deploymentSnapshotHash(),
                complete.readinessReportHash(),
                complete.faultDrillReportHash(),
                complete.changeSetHash(),
                complete.endpointTrustAssessmentHash(),
                complete.secretReferenceEvidenceHash(),
                complete.killSwitchEvidenceHash(),
                complete.approvals(),
                complete.status(),
                complete.bundleHash(),
                true,
                false,
                false,
                false,
                false
            )
        );
    }

    private static AiProviderActivationReviewBundle bundle(
        List<AiProviderActivationReviewBundle.ReviewerApproval> approvals
    ) {
        return AiProviderActivationReviewBundle.create(
            "provider-a-review",
            "1",
            provider(),
            "1".repeat(64),
            "2".repeat(64),
            "3".repeat(64),
            "4".repeat(64),
            "5".repeat(64),
            "6".repeat(64),
            "7".repeat(64),
            approvals
        );
    }

    private static AiProviderActivationReviewBundle.ReviewerApproval approval(
        String reviewer,
        AiProviderActivationReviewBundle.Role role
    ) {
        return new AiProviderActivationReviewBundle.ReviewerApproval(
            reviewer,
            role,
            AiProviderActivationReviewBundle.Decision.APPROVED,
            "a".repeat(64)
        );
    }

    private static AiVersionReferences.ProviderVersion provider() {
        return new AiVersionReferences.ProviderVersion("provider-a", "1");
    }
}
