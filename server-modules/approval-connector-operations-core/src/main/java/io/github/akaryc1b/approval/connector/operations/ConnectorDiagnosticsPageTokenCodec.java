package io.github.akaryc1b.approval.connector.operations;

import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsContracts
    .PageCursor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-local HMAC page token. Restart invalidates outstanding tokens by design. */
public final class ConnectorDiagnosticsPageTokenCodec implements AutoCloseable {

    private static final String HMAC = "HmacSHA256";
    private final byte[] key;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ConnectorDiagnosticsPageTokenCodec(byte[] keyMaterial) {
        Objects.requireNonNull(keyMaterial, "keyMaterial must not be null");
        if (keyMaterial.length < 32 || keyMaterial.length > 64) {
            Arrays.fill(keyMaterial, (byte) 0);
            throw new IllegalArgumentException("page token key length is outside the closed bound");
        }
        key = Arrays.copyOf(keyMaterial, keyMaterial.length);
        Arrays.fill(keyMaterial, (byte) 0);
    }

    public String encode(PageCursor cursor) {
        requireOpen();
        Objects.requireNonNull(cursor, "cursor must not be null");
        String payload = cursor.tenantHash() + "." + cursor.filterHash() + "."
            + cursor.highWatermark() + "." + cursor.beforeSequence();
        byte[] payloadBytes = payload.getBytes(StandardCharsets.US_ASCII);
        byte[] signature = sign(payloadBytes);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes)
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } finally {
            Arrays.fill(payloadBytes, (byte) 0);
            Arrays.fill(signature, (byte) 0);
        }
    }

    public PageCursor decode(
        String token,
        String expectedTenantHash,
        String expectedFilterHash
    ) {
        requireOpen();
        Objects.requireNonNull(token, "token must not be null");
        if (token.isBlank() || token.length() > 1_024) {
            throw invalid();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) {
            throw invalid();
        }
        byte[] payload;
        byte[] supplied;
        try {
            payload = Base64.getUrlDecoder().decode(parts[0]);
            supplied = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException problem) {
            throw invalid();
        }
        byte[] expected = sign(payload);
        try {
            if (!MessageDigest.isEqual(expected, supplied)) {
                throw invalid();
            }
            String value = new String(payload, StandardCharsets.US_ASCII);
            String[] fields = value.split("\\.", -1);
            if (fields.length != 4
                || !Objects.equals(fields[0], expectedTenantHash)
                || !Objects.equals(fields[1], expectedFilterHash)) {
                throw invalid();
            }
            return new PageCursor(
                fields[0],
                fields[1],
                parseLong(fields[2]),
                parseLong(fields[3])
            );
        } finally {
            Arrays.fill(payload, (byte) 0);
            Arrays.fill(supplied, (byte) 0);
            Arrays.fill(expected, (byte) 0);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            Arrays.fill(key, (byte) 0);
        }
    }

    public boolean closed() {
        return closed.get();
    }

    @Override
    public String toString() {
        return "ConnectorDiagnosticsPageTokenCodec[closed=" + closed.get() + ", key=<redacted>]";
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException problem) {
            throw new ConnectorOperationsDiagnosticsExceptions.InternalFailure();
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new ConnectorOperationsDiagnosticsExceptions.SourceUnavailable();
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException problem) {
            throw invalid();
        }
    }

    private static ConnectorOperationsDiagnosticsExceptions.InvalidRequest invalid() {
        return new ConnectorOperationsDiagnosticsExceptions.InvalidRequest();
    }
}
