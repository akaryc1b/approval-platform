package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Re-derives the existing D4 snapshot hash protocols at the persistence boundary. */
final class JdbcApprovalMigrationEngineSnapshotHash {

    private JdbcApprovalMigrationEngineSnapshotHash() {
    }

    static void requireValid(
        ApprovalMigrationEngineSnapshot value,
        String requestHash
    ) {
        ApprovalMigrationEngineSnapshot exact = Objects.requireNonNull(
            value,
            "snapshot must not be null"
        );
        String expectedHash = exact.readSucceeded()
            ? expectedSuccessfulSnapshot(exact)
            : expectedReadFailure(exact, requestHash);
        if (!expectedHash.equals(exact.snapshotHash())) {
            throw new IllegalStateException(
                "migration engine snapshot hash does not match bounded evidence"
            );
        }
    }

    static String expectedSuccessfulSnapshot(ApprovalMigrationEngineSnapshot value) {
        ApprovalMigrationEngineSnapshot exact = Objects.requireNonNull(
            value,
            "snapshot must not be null"
        );
        return sha256(String.join(
            "|",
            "m5-exact-engine-snapshot-v1",
            Boolean.toString(exact.readSucceeded()),
            text(exact.readFailureCode()),
            Boolean.toString(exact.runtimePresent()),
            text(exact.runtimeEngineDefinitionId()),
            text(exact.runtimeEngineDeploymentId()),
            Boolean.toString(exact.suspended()),
            exact.activeActivityIds().toString(),
            exact.executions().toString(),
            exact.activeTasks().toString(),
            exact.jobs().toString(),
            exact.subscriptions().toString(),
            exact.allowlistedVariableHashes().toString(),
            exact.identityLinkHashes().toString(),
            Boolean.toString(exact.historyPresent()),
            text(exact.historicEngineDefinitionId()),
            exact.historicEndTime() == null ? "" : exact.historicEndTime().toString(),
            text(exact.boundedDeleteReason()),
            exact.historicTasks().toString(),
            Boolean.toString(exact.truncated())
        ));
    }

    private static String expectedReadFailure(
        ApprovalMigrationEngineSnapshot value,
        String requestHash
    ) {
        String exactRequestHash = Objects.requireNonNull(
            requestHash,
            "requestHash must not be null"
        );
        if (exactRequestHash.isBlank()) {
            throw new IllegalArgumentException("requestHash must not be blank");
        }
        return sha256(
            "m5-verification-read-failure-v1|"
                + exactRequestHash
                + '|'
                + value.readFailureCode()
        );
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
