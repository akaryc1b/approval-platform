package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.DefinitionEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.TaskEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcApprovalMigrationEngineSnapshotHashTest {

    private static final String REQUEST_HASH = "a".repeat(64);
    private static final String STABLE_CODE = "ENGINE_READ_FAILURE";
    private static final String TASK_HASH = "b".repeat(64);

    @Test
    void acceptsExistingFlowableSuccessfulSnapshotHashProtocol() {
        ApprovalMigrationEngineSnapshot snapshot = successfulSnapshot();

        assertDoesNotThrow(() ->
            JdbcApprovalMigrationEngineSnapshotHash.requireValid(
                snapshot,
                REQUEST_HASH
            )
        );
    }

    @Test
    void rejectsSuccessfulSnapshotWithUnboundHash() {
        ApprovalMigrationEngineSnapshot valid = successfulSnapshot();
        ApprovalMigrationEngineSnapshot snapshot = new ApprovalMigrationEngineSnapshot(
            valid.readSucceeded(),
            valid.readFailureCode(),
            valid.runtimePresent(),
            valid.runtimeEngineDefinitionId(),
            valid.runtimeEngineDeploymentId(),
            valid.suspended(),
            valid.activeActivityIds(),
            valid.executions(),
            valid.activeTasks(),
            valid.jobs(),
            valid.subscriptions(),
            valid.allowlistedVariableHashes(),
            valid.identityLinkHashes(),
            valid.historyPresent(),
            valid.historicEngineDefinitionId(),
            valid.historicEndTime(),
            valid.boundedDeleteReason(),
            valid.historicTasks(),
            valid.truncated(),
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

    private static ApprovalMigrationEngineSnapshot successfulSnapshot() {
        ApprovalMigrationEngineSnapshot unsigned = new ApprovalMigrationEngineSnapshot(
            true,
            null,
            true,
            "engine-definition-target",
            "engine-deployment-target",
            false,
            List.of("review"),
            List.of(new DefinitionEvidence(
                "EXECUTION",
                "execution",
                "engine-definition-target"
            )),
            List.of(new TaskEvidence(
                TASK_HASH,
                "review",
                "engine-definition-target",
                false
            )),
            List.of(),
            List.of(),
            List.of(TASK_HASH),
            List.of(TASK_HASH),
            true,
            "engine-definition-target",
            null,
            null,
            List.of(new TaskEvidence(
                TASK_HASH,
                "review",
                "engine-definition-target",
                false
            )),
            false,
            "0".repeat(64)
        );
        return MySqlH5ExactVerificationHashFixture.withCanonicalSnapshotHash(unsigned);
    }
}
