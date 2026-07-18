package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService;
import io.github.akaryc1b.approval.application.ApprovalFormRuntimeService;
import io.github.akaryc1b.approval.application.ApprovalFormService;
import io.github.akaryc1b.approval.application.ApprovalFormSubmissionService;
import io.github.akaryc1b.approval.application.ApprovalUiSchemaService;
import io.github.akaryc1b.approval.application.FormDataValidator;
import io.github.akaryc1b.approval.application.FormDefaultValueResolver;
import io.github.akaryc1b.approval.application.FormDefinitionValidator;
import io.github.akaryc1b.approval.application.FormPackageHasher;
import io.github.akaryc1b.approval.application.FormSchemaHasher;
import io.github.akaryc1b.approval.application.FormSubmissionHasher;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService;
import io.github.akaryc1b.approval.application.PurchasePaymentFormSubmissionStarter;
import io.github.akaryc1b.approval.application.UiSchemaDefinitionValidator;
import io.github.akaryc1b.approval.application.UiSchemaHasher;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormDesignDraftStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.FormSubmissionWorkflowStarter;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalFormDesignDraftStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalFormPackageStore;
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
    FormPackageHasher formPackageHasher() {
        return new FormPackageHasher();
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
    FormDefaultValueResolver formDefaultValueResolver(Clock approvalClock) {
        return new FormDefaultValueResolver(approvalClock);
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
    ApprovalFormDesignDraftStore approvalFormDesignDraftStore(
        DataSource dataSource,
        ObjectMapper approvalPersistenceObjectMapper
    ) {
        return new JdbcApprovalFormDesignDraftStore(dataSource, approvalPersistenceObjectMapper);
    }

    @Bean
    ApprovalFormPackageStore approvalFormPackageStore(DataSource dataSource) {
        return new JdbcApprovalFormPackageStore(dataSource);
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
    ApprovalFormDesignService approvalFormDesignService(
        IdempotencyGuard idempotencyGuard,
        ApprovalFormDesignDraftStore approvalFormDesignDraftStore,
        ApprovalFormPackageStore approvalFormPackageStore,
        ApprovalFormStore approvalFormStore,
        ApprovalUiSchemaStore approvalUiSchemaStore,
        AuditEventSink auditEventSink,
        FormDefinitionValidator formDefinitionValidator,
        UiSchemaDefinitionValidator uiSchemaDefinitionValidator,
        FormSchemaHasher formSchemaHasher,
        UiSchemaHasher uiSchemaHasher,
        FormPackageHasher formPackageHasher,
        FormDefaultValueResolver formDefaultValueResolver,
        Clock approvalClock
    ) {
        return new ApprovalFormDesignService(
            idempotencyGuard,
            approvalFormDesignDraftStore,
            approvalFormPackageStore,
            approvalFormStore,
            approvalUiSchemaStore,
            auditEventSink,
            formDefinitionValidator,
            uiSchemaDefinitionValidator,
            formSchemaHasher,
            uiSchemaHasher,
            formPackageHasher,
            formDefaultValueResolver,
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
        FormSubmissionHasher formSubmissionHasher,
        FormDefaultValueResolver formDefaultValueResolver
    ) {
        return new ApprovalFormRuntimeService(
            approvalFormStore,
            approvalUiSchemaStore,
            approvalFormSubmissionStore,
            approvalProjectionStore,
            approvalAttachmentStore,
            formDataValidator,
            formSubmissionHasher,
            formDefaultValueResolver,
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
