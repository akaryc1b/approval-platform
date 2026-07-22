package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;

import java.util.List;
import java.util.UUID;

/** Finds every active or paused SLA attached to a tenant-owned approval task. */
@FunctionalInterface
public interface ApprovalSlaActiveTaskQuery {

    List<SlaInstance> findActiveByTask(String tenantId, UUID taskId);
}
