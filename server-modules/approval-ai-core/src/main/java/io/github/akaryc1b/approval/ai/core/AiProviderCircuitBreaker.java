package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory provider-health gate with one bounded half-open probe and no retry behavior. */
public final class AiProviderCircuitBreaker {

    private final Configuration configuration;
    private final Map<AiProviderRegistry.ProviderKey, Entry> entries = new ConcurrentHashMap<>();

    public AiProviderCircuitBreaker(Configuration configuration) {
        this.configuration = Objects.requireNonNull(
            configuration,
            "configuration must not be null"
        );
    }

    public Permit tryAcquire(
        AiVersionReferences.ProviderVersion providerVersion,
        Instant now
    ) {
        AiProviderRegistry.ProviderKey key = AiProviderRegistry.ProviderKey.from(providerVersion);
        Instant current = Objects.requireNonNull(now, "now must not be null");
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
        synchronized (entry) {
            if (entry.state == State.OPEN) {
                Instant retryAt = entry.openedAt.plus(configuration.openDuration());
                if (current.isBefore(retryAt)) {
                    return new Permit(key, false, State.OPEN, false);
                }
                entry.state = State.HALF_OPEN;
                entry.probeInFlight = true;
                return new Permit(key, true, State.HALF_OPEN, true);
            }
            if (entry.state == State.HALF_OPEN) {
                if (entry.probeInFlight) {
                    return new Permit(key, false, State.HALF_OPEN, false);
                }
                entry.probeInFlight = true;
                return new Permit(key, true, State.HALF_OPEN, true);
            }
            return new Permit(key, true, State.CLOSED, false);
        }
    }

    public State record(
        Permit permit,
        AiOutcomeClassification classification,
        Instant now
    ) {
        Objects.requireNonNull(permit, "permit must not be null");
        Objects.requireNonNull(classification, "classification must not be null");
        Instant current = Objects.requireNonNull(now, "now must not be null");
        Entry entry = entries.computeIfAbsent(permit.key(), ignored -> new Entry());
        synchronized (entry) {
            if (!permit.allowed()) {
                return entry.state;
            }
            entry.probeInFlight = false;
            if (isHealthy(classification)) {
                close(entry);
                return State.CLOSED;
            }
            if (!isProviderHealthFailure(classification)) {
                if (permit.probe() || entry.state == State.HALF_OPEN) {
                    close(entry);
                }
                return entry.state;
            }
            if (permit.probe() || entry.state == State.HALF_OPEN) {
                open(entry, current);
                return State.OPEN;
            }
            entry.consecutiveFailures++;
            if (entry.consecutiveFailures >= configuration.failureThreshold()) {
                open(entry, current);
                return State.OPEN;
            }
            return State.CLOSED;
        }
    }

    public void release(Permit permit) {
        Objects.requireNonNull(permit, "permit must not be null");
        if (!permit.allowed() || !permit.probe()) {
            return;
        }
        Entry entry = entries.computeIfAbsent(permit.key(), ignored -> new Entry());
        synchronized (entry) {
            entry.probeInFlight = false;
        }
    }

    public State state(AiVersionReferences.ProviderVersion providerVersion) {
        Entry entry = entries.get(AiProviderRegistry.ProviderKey.from(providerVersion));
        if (entry == null) {
            return State.CLOSED;
        }
        synchronized (entry) {
            return entry.state;
        }
    }

    private static boolean isHealthy(AiOutcomeClassification classification) {
        return classification == AiOutcomeClassification.SUCCESS
            || classification == AiOutcomeClassification.LOW_CONFIDENCE;
    }

    private static boolean isProviderHealthFailure(AiOutcomeClassification classification) {
        return classification == AiOutcomeClassification.TIMEOUT
            || classification == AiOutcomeClassification.PROVIDER_UNAVAILABLE
            || classification == AiOutcomeClassification.INVALID_OUTPUT
            || classification == AiOutcomeClassification.UNKNOWN;
    }

    private static void close(Entry entry) {
        entry.state = State.CLOSED;
        entry.consecutiveFailures = 0;
        entry.openedAt = null;
        entry.probeInFlight = false;
    }

    private static void open(Entry entry, Instant now) {
        entry.state = State.OPEN;
        entry.consecutiveFailures = 0;
        entry.openedAt = now;
        entry.probeInFlight = false;
    }

    public record Configuration(int failureThreshold, Duration openDuration) {
        public Configuration {
            if (failureThreshold < 1) {
                throw new IllegalArgumentException("failureThreshold must be positive");
            }
            openDuration = Objects.requireNonNull(openDuration, "openDuration must not be null");
            if (openDuration.isZero() || openDuration.isNegative()) {
                throw new IllegalArgumentException("openDuration must be positive");
            }
        }
    }

    public record Permit(
        AiProviderRegistry.ProviderKey key,
        boolean allowed,
        State stateBefore,
        boolean probe
    ) {
        public Permit {
            key = Objects.requireNonNull(key, "key must not be null");
            stateBefore = Objects.requireNonNull(stateBefore, "stateBefore must not be null");
            if (!allowed && probe) {
                throw new IllegalArgumentException("blocked permit cannot be a probe");
            }
        }
    }

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private static final class Entry {
        private State state = State.CLOSED;
        private int consecutiveFailures;
        private Instant openedAt;
        private boolean probeInFlight;
    }
}
