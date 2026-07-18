package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalFormRuntimeService;
import io.github.akaryc1b.approval.application.ApprovalFormService;
import io.github.akaryc1b.approval.application.ApprovalFormSubmissionService;
import io.github.akaryc1b.approval.application.ApprovalUiSchemaService;
import io.github.akaryc1b.approval.application.FormDataValidator;
import io.github.akaryc1b.approval.application.FormDefinitionValidator;
import io.github.akaryc1b.approval.application.FormSchemaHasher;
import io.github.akaryc1b.approval.application.FormSubmissionHasher;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService;
import io.github.akaryc1b.approval.application.PurchasePaymentFormSubmissionStarter;
import io.github.akaryc1b.approval.application.UiSchemaDefinitionValidator;
import io.github.akaryc1b.approval.application.UiSchemaHasher;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.FormSubmissionWorkflowStarter;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalFormStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalFormSubmissionStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalUiSchemaStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class ApprovalFormConfiguration {

    @Bean
    FormDefinitionValidator formDefinitionValidator() {
        return new FormDefinitionValidator();
    }

    @Bean
    FormDataValidator formDataValidator() {
        return new FormDataValidator();
    }

    @Bean
    FormSchemaHasher formSchemaHasher() {
        return new FormSchemaHasher();
    }

    @Bean
    FormSubmissionHasher formSubmissionHasher() {
        return new FormSubmissionHasher();
    }

    @Bean
    UiSchemaDefinitionValidator uiSchemaDefinitionValidator() {
        return new UiSchemaDefinitionValidator();
    }

    @Bean
    UiSchemaHasher uiSchemaHasher() {
        return new UiSchemaHasher();
    }

    @Bean
    ApprovalFormStore approvalFormStore(
        DataSource dataSource,
        ObjectMapper approvalPersistenceObjectMapper
    ) {
        return new JdbcApprovalFormStore(dataSource, approvalPersistenceObjectMapper);
    }

    @Bean
    ApprovalFormSubmissionStore approvalFormSubmissionStore(
        DataSource dataSource,
        ObjectMapper approvalPersistenceObjectMapper
    ) {
        return new JdbcApprovalFormSubmissionStore(dataSource, approvalPersistenceObjectMapper);
    }

    @Bean
    ApprovalUiSchemaStore approvalUiSchemaStore(
        DataSource dataSource,
        ObjectMapper approvalPersistenceObjectMapper
    ) {
        return new JdbcApprovalUiSchemaStore(dataSource, approvalPersistenceObjectMapper);
    }

    @Bean
    ApprovalFormService approvalFormService(
        IdempotencyGuard idempotencyGuard,
        ApprovalFormStore approvalFormStore,
        AuditEventSink auditEventSink,
        FormDefinitionValidator formDefinitionValidator,
        FormSchemaHasher formSchemaHasher,
        Clock approvalClock
    ) {
        return new ApprovalFormService(
            idempotencyGuard,
            approvalFormStore,
            auditEventSink,
            formDefinitionValidator,
            formSchemaHasher,
            approvalClock,
            UUID::randomUUID
        );
    }

    @Bean
    ApprovalUiSchemaService approvalUiSchemaService(
        IdempotencyGuard idempotencyGuard,
        ApprovalFormStore approvalFormStore,
        ApprovalUiSchemaStore approvalUiSchemaStore,
        AuditEventSink auditEventSink,
        UiSchemaDefinitionValidator validator,
        UiSchemaHasher hasher,
        Clock approvalClock
    ) {
        return new ApprovalUiSchemaService(
            idempotencyGuard,
            approvalFormStore,
            approvalUiSchemaStore,
            auditEventSink,
            validator,
            hasher,
            approvalClock,
            UUID::randomUUID
        );
    }

    @Bean
    ApprovalFormRuntimeService approvalFormRuntimeService(
        ApprovalFormStore approvalFormStore,
        ApprovalUiSchemaStore approvalUiSchemaStore,
        ApprovalFormSubmissionStore approvalFormSubmissionStore,
        ApprovalProjectionStore approvalProjectionStore,
        ApprovalAttachmentStore approvalAttachmentStore,
        FormDataValidator formDataValidator,
        FormSubmissionHasher formSubmissionHasher
    ) {
        return new ApprovalFormRuntimeService(
            approvalFormStore,
            approvalUiSchemaStore,
            approvalFormSubmissionStore,
            approvalProjectionStore,
            approvalAttachmentStore,
            formDataValidator,
            formSubmissionHasher,
            UUID::randomUUID
        );
    }

    @Bean
    FormSubmissionWorkflowStarter formSubmissionWorkflowStarter(
        PurchasePaymentApplicationService purchasePaymentApplicationService
    ) {
        return new PurchasePaymentFormSubmissionStarter(purchasePaymentApplicationService);
    }

    @Bean
    ApprovalFormSubmissionService approvalFormSubmissionService(
        IdempotencyGuard idempotencyGuard,
        ApprovalFormStore approvalFormStore,
        ApprovalFormSubmissionStore approvalFormSubmissionStore,
        ApprovalAttachmentStore approvalAttachmentStore,
        ApprovalProjectionStore approvalProjectionStore,
        ApprovalMessageStore approvalMessageStore,
        FormSubmissionWorkflowStarter formSubmissionWorkflowStarter,
        ApprovalFormRuntimeService approvalFormRuntimeService,
        FormDataValidator formDataValidator,
        FormSubmissionHasher formSubmissionHasher,
        AuditEventSink auditEventSink,
        Clock approvalClock
    ) {
        return new ApprovalFormSubmissionService(
            idempotencyGuard,
            approvalFormStore,
            approvalFormSubmissionStore,
            approvalAttachmentStore,
            approvalProjectionStore,
            approvalMessageStore,
            formSubmissionWorkflowStarter,
            approvalFormRuntimeService,
            formDataValidator,
            formSubmissionHasher,
            auditEventSink,
            approvalClock,
            UUID::randomUUID
        );
    }
}
