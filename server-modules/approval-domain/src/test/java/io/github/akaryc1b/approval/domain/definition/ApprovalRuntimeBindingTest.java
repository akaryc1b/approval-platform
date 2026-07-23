package io.github.akaryc1b.approval.domain.definition;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalRuntimeBindingTest {

    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");

    @Test
    void bindsOneExactImmutableReleasePackage() {
        ApprovalReleasePackage releasePackage = releasePackage("tenant-a", "a".repeat(64));
        ApprovalRuntimeBinding binding = binding("tenant-a", "a".repeat(64));

        assertTrue(binding.binds(releasePackage));
        assertTrue(binding.binds(releasePackage, deployment("tenant-a", "a".repeat(64))));
    }

    @Test
    void rejectsDifferentTenantOrReleaseEvidence() {
        ApprovalRuntimeBinding binding = binding("tenant-a", "a".repeat(64));

        assertFalse(binding.binds(releasePackage("tenant-b", "a".repeat(64))));
        assertFalse(binding.binds(releasePackage("tenant-a", "9".repeat(64))));
        assertFalse(binding.binds(
            releasePackage("tenant-a", "a".repeat(64)),
            deployment("tenant-a", "9".repeat(64))
        ));
    }

    @Test
    void rejectsMalformedOrIncompleteBindingEvidence() {
        assertThrows(
            IllegalArgumentException.class,
            () -> binding("tenant-a", "not-a-sha256")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ApprovalRuntimeBinding(
                "tenant-a",
                UUID.randomUUID(),
                "business-1",
                "engine-instance-1",
                "purchasePayment",
                7,
                "a".repeat(64),
                6,
                "b".repeat(64),
                5,
                "c".repeat(64),
                4,
                "d".repeat(64),
                3,
                "e".repeat(64),
                "compiler-v2",
                "f".repeat(64),
                "0".repeat(64),
                "1".repeat(64),
                "engine-deployment-1",
                "engine-definition-1",
                2,
                "2".repeat(64),
                " ",
                NOW,
                "request-1",
                "trace-1",
                "audit-1"
            )
        );
    }

    private static ApprovalRuntimeBinding binding(String tenantId, String releaseHash) {
        return new ApprovalRuntimeBinding(
            tenantId,
            UUID.randomUUID(),
            "business-1",
            "engine-instance-1",
            "purchasePayment",
            7,
            releaseHash,
            6,
            "b".repeat(64),
            5,
            "c".repeat(64),
            4,
            "d".repeat(64),
            3,
            "e".repeat(64),
            "compiler-v2",
            "f".repeat(64),
            "0".repeat(64),
            "1".repeat(64),
            "engine-deployment-1",
            "engine-definition-1",
            2,
            "2".repeat(64),
            "operator-a",
            NOW,
            "request-1",
            "trace-1",
            "audit-1"
        );
    }

    private static ApprovalReleaseDeployment deployment(
        String tenantId,
        String releaseHash
    ) {
        return new ApprovalReleaseDeployment(
            UUID.randomUUID(),
            tenantId,
            "purchasePayment",
            7,
            releaseHash,
            ApprovalReleaseDeployment.Status.DEPLOYED,
            1,
            "engine-deployment-1",
            "engine-definition-1",
            2,
            null,
            null,
            "operator-a",
            NOW,
            NOW,
            NOW
        );
    }

    private static ApprovalReleasePackage releasePackage(
        String tenantId,
        String releaseHash
    ) {
        return new ApprovalReleasePackage(
            tenantId,
            "purchasePayment",
            7,
            6,
            "b".repeat(64),
            5,
            "c".repeat(64),
            4,
            "d".repeat(64),
            3,
            "e".repeat(64),
            "compiler-v2",
            "purchase-payment.bpmn20.xml",
            "<definitions/>",
            "f".repeat(64),
            "0".repeat(64),
            null,
            null,
            "1".repeat(64),
            releaseHash,
            UUID.randomUUID(),
            "operator-a",
            NOW
        );
    }
}
