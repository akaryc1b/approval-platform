package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.WorkerMetrics;
import io.github.akaryc1b.approval.application.ApprovalSlaLagCalculator;
import io.github.akaryc1b.approval.application.ApprovalSlaLagCalculator.Lag;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.DurationMode;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntent;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaDurationMode;
import io.github.akaryc1b.approval.observability.SlaCompletionLagMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/** No new poller or queue. Default off; reads exact immutable versions only after worker completion. */
@Configuration(proxyBeanMethods = false)
public class ApprovalSlaLagObservabilityConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "approval.observability.sla-lag", name = "enabled", havingValue = "true")
    WorkerMetrics calendarAwareSlaExecutionMetrics(
        @Qualifier("approvalSlaExecutionWorkerMetrics") WorkerMetrics original,
        ApprovalSlaStore store, ApprovalWorkingTimeCalculator workingTime,
        PlatformTransactionManager transactionManager, MeterRegistry registry) {
        var calculator = new ApprovalSlaLagCalculator(workingTime);
        var reads = new TransactionTemplate(transactionManager);
        reads.setReadOnly(true);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setTimeout(2);
        return new SlaCompletionLagMetrics(original, registry,
            intent -> Objects.requireNonNull(reads.execute(status -> readLag(store, calculator, intent))));
    }

    static Lag readLag(ApprovalSlaStore store, ApprovalSlaLagCalculator calculator, ExecutionIntent intent) {
        var policy = store.findPolicyVersion(intent.tenantId(), intent.policyId(), intent.policyVersion())
            .orElseThrow(() -> new IllegalStateException("pinned SLA policy unavailable"));
        if (!policy.immutable() || !policy.tenantId().equals(intent.tenantId())
            || !policy.policyId().equals(intent.policyId()) || policy.policyVersion() != intent.policyVersion()
            || !Objects.equals(policy.calendarId(), intent.calendarId())
            || !Objects.equals(policy.calendarVersion(), intent.calendarVersion())) {
            throw new IllegalStateException("pinned SLA policy identity mismatch");
        }
        if (policy.durationMode() == SlaDurationMode.NATURAL_TIME) {
            return calculator.calculate(DurationMode.NATURAL_TIME, null, intent.scheduledAt(), intent.completedAt());
        }
        var calendar = store.findCalendarVersion(intent.tenantId(), intent.calendarId(), intent.calendarVersion())
            .orElseThrow(() -> new IllegalStateException("pinned SLA calendar unavailable"));
        if (!calendar.immutable() || !calendar.tenantId().equals(intent.tenantId())
            || !calendar.calendarId().equals(intent.calendarId())
            || calendar.calendarVersion() != intent.calendarVersion()) {
            throw new IllegalStateException("pinned SLA calendar identity mismatch");
        }
        // The calendar is the execution's immutable version, not whichever version is active today.
        // Do not recompute scheduledAt from process age, policy duration or a newer SLA instance.
        return calculator.calculate(DurationMode.WORKING_TIME, calendar.snapshot(),
            intent.scheduledAt(), intent.completedAt());
    }
}
