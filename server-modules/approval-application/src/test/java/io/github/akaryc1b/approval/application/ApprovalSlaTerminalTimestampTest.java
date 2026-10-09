package io.github.akaryc1b.approval.application;

import org.junit.jupiter.api.Test;

class ApprovalSlaTerminalTimestampTest {
    @Test
    void keepsAuthoritativeServiceTimesAndCompatibleSignatures() {
        ApprovalSlaTerminalTimestampChecks.serviceTimesAndCompatibility();
    }

    @Test
    void forwardsProjectionTimesOnlyAfterSuccessfulBusinessWrites() {
        ApprovalSlaTerminalTimestampChecks.projectionTimesAndFailureOrder();
    }

    @Test
    void preservesParticipantTimesAcrossOrderingAndReplay() {
        ApprovalSlaTerminalTimestampChecks.participantTimesAndOrdering();
    }

    @Test
    void preservesAggregateTerminalTimesAndExistingReasons() {
        ApprovalSlaTerminalTimestampChecks.collaborationTerminalTimes();
    }

    @Test
    void rejectsMissingAndInvalidTimingEvidenceBeforeWrites() {
        ApprovalSlaTerminalTimestampChecks.rejectsInvalidEvidenceBeforeWrites();
    }

    @Test
    void usesCollaborationStoreResultsRatherThanNewCommandTimes() {
        ApprovalSlaTerminalTimestampChecks.collaborationDecoratorUsesPersistedEvidence();
    }
}
