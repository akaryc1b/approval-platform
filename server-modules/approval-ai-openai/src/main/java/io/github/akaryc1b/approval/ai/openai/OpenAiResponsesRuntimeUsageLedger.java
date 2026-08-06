package io.github.akaryc1b.approval.ai.openai;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Process-local P6-D ledger for dispatched request counts and admitted cost upper bounds.
 *
 * <p>The ledger is observational only. It does not authorize, reserve, rate-limit, bill or retry
 * work. Entries are tenant-hash scoped, bounded by the production rate envelope and reset when the
 * process restarts. A small fixed number of adjacent admission windows is retained so a dispatch
 * that crosses a rate-window boundary cannot overwrite newer usage.</p>
 */
public final class OpenAiResponsesRuntimeUsageLedger {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MAXIMUM_TRACKED_WINDOWS = 4;

    private final int perTenantLimit;
    private final int globalLimit;
    private final int maximumTenants;
    private final Duration window;
    private final long maximumRequestMicros;
    private final long tenantEnvelopeMicros;
    private final long globalEnvelopeMicros;
    private final Map<String, Map<Instant, Bucket>> tenantBuckets = new HashMap<>();
    private final Map<Instant, Bucket> globalBuckets = new HashMap<>();

    public OpenAiResponsesRuntimeUsageLedger(
        int perTenantLimit,
        int globalLimit,
        int maximumTenants,
        Duration window,
        long maximumRequestMicros
    ) {
        if (perTenantLimit < 1 || perTenantLimit > 1_000_000
            || globalLimit < perTenantLimit || globalLimit > 1_000_000
            || maximumTenants < 1 || maximumTenants > 100_000
            || maximumRequestMicros < 1) {
            throw new IllegalArgumentException(
                "usage ledger limits must be positive, coherent and bounded"
            );
        }
        this.window = Objects.requireNonNull(window, "window must not be null");
        if (window.isZero() || window.isNegative()
            || window.compareTo(Duration.ofHours(1)) > 0
            || window.getNano() != 0) {
            throw new IllegalArgumentException(
                "usage ledger window must use positive whole seconds up to one hour"
            );
        }
        try {
            this.tenantEnvelopeMicros = Math.multiplyExact(
                perTenantLimit,
                maximumRequestMicros
            );
            this.globalEnvelopeMicros = Math.multiplyExact(
                globalLimit,
                maximumRequestMicros
            );
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("usage ledger envelope must fit in long", overflow);
        }
        this.perTenantLimit = perTenantLimit;
        this.globalLimit = globalLimit;
        this.maximumTenants = maximumTenants;
        this.maximumRequestMicros = maximumRequestMicros;
    }

    /**
     * Records one request only after admission is committed, in the original rate window.
     */
    public synchronized void recordDispatched(
        String tenantHash,
        Instant rateWindowStart,
        long estimatedUpperBoundMicros
    ) {
        String tenant = requireHash(tenantHash, "tenantHash");
        Instant start = requireWindowStart(rateWindowStart);
        if (estimatedUpperBoundMicros < 1
            || estimatedUpperBoundMicros > maximumRequestMicros) {
            throw new IllegalArgumentException(
                "estimatedUpperBoundMicros must be positive and request-bounded"
            );
        }
        Map<Instant, Bucket> tenantWindows = tenantBuckets.get(tenant);
        if (tenantWindows == null) {
            if (tenantBuckets.size() >= maximumTenants) {
                throw new IllegalStateException("AI usage tenant capacity exceeded");
            }
            tenantWindows = new HashMap<>();
            tenantBuckets.put(tenant, tenantWindows);
        }
        Bucket tenantBucket = tenantWindows.computeIfAbsent(start, Bucket::new);
        Bucket globalBucket = globalBuckets.computeIfAbsent(start, Bucket::new);
        if (tenantBucket.committedRequests >= perTenantLimit
            || globalBucket.committedRequests >= globalLimit) {
            throw new IllegalStateException("AI usage ledger drifted beyond rate limits");
        }
        long nextTenant = addBounded(
            tenantBucket.committedUpperBoundMicros,
            estimatedUpperBoundMicros,
            tenantEnvelopeMicros
        );
        long nextGlobal = addBounded(
            globalBucket.committedUpperBoundMicros,
            estimatedUpperBoundMicros,
            globalEnvelopeMicros
        );
        tenantBucket.committedRequests++;
        tenantBucket.committedUpperBoundMicros = nextTenant;
        globalBucket.committedRequests++;
        globalBucket.committedUpperBoundMicros = nextGlobal;
        retainNewest(tenantWindows);
        retainNewest(globalBuckets);
    }

    /** Returns a side-effect-free tenant-scoped snapshot without creating a tenant bucket. */
    public synchronized UsageSnapshot snapshot(String tenantHash, Instant observedAt) {
        String tenant = requireHash(tenantHash, "tenantHash");
        Instant now = Objects.requireNonNull(observedAt, "observedAt must not be null");
        Instant start = windowStart(now, window);
        Instant end = start.plus(window);
        Bucket tenantBucket = bucket(tenantBuckets.get(tenant), start);
        Bucket globalBucket = globalBuckets.get(start);
        int tenantRequests = tenantBucket == null ? 0 : tenantBucket.committedRequests;
        long tenantMicros = tenantBucket == null
            ? 0
            : tenantBucket.committedUpperBoundMicros;
        boolean globalSaturated = globalBucket != null
            && globalBucket.committedRequests >= globalLimit;
        String evidenceHash = OpenAiResponsesProtocol.sha256Utf8(String.join(
            "\n",
            "openai-responses-process-usage-v1",
            tenant,
            now.toString(),
            start.toString(),
            end.toString(),
            Integer.toString(tenantRequests),
            Integer.toString(perTenantLimit),
            Long.toString(tenantMicros),
            Long.toString(tenantEnvelopeMicros),
            Integer.toString(globalLimit),
            Long.toString(globalEnvelopeMicros),
            "global-exact-usage-redacted",
            Boolean.toString(globalSaturated)
        ));
        return new UsageSnapshot(
            now,
            tenant,
            start,
            end,
            tenantRequests,
            perTenantLimit,
            globalLimit,
            tenantMicros,
            tenantEnvelopeMicros,
            globalEnvelopeMicros,
            globalSaturated,
            true,
            false,
            false,
            evidenceHash
        );
    }

    public record UsageSnapshot(
        Instant observedAt,
        String tenantHash,
        Instant windowStart,
        Instant windowEnd,
        int committedRequests,
        int requestLimit,
        int globalRequestLimit,
        long committedUpperBoundMicros,
        long derivedEnvelopeMicros,
        long globalDerivedEnvelopeMicros,
        boolean globalSaturated,
        boolean processLocal,
        boolean durable,
        boolean actualProviderCost,
        String evidenceHash
    ) {
        public UsageSnapshot {
            observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
            tenantHash = requireHash(tenantHash, "tenantHash");
            windowStart = Objects.requireNonNull(windowStart, "windowStart must not be null");
            windowEnd = Objects.requireNonNull(windowEnd, "windowEnd must not be null");
            if (!windowStart.isBefore(windowEnd)
                || committedRequests < 0
                || requestLimit < 1
                || globalRequestLimit < requestLimit
                || committedRequests > requestLimit
                || committedUpperBoundMicros < 0
                || derivedEnvelopeMicros < 1
                || globalDerivedEnvelopeMicros < derivedEnvelopeMicros
                || committedUpperBoundMicros > derivedEnvelopeMicros) {
                throw new IllegalArgumentException("usage snapshot must be coherent and bounded");
            }
            if (!processLocal || durable || actualProviderCost) {
                throw new IllegalArgumentException(
                    "P6-D usage must remain process-local, non-durable and upper-bound only"
                );
            }
            evidenceHash = requireHash(evidenceHash, "evidenceHash");
        }

        public int remainingRequests() {
            return requestLimit - committedRequests;
        }

        public long remainingDerivedEnvelopeMicros() {
            return derivedEnvelopeMicros - committedUpperBoundMicros;
        }

        public boolean tenantSaturated() {
            return committedRequests >= requestLimit;
        }
    }

    private static Bucket bucket(Map<Instant, Bucket> windows, Instant start) {
        return windows == null ? null : windows.get(start);
    }

    private Instant requireWindowStart(Instant value) {
        Instant start = Objects.requireNonNull(value, "rateWindowStart must not be null");
        if (!start.equals(windowStart(start, window))) {
            throw new IllegalArgumentException(
                "rateWindowStart must align with the configured rate window"
            );
        }
        return start;
    }

    private static long addBounded(long current, long delta, long maximum) {
        try {
            long next = Math.addExact(current, delta);
            if (next > maximum) {
                throw new IllegalStateException("AI usage ledger exceeded derived envelope");
            }
            return next;
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("AI usage ledger overflow", overflow);
        }
    }

    private static void retainNewest(Map<Instant, Bucket> windows) {
        while (windows.size() > MAXIMUM_TRACKED_WINDOWS) {
            Instant oldest = windows.keySet().stream()
                .min(Comparator.naturalOrder())
                .orElseThrow();
            windows.remove(oldest);
        }
    }

    private static Instant windowStart(Instant now, Duration window) {
        long seconds = window.getSeconds();
        long epoch = Math.floorDiv(now.getEpochSecond(), seconds) * seconds;
        return Instant.ofEpochSecond(epoch);
    }

    private static String requireHash(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
        return value;
    }

    private static final class Bucket {

        private final Instant start;
        private int committedRequests;
        private long committedUpperBoundMicros;

        private Bucket(Instant start) {
            this.start = start;
        }
    }
}
