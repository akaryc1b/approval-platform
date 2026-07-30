package io.github.akaryc1b.approval.ai.spi;

import java.util.concurrent.CancellationException;

/** Cooperative cancellation boundary supplied to a provider invocation. */
@FunctionalInterface
public interface AiCancellation {

    boolean isCancellationRequested();

    default void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new CancellationException("AI provider invocation was cancelled");
        }
    }
}
