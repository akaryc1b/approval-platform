package io.github.akaryc1b.approval.ai.openai;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Closed P6-D kill-switch, cost, rate and circuit control primitives. */
public final class OpenAiResponsesTransportControls {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private OpenAiResponsesTransportControls() {
    }

    public enum Outcome {
        SUCCESS,
        HTTP_REJECTED,
        TRANSPORT_FAILURE,
        UNKNOWN
    }

    public record KillSwitchSnapshot(
        String providerId,
        String providerVersion,
        long generation,
        boolean enabled,
        String policyRevision
    ) {
        public KillSwitchSnapshot {
            providerId = requireText(providerId, "providerId", 160);
            providerVersion = requireText(providerVersion, "providerVersion", 120);
            if (generation < 1) {
                throw new IllegalArgumentException("generation must be positive");
            }
            policyRevision = requireText(policyRevision, "policyRevision", 160);
        }

        public String evidenceHash() {
            return OpenAiResponsesProtocol.sha256Utf8(String.join(
                "\n",
                "openai-responses-kill-switch-v1",
                providerId,
                providerVersion,
                Long.toString(generation),
                Boolean.toString(enabled),
                policyRevision
            ));
        }
    }

    public record CostPolicy(
        String policyVersion,
        String modelSnapshot,
        long inputMicrosPerConservativeToken,
        long outputMicrosPerToken,
        long maximumRequestMicros,
        Instant effectiveFrom,
        Instant expiresAt
    ) {
        public CostPolicy {
            policyVersion = requireText(policyVersion, "policyVersion", 120);
            modelSnapshot = requireText(modelSnapshot, "modelSnapshot", 160);
            if (!OpenAiResponsesProtocol.MODEL_SNAPSHOT.equals(modelSnapshot)) {
                throw new IllegalArgumentException(
                    "cost policy must match the exact model snapshot"
                );
            }
            if (inputMicrosPerConservativeToken < 1
                || outputMicrosPerToken < 1
                || maximumRequestMicros < 1) {
                throw new IllegalArgumentException(
                    "cost values must be positive and bounded"
                );
            }
            effectiveFrom = Objects.requireNonNull(
                effectiveFrom,
                "effectiveFrom must not be null"
            );
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
            if (!effectiveFrom.isBefore(expiresAt)) {
                throw new IllegalArgumentException("cost policy interval must be positive");
            }
        }

        public Estimate estimate(
            int requestBytes,
            int maximumOutputTokens,
            Instant now
        ) {
            requireCurrent(now);
            if (requestBytes < 1 || maximumOutputTokens < 1) {
                throw failure(OpenAiResponsesTransportException.Failure.REQUEST_INVALID);
            }
            long inputCost;
            long outputCost;
            long total;
            try {
                inputCost = Math.multiplyExact(
                    requestBytes,
                    inputMicrosPerConservativeToken
                );
                outputCost = Math.multiplyExact(
                    maximumOutputTokens,
                    outputMicrosPerToken
                );
                total = Math.addExact(inputCost, outputCost);
            } catch (ArithmeticException overflow) {
                throw failure(OpenAiResponsesTransportException.Failure.COST_LIMIT_EXCEEDED);
            }
            if (total > maximumRequestMicros) {
                throw failure(OpenAiResponsesTransportException.Failure.COST_LIMIT_EXCEEDED);
            }
            String evidence = OpenAiResponsesProtocol.sha256Utf8(String.join(
                "\n",
                "openai-responses-cost-estimate-v1",
                evidenceHash(),
                Integer.toString(requestBytes),
                Integer.toString(maximumOutputTokens),
                Long.toString(total)
            ));
            return new Estimate(total, maximumRequestMicros, evidenceHash(), evidence);
        }

        public void requireCurrent(Instant now) {
            Objects.requireNonNull(now, "now must not be null");
            if (now.isBefore(effectiveFrom) || !now.isBefore(expiresAt)) {
                throw failure(OpenAiResponsesTransportException.Failure.COST_POLICY_STALE);
            }
        }

        public String evidenceHash() {
            return OpenAiResponsesProtocol.sha256Utf8(String.join(
                "\n",
                "openai-responses-cost-policy-v1",
                policyVersion,
                modelSnapshot,
                Long.toString(inputMicrosPerConservativeToken),
                Long.toString(outputMicrosPerToken),
                Long.toString(maximumRequestMicros),
                effectiveFrom.toString(),
                expiresAt.toString()
            ));
        }

        public record Estimate(
            long estimatedMicros,
            long maximumMicros,
            String policyHash,
            String evidenceHash
        ) {
            public Estimate {
                if (estimatedMicros < 0 || maximumMicros < 1
                    || estimatedMicros > maximumMicros) {
                    throw new IllegalArgumentException("cost estimate must be bounded");
                }
                policyHash = requireSha256(policyHash, "policyHash");
                evidenceHash = requireSha256(evidenceHash, "evidenceHash");
            }
        }
    }

    public static final class RateLimiter {

        private final int perTenantLimit;
        private final int globalLimit;
        private final int maximumTenants;
        private final Duration window;
        private final Map<String, Bucket> tenantBuckets = new HashMap<>();
        private Bucket globalBucket;
        private long sequence;

        public RateLimiter(
            int perTenantLimit,
            int globalLimit,
            int maximumTenants,
            Duration window
        ) {
            if (perTenantLimit < 1 || perTenantLimit > 1_000_000
                || globalLimit < perTenantLimit || globalLimit > 1_000_000
                || maximumTenants < 1 || maximumTenants > 100_000) {
                throw new IllegalArgumentException(
                    "rate limits must be positive, coherent and bounded"
                );
            }
            this.window = requireDuration(window, "window", Duration.ofHours(1));
            if (this.window.getSeconds() < 1 || this.window.getNano() != 0) {
                throw new IllegalArgumentException(
                    "window must use whole seconds"
                );
            }
            this.perTenantLimit = perTenantLimit;
            this.globalLimit = globalLimit;
            this.maximumTenants = maximumTenants;
        }

        public synchronized RatePermit reserve(String tenantHash, Instant now) {
            String tenant = requireSha256(tenantHash, "tenantHash");
            Objects.requireNonNull(now, "now must not be null");
            Instant start = windowStart(now, window);
            tenantBuckets.entrySet().removeIf(entry -> !entry.getValue().start.equals(start));
            globalBucket = bucket(globalBucket, start);
            Bucket existing = tenantBuckets.get(tenant);
            if (existing == null && tenantBuckets.size() >= maximumTenants) {
                return denied(tenant, start);
            }
            Bucket tenantBucket = bucket(existing, start);
            if (globalBucket.count >= globalLimit
                || tenantBucket.count >= perTenantLimit) {
                return denied(tenant, start);
            }
            tenantBuckets.put(tenant, tenantBucket);
            globalBucket.count++;
            tenantBucket.count++;
            long id = ++sequence;
            String evidence = OpenAiResponsesProtocol.sha256Utf8(String.join(
                "\n",
                "openai-responses-rate-permit-v1",
                tenant,
                start.toString(),
                Long.toString(id),
                Integer.toString(tenantBucket.count),
                Integer.toString(globalBucket.count)
            ));
            return new RatePermit(id, tenant, start, true, evidence);
        }

        public synchronized void commit(RatePermit permit) {
            requireKnown(permit);
            if (!permit.completed.compareAndSet(false, true)) {
                throw failure(OpenAiResponsesTransportException.Failure.REQUEST_INVALID);
            }
            permit.committed.set(true);
        }

        public synchronized void rollback(RatePermit permit) {
            if (permit == null || !permit.allowed()) {
                return;
            }
            if (!permit.completed.compareAndSet(false, true)) {
                return;
            }
            Bucket tenant = tenantBuckets.get(permit.tenantHash());
            if (tenant != null && tenant.start.equals(permit.windowStart())
                && tenant.count > 0) {
                tenant.count--;
            }
            if (globalBucket != null
                && globalBucket.start.equals(permit.windowStart())
                && globalBucket.count > 0) {
                globalBucket.count--;
            }
        }

        private RatePermit denied(String tenantHash, Instant start) {
            String evidence = OpenAiResponsesProtocol.sha256Utf8(String.join(
                "\n",
                "openai-responses-rate-denied-v1",
                tenantHash,
                start.toString(),
                Integer.toString(perTenantLimit),
                Integer.toString(globalLimit)
            ));
            return new RatePermit(-1, tenantHash, start, false, evidence);
        }

        private void requireKnown(RatePermit permit) {
            Objects.requireNonNull(permit, "permit must not be null");
            if (!permit.allowed() || permit.id() < 1) {
                throw failure(OpenAiResponsesTransportException.Failure.REQUEST_INVALID);
            }
        }

        private static Bucket bucket(Bucket source, Instant start) {
            return source == null || !source.start.equals(start)
                ? new Bucket(start)
                : source;
        }

        private static Instant windowStart(Instant now, Duration window) {
            long seconds = window.getSeconds();
            long epoch = Math.floorDiv(now.getEpochSecond(), seconds) * seconds;
            return Instant.ofEpochSecond(epoch);
        }

        private static final class Bucket {

            private final Instant start;
            private int count;

            private Bucket(Instant start) {
                this.start = start;
            }
        }

        public static final class RatePermit {

            private final long id;
            private final String tenantHash;
            private final Instant windowStart;
            private final boolean allowed;
            private final String evidenceHash;
            private final AtomicBoolean completed = new AtomicBoolean();
            private final AtomicBoolean committed = new AtomicBoolean();

            private RatePermit(
                long id,
                String tenantHash,
                Instant windowStart,
                boolean allowed,
                String evidenceHash
            ) {
                this.id = id;
                this.tenantHash = tenantHash;
                this.windowStart = windowStart;
                this.allowed = allowed;
                this.evidenceHash = requireSha256(evidenceHash, "evidenceHash");
            }

            public long id() {
                return id;
            }

            public String tenantHash() {
                return tenantHash;
            }

            public Instant windowStart() {
                return windowStart;
            }

            public boolean allowed() {
                return allowed;
            }

            public boolean committed() {
                return committed.get();
            }

            public String evidenceHash() {
                return evidenceHash;
            }

            @Override
            public String toString() {
                return "RatePermit[evidenceHash=" + evidenceHash + ", allowed="
                    + allowed + ", committed=" + committed.get() + "]";
            }
        }
    }

    public static final class CircuitBreaker {

        private final int failureThreshold;
        private final Duration openDuration;
        private final Set<Long> activePermits = new HashSet<>();
        private State state = State.CLOSED;
        private int consecutiveFailures;
        private Instant openUntil = Instant.EPOCH;
        private boolean halfOpenInFlight;
        private long sequence;
        private long generation = 1;

        public CircuitBreaker(int failureThreshold, Duration openDuration) {
            if (failureThreshold < 1 || failureThreshold > 1_000) {
                throw new IllegalArgumentException(
                    "failureThreshold must be positive and bounded"
                );
            }
            this.openDuration = requireDuration(
                openDuration,
                "openDuration",
                Duration.ofHours(1)
            );
            this.failureThreshold = failureThreshold;
        }

        public synchronized CircuitPermit tryAcquire(Instant now) {
            Objects.requireNonNull(now, "now must not be null");
            State before = state;
            boolean allowed;
            if (state == State.OPEN) {
                if (now.isBefore(openUntil) || halfOpenInFlight) {
                    allowed = false;
                } else {
                    state = State.HALF_OPEN;
                    halfOpenInFlight = true;
                    before = State.HALF_OPEN;
                    allowed = true;
                }
            } else if (state == State.HALF_OPEN) {
                allowed = false;
            } else {
                allowed = true;
            }
            long id = allowed ? ++sequence : -1;
            if (allowed) {
                activePermits.add(id);
            }
            String evidence = OpenAiResponsesProtocol.sha256Utf8(String.join(
                "\n",
                "openai-responses-circuit-permit-v1",
                before.name(),
                Boolean.toString(allowed),
                Long.toString(id),
                Long.toString(generation),
                openUntil.toString()
            ));
            return new CircuitPermit(id, generation, before, allowed, evidence);
        }

        public synchronized void record(
            CircuitPermit permit,
            Outcome outcome,
            Instant now
        ) {
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(now, "now must not be null");
            requireActive(permit);
            activePermits.remove(permit.id());
            if (outcome == Outcome.SUCCESS) {
                state = State.CLOSED;
                consecutiveFailures = 0;
                halfOpenInFlight = false;
                openUntil = Instant.EPOCH;
                generation++;
                return;
            }
            consecutiveFailures++;
            if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
                state = State.OPEN;
                openUntil = now.plus(openDuration);
                halfOpenInFlight = false;
            }
            generation++;
        }

        public synchronized void abandon(CircuitPermit permit) {
            if (permit == null || !permit.allowed()) {
                return;
            }
            if (!activePermits.remove(permit.id())) {
                return;
            }
            if (permit.stateBefore() == State.HALF_OPEN) {
                state = State.OPEN;
                halfOpenInFlight = false;
            }
        }

        public synchronized State state() {
            return state;
        }

        public synchronized long generation() {
            return generation;
        }

        private void requireActive(CircuitPermit permit) {
            Objects.requireNonNull(permit, "permit must not be null");
            if (!permit.allowed()
                || permit.generation() > generation
                || !activePermits.contains(permit.id())) {
                throw failure(OpenAiResponsesTransportException.Failure.REQUEST_INVALID);
            }
        }

        public enum State {
            CLOSED,
            OPEN,
            HALF_OPEN
        }

        public record CircuitPermit(
            long id,
            long generation,
            State stateBefore,
            boolean allowed,
            String evidenceHash
        ) {
            public CircuitPermit {
                stateBefore = Objects.requireNonNull(
                    stateBefore,
                    "stateBefore must not be null"
                );
                evidenceHash = requireSha256(evidenceHash, "evidenceHash");
                if (allowed && id < 1) {
                    throw new IllegalArgumentException("allowed permit requires an id");
                }
                if (!allowed && id != -1) {
                    throw new IllegalArgumentException("denied permit must use id -1");
                }
            }
        }
    }

    private static Duration requireDuration(
        Duration value,
        String name,
        Duration maximum
    ) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " must be positive and bounded");
        }
        return value;
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || !value.equals(value.trim())
            || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return value;
    }

    private static OpenAiResponsesTransportException failure(
        OpenAiResponsesTransportException.Failure failure
    ) {
        return new OpenAiResponsesTransportException(failure);
    }

    private static String requireSha256(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256 hex");
        }
        return value;
    }
}
