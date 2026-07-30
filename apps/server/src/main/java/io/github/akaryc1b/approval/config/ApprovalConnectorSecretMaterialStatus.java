package io.github.akaryc1b.approval.config;

/**
 * Secret-free startup status. It is not a credential source or execution authorization.
 */
public record ApprovalConnectorSecretMaterialStatus(
    boolean enabled,
    String backendSelection,
    String statusCode
) {
}
