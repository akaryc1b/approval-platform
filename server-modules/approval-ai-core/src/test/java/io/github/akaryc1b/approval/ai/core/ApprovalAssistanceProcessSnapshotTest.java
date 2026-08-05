package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProcessSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalAssistanceProcessSnapshotTest {

    @Test
    void rejectsReleaseVersionWithBlankPackageHash() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessSnapshot(
            "purchase-payment",
            2,
            "compiler-1.1.0",
            "definition-hash-v2",
            "purchase-payment-form",
            3,
            5,
            "   "
        ));
    }

    @Test
    void rejectsReleaseHashWithoutReleaseVersion() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessSnapshot(
            "purchase-payment",
            2,
            "compiler-1.1.0",
            "definition-hash-v2",
            "purchase-payment-form",
            3,
            null,
            "release-package-hash-v5"
        ));
    }
}
