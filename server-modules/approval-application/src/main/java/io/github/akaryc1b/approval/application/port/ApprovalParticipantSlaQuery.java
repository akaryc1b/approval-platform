package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;

import java.util.Optional;
import java.util.UUID;

/** Server-owned participant visibility query; the client cannot nominate a trusted user. */
public interface ApprovalParticipantSlaQuery {

    Optional<SlaInstance> findVisibleTaskSla(String tenantId, UUID taskId, String userId);
}
