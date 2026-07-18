package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.CopiedInstanceCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore.CopiedInstancePage;

import java.util.Objects;

/**
 * User-facing approvals copied to the current operator.
 */
public final class ApprovalCopiedQueryService {

    private final ApprovalMessageStore messages;

    public ApprovalCopiedQueryService(ApprovalMessageStore messages) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    public CopiedInstancePage findCopiedInstances(
        String tenantId,
        String operatorId,
        String keyword,
        int limit,
        int offset
    ) {
        return messages.findCopiedInstances(new CopiedInstanceCriteria(
            tenantId,
            operatorId,
            keyword,
            limit,
            offset
        ));
    }
}
