package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalFormSubmissionService;
import io.github.akaryc1b.approval.application.ApprovalFormSubmissionService.SubmissionCommand;
import io.github.akaryc1b.approval.application.FormDataValidator;
import io.github.akaryc1b.approval.application.FormSchemaHasher;
import io.github.akaryc1b.approval.application.FormSubmissionHasher;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore.ApprovalAttachment;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.PublishedForm;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.FormSubmissionWorkflowStarter.WorkflowStartResult;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.context.RequestContext;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalFormSubmissionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-18T09:00:00Z");
    private static final UUID INSTANCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final UUID ATTACHMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_form_submission_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private JdbcApprovalProjectionStore projections;
    private JdbcApprovalAttachmentStore attachments;
    private ApprovalFormSubmissionService service;

    @BeforeAll
    static void migrateDatabase() {
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            truncate table
                ap_form_submission, ap_approval_attachment, ap_form_definition,
                ap_approval_message, ap_audit_event, ap_approval_task,
                ap_approval_instance, ap_definition_version, ap_command_idempotency
            cascade
            """);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        projections = new JdbcApprovalProjectionStore(dataSource, objectMapper);
        attachments = new JdbcApprovalAttachmentStore(dataSource);
        JdbcApprovalFormStore forms = new JdbcApprovalFormStore(dataSource, objectMapper);
        JdbcApprovalMessageStore messages = new JdbcApprovalMessageStore(dataSource, objectMapper);
        JdbcApprovalFormSubmissionStore submissions = new JdbcApprovalFormSubmissionStore(
            dataSource, objectMapper
        );
        JdbcAuditEventSink audit = new JdbcAuditEventSink(dataSource, objectMapper);
        JdbcIdempotencyGuard idempotency = new JdbcIdempotencyGuard(
            dataSource, objectMapper, new JdbcTransactionManager(dataSource), clock
        );
        var definition = PurchasePaymentTemplate.formDefinition();
        forms.save(new PublishedForm(
            "tenant-a", definition, new FormSchemaHasher().hash(definition), "publisher", NOW
        ));
        saveProcessDefinition();
        byte[] content = "invoice".getBytes(StandardCharsets.UTF_8);
        attachments.save(new ApprovalAttachment(
            ATTACHMENT_ID, "tenant-a", "initiator-1", null, "invoice.txt", "text/plain",
            content.length, "a".repeat(64), content, NOW, null
        ));
        service = new ApprovalFormSubmissionService(
            idempotency, forms, submissions, attachments, projections, messages,
            (context, formKey, businessKey, values, parameters) -> startProjection(
                context, businessKey, values
            ),
            new FormDataValidator(), new FormSubmissionHasher(objectMapper), audit,
            clock, UUID::randomUUID
        );
    }

    @Test
    void submissionIsValidatedStartedBoundSnapshottedAndAuthorized() {
        SubmissionCommand command = command(
            "submit-request", "submit-key", "PO-FORM-001", new BigDecimal("1888.50")
        );
        var created = service.submit(command);
        var replayed = service.submit(command);

        assertEquals(created.submissionId(), replayed.submissionId());
        assertEquals(created.instanceId(), replayed.instanceId());
        assertEquals(created.formKey(), replayed.formKey());
        assertEquals(created.formVersion(), replayed.formVersion());
        assertEquals(created.schemaHash(), replayed.schemaHash());
        assertEquals(created.businessKey(), replayed.businessKey());
        assertEquals(created.submittedBy(), replayed.submittedBy());
        assertEquals(created.submittedAt(), replayed.submittedAt());
        assertEquals(created.replayedExistingSubmission(), replayed.replayedExistingSubmission());
        assertEquals(created.values().keySet(), replayed.values().keySet());
        assertEquals(0, decimal(created.values().get("amount")).compareTo(
            decimal(replayed.values().get("amount"))
        ));
        assertEquals(created.values().get("supplier"), replayed.values().get("supplier"));
        assertEquals(
            created.values().get("purchaseOrderReference"),
            replayed.values().get("purchaseOrderReference")
        );
        assertEquals(created.values().get("attachments"), replayed.values().get("attachments"));
        assertEquals(INSTANCE_ID, created.instanceId());
        assertEquals(1, countRows("ap_form_submission"));
        assertEquals(1, countAudit("FORM_SUBMITTED"));
        assertEquals(INSTANCE_ID, attachments.find("tenant-a", ATTACHMENT_ID).orElseThrow().instanceId());

        var initiatorSnapshot = service.findByInstance(
            "tenant-a", "initiator-1", INSTANCE_ID
        ).orElseThrow();
        assertEquals("1888.5", initiatorSnapshot.submission().values().get("amount").toString());
        assertEquals(PurchasePaymentTemplate.FORM_VERSION, initiatorSnapshot.definition().version());
        assertTrue(service.findByInstance("tenant-a", "manager-1", INSTANCE_ID).isPresent());
        assertThrows(
            RuntimeException.class,
            () -> service.findByInstance("tenant-a", "outsider", INSTANCE_ID)
        );

        assertThrows(
            RuntimeException.class,
            () -> service.submit(command(
                "changed-request", "changed-key", "PO-FORM-001", new BigDecimal("2000.00")
            ))
        );
        assertThrows(
            RuntimeException.class,
            () -> service.submit(new SubmissionCommand(
                context("invalid-request", "invalid-key"),
                PurchasePaymentTemplate.DEFINITION_KEY,
                PurchasePaymentTemplate.FORM_VERSION,
                "PO-FORM-002",
                Map.of(
                    "amount", 10,
                    "supplier", "A",
                    "unknown", "x",
                    "attachments", List.of(ATTACHMENT_ID.toString())
                ),
                Map.of()
            ))
        );
    }

    private WorkflowStartResult startProjection(
        RequestContext context,
        String businessKey,
        Map<String, Object> values
    ) {
        Instant now = NOW;
        InstanceProjection instance = new InstanceProjection(
            INSTANCE_ID, context.tenantId(), businessKey, "engine-form-instance",
            PurchasePaymentTemplate.DEFINITION_KEY, PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY, PurchasePaymentTemplate.FORM_VERSION,
            ApprovalDslCompiler.COMPILER_VERSION, "b".repeat(64), context.operatorId(),
            new BigDecimal(values.get("amount").toString()), String.valueOf(values.get("supplier")),
            String.valueOf(values.get("purchaseOrderReference")),
            castStrings(values.get("attachments")),
            new AssigneeSnapshot("manager-1", "finance-reviewer", List.of("finance-a"), Map.of()),
            "c".repeat(64), InstanceStatus.RUNNING, 1, now, now
        );
        TaskProjection task = new TaskProjection(
            UUID.randomUUID(), INSTANCE_ID, context.tenantId(), "engine-form-task",
            "managerApproval", "Manager approval", "manager-1", TaskStatus.PENDING,
            1, now, now, null
        );
        projections.createInstance(instance, List.of(task));
        return new WorkflowStartResult(INSTANCE_ID, InstanceStatus.RUNNING.name(), now);
    }

    private SubmissionCommand command(
        String requestId,
        String idempotencyKey,
        String businessKey,
        BigDecimal amount
    ) {
        return new SubmissionCommand(
            context(requestId, idempotencyKey),
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            businessKey,
            Map.of(
                "amount", amount,
                "supplier", "快照测试供应商",
                "purchaseOrderReference", "PURCHASE-FORM-001",
                "attachments", List.of(ATTACHMENT_ID.toString())
            ),
            Map.of("connectorKey", "test")
        );
    }

    private void saveProcessDefinition() {
        projections.saveDefinition(new PublishedDefinition(
            "tenant-a", PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION, PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION, ApprovalDslCompiler.COMPILER_VERSION,
            "b".repeat(64), "deployment-form", "engine-definition-form", 1, "publisher", NOW
        ));
    }

    private RequestContext context(String requestId, String idempotencyKey) {
        return new RequestContext("tenant-a", "initiator-1", requestId, idempotencyKey, "trace-form");
    }

    private static BigDecimal decimal(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static List<String> castStrings(Object value) {
        return (List<String>) value;
    }

    private int countRows(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private int countAudit(String action) {
        return jdbc.queryForObject(
            "select count(*) from ap_audit_event where action = ?", Integer.class, action
        );
    }
}
