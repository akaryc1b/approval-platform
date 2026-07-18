package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalFormService;
import io.github.akaryc1b.approval.application.ApprovalFormService.FormVersionConflictException;
import io.github.akaryc1b.approval.application.ApprovalFormService.PublishCommand;
import io.github.akaryc1b.approval.application.FormDefinitionValidator;
import io.github.akaryc1b.approval.application.FormSchemaHasher;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalFormStoreIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-18T09:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_form_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private ApprovalFormService service;

    @BeforeAll
    static void migrateDatabase() {
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            truncate table
                ap_form_definition,
                ap_audit_event,
                ap_command_idempotency
            cascade
            """);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ApprovalFormService(
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                new JdbcTransactionManager(dataSource),
                clock
            ),
            new JdbcApprovalFormStore(dataSource, objectMapper),
            new JdbcAuditEventSink(dataSource, objectMapper),
            new FormDefinitionValidator(),
            new FormSchemaHasher(),
            clock,
            UUID::randomUUID
        );
    }

    @Test
    void publishedVersionsAreImmutableSearchableAndReplayable() {
        FormDefinition definition = PurchasePaymentTemplate.formDefinition();
        PublishCommand command = new PublishCommand(
            context("publish-request", "publish-key"),
            definition
        );

        var published = service.publish(command);
        var replayed = service.publish(command);

        assertEquals(published.contentHash(), replayed.contentHash());
        assertEquals(1, count("ap_form_definition"));
        assertEquals(1, countAudit("FORM_PUBLISHED"));
        assertEquals(definition, service.find(
            "tenant-a",
            definition.formKey(),
            definition.version()
        ).orElseThrow().definition());

        var page = service.findForms("tenant-a", "payment", 20, 0);
        assertEquals(1, page.total());
        assertEquals(definition.formKey(), page.items().getFirst().formKey());
        assertEquals(definition.fields().size(), page.items().getFirst().fieldCount());

        FormDefinition conflicting = new FormDefinition(
            definition.schemaVersion(),
            definition.formKey(),
            definition.version(),
            "Changed purchase payment form",
            definition.fields()
        );
        assertThrows(
            FormVersionConflictException.class,
            () -> service.publish(new PublishCommand(
                context("conflict-request", "conflict-key"),
                conflicting
            ))
        );
        assertEquals(1, count("ap_form_definition"));
    }

    @Test
    void invalidFieldDefinitionsAreRejectedBeforePersistence() {
        FormDefinition template = PurchasePaymentTemplate.formDefinition();
        FormDefinition.FormField first = template.fields().getFirst();
        FormDefinition duplicate = new FormDefinition(
            template.schemaVersion(),
            "duplicate-form",
            1,
            "Duplicate form",
            List.of(first, first)
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> service.validate(duplicate)
        );

        assertTrue(exception.getMessage().contains("duplicate field key"));
        assertEquals(0, count("ap_form_definition"));
    }

    private static RequestContext context(String requestId, String idempotencyKey) {
        return new RequestContext(
            "tenant-a",
            "form-admin",
            requestId,
            idempotencyKey,
            "trace-form"
        );
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private int countAudit(String action) {
        return jdbc.queryForObject(
            "select count(*) from ap_audit_event where action = ?",
            Integer.class,
            action
        );
    }
}
