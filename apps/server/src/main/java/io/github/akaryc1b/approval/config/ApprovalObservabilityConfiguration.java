package io.github.akaryc1b.approval.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;
import java.util.Set;

/** Central low-cardinality boundary for approval-owned metrics. */
@Configuration(proxyBeanMethods = false)
public class ApprovalObservabilityConfiguration {

    private static final String APPROVAL_METRIC_PREFIX = "approval.";
    private static final Set<String> FORBIDDEN_TAG_KEYS = Set.of(
        "tenant",
        "tenant_id",
        "user",
        "user_id",
        "process_instance",
        "process_instance_id",
        "task",
        "task_id",
        "request",
        "request_id",
        "business_key",
        "idempotency_key",
        "trace_id",
        "span_id",
        "exception",
        "error_message",
        "payload"
    );

    @Bean
    MeterFilter approvalMetricCardinalityGuard() {
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                if (!id.getName().startsWith(APPROVAL_METRIC_PREFIX)) {
                    return MeterFilterReply.NEUTRAL;
                }
                boolean containsForbiddenTag = id.getTags().stream()
                    .map(Tag::getKey)
                    .map(ApprovalObservabilityConfiguration::normalize)
                    .anyMatch(FORBIDDEN_TAG_KEYS::contains);
                return containsForbiddenTag
                    ? MeterFilterReply.DENY
                    : MeterFilterReply.NEUTRAL;
            }
        };
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
            .replace('-', '_')
            .replace('.', '_');
    }
}
