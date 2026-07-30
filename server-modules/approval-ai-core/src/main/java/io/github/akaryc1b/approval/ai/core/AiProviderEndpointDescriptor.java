package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Exact HTTPS endpoint metadata. This contract performs no DNS lookup or network connection. */
public record AiProviderEndpointDescriptor(
    String endpointId,
    AiVersionReferences.ProviderVersion providerVersion,
    Scheme scheme,
    String host,
    int port,
    String basePath,
    boolean tlsRequired,
    boolean redirectsAllowed,
    boolean privateAddressAllowed
) {

    private static final Pattern HOST = Pattern.compile("[a-z0-9.-]{1,253}");
    private static final Pattern IPV4_LITERAL = Pattern.compile("[0-9.]+");

    public AiProviderEndpointDescriptor {
        endpointId = requireText(endpointId, "endpointId", 160);
        providerVersion = Objects.requireNonNull(
            providerVersion,
            "providerVersion must not be null"
        );
        scheme = Objects.requireNonNull(scheme, "scheme must not be null");
        if (scheme != Scheme.HTTPS) {
            throw new IllegalArgumentException("AI Provider endpoints must use HTTPS");
        }
        host = normalizeHost(host);
        if (port != 443) {
            throw new IllegalArgumentException("AI Provider endpoint port must be 443");
        }
        basePath = normalizeBasePath(basePath);
        if (!tlsRequired) {
            throw new IllegalArgumentException("AI Provider endpoints must require TLS");
        }
        if (redirectsAllowed) {
            throw new IllegalArgumentException("AI Provider endpoint redirects are prohibited");
        }
        if (privateAddressAllowed) {
            throw new IllegalArgumentException(
                "private AI Provider endpoint addresses are prohibited in M6-D"
            );
        }
    }

    public String authorizationKey() {
        return scheme.name().toLowerCase(Locale.ROOT)
            + "://"
            + host
            + ":"
            + port
            + basePath;
    }

    public enum Scheme {
        HTTPS
    }

    private static String normalizeHost(String value) {
        String normalized = requireText(value, "host", 253).toLowerCase(Locale.ROOT);
        if (!HOST.matcher(normalized).matches()
            || normalized.contains("..")
            || normalized.startsWith(".")
            || normalized.endsWith(".")
            || normalized.contains("*")
            || normalized.contains("@")
            || IPV4_LITERAL.matcher(normalized).matches()
            || normalized.startsWith("[")
            || normalized.endsWith("]")
            || normalized.equals("localhost")
            || normalized.endsWith(".localhost")
            || normalized.endsWith(".local")
            || normalized.endsWith(".internal")) {
            throw new IllegalArgumentException("host must be an exact public DNS name");
        }
        for (String label : normalized.split("\\.")) {
            if (label.isEmpty() || label.startsWith("-") || label.endsWith("-")) {
                throw new IllegalArgumentException("host labels must be valid and exact");
            }
        }
        return normalized;
    }

    private static String normalizeBasePath(String value) {
        String normalized = requireText(value, "basePath", 500);
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("/")
            || normalized.startsWith("//")
            || normalized.contains("\\")
            || normalized.contains("?")
            || normalized.contains("#")
            || normalized.contains("..")
            || lower.contains("%2e")
            || lower.contains("%2f")) {
            throw new IllegalArgumentException("basePath must be an exact safe path prefix");
        }
        return normalized.endsWith("/") && normalized.length() > 1
            ? normalized.substring(0, normalized.length() - 1)
            : normalized;
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }
}
