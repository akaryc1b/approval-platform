package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalFormRuntimeService;
import io.github.akaryc1b.approval.application.ApprovalFormSubmissionService;
import io.github.akaryc1b.approval.application.ApprovalFormSubmissionService.SubmissionCommand;
import io.github.akaryc1b.approval.application.FormDataValidator;
import io.github.akaryc1b.approval.application.FormDefaultValueResolver;
import io.github.akaryc1b.approval.application.FormSchemaHasher;
import io.github.akaryc1b.approval.application.FormSubmissionHasher;
import io.github.akaryc1b.approval.application.UiSchemaHasher;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.PublishedForm;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore.PublishedUiSchema;
import io.github.akaryc1b.approval.application.port.FormSubmissionWorkflowStarter.WorkflowStartResult;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition.DefaultValue;
import io.github.akaryc1b.approval.domain.form.FormDefinition.DefaultValueType;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldAccess;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.RequiredOverride;
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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalFormDefaultSnapshotIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-18T12:34:56Z");
    private static final UUID INSTANCE_ID = UUID.fromString(
        "77777777-7777-7777-7777-777777777777"
    );

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_form_default_snapshot_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private ObjectMapper objectMapper;
    private Clock clock;
    private JdbcApprovalFormStore forms;
    private JdbcApprovalUiSchemaStore uiSchemas;
    private JdbcApprovalFormSubmissionStore submissions;
    private ApprovalFormSubmissionService service;

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
        new JdbcTemplate(dataSource).execute("""
            truncate table
                ap_form_submission_revision, ap_form_submission, ap_form_ui_schema,
                ap_form_definition, ap_approval_attachment, ap_audit_event,
                ap_command_idempotency
            cascade
            """);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        forms = new JdbcApprovalFormStore(dataSource, objectMapper);
        uiSchemas = new JdbcApprovalUiSchemaStore(dataSource, objectMapper);
        submissions = new JdbcApprovalFormSubmissionStore(dataSource, objectMapper);
        JdbcApprovalAttachmentStore attachments = new JdbcApprovalAttachmentStore(dataSource);
        JdbcApprovalProjectionStore projections = new JdbcApprovalProjectionStore(
            dataSource,
            objectMapper
        );
        JdbcApprovalMessageStore messages = new JdbcApprovalMessageStore(dataSource, objectMapper);
        FormDataValidator validator = new FormDataValidator();
        FormSubmissionHasher submissionHasher = new FormSubmissionHasher(objectMapper);
        ApprovalFormRuntimeService runtimeService = new ApprovalFormRuntimeService(
            forms,
            uiSchemas,
            submissions,
            projections,
            attachments,
            validator,
            submissionHasher,
            new FormDefaultValueResolver(clock),
            UUID::randomUUID
        );
        service = new ApprovalFormSubmissionService(
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                new JdbcTransactionManager(dataSource),
                clock
            ),
            forms,
            submissions,
            attachments,
            projections,
            messages,
            (context, formKey, businessKey, values, parameters) -> new WorkflowStartResult(
                INSTANCE_ID,
                "RUNNING",
                NOW
            ),
            runtimeService,
            validator,
            submissionHasher,
            new JdbcAuditEventSink(dataSource, objectMapper),
            clock,
            UUID::randomUUID
        );
        publishDefinition();
    }

    @Test
    void resolvesReadOnlyRequiredDefaultsIntoImmutableSnapshot() {
        var result = service.submit(new SubmissionCommand(
            new RequestContext(
                "tenant-defaults",
                "initiator-1",
                "request-defaults",
                "idempotency-defaults",
                "trace-defaults"
            ),
            "default-snapshot",
            1,
            "DEFAULT-SNAPSHOT-1",
            Map.of(),
            Map.of()
        ));

        assertEquals("initiator-1", result.values().get("owner"));
        assertEquals(NOW.toString(), result.values().get("submittedAt"));
        var snapshot = submissions.findByInstance("tenant-defaults", INSTANCE_ID).orElseThrow();
        assertEquals("initiator-1", snapshot.values().get("owner"));
        assertEquals(NOW.toString(), snapshot.values().get("submittedAt"));
    }

    private void publishDefinition() {
        FormDefinition definition = new FormDefinition(
            FormDefinition.CURRENT_SCHEMA_VERSION,
            "default-snapshot",
            1,
            "Default snapshot",
            List.of(
                new FormDefinition.FormField(
                    "owner",
                    FormDefinition.FieldType.TEXT,
                    "Owner",
                    true,
                    FormDefinition.FieldConstraints.text(100),
                    new DefaultValue(DefaultValueType.CURRENT_USER, null)
                ),
                new FormDefinition.FormField(
                    "submittedAt",
                    FormDefinition.FieldType.TEXT,
                    "Submitted at",
                    false,
                    FormDefinition.FieldConstraints.text(100),
                    new DefaultValue(DefaultValueType.CURRENT_DATETIME, null)
                )
            )
        );
        UiSchemaDefinition uiSchema = new UiSchemaDefinition(
            UiSchemaDefinition.CURRENT_SCHEMA_VERSION,
            definition.formKey(),
            definition.version(),
            1,
            "Default snapshot UI",
            List.of(new UiSchemaDefinition.Section(
                "main",
                "Main",
                null,
                false,
                List.of(
                    new UiSchemaDefinition.FieldLayout("owner", null, null, 24),
                    new UiSchemaDefinition.FieldLayout("submittedAt", null, null, 24)
                )
            )),
            List.of(new UiSchemaDefinition.NodePermissions(
                UiSchemaDefinition.START_CONTEXT,
                List.of(
                    new UiSchemaDefinition.FieldPermission(
                        "owner",
                        FieldAccess.READONLY,
                        RequiredOverride.REQUIRED
                    ),
                    new UiSchemaDefinition.FieldPermission(
                        "submittedAt",
                        FieldAccess.READONLY,
                        RequiredOverride.OPTIONAL
                    )
                )
            ))
        );
        forms.save(new PublishedForm(
            "tenant-defaults",
            definition,
            new FormSchemaHasher().hash(definition),
            "publisher",
            NOW
        ));
        uiSchemas.save(new PublishedUiSchema(
            "tenant-defaults",
            uiSchema,
            new UiSchemaHasher().hash(uiSchema),
            "publisher",
            NOW
        ));
    }
}
