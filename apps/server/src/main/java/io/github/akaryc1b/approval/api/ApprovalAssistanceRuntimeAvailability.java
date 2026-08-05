package io.github.akaryc1b.approval.api;

/** Read-only projection of validated production Provider runtime presence. */
@FunctionalInterface
public interface ApprovalAssistanceRuntimeAvailability {

    boolean providerConfigured();
}
