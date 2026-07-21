package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ApprovalFormRuntimeService;
import io.github.akaryc1b.approval.application.ApprovalHandoverService;
import io.github.akaryc1b.approval.application.PurchasePaymentTaskActionService;
import io.github.akaryc1b.approval.application.port.ApprovalBusinessEventOutbox;
import io.github.akaryc1b.approval.application.port.ApprovalHandoverStore;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.engine.ApprovalEngine;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalHandoverStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class ApprovalHandoverConfiguration {

    @Bean
    ApprovalHandoverStore approvalHandoverStore(DataSource dataSource) {
        return new JdbcApprovalHandoverStore(dataSource);
    }

    @Bean
    ApprovalHandoverService approvalHandoverService(
        IdempotencyGuard idempotencyGuard,
        ApprovalIdentityDirectory approvalIdentityDirectory,
        ApprovalHandoverStore approvalHandoverStore,
        ApprovalProjectionStore approvalProjectionStore,
        ApprovalEngine approvalEngine,
        AuditEventSink auditEventSink,
        Clock approvalClock
    ) {
        return new ApprovalHandoverService(
            idempotencyGuard,
            approvalIdentityDirectory,
            approvalHandoverStore,
            approvalProjectionStore,
            approvalEngine,
            auditEventSink,
            approvalClock,
            UUID::randomUUID
        );
    }

    @Bean
    @Primary
    PurchasePaymentTaskActionService handoverAwarePurchasePaymentTaskActionService(
        ApprovalEngine approvalEngine,
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore approvalProjectionStore,
        AuditEventSink auditEventSink,
        ApprovalBusinessEventOutbox businessEventOutbox,
        ApprovalFormRuntimeService approvalFormRuntimeService,
        ApprovalHandoverStore approvalHandoverStore,
        Clock approvalClock
    ) {
        return new PurchasePaymentTaskActionService(
            approvalEngine,
            idempotencyGuard,
            approvalProjectionStore,
            auditEventSink,
            businessEventOutbox,
            approvalFormRuntimeService,
            approvalHandoverStore,
            approvalClock,
            UUID::randomUUID
        );
    }
}
