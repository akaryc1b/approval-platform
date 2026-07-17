package io.github.akaryc1b.approval.example.host;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@ConfigurationProperties(prefix = "example.host")
public class ExampleHostProperties {

    private String source = "generic";
    private String tenantId = "demo";
    private String tenantName = "Generic Spring Host";
    private String keyId = "demo-key";
    private String secret;
    private String bearerToken;
    private Duration allowedClockSkew = Duration.ofMinutes(5);
    private Duration nonceTtl = Duration.ofMinutes(10);

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

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
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

    public byte[] secretBytes() {
        String configured = requireText(secret, "example.host.secret");
        byte[] decoded = configured.startsWith("base64:")
            ? Base64.getDecoder().decode(configured.substring("base64:".length()))
            : configured.getBytes(StandardCharsets.UTF_8);
        if (decoded.length < 32) {
            java.util.Arrays.fill(decoded, (byte) 0);
            throw new IllegalStateException("example.host.secret must contain at least 32 bytes");
        }
        return decoded;
    }

    public void validate() {
        requireText(source, "example.host.source");
        requireText(tenantId, "example.host.tenant-id");
        requireText(tenantName, "example.host.tenant-name");
        requireText(keyId, "example.host.key-id");
        requireText(bearerToken, "example.host.bearer-token");
        byte[] secretValue = secretBytes();
        java.util.Arrays.fill(secretValue, (byte) 0);
        if (allowedClockSkew == null || allowedClockSkew.isZero() || allowedClockSkew.isNegative()) {
            throw new IllegalStateException("example.host.allowed-clock-skew must be positive");
        }
        if (nonceTtl == null || nonceTtl.compareTo(allowedClockSkew) < 0) {
            throw new IllegalStateException("example.host.nonce-ttl must not be shorter than clock skew");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must not be blank");
        }
        return value;
    }
}
