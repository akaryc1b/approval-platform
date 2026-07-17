package io.github.akaryc1b.approval.host.security;

import java.time.Instant;

/**
 * Atomically reserves a tenant nonce until its expiry.
 */
public interface ReplayGuard {

    boolean reserve(String tenantKey, String nonce, Instant expiresAt);
}
