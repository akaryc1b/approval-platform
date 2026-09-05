package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.ai.core.AiAdvisoryAuditSink;
import io.github.akaryc1b.approval.ai.core.AiAdvisoryMetrics;
import io.github.akaryc1b.approval.ai.core.AiAdvisoryService;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidenceStore;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProductionRuntimeFactory;
import io.github.akaryc1b.approval.api.ApprovalAssistanceGenerationService;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalAssistanceDurableEvidenceStoreFactory;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalAssistanceGovernanceHistoryQueryFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Server composition-root wiring for the default-disabled P6-E production path. */
@Configuration(proxyBeanMethods = false)
public class ApprovalAssistanceProductionConfiguration {

    private static final String ENABLED = "APPROVAL_AI_OPENAI_ENABLED";

    @Bean(destroyMethod = "close")
    ExecutorService approvalAssistanceProviderExecutor() {
        return Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("approval-ai-provider-", 0).factory()
        );
    }

    @Bean
    AiAdvisoryService approvalAssistanceAdvisoryService(
        @Qualifier("approvalAssistanceProviderExecutor") ExecutorService executor
    ) {
        return new AiAdvisoryService(
            executor,
            AiAdvisoryAuditSink.noop(),
            AiAdvisoryMetrics.noop()
        );
    }

    @Bean
    ApprovalAssistanceProductionRuntime approvalAssistanceProductionRuntime(
        Environment environment,
        Clock approvalClock
    ) {
        return new ApprovalAssistanceProductionRuntime(runtime(environment, approvalClock));
    }

    @Bean
    ApprovalAssistanceDurableEvidenceStore approvalAssistanceDurableEvidenceStore(
        DataSource dataSource,
        PlatformTransactionManager transactionManager
    ) {
        return JdbcApprovalAssistanceDurableEvidenceStoreFactory.create(
            dataSource,
            transactionManager,
            UUID::randomUUID
        );
    }

    @Bean
    ApprovalAssistanceGovernanceHistoryQuery approvalAssistanceGovernanceHistoryQuery(
        DataSource dataSource,
        PlatformTransactionManager transactionManager
    ) {
        return JdbcApprovalAssistanceGovernanceHistoryQueryFactory.create(
            dataSource,
            transactionManager
        );
    }

    @Bean
    ApprovalAssistanceGenerationService approvalAssistanceGenerationService(
        ApprovalTaskQuery approvalTaskQuery,
        ApprovalAssistanceDurableEvidenceStore approvalAssistanceDurableEvidenceStore,
        AiAdvisoryService approvalAssistanceAdvisoryService,
        ApprovalAssistanceProductionRuntime productionRuntime,
        Clock approvalClock
    ) {
        return new ApprovalAssistanceGenerationService(
            approvalTaskQuery,
            approvalAssistanceDurableEvidenceStore,
            productionRuntime.factory(),
            approvalAssistanceAdvisoryService,
            approvalClock,
            UUID::randomUUID
        );
    }

    static Optional<OpenAiResponsesProductionRuntimeFactory> runtime(
        Environment environment,
        Clock clock
    ) {
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        String enabled = environment.getProperty(ENABLED, "false").trim();
        if (!"true".equals(enabled)) {
            if (!"false".equals(enabled)) {
                throw invalid(ENABLED);
            }
            return Optional.empty();
        }

        OpenAiResponsesProductionRuntimeFactory.RuntimeProfile profile =
            new OpenAiResponsesProductionRuntimeFactory.RuntimeProfile(
                required(environment, "OPENAI_API_KEY_VERSION"),
                instant(environment, "APPROVAL_AI_OPENAI_SECRET_EFFECTIVE_FROM"),
                instant(environment, "APPROVAL_AI_OPENAI_SECRET_EXPIRES_AT"),
                required(environment, "APPROVAL_AI_OPENAI_SECRET_POLICY_REVISION"),
                positiveLong(environment, "APPROVAL_AI_OPENAI_KILL_SWITCH_GENERATION"),
                required(environment, "APPROVAL_AI_OPENAI_KILL_SWITCH_POLICY_REVISION"),
                required(environment, "APPROVAL_AI_OPENAI_COST_POLICY_VERSION"),
                instant(environment, "APPROVAL_AI_OPENAI_COST_POLICY_EFFECTIVE_FROM"),
                instant(environment, "APPROVAL_AI_OPENAI_COST_POLICY_EXPIRES_AT"),
                positiveLong(environment, "APPROVAL_AI_OPENAI_INPUT_MICROS_PER_TOKEN"),
                positiveLong(environment, "APPROVAL_AI_OPENAI_OUTPUT_MICROS_PER_TOKEN"),
                positiveLong(environment, "APPROVAL_AI_OPENAI_MAX_REQUEST_MICROS"),
                positiveInt(environment, "APPROVAL_AI_OPENAI_TENANT_RATE_LIMIT"),
                positiveInt(environment, "APPROVAL_AI_OPENAI_GLOBAL_RATE_LIMIT"),
                Duration.ofSeconds(positiveLong(
                    environment,
                    "APPROVAL_AI_OPENAI_RATE_WINDOW_SECONDS"
                )),
                positiveInt(environment, "APPROVAL_AI_OPENAI_CIRCUIT_FAILURE_THRESHOLD"),
                Duration.ofSeconds(positiveLong(
                    environment,
                    "APPROVAL_AI_OPENAI_CIRCUIT_OPEN_SECONDS"
                ))
            );
        Instant now = clock.instant();
        if (now.isBefore(profile.secretVersionEffectiveFrom())
            || !now.isBefore(profile.secretVersionExpiresAt())
            || now.isBefore(profile.costPolicyEffectiveFrom())
            || !now.isBefore(profile.costPolicyExpiresAt())) {
            throw new IllegalStateException("AI production version policy is not currently valid");
        }
        return Optional.of(new OpenAiResponsesProductionRuntimeFactory(profile, clock));
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw invalid(name);
        }
        return value;
    }

    private static Instant instant(Environment environment, String name) {
        try {
            return Instant.parse(required(environment, name));
        } catch (RuntimeException invalid) {
            throw invalid(name);
        }
    }

    private static long positiveLong(Environment environment, String name) {
        try {
            long value = Long.parseLong(required(environment, name));
            if (value < 1) {
                throw invalid(name);
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw invalid(name);
        }
    }

    private static int positiveInt(Environment environment, String name) {
        long value = positiveLong(environment, name);
        if (value > Integer.MAX_VALUE) {
            throw invalid(name);
        }
        return (int) value;
    }

    private static IllegalStateException invalid(String name) {
        return new IllegalStateException("Missing or invalid AI production setting: " + name);
    }
}
