package io.github.akaryc1b.approval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

@ConfigurationProperties(prefix = "approval.connector.generic")
public class GenericConnectorProperties {

    private boolean enabled;
    private String connectorKey = "generic-rest";
    private URI hostBaseUri;
    private URI callbackUri;
    private String keyId;
    private String secret;
    private Duration timeout = Duration.ofSeconds(10);
    private Map<String, String> headers = Map.of();
    private Dispatch dispatch = new Dispatch();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getConnectorKey() {
        return connectorKey;
    }

    public void setConnectorKey(String connectorKey) {
        this.connectorKey = connectorKey;
    }

    public URI getHostBaseUri() {
        return hostBaseUri;
    }

    public void setHostBaseUri(URI hostBaseUri) {
        this.hostBaseUri = hostBaseUri;
    }

    public URI getCallbackUri() {
        return callbackUri;
    }

    public void setCallbackUri(URI callbackUri) {
        this.callbackUri = callbackUri;
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

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public Dispatch getDispatch() {
        return dispatch;
    }

    public void setDispatch(Dispatch dispatch) {
        this.dispatch = dispatch == null ? new Dispatch() : dispatch;
    }

    public byte[] secretBytes() {
        String value = requireText(secret, "approval.connector.generic.secret");
        byte[] decoded = value.startsWith("base64:")
            ? Base64.getDecoder().decode(value.substring("base64:".length()))
            : value.getBytes(StandardCharsets.UTF_8);
        if (decoded.length < 32) {
            java.util.Arrays.fill(decoded, (byte) 0);
            throw new IllegalStateException(
                "approval.connector.generic.secret must contain at least 32 bytes"
            );
        }
        return decoded;
    }

    public void validateEnabled() {
        if (!enabled) {
            return;
        }
        requireText(connectorKey, "approval.connector.generic.connector-key");
        requireText(keyId, "approval.connector.generic.key-id");
        validateUri(hostBaseUri, "host-base-uri");
        validateUri(callbackUri, "callback-uri");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("approval.connector.generic.timeout must be positive");
        }
        byte[] value = secretBytes();
        java.util.Arrays.fill(value, (byte) 0);
        dispatch.validate();
    }

    private static void validateUri(URI uri, String name) {
        if (uri == null) {
            throw new IllegalStateException("approval.connector.generic." + name + " is required");
        }
        boolean loopback = "localhost".equalsIgnoreCase(uri.getHost())
            || "127.0.0.1".equals(uri.getHost())
            || "::1".equals(uri.getHost());
        if (!"https".equalsIgnoreCase(uri.getScheme())
            && !(loopback && "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalStateException(
                "approval.connector.generic." + name + " must use HTTPS except loopback HTTP"
            );
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException(
                "approval.connector.generic." + name
                    + " must not include credentials, query or fragment"
            );
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must not be blank");
        }
        return value;
    }

    public static class Dispatch {

        private boolean enabled = true;
        private int batchSize = 50;
        private Duration leaseDuration = Duration.ofSeconds(30);
        private Duration initialDelay = Duration.ofSeconds(1);
        private Duration maximumDelay = Duration.ofMinutes(5);
        private int maximumAttempts = 10;
        private double jitterRatio = 0.2;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }

        public Duration getInitialDelay() {
            return initialDelay;
        }

        public void setInitialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
        }

        public Duration getMaximumDelay() {
            return maximumDelay;
        }

        public void setMaximumDelay(Duration maximumDelay) {
            this.maximumDelay = maximumDelay;
        }

        public int getMaximumAttempts() {
            return maximumAttempts;
        }

        public void setMaximumAttempts(int maximumAttempts) {
            this.maximumAttempts = maximumAttempts;
        }

        public double getJitterRatio() {
            return jitterRatio;
        }

        public void setJitterRatio(double jitterRatio) {
            this.jitterRatio = jitterRatio;
        }

        void validate() {
            if (batchSize < 1 || batchSize > 1000) {
                throw new IllegalStateException("dispatch.batch-size must be between 1 and 1000");
            }
            if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
                throw new IllegalStateException("dispatch.lease-duration must be positive");
            }
            if (initialDelay == null || initialDelay.isZero() || initialDelay.isNegative()) {
                throw new IllegalStateException("dispatch.initial-delay must be positive");
            }
            if (maximumDelay == null || maximumDelay.compareTo(initialDelay) < 0) {
                throw new IllegalStateException(
                    "dispatch.maximum-delay must not be shorter than initial-delay"
                );
            }
            if (maximumAttempts < 1) {
                throw new IllegalStateException("dispatch.maximum-attempts must be positive");
            }
            if (jitterRatio < 0 || jitterRatio > 1) {
                throw new IllegalStateException("dispatch.jitter-ratio must be between 0 and 1");
            }
        }
    }
}
