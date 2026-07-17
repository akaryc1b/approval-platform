package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.util.function.Supplier;

/**
 * Executes a write command at most once for a tenant, operation and idempotency key.
 * Implementations must return the previously completed result for a replayed command.
 */
public interface IdempotencyGuard {

    <T> T execute(RequestContext context, String operation, Supplier<T> action);
}
