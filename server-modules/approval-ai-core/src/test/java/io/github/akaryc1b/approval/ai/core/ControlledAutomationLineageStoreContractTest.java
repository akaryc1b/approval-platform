package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.LineageOutcome;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.LineageStatus;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.RegistrationCommand;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.TransitionCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlledAutomationLineageStoreContractTest {

    private static final Instant NOW = Instant.parse("2026-08-05T06:00:00Z");

    @Test
    void registrationEvidenceIsDeterministicAndHashOnly() {
        RegistrationCommand first = registration("registration-key", "registration-payload");
        RegistrationCommand replay = registration("registration-key", "registration-payload");

        assertEquals(first, replay);
        assertEquals(64, first.evidenceHash().length());
        assertFalse(first.toString().contains("raw-value"));
    }

    @Test
    void registrationRejectsTamperedEvidenceHash() {
        RegistrationCommand valid = registration("registration-key-tampered", "payload");

        assertThrows(IllegalArgumentException.class, () -> new RegistrationCommand(
            valid.proposalId(),
            valid.confirmationId(),
            valid.tenantEvidenceHash(),
            valid.operatorEvidenceHash(),
            valid.proposalLineageHash(),
            valid.confirmationEvidenceHash(),
            valid.canonicalActionType(),
            valid.resourceEvidenceHash(),
            valid.whitelistVersion(),
            valid.policyVersion(),
            valid.idempotencyKeyHash(),
            valid.idempotencyPayloadHash(),
            valid.confirmedAt(),
            valid.expiresAt(),
            "f".repeat(64)
        ));
    }

    @Test
    void cancellationRequiresZeroAttemptsAndNoRetry() {
        TransitionCommand cancelled = TransitionCommand.create(
            hash("tenant"),
            hash("operator"),
            uuid("proposal-cancel"),
            1,
            LineageStatus.CONFIRMED,
            LineageStatus.CANCELLED,
            LineageOutcome.NONE,
            hash("cancel-evidence"),
            hash("cancel-key"),
            hash("cancel-payload"),
            NOW,
            0
        );

        assertEquals(0, cancelled.commandAttempts());
        assertFalse(cancelled.automaticRetryAllowed());
    }

    @Test
    void unknownIsTerminalSingleAttemptAndAutomaticRetryIsRejected() {
        TransitionCommand unknown = TransitionCommand.create(
            hash("tenant"),
            hash("operator"),
            uuid("proposal-unknown"),
            1,
            LineageStatus.CONFIRMED,
            LineageStatus.UNKNOWN,
            LineageOutcome.UNKNOWN,
            hash("unknown-evidence"),
            hash("unknown-key"),
            hash("unknown-payload"),
            NOW,
            1
        );

        assertEquals(LineageStatus.UNKNOWN, unknown.targetStatus());
        assertEquals(1, unknown.commandAttempts());
        assertFalse(unknown.automaticRetryAllowed());
        assertThrows(IllegalArgumentException.class, () -> new TransitionCommand(
            unknown.tenantEvidenceHash(),
            unknown.operatorEvidenceHash(),
            unknown.proposalId(),
            unknown.expectedRevision(),
            unknown.expectedStatus(),
            unknown.targetStatus(),
            unknown.outcome(),
            unknown.resultEvidenceHash(),
            unknown.idempotencyKeyHash(),
            unknown.idempotencyPayloadHash(),
            unknown.occurredAt(),
            unknown.commandAttempts(),
            true,
            unknown.transitionHash()
        ));
    }

    @Test
    void terminalStatusOutcomeAndAttemptCountsAreClosed() {
        assertThrows(IllegalArgumentException.class, () -> TransitionCommand.create(
            hash("tenant"),
            hash("operator"),
            uuid("proposal-invalid"),
            1,
            LineageStatus.CONFIRMED,
            LineageStatus.SUCCEEDED,
            LineageOutcome.FAILURE,
            hash("invalid-evidence"),
            hash("invalid-key"),
            hash("invalid-payload"),
            NOW,
            1
        ));
        assertThrows(IllegalArgumentException.class, () -> TransitionCommand.create(
            hash("tenant"),
            hash("operator"),
            uuid("proposal-invalid-attempt"),
            1,
            LineageStatus.CONFIRMED,
            LineageStatus.PARTIAL,
            LineageOutcome.PARTIAL,
            hash("invalid-attempt-evidence"),
            hash("invalid-attempt-key"),
            hash("invalid-attempt-payload"),
            NOW,
            2
        ));
    }

    private static RegistrationCommand registration(String key, String payload) {
        return RegistrationCommand.fromEvidence(
            uuid("proposal-" + key),
            uuid("confirmation-" + key),
            hash("tenant"),
            hash("operator"),
            hash("proposal-lineage-" + key),
            hash("confirmation-evidence-" + key),
            "TEST_ONLY_NON_EXECUTABLE_ACTION",
            hash("resource-" + key),
            "test-whitelist-v1",
            "test-policy-v1",
            hash(key),
            hash(payload),
            NOW,
            NOW.plusSeconds(120)
        );
    }

    private static String hash(String value) {
        return ControlledAutomationProposal.hashTuple("p4-test-hash-v1", value);
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
