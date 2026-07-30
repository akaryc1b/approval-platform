package io.github.akaryc1b.approval.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/**
 * Records bounded read latency with closed tags. Telemetry failures are fail-open and cannot
 * change the HTTP or migration safety outcome. Diagnostics are explicitly non-cacheable.
 */
@Component
public final class ApprovalMigrationOperationsTelemetryFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        ApprovalMigrationOperationsTelemetryFilter.class
    );
    private static final Duration MIN_EXPECTED = Duration.ofMillis(1);
    private static final Duration MAX_EXPECTED = Duration.ofSeconds(30);

    private final MeterRegistry meters;

    public ApprovalMigrationOperationsTelemetryFilter(MeterRegistry meters) {
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ApprovalMigrationOperationsTelemetryClassifier.isReadOperationsPath(
            request.getMethod(),
            request.getRequestURI()
        );
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0L);
        Timer.Sample sample = safeStart();
        boolean failedBeforeResponse = false;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failedBeforeResponse = true;
            throw exception;
        } finally {
            int status = failedBeforeResponse && response.getStatus() < 400
                ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                : response.getStatus();
            safeStop(sample, request.getRequestURI(), status);
        }
    }

    private Timer.Sample safeStart() {
        try {
            return Timer.start(meters);
        } catch (RuntimeException exception) {
            LOGGER.warn("migration operations telemetry start failed; request remains fail-open");
            return null;
        }
    }

    private void safeStop(Timer.Sample sample, String path, int status) {
        if (sample == null) {
            return;
        }
        try {
            var classification = ApprovalMigrationOperationsTelemetryClassifier.classify(
                path,
                status
            );
            if (classification.operation()
                == ApprovalMigrationOperationsTelemetryClassifier.Operation.NONE) {
                return;
            }
            Timer timer = Timer.builder(
                ApprovalMigrationOperationsTelemetryClassifier.READ_LATENCY_METRIC
            ).description("Bounded latency for read-only migration operations APIs")
                .minimumExpectedValue(MIN_EXPECTED)
                .maximumExpectedValue(MAX_EXPECTED)
                .publishPercentileHistogram(false)
                .tags(
                    "operation", classification.operation().metricValue(),
                    "result", classification.result().metricValue(),
                    "failure_class", classification.failureClass().metricValue()
                )
                .register(meters);
            sample.stop(timer);
        } catch (RuntimeException exception) {
            LOGGER.warn("migration operations telemetry stop failed; request remains fail-open");
        }
    }
}
