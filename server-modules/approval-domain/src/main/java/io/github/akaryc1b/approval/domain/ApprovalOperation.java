package io.github.akaryc1b.approval.domain;

/**
 * Product-level operations. Engine-specific operations must be translated into these semantics.
 */
public enum ApprovalOperation {
    APPROVE,
    REJECT,
    RETURN,
    WITHDRAW,
    RETRIEVE,
    TRANSFER,
    DELEGATE,
    ASSIST,
    ADD_BEFORE,
    ADD_AFTER,
    REMOVE_APPROVER,
    SUSPEND,
    RESUME,
    TERMINATE
}
