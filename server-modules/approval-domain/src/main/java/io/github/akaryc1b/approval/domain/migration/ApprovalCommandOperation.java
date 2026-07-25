package io.github.akaryc1b.approval.domain.migration;

/** Closed operation vocabulary for the shared approval-instance command serialization boundary. */
public enum ApprovalCommandOperation {
    COMPLETE,
    APPROVE,
    REJECT,
    RETURN,
    WITHDRAW,
    RETRIEVE,
    TRANSFER,
    TERMINATE,
    MIGRATION
}
