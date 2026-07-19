package io.github.akaryc1b.approval.domain.definition;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalReleaseDeploymentTest {

    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");

    @Test
    void acceptsCompletePendingDeployedAndFailedStates() {
        assertDoesNotThrow(() -> deployment(
            ApprovalReleaseDeployment.Status.PENDING,
            null,
            null,
            null,
            null,
            null,
            null
        ));
        assertDoesNotThrow(() -> deployment(
            ApprovalReleaseDeployment.Status.DEPLOYED,
            "deployment-1",
            "definition-1",
            1,
            null,
            null,
            NOW
        ));
        assertDoesNotThrow(() -> deployment(
            ApprovalReleaseDeployment.Status.FAILED,
            null,
            null,
            null,
            "ENGINE_FAILED",
            "engine failed",
            null
        ));
    }

    @Test
    void rejectsPartialEngineIdentityAndPartialErrors() {
        assertThrows(
            IllegalArgumentException.class,
            () -> deployment(
                ApprovalReleaseDeployment.Status.PENDING,
                "deployment-1",
                null,
                null,
                null,
                null,
                null
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> deployment(
                ApprovalReleaseDeployment.Status.DEPLOYED,
                "deployment-1",
                null,
                1,
                null,
                null,
                NOW
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> deployment(
                ApprovalReleaseDeployment.Status.FAILED,
                null,
                null,
                null,
                "ENGINE_FAILED",
                null,
                null
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> deployment(
                ApprovalReleaseDeployment.Status.PENDING,
                null,
                null,
                null,
                null,
                "unexpected",
                null
            )
        );
    }

    private static ApprovalReleaseDeployment deployment(
        ApprovalReleaseDeployment.Status status,
        String engineDeploymentId,
        String engineDefinitionId,
        Integer engineVersion,
        String errorCode,
        String errorMessage,
        Instant deployedAt
    ) {
        return new ApprovalReleaseDeployment(
            UUID.randomUUID(),
            "tenant-a",
            "purchase-payment",
            1,
            "a".repeat(64),
            status,
            1,
            engineDeploymentId,
            engineDefinitionId,
            engineVersion,
            errorCode,
            errorMessage,
            "operator-a",
            NOW,
            NOW,
            deployedAt
        );
    }
}
