package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcApprovalMigrationEngineSnapshotHashTest {

    private static final String REQUEST_HASH = "a".repeat(64);
    private static final String STABLE_CODE = "ENGINE_READ_FAILURE";

    @Test
    void acceptsExistingApplicationReadFailureHashProtocol() {
        ApprovalMigrationEngineSnapshot snapshot = ApprovalMigrationEngineSnapshot.readFailure(
            STABLE_CODE,
            MySqlH5ExactVerificationHashFixture.readFailureHash(
                REQUEST_HASH,
                STABLE_CODE
            )
        );

        assertDoesNotThrow(() ->
            JdbcApprovalMigrationEngineSnapshotHash.requireValid(
                snapshot,
                REQUEST_HASH
            )
        );
    }

    @Test
    void rejectsReadFailureSnapshotWithUnboundHash() {
        ApprovalMigrationEngineSnapshot snapshot = ApprovalMigrationEngineSnapshot.readFailure(
            STABLE_CODE,
            "f".repeat(64)
        );

        assertThrows(
            IllegalStateException.class,
            () -> JdbcApprovalMigrationEngineSnapshotHash.requireValid(
                snapshot,
                REQUEST_HASH
            )
        );
    }
}
