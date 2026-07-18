package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.FormSchemaHasher;
import io.github.akaryc1b.approval.application.UiSchemaHasher;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.PublishedForm;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore.FormSubmission;
import io.github.akaryc1b.approval.application.port.ApprovalFormSubmissionStore.FormSubmissionRevision;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore.PublishedUiSchema;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalUiSchemaIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-18T10:00:00Z");
    private static final UUID INSTANCE_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000000981"
    );
    private static final UUID SUBMISSION_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000000982"
    );

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_ui_schema_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcApprovalUiSchemaStore uiSchemas;
    private JdbcApprovalFormSubmissionStore submissions;
    private JdbcApprovalProjectionStore projections;
    private JdbcTemplate jdbc;

    @BeforeAll
    static void migrateDatabase() {
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            truncate table
                ap_form_submission_revision, ap_form_submission, ap_form_ui_schema,
                ap_form_definition, ap_approval_task, ap_approval_instance,
                ap_definition_version, ap_command_idempotency
            cascade
            """);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        JdbcApprovalFormStore forms = new JdbcApprovalFormStore(dataSource, objectMapper);
        uiSchemas = new JdbcApprovalUiSchemaStore(dataSource, objectMapper);
        submissions = new JdbcApprovalFormSubmissionStore(dataSource, objectMapper);
        projections = new JdbcApprovalProjectionStore(dataSource, objectMapper);

        var form = PurchasePaymentTemplate.formDefinition();
        forms.save(new PublishedForm(
            "tenant-a",
            form,
            new FormSchemaHasher().hash(form),
            "publisher",
            NOW
        ));
        var uiSchema = PurchasePaymentTemplate.uiSchemaDefinition();
        uiSchemas.save(new PublishedUiSchema(
            "tenant-a",
            uiSchema,
            new UiSchemaHasher().hash(uiSchema),
            "publisher",
            NOW
        ));
        projections.saveDefinition(new PublishedDefinition(
            "tenant-a",
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            "compiler-v1",
            "a".repeat(64),
            "deployment-ui",
            "engine-definition-ui",
            1,
            "publisher",
            NOW
        ));
        projections.createInstance(instance(), List.of());
    }

    @Test
    void uiSchemaVersionIsImmutableAndSubmissionRevisionsAreAppendOnly() {
        var published = uiSchemas.findLatest(
            "tenant-a",
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION
        ).orElseThrow();
        assertEquals(PurchasePaymentTemplate.UI_SCHEMA_VERSION, published.definition().version());

        FormSubmission submission = submission(published.contentHash());
        submissions.save(submission);
        assertEquals(
            PurchasePaymentTemplate.UI_SCHEMA_VERSION,
            submissions.findByInstance("tenant-a", INSTANCE_ID).orElseThrow().uiSchemaVersion()
        );

        submissions.saveRevision(revision(1, "supplier-v1"));
        submissions.saveRevision(revision(2, "supplier-v2"));
        FormSubmissionRevision latest = submissions.findLatestRevision(
            "tenant-a",
            INSTANCE_ID
        ).orElseThrow();
        assertEquals(2, latest.revisionNumber());
        assertEquals("supplier-v2", latest.values().get("supplier"));
        assertEquals(2, countRows("ap_form_submission_revision"));

        assertThrows(DataAccessException.class, () -> uiSchemas.save(published));
        assertThrows(DataAccessException.class, () -> submissions.saveRevision(
            revision(2, "duplicate")
        ));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
            "select ui_schema_hash is not null from ap_form_submission where submission_id = ?",
            Boolean.class,
            SUBMISSION_ID
        )));
    }

    private InstanceProjection instance() {
        return new InstanceProjection(
            INSTANCE_ID,
            "tenant-a",
            "UI-SCHEMA-001",
            "engine-ui-instance",
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            "compiler-v1",
            "a".repeat(64),
            "initiator-1",
            new BigDecimal("100.00"),
            "supplier-v0",
            "PO-001",
            List.of(),
            new AssigneeSnapshot("manager-1", "finance-1", List.of("finance-2"), Map.of()),
            "b".repeat(64),
            InstanceStatus.RUNNING,
            1,
            NOW,
            NOW
        );
    }

    private FormSubmission submission(String uiSchemaHash) {
        return new FormSubmission(
            SUBMISSION_ID,
            "tenant-a",
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            "c".repeat(64),
            PurchasePaymentTemplate.UI_SCHEMA_VERSION,
            uiSchemaHash,
            "UI-SCHEMA-001",
            Map.of(
                "amount", new BigDecimal("100.00"),
                "supplier", "supplier-v0",
                "purchaseOrderReference", "PO-001",
                "attachments", List.of()
            ),
            Map.of(),
            INSTANCE_ID,
            "initiator-1",
            NOW,
            "d".repeat(64)
        );
    }

    private FormSubmissionRevision revision(int number, String supplier) {
        return new FormSubmissionRevision(
            UUID.randomUUID(),
            "tenant-a",
            INSTANCE_ID,
            number,
            Map.of(
                "amount", new BigDecimal("100.00"),
                "supplier", supplier,
                "purchaseOrderReference", "PO-001",
                "attachments", List.of()
            ),
            "initiator-1",
            NOW.plusSeconds(number),
            Integer.toHexString(number).repeat(64).substring(0, 64)
        );
    }

    private int countRows(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
