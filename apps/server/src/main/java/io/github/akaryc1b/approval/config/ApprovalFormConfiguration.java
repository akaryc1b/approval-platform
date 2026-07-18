package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalFormService;
import io.github.akaryc1b.approval.application.FormDefinitionValidator;
import io.github.akaryc1b.approval.application.FormSchemaHasher;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalFormStore;
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
    FormSchemaHasher formSchemaHasher() {
        return new FormSchemaHasher();
    }

    @Bean
    ApprovalFormStore approvalFormStore(
        DataSource dataSource,
        ObjectMapper approvalPersistenceObjectMapper
    ) {
        return new JdbcApprovalFormStore(dataSource, approvalPersistenceObjectMapper);
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
}
