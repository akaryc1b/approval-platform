package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AiProviderActivationReviewBundleHashTest {

    @Test
    void canonicalConstructorRejectsTamperedReviewEvidence() {
        AiProviderActivationReviewBundle original = AiProviderActivationReviewBundle.create(
            "review-a",
            "1",
            new AiVersionReferences.ProviderVersion("provider-a", "1"),
            "1".repeat(64),
            "2".repeat(64),
            "3".repeat(64),
            "4".repeat(64),
            "5".repeat(64),
            "6".repeat(64),
            "7".repeat(64),
            List.of(
                AiProviderActivationReviewBundle.ReviewerApproval.create(
                    "security-reviewer",
                    AiProviderActivationReviewBundle.Role.SECURITY,
                    AiProviderActivationReviewBundle.Decision.APPROVED,
                    "8".repeat(64)
                ),
                AiProviderActivationReviewBundle.ReviewerApproval.create(
                    "platform-reviewer",
                    AiProviderActivationReviewBundle.Role.PLATFORM,
                    AiProviderActivationReviewBundle.Decision.APPROVED,
                    "9".repeat(64)
                )
            )
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new AiProviderActivationReviewBundle(
                original.bundleId(),
                original.bundleVersion(),
                original.providerVersion(),
                original.deploymentSnapshotHash(),
                "a".repeat(64),
                original.faultDrillReportHash(),
                original.changeSetHash(),
                original.endpointTrustAssessmentHash(),
                original.secretReferenceEvidenceHash(),
                original.killSwitchEvidenceHash(),
                original.approvals(),
                original.status(),
                original.bundleHash(),
                false,
                false,
                false,
                false,
                false
            )
        );
    }
}
