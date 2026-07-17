package io.github.akaryc1b.approval.host.security;

import io.github.akaryc1b.approval.host.model.ConnectorError;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Verifies freshness and signature before atomically reserving the request nonce.
 */
public final class DefaultHostRequestVerifier implements HostRequestVerifier {

    private final HostConnectorProperties properties;
    private final TenantSecretResolver secretResolver;
    private final HostSignatureVerifier signatureVerifier;
    private final ReplayGuard replayGuard;
    private final Clock clock;

    public DefaultHostRequestVerifier(
        HostConnectorProperties properties,
        TenantSecretResolver secretResolver,
        HostSignatureVerifier signatureVerifier,
        ReplayGuard replayGuard,
        Clock clock
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.secretResolver = Objects.requireNonNull(secretResolver, "secretResolver must not be null");
        this.signatureVerifier = Objects.requireNonNull(
            signatureVerifier,
            "signatureVerifier must not be null"
        );
        this.replayGuard = Objects.requireNonNull(replayGuard, "replayGuard must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public VerifiedRequest verify(Request request) {
        Objects.requireNonNull(request, "request must not be null");
        Instant now = clock.instant();
        Instant requestedAt = parseTimestamp(request.timestamp());
        long timestamp = requestedAt.getEpochSecond();

        Duration skew = Duration.between(requestedAt, now).abs();
        if (skew.compareTo(properties.allowedClockSkew()) > 0) {
            throw new HostVerificationException(ConnectorError.expiredRequest());
        }

        TenantSecretResolver.SecretMaterial material = secretResolver
            .resolve(request.tenantKey(), request.keyId())
            .orElseThrow(() -> new HostVerificationException(ConnectorError.unknownKey()));
        byte[] secret = material.secret();
        try {
            boolean valid = signatureVerifier.verify(
                secret,
                timestamp,
                request.nonce(),
                request.body(),
                request.signature()
            );
            if (!valid) {
                throw new HostVerificationException(ConnectorError.invalidSignature());
            }
        } finally {
            Arrays.fill(secret, (byte) 0);
        }

        Instant nonceExpiry = now.plus(properties.nonceTtl());
        if (!replayGuard.reserve(request.tenantKey(), request.nonce(), nonceExpiry)) {
            throw new HostVerificationException(ConnectorError.replayedRequest());
        }

        return new VerifiedRequest(
            request.tenantKey(),
            request.keyId(),
            request.requestId(),
            requestedAt,
            now,
            request.nonce()
        );
    }

    private static Instant parseTimestamp(String value) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(value));
        } catch (NumberFormatException exception) {
            try {
                return Instant.parse(value);
            } catch (DateTimeException parseException) {
                throw invalidTimestamp();
            }
        } catch (DateTimeException exception) {
            throw invalidTimestamp();
        }
    }

    private static HostVerificationException invalidTimestamp() {
        return new HostVerificationException(
            ConnectorError.invalidRequest("timestamp must be epoch seconds or ISO-8601")
        );
    }
}
