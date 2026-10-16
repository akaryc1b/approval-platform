package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.util.function.Supplier;

/**
 * Executes a write command at most once for a tenant, operation and idempotency key.
 * Implementations must bind that reservation to the authenticated operator in the request context
 * and the request hash, and durably return the original completed result for a matching replay.
 * A different operator or request hash must be rejected without invoking the action or exposing
 * the cached result. Persistence adapters document compatibility with previously stored records.
 */
public interface IdempotencyGuard {

    <T> T execute(
        RequestContext context,
        String operation,
        String requestHash,
        Class<T> resultType,
        Supplier<T> action
    );

    final class IdempotencyConflictException extends RuntimeException {

        public IdempotencyConflictException(String message) {
            super(message);
        }
    }
}
