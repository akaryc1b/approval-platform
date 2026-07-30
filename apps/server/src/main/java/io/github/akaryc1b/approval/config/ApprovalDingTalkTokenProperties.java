package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.connector.dingtalk.token.DingTalkTokenPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(
    prefix = "approval.connector.dingtalk-token",
    ignoreUnknownFields = false
)
public final class ApprovalDingTalkTokenProperties {

    private boolean enabled;
    private String policyVersion = "dingtalk-token-policy-v1";
    private Duration refreshBeforeExpiry = Duration.ofMinutes(5);
    private Duration minimumValidity = Duration.ofSeconds(30);
    private Duration maximumLifetime = Duration.ofHours(2);
    private Duration singleFlightWait = Duration.ofSeconds(5);
    private int maximumEntries = 256;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public Duration getRefreshBeforeExpiry() {
        return refreshBeforeExpiry;
    }

    public void setRefreshBeforeExpiry(Duration refreshBeforeExpiry) {
        this.refreshBeforeExpiry = refreshBeforeExpiry;
    }

    public Duration getMinimumValidity() {
        return minimumValidity;
    }

    public void setMinimumValidity(Duration minimumValidity) {
        this.minimumValidity = minimumValidity;
    }

    public Duration getMaximumLifetime() {
        return maximumLifetime;
    }

    public void setMaximumLifetime(Duration maximumLifetime) {
        this.maximumLifetime = maximumLifetime;
    }

    public Duration getSingleFlightWait() {
        return singleFlightWait;
    }

    public void setSingleFlightWait(Duration singleFlightWait) {
        this.singleFlightWait = singleFlightWait;
    }

    public int getMaximumEntries() {
        return maximumEntries;
    }

    public void setMaximumEntries(int maximumEntries) {
        this.maximumEntries = maximumEntries;
    }

    DingTalkTokenPolicy toPolicy() {
        return new DingTalkTokenPolicy(
            policyVersion,
            refreshBeforeExpiry,
            minimumValidity,
            maximumLifetime,
            singleFlightWait,
            maximumEntries
        );
    }
}
