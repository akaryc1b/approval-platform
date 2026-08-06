package io.github.akaryc1b.approval.ai.openai;

import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesTransportControls.CircuitBreaker;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesTransportControls.CostPolicy;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesTransportControls.KillSwitchSnapshot;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesTransportControls.Outcome;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesTransportControls.RateLimiter;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * P6-D pre-dispatch admission for kill switch, circuit, rate and cost controls.
 *
 * <p>The controller owns no Secret and opens no network connection. A permit is bound to one exact
 * request-body hash and can be dispatched at most once.</p>
 */
public final class OpenAiResponsesTransportAdmission {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final String tenantHash;
    private final Supplier<KillSwitchSnapshot> killSwitchSource;
    private final long expectedKillSwitchGeneration;
    private final String expectedKillSwitchEvidenceHash;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;
    private final CostPolicy costPolicy;
    private final UsageRecorder usageRecorder;
    private final Clock clock;

    public OpenAiResponsesTransportAdmission(
        String tenantHash,
        Supplier<KillSwitchSnapshot> killSwitchSource,
        long expectedKillSwitchGeneration,
        String expectedKillSwitchEvidenceHash,
        CircuitBreaker circuitBreaker,
        RateLimiter rateLimiter,
        CostPolicy costPolicy,
        Clock clock
    ) {
        this(
            tenantHash,
            killSwitchSource,
            expectedKillSwitchGeneration,
            expectedKillSwitchEvidenceHash,
            circuitBreaker,
            rateLimiter,
            costPolicy,
            (ignoredTenant, ignoredWindow, ignoredMicros) ->
                Objects.requireNonNull(ignoredWindow, "ignoredWindow must not be null"),
            clock
        );
    }

    public OpenAiResponsesTransportAdmission(
        String tenantHash,
        Supplier<KillSwitchSnapshot> killSwitchSource,
        long expectedKillSwitchGeneration,
        String expectedKillSwitchEvidenceHash,
        CircuitBreaker circuitBreaker,
        RateLimiter rateLimiter,
        CostPolicy costPolicy,
        OpenAiResponsesRuntimeUsageLedger usageLedger,
        Clock clock
    ) {
        this(
            tenantHash,
            killSwitchSource,
            expectedKillSwitchGeneration,
            expectedKillSwitchEvidenceHash,
            circuitBreaker,
            rateLimiter,
            costPolicy,
            Objects.requireNonNull(usageLedger, "usageLedger must not be null")
                ::recordDispatched,
            clock
        );
    }

    private OpenAiResponsesTransportAdmission(
        String tenantHash,
        Supplier<KillSwitchSnapshot> killSwitchSource,
        long expectedKillSwitchGeneration,
        String expectedKillSwitchEvidenceHash,
        CircuitBreaker circuitBreaker,
        RateLimiter rateLimiter,
        CostPolicy costPolicy,
        UsageRecorder usageRecorder,
        Clock clock
    ) {
        this.tenantHash = requireSha256(tenantHash, "tenantHash");
        this.killSwitchSource = Objects.requireNonNull(
            killSwitchSource,
            "killSwitchSource must not be null"
        );
        if (expectedKillSwitchGeneration < 1) {
            throw new IllegalArgumentException(
                "expectedKillSwitchGeneration must be positive"
            );
        }
        this.expectedKillSwitchGeneration = expectedKillSwitchGeneration;
        this.expectedKillSwitchEvidenceHash = requireSha256(
            expectedKillSwitchEvidenceHash,
            "expectedKillSwitchEvidenceHash"
        );
        this.circuitBreaker = Objects.requireNonNull(
            circuitBreaker,
            "circuitBreaker must not be null"
        );
        this.rateLimiter = Objects.requireNonNull(
            rateLimiter,
            "rateLimiter must not be null"
        );
        this.costPolicy = Objects.requireNonNull(costPolicy, "costPolicy must not be null");
        this.usageRecorder = Objects.requireNonNull(
            usageRecorder,
            "usageRecorder must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public Permit admit(
        OpenAiResponsesTransportPort.Request request,
        int maximumOutputTokens
    ) {
        Objects.requireNonNull(request, "request must not be null");
        if (maximumOutputTokens < 1 || maximumOutputTokens > 16_384) {
            throw failure(OpenAiResponsesTransportException.Failure.REQUEST_INVALID);
        }
        requireNotCancelled(request);
        Instant now = clock.instant();
        KillSwitchSnapshot killSwitch = requireKillSwitch();

        CircuitBreaker.CircuitPermit circuitPermit = circuitBreaker.tryAcquire(now);
        if (!circuitPermit.allowed()) {
            throw failure(OpenAiResponsesTransportException.Failure.CIRCUIT_OPEN);
        }

        RateLimiter.RatePermit ratePermit = null;
        try {
            ratePermit = rateLimiter.reserve(tenantHash, now);
            if (!ratePermit.allowed()) {
                circuitBreaker.abandon(circuitPermit);
                throw failure(OpenAiResponsesTransportException.Failure.RATE_LIMITED);
            }
            CostPolicy.Estimate estimate = costPolicy.estimate(
                request.bodyLength(),
                maximumOutputTokens,
                now
            );
            return new Permit(
                this,
                request.bodyHash(),
                killSwitch,
                circuitPermit,
                ratePermit,
                estimate,
                now
            );
        } catch (RuntimeException failure) {
            if (ratePermit != null && ratePermit.allowed()) {
                rateLimiter.rollback(ratePermit);
            }
            if (!(failure instanceof OpenAiResponsesTransportException exception)
                || exception.failure()
                    != OpenAiResponsesTransportException.Failure.RATE_LIMITED) {
                circuitBreaker.abandon(circuitPermit);
            }
            throw failure;
        }
    }

    public String tenantHash() {
        return tenantHash;
    }

    public String costPolicyHash() {
        return costPolicy.evidenceHash();
    }

    @Override
    public String toString() {
        return "OpenAiResponsesTransportAdmission[tenantHash=" + tenantHash
            + ", expectedKillSwitchEvidenceHash=" + expectedKillSwitchEvidenceHash
            + ", costPolicyHash=" + costPolicy.evidenceHash() + "]";
    }

    private void revalidate(
        OpenAiResponsesTransportPort.Request request,
        String admittedBodyHash
    ) {
        Objects.requireNonNull(request, "request must not be null");
        if (!admittedBodyHash.equals(request.bodyHash())) {
            throw failure(OpenAiResponsesTransportException.Failure.REQUEST_INVALID);
        }
        requireNotCancelled(request);
        requireKillSwitch();
        costPolicy.requireCurrent(clock.instant());
    }

    private KillSwitchSnapshot requireKillSwitch() {
        KillSwitchSnapshot snapshot;
        try {
            snapshot = killSwitchSource.get();
        } catch (RuntimeException failure) {
            throw failure(OpenAiResponsesTransportException.Failure.KILL_SWITCH_DRIFT);
        }
        if (snapshot == null) {
            throw failure(OpenAiResponsesTransportException.Failure.KILL_SWITCH_DRIFT);
        }
        if (!OpenAiResponsesProtocol.PROVIDER_ID.equals(snapshot.providerId())
            || !OpenAiResponsesProtocol.PROVIDER_VERSION.equals(snapshot.providerVersion())
            || snapshot.generation() != expectedKillSwitchGeneration
            || !snapshot.evidenceHash().equals(expectedKillSwitchEvidenceHash)) {
            throw failure(OpenAiResponsesTransportException.Failure.KILL_SWITCH_DRIFT);
        }
        if (!snapshot.enabled()) {
            throw failure(OpenAiResponsesTransportException.Failure.KILL_SWITCH_DISABLED);
        }
        return snapshot;
    }

    private static void requireNotCancelled(OpenAiResponsesTransportPort.Request request) {
        if (request.cancelled() || Thread.currentThread().isInterrupted()) {
            throw failure(OpenAiResponsesTransportException.Failure.CANCELLED);
        }
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

    @FunctionalInterface
    private interface UsageRecorder {

        void record(String tenantHash, Instant rateWindowStart, long estimatedUpperBoundMicros);
    }

    public final class Permit implements AutoCloseable {

        private final OpenAiResponsesTransportAdmission owner;
        private final String requestBodyHash;
        private final KillSwitchSnapshot killSwitch;
        private final CircuitBreaker.CircuitPermit circuitPermit;
        private final RateLimiter.RatePermit ratePermit;
        private final CostPolicy.Estimate costEstimate;
        private final String admissionEvidenceHash;
        private final AtomicBoolean dispatched = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(
            OpenAiResponsesTransportAdmission owner,
            String requestBodyHash,
            KillSwitchSnapshot killSwitch,
            CircuitBreaker.CircuitPermit circuitPermit,
            RateLimiter.RatePermit ratePermit,
            CostPolicy.Estimate costEstimate,
            Instant admittedAt
        ) {
            this.owner = owner;
            this.requestBodyHash = requestBodyHash;
            this.killSwitch = killSwitch;
            this.circuitPermit = circuitPermit;
            this.ratePermit = ratePermit;
            this.costEstimate = costEstimate;
            this.admissionEvidenceHash = OpenAiResponsesProtocol.sha256Utf8(String.join(
                "\n",
                "openai-responses-transport-admission-v1",
                tenantHash,
                requestBodyHash,
                killSwitch.evidenceHash(),
                circuitPermit.evidenceHash(),
                ratePermit.evidenceHash(),
                costEstimate.evidenceHash(),
                admittedAt.toString()
            ));
        }

        public void revalidateBeforeSecret(OpenAiResponsesTransportPort.Request request) {
            requireOpen();
            owner.revalidate(request, requestBodyHash);
        }

        public void revalidateBeforeDispatch(OpenAiResponsesTransportPort.Request request) {
            requireOpen();
            owner.revalidate(request, requestBodyHash);
        }

        public void markDispatched(OpenAiResponsesTransportPort.Request request) {
            requireOpen();
            owner.revalidate(request, requestBodyHash);
            if (!dispatched.compareAndSet(false, true)) {
                throw failure(OpenAiResponsesTransportException.Failure.REQUEST_INVALID);
            }
            rateLimiter.commit(ratePermit);
            usageRecorder.record(
                tenantHash,
                ratePermit.windowStart(),
                costEstimate.estimatedMicros()
            );
        }

        public void record(Outcome outcome) {
            Objects.requireNonNull(outcome, "outcome must not be null");
            requireOpen();
            if (!dispatched.get() || !completed.compareAndSet(false, true)) {
                throw failure(OpenAiResponsesTransportException.Failure.REQUEST_INVALID);
            }
            circuitBreaker.record(circuitPermit, outcome, clock.instant());
        }

        public boolean dispatched() {
            return dispatched.get();
        }

        public String admissionEvidenceHash() {
            return admissionEvidenceHash;
        }

        public CostPolicy.Estimate costEstimate() {
            return costEstimate;
        }

        public KillSwitchSnapshot killSwitch() {
            return killSwitch;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (completed.get()) {
                return;
            }
            if (dispatched.get()) {
                circuitBreaker.record(
                    circuitPermit,
                    Outcome.UNKNOWN,
                    clock.instant()
                );
                completed.set(true);
            } else {
                rateLimiter.rollback(ratePermit);
                circuitBreaker.abandon(circuitPermit);
            }
        }

        @Override
        public String toString() {
            return "OpenAiResponsesTransportAdmission.Permit[admissionEvidenceHash="
                + admissionEvidenceHash + ", dispatched=" + dispatched.get()
                + ", completed=" + completed.get() + "]";
        }

        private void requireOpen() {
            if (closed.get() || completed.get()) {
                throw failure(OpenAiResponsesTransportException.Failure.REQUEST_INVALID);
            }
        }
    }
}
