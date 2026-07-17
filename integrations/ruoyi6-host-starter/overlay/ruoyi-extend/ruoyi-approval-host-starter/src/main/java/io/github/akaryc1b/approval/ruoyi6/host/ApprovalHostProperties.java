package io.github.akaryc1b.approval.ruoyi6.host;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "approval.host")
public class ApprovalHostProperties {

    private boolean enabled;
    private String source = "ruoyi6";
    private String tenantId = "default";
    private String tenantName = "RuoYi 6";
    private Duration allowedClockSkew = Duration.ofMinutes(5);
    private Duration nonceTtl = Duration.ofMinutes(10);
    private Map<String, TenantKey> tenants = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getTenantName() {
        return tenantName;
    }

    public void setTenantName(String tenantName) {
        this.tenantName = tenantName;
    }

    public Duration getAllowedClockSkew() {
        return allowedClockSkew;
    }

    public void setAllowedClockSkew(Duration allowedClockSkew) {
        this.allowedClockSkew = allowedClockSkew;
    }

    public Duration getNonceTtl() {
        return nonceTtl;
    }

    public void setNonceTtl(Duration nonceTtl) {
        this.nonceTtl = nonceTtl;
    }

    public Map<String, TenantKey> getTenants() {
        return tenants;
    }

    public void setTenants(Map<String, TenantKey> tenants) {
        this.tenants = tenants == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tenants);
    }

    public TenantKey tenant(String requestedTenantId) {
        return tenants.get(requestedTenantId);
    }

    public static class TenantKey {

        private String keyId;
        private String secret;

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String keyId) {
            this.keyId = keyId;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public byte[] secretBytes() {
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException("approval.host tenant secret must not be blank");
            }
            if (secret.startsWith("base64:")) {
                return Base64.getDecoder().decode(secret.substring("base64:".length()));
            }
            return secret.getBytes(StandardCharsets.UTF_8);
        }
    }
}
