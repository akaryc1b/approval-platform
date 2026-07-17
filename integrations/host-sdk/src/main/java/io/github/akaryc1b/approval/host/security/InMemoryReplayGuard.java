package io.github.akaryc1b.approval.host.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory replay guard for tests and single-node development.
 * Production starters should provide a distributed Redis implementation.
 */
public final class InMemoryReplayGuard implements ReplayGuard {

    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> reservations = new ConcurrentHashMap<>();

    public InMemoryReplayGuard(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public boolean reserve(String tenantKey, String nonce, Instant expiresAt) {
        tenantKey = requireText(tenantKey, "tenantKey");
        nonce = requireText(nonce, "nonce");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");

        Instant now = clock.instant();
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }

        String reservationKey = tenantKey + '\n' + nonce;
        AtomicBoolean reserved = new AtomicBoolean();
        reservations.compute(reservationKey, (key, existingExpiry) -> {
            if (existingExpiry == null || !existingExpiry.isAfter(now)) {
                reserved.set(true);
                return expiresAt;
            }
            return existingExpiry;
        });
        return reserved.get();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
