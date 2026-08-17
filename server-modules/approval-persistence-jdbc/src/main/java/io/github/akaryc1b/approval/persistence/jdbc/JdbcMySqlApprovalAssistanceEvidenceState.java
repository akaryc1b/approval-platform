package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidenceStore.DeleteReason;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidenceStore.EvidenceState;

import java.time.Instant;
import java.util.UUID;

record JdbcMySqlApprovalAssistanceStoredState(
    UUID evidenceId,
    String requestEvidenceHash,
    String evidenceHash,
    long revision,
    EvidenceState state,
    String currentEventHash
) {
}

record JdbcMySqlApprovalAssistanceLockedState(
    UUID evidenceId,
    String evidenceHash,
    Instant recordedAt,
    Instant retentionUntil,
    long revision,
    EvidenceState state,
    DeleteReason deleteReason,
    Instant tombstonedAt,
    String deletionRequestHash,
    String tombstoneHash,
    String currentEventHash
) {
}
