package io.github.akaryc1b.approval.application.port;

/** Clears only the mutable current-effective projection with optimistic revision evidence. */
@FunctionalInterface
public interface ApprovalEffectiveReleaseDeactivationPort {

    boolean clear(String tenantId, String definitionKey, long expectedRevision);
}
