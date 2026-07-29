package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .InvocationPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(
    prefix = "approval.connector.invocation",
    ignoreUnknownFields = false
)
public final class ApprovalConnectorInvocationProperties {

    private boolean enabled;
    private String policyVersion = "connector-invocation-policy-v1";
    private int maximumRequestBytes = 65_536;
    private int maximumResponseBytes = 262_144;
    private Duration timeout = Duration.ofSeconds(5);
    private String killSwitchRevision = "kill-switch-v1";
    private String tokenPolicyVersion = "dingtalk-token-policy-v1";

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

    public int getMaximumRequestBytes() {
        return maximumRequestBytes;
    }

    public void setMaximumRequestBytes(int maximumRequestBytes) {
        this.maximumRequestBytes = maximumRequestBytes;
    }

    public int getMaximumResponseBytes() {
        return maximumResponseBytes;
    }

    public void setMaximumResponseBytes(int maximumResponseBytes) {
        this.maximumResponseBytes = maximumResponseBytes;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public String getKillSwitchRevision() {
        return killSwitchRevision;
    }

    public void setKillSwitchRevision(String killSwitchRevision) {
        this.killSwitchRevision = killSwitchRevision;
    }

    public String getTokenPolicyVersion() {
        return tokenPolicyVersion;
    }

    public void setTokenPolicyVersion(String tokenPolicyVersion) {
        this.tokenPolicyVersion = tokenPolicyVersion;
    }

    InvocationPolicy toPolicy() {
        return new InvocationPolicy(
            policyVersion,
            maximumRequestBytes,
            maximumResponseBytes,
            timeout,
            killSwitchRevision,
            tokenPolicyVersion
        );
    }
}
