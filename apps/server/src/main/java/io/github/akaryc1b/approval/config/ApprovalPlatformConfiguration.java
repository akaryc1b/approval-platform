package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalApplicationService;
import io.github.akaryc1b.approval.application.ApprovalCommentService;
import io.github.akaryc1b.approval.application.ApprovalCopiedQueryService;
import io.github.akaryc1b.approval.application.ApprovalMessageService;
import io.github.akaryc1b.approval.application.ApprovalParticipationQueryService;
import io.github.akaryc1b.approval.application.ApprovalTaskQueryService;
import io.github.akaryc1b.approval.application.ApprovalTimelineQueryService;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService;
import io.github.akaryc1b.approval.application.PurchasePaymentCollaborationService;
import io.github.akaryc1b.approval.application.PurchasePaymentTaskActionService;
import io.github.akaryc1b.approval.application.port.ApprovalBusinessEventOutbox;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalParticipationQuery;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery;
import io.github.akaryc1b.approval.application.port.ApprovalTimelineQuery;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.application.port.PurchasePaymentAssigneeResolver;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.engine.ApprovalEngine;
import io.github.akaryc1b.approval.engine.flowable.FlowableApprovalEngine;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalCommentStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMessageStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalParticipationQuery;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalProjectionStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalTaskQuery;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalTimelineQuery;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcAuditEventSink;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcIdempotencyGuard;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class ApprovalPlatformConfiguration {

    @Bean
    Clock approvalClock() {
        return Clock.systemUTC();
    }

    @Bean
    ObjectMapper approvalPersistenceObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    ApprovalEngine approvalEngine(
        RepositoryService repositoryService,
        RuntimeService runtimeService,
        TaskService taskService
    ) {
        return new FlowableApprovalEngine(repositoryService, runtimeService, taskService);
    }

    @Bean
    ApprovalDslCompiler approvalDslCompiler() {
        return new ApprovalDslCompiler();
    }

    @Bean
    IdempotencyGuard idempotencyGuard(
        DataSource dataSource,
        ObjectMapper approvalPersistenceObjectMapper,
        PlatformTransactionManager transactionManager,
        Clock approvalClock
    ) {
        return new JdbcIdempotencyGuard(
            dataSource,
            approvalPersistenceObjectMapper,
            transactionManager,
            approvalClock
        );
    }

    @Bean
    ApprovalProjectionStore approvalProjectionStore(
        DataSource dataSource,
        ObjectMapper approvalPersistenceObjectMapper
    ) {
        return new JdbcApprovalProjectionStore(dataSource, approvalPersistenceObjectMapper);
    }

    @Bean
    ApprovalMessageStore approvalMessageStore(
        DataSource dataSource,
        ObjectMapper approvalPersistenceObjectMapper
    ) {
        return new JdbcApprovalMessageStore(dataSource, approvalPersistenceObjectMapper);
    }

    @Bean
    ApprovalCommentStore approvalCommentStore(
        DataSource dataSource,
        ObjectMapper approvalPersistenceObjectMapper
    ) {
        return new JdbcApprovalCommentStore(dataSource, approvalPersistenceObjectMapper);
    }

    @Bean
    ApprovalCopiedQueryService approvalCopiedQueryService(ApprovalMessageStore approvalMessageStore) {
        return new ApprovalCopiedQueryService(approvalMessageStore);
    }

    @Bean
    ApprovalTaskQuery approvalTaskQuery(
        DataSource dataSource,
        ObjectMapper approvalPersistenceObjectMapper
    ) {
        return new JdbcApprovalTaskQuery(dataSource, approvalPersistenceObjectMapper);
    }

    @Bean
    ApprovalTaskQueryService approvalTaskQueryService(ApprovalTaskQuery approvalTaskQuery) {
        return new ApprovalTaskQueryService(approvalTaskQuery);
    }

    @Bean
    ApprovalParticipationQuery approvalParticipationQuery(
        DataSource dataSource,
        ObjectMapper approvalPersistenceObjectMapper
    ) {
        return new JdbcApprovalParticipationQuery(dataSource, approvalPersistenceObjectMapper);
    }

    @Bean
    ApprovalParticipationQueryService approvalParticipationQueryService(
        ApprovalParticipationQuery approvalParticipationQuery
    ) {
        return new ApprovalParticipationQueryService(approvalParticipationQuery);
    }

    @Bean
    ApprovalTimelineQuery approvalTimelineQuery(
        DataSource dataSource,
        ObjectMapper approvalPersistenceObjectMapper
    ) {
        return new JdbcApprovalTimelineQuery(dataSource, approvalPersistenceObjectMapper);
    }

    @Bean
    ApprovalTimelineQueryService approvalTimelineQueryService(
        ApprovalTimelineQuery approvalTimelineQuery
    ) {
        return new ApprovalTimelineQueryService(approvalTimelineQuery);
    }

    @Bean
    AuditEventSink auditEventSink(
        DataSource dataSource,
        ObjectMapper approvalPersistenceObjectMapper
    ) {
        return new JdbcAuditEventSink(dataSource, approvalPersistenceObjectMapper);
    }

    @Bean
    ApprovalApplicationService approvalApplicationService(ApprovalEngine approvalEngine) {
        return new ApprovalApplicationService(approvalEngine);
    }

    @Bean
    PurchasePaymentApplicationService purchasePaymentApplicationService(
        ApprovalEngine approvalEngine,
        ApprovalDslCompiler approvalDslCompiler,
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore approvalProjectionStore,
        AuditEventSink auditEventSink,
        PurchasePaymentAssigneeResolver assigneeResolver,
        ApprovalBusinessEventOutbox businessEventOutbox,
        Clock approvalClock
    ) {
        return new PurchasePaymentApplicationService(
            approvalEngine,
            approvalDslCompiler,
            idempotencyGuard,
            approvalProjectionStore,
            auditEventSink,
            assigneeResolver,
            businessEventOutbox,
            approvalClock,
            UUID::randomUUID
        );
    }

    @Bean
    PurchasePaymentTaskActionService purchasePaymentTaskActionService(
        ApprovalEngine approvalEngine,
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore approvalProjectionStore,
        AuditEventSink auditEventSink,
        ApprovalBusinessEventOutbox businessEventOutbox,
        Clock approvalClock
    ) {
        return new PurchasePaymentTaskActionService(
            approvalEngine,
            idempotencyGuard,
            approvalProjectionStore,
            auditEventSink,
            businessEventOutbox,
            approvalClock,
            UUID::randomUUID
        );
    }

    @Bean
    PurchasePaymentCollaborationService purchasePaymentCollaborationService(
        ApprovalEngine approvalEngine,
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore approvalProjectionStore,
        AuditEventSink auditEventSink,
        Clock approvalClock
    ) {
        return new PurchasePaymentCollaborationService(
            approvalEngine,
            idempotencyGuard,
            approvalProjectionStore,
            auditEventSink,
            approvalClock,
            UUID::randomUUID
        );
    }

    @Bean
    ApprovalMessageService approvalMessageService(
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore approvalProjectionStore,
        ApprovalMessageStore approvalMessageStore,
        AuditEventSink auditEventSink,
        Clock approvalClock
    ) {
        return new ApprovalMessageService(
            idempotencyGuard,
            approvalProjectionStore,
            approvalMessageStore,
            auditEventSink,
            approvalClock,
            UUID::randomUUID
        );
    }

    @Bean
    ApprovalCommentService approvalCommentService(
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore approvalProjectionStore,
        ApprovalMessageStore approvalMessageStore,
        ApprovalCommentStore approvalCommentStore,
        AuditEventSink auditEventSink,
        Clock approvalClock
    ) {
        return new ApprovalCommentService(
            idempotencyGuard,
            approvalProjectionStore,
            approvalMessageStore,
            approvalCommentStore,
            auditEventSink,
            approvalClock,
            UUID::randomUUID
        );
    }
}
