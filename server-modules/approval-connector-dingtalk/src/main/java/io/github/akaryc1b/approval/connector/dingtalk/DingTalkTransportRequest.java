package io.github.akaryc1b.approval.connector.dingtalk;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Credential-free request captured at the DingTalk transport boundary.
 */
public record DingTalkTransportRequest(
    ApiFamily apiFamily,
    HttpMethod method,
    String path,
    Map<String, String> headers,
    String body,
    Duration timeout
) {

    public DingTalkTransportRequest {
        apiFamily = Objects.requireNonNull(apiFamily, "apiFamily must not be null");
        method = Objects.requireNonNull(method, "method must not be null");
        path = requirePath(path);
        headers = boundedHeaders(headers);
        body = requireText(body, "body", 16_384);
        timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("timeout must be between 1 nanosecond and 30 seconds");
        }
    }

    public String canonicalRequest() {
        StringBuilder canonical = new StringBuilder(512);
        canonical.append("apiFamily=").append(apiFamily.name())
            .append("\nmethod=").append(method.name())
            .append("\npath=").append(path)
            .append("\nheaders=");
        headers.forEach((name, value) -> canonical.append(name).append('=').append(value).append(';'));
        return canonical.append("\nbody=").append(body)
            .append("\ntimeoutMillis=").append(timeout.toMillis())
            .toString();
    }

    public String requestHash() {
        return CanonicalPayloadHash.sha256Utf8(canonicalRequest());
    }

    public boolean credentialMaterialPresent() {
        return false;
    }

    public boolean absoluteEndpointPresent() {
        return false;
    }

    public boolean requiresExternalCredentialBinding() {
        return true;
    }

    public enum ApiFamily {
        OPEN_API_V1,
        LEGACY_OAPI
    }

    public enum HttpMethod {
        POST
    }

    private static String requirePath(String value) {
        String pathValue = requireText(value, "path", 256);
        if (!pathValue.startsWith("/") || pathValue.contains("://") || pathValue.contains("..")) {
            throw new IllegalArgumentException("path must be a relative provider path");
        }
        return pathValue;
    }

    private static Map<String, String> boundedHeaders(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        if (source.size() > 8) {
            throw new IllegalArgumentException("headers exceed 8 entries");
        }
        Map<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        source.forEach((name, value) -> {
            String headerName = requireText(name, "header name", 64);
            if (isSensitiveHeader(headerName)) {
                throw new IllegalArgumentException(
                    "captured request must not contain sensitive header " + headerName
                );
            }
            sorted.put(headerName, requireText(value, "header value", 256));
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static boolean isSensitiveHeader(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return normalized.contains("authorization")
            || normalized.contains("token")
            || normalized.contains("secret")
            || normalized.contains("credential")
            || normalized.contains("password")
            || normalized.contains("apikey")
            || normalized.contains("accesskey");
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds " + maximumLength + " characters");
        }
        return normalized;
    }
}
