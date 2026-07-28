package io.github.akaryc1b.approval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Closed startup gate for the unselected production Secret material backend.
 */
@ConfigurationProperties(
    prefix = "approval.connector.secret-material",
    ignoreUnknownFields = false
)
public final class ApprovalConnectorSecretMaterialProperties {

    static final String BLOCKED_BACKEND = "BLOCKED_PENDING_BACKEND_SELECTION";

    private boolean enabled;
    private String backendSelection = BLOCKED_BACKEND;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBackendSelection() {
        return backendSelection;
    }

    public void setBackendSelection(String backendSelection) {
        this.backendSelection = backendSelection;
    }

    void requireBlockedSelection() {
        if (enabled) {
            throw new IllegalStateException(
                "production Secret material is blocked until a backend is selected"
            );
        }
        if (!BLOCKED_BACKEND.equals(backendSelection)) {
            throw new IllegalStateException(
                "production Secret backend selection is not authorized"
            );
        }
    }
}
