package io.github.akaryc1b.approval.application;

/** A missing or invisible participant resource; deliberately carries no existence details. */
public final class ApprovalParticipantResourceNotFoundException extends RuntimeException {

    public ApprovalParticipantResourceNotFoundException() {
        super("approval resource was not found");
    }
}
