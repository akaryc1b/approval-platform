package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Test-only mirror of the accepted bounded verification hash protocols. */
final class MySqlH5ExactVerificationHashFixture {

    private MySqlH5ExactVerificationHashFixture() {
    }

    static ApprovalMigrationEngineSnapshot withCanonicalSnapshotHash(
        ApprovalMigrationEngineSnapshot value
    ) {
        return new ApprovalMigrationEngineSnapshot(
            value.readSucceeded(),
            value.readFailureCode(),
            value.runtimePresent(),
            value.runtimeEngineDefinitionId(),
            value.runtimeEngineDeploymentId(),
            value.suspended(),
            value.activeActivityIds(),
            value.executions(),
            value.activeTasks(),
            value.jobs(),
            value.subscriptions(),
            value.allowlistedVariableHashes(),
            value.identityLinkHashes(),
            value.historyPresent(),
            value.historicEngineDefinitionId(),
            value.historicEndTime(),
            value.boundedDeleteReason(),
            value.historicTasks(),
            value.truncated(),
            snapshotHash(value)
        );
    }

    static String snapshotHash(ApprovalMigrationEngineSnapshot value) {
        return sha256(String.join(
            "|",
            "m5-exact-engine-snapshot-v1",
            Boolean.toString(value.readSucceeded()),
            text(value.readFailureCode()),
            Boolean.toString(value.runtimePresent()),
            text(value.runtimeEngineDefinitionId()),
            text(value.runtimeEngineDeploymentId()),
            Boolean.toString(value.suspended()),
            value.activeActivityIds().toString(),
            value.executions().toString(),
            value.activeTasks().toString(),
            value.jobs().toString(),
            value.subscriptions().toString(),
            value.allowlistedVariableHashes().toString(),
            value.identityLinkHashes().toString(),
            Boolean.toString(value.historyPresent()),
            text(value.historicEngineDefinitionId()),
            value.historicEndTime() == null ? "" : value.historicEndTime().toString(),
            text(value.boundedDeleteReason()),
            value.historicTasks().toString(),
            Boolean.toString(value.truncated())
        ));
    }

    static String verificationEvidenceHash(
        ApprovalMigrationExactVerification evidence,
        String snapshotHash
    ) {
        return sha256(String.join(
            "|",
            "m5-exact-verification-evidence-v1",
            evidence.verificationId().toString(),
            evidence.tenantId(),
            evidence.intentId().toString(),
            evidence.attemptId().toString(),
            evidence.engineRequestId().toString(),
            evidence.engineOutcomeId().toString(),
            evidence.sourceEngineDefinitionId(),
            evidence.targetEngineDefinitionId(),
            evidence.classification().name(),
            snapshotHash,
            evidence.requestHash()
        ));
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
