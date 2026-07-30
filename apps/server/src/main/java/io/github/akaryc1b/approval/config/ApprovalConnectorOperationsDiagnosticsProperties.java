package io.github.akaryc1b.approval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(
    prefix = "approval.connector.operations-diagnostics",
    ignoreUnknownFields = false
)
public final class ApprovalConnectorOperationsDiagnosticsProperties {
    private boolean enabled;
    private int maximumEntries = 1_024;
    private int maximumEntriesPerTenant = 256;
    private int maximumResponseBytes = 262_144;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaximumEntries() { return maximumEntries; }
    public void setMaximumEntries(int maximumEntries) { this.maximumEntries = maximumEntries; }
    public int getMaximumEntriesPerTenant() { return maximumEntriesPerTenant; }
    public void setMaximumEntriesPerTenant(int value) { maximumEntriesPerTenant = value; }
    public int getMaximumResponseBytes() { return maximumResponseBytes; }
    public void setMaximumResponseBytes(int maximumResponseBytes) {
        this.maximumResponseBytes = maximumResponseBytes;
    }
}
