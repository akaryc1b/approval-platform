package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher.DispatchResult;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionStateRecorder;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionStateRecorder.ActionStateException;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ActionType;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaActionStateRecorder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class ApprovalSlaGovernedActionConfiguration {

    @Bean
    ApprovalSlaActionStateRecorder approvalSlaActionStateRecorder(
        DataSource dataSource,
        PlatformTransactionManager transactionManager
    ) {
        return new JdbcApprovalSlaActionStateRecorder(dataSource, transactionManager);
    }

    @Bean
    @Primary
    ApprovalSlaActionDispatcher governedApprovalSlaActionDispatcher(
        @Qualifier("approvalSlaActionDispatcher") ApprovalSlaActionDispatcher fallback,
        ApprovalSlaActionStateRecorder actionState,
        Clock approvalClock
    ) {
        return intent -> {
            if (intent.actionType() != ActionType.OVERDUE) {
                return fallback.dispatch(intent);
            }
            try {
                actionState.recordOverdue(
                    intent.tenantId(),
                    intent.slaInstanceId(),
                    intent.actionSequence(),
                    intent.requestId(),
                    intent.traceId(),
                    approvalClock.instant()
                );
                return DispatchResult.succeeded();
            } catch (ActionStateException exception) {
                return exception.retryable()
                    ? DispatchResult.retryableFailure(exception.code(), exception.getMessage())
                    : DispatchResult.permanentFailure(exception.code(), exception.getMessage());
            }
        };
    }
}
