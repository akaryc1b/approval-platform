package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.ApprovalTimelineQuery.TimelineIdentity;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalTimelineQueryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-18T02:00:00Z");
    private static final UUID INSTANCE_ID = identifier(1);
    private static final UUID TASK_ID = identifier(2);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_timeline_query_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcApprovalTimelineQuery timelineQuery;
    private JdbcAuditEventSink auditEventSink;

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
        new JdbcTemplate(dataSource).execute("""
            truncate table
                ap_audit_event,
                ap_approval_task,
                ap_approval_instance,
                ap_definition_version
            cascade
            """);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ApprovalProjectionStore projectionStore = new JdbcApprovalProjectionStore(
            dataSource,
            objectMapper
        );
        timelineQuery = new JdbcApprovalTimelineQuery(dataSource, objectMapper);
        auditEventSink = new JdbcAuditEventSink(dataSource, objectMapper);

        saveDefinition(projectionStore, "tenant-a");
        saveDefinition(projectionStore, "tenant-b");
        createInstance(projectionStore, "tenant-a", INSTANCE_ID, TASK_ID, "initiator-a", "manager-a");
    }

    @Test
    void initiatorAndHistoricalAssigneeCanReadTheOrderedTimeline() {
        appendEvent(
            identifier(100),
            "tenant-a",
            "initiator-a",
            "INSTANCE_STARTED",
            "APPROVAL_INSTANCE",
            INSTANCE_ID.toString(),
            NOW,
            Map.of("definitionVersion", "1")
        );
        appendEvent(
            identifier(101),
            "tenant-a",
            "manager-a",
            "TASK_APPROVED",
            "APPROVAL_TASK",
            TASK_ID.toString(),
            NOW.plusSeconds(10),
            Map.of("decision", "APPROVED", "taskDefinitionKey", "managerApproval")
        );
        appendEvent(
            identifier(102),
            "tenant-a",
            "outsider",
            "UNRELATED_EVENT",
            "APPROVAL_INSTANCE",
            identifier(999).toString(),
            NOW.plusSeconds(5),
            Map.of()
        );

        var initiatorTimeline = timelineQuery.findTimeline(new TimelineIdentity(
            "tenant-a",
            "initiator-a",
            INSTANCE_ID
        )).orElseThrow();
        assertEquals(List.of("INSTANCE_STARTED", "TASK_APPROVED"), initiatorTimeline.items().stream()
            .map(item -> item.action())
            .toList());
        assertEquals("APPROVED", initiatorTimeline.items().get(1).attributes().get("decision"));

        var assigneeTimeline = timelineQuery.findTimeline(new TimelineIdentity(
            "tenant-a",
            "manager-a",
            INSTANCE_ID
        )).orElseThrow();
        assertEquals(2, assigneeTimeline.items().size());
    }

    @Test
    void timelineIsHiddenFromOtherUsersAndTenants() {
        appendEvent(
            identifier(110),
            "tenant-a",
            "initiator-a",
            "INSTANCE_STARTED",
            "APPROVAL_INSTANCE",
            INSTANCE_ID.toString(),
            NOW,
            Map.of()
        );

        assertTrue(timelineQuery.findTimeline(new TimelineIdentity(
            "tenant-a",
            "unrelated-user",
            INSTANCE_ID
        )).isEmpty());
        assertTrue(timelineQuery.findTimeline(new TimelineIdentity(
            "tenant-b",
            "initiator-a",
            INSTANCE_ID
        )).isEmpty());
        assertTrue(timelineQuery.findTimeline(new TimelineIdentity(
            "tenant-a",
            "initiator-a",
            identifier(404)
        )).isEmpty());
    }

    private void appendEvent(
        UUID eventId,
        String tenantId,
        String operatorId,
        String action,
        String aggregateType,
        String aggregateId,
        Instant occurredAt,
        Map<String, String> attributes
    ) {
        auditEventSink.append(new AuditEvent(
            eventId,
            tenantId,
            operatorId,
            action,
            aggregateType,
            aggregateId,
            "request-" + eventId,
            "trace-1",
            occurredAt,
            attributes
        ));
    }

    private static void saveDefinition(ApprovalProjectionStore projectionStore, String tenantId) {
        projectionStore.saveDefinition(new PublishedDefinition(
            tenantId,
            "purchase-payment",
            1,
            "purchase-payment-form",
            1,
            "approval-compiler-v1",
            "a".repeat(64),
            "deployment-" + tenantId,
            "definition-" + tenantId,
            1,
            "publisher",
            NOW
        ));
    }

    private static void createInstance(
        ApprovalProjectionStore projectionStore,
        String tenantId,
        UUID instanceId,
        UUID taskId,
        String initiatorId,
        String assigneeId
    ) {
        InstanceProjection instance = new InstanceProjection(
            instanceId,
            tenantId,
            "PO-TIMELINE-001",
            "engine-instance-timeline",
            "purchase-payment",
            1,
            "purchase-payment-form",
            1,
            "approval-compiler-v1",
            "a".repeat(64),
            initiatorId,
            new BigDecimal("18000.00"),
            "Supplier Timeline",
            "PURCHASE-ORDER-TIMELINE",
            List.of("attachment-1"),
            new AssigneeSnapshot(
                assigneeId,
                "finance-reviewer",
                List.of("finance-a", "finance-b"),
                Map.of("source", "test")
            ),
            "b".repeat(64),
            InstanceStatus.COMPLETED,
            2,
            NOW,
            NOW.plusSeconds(10)
        );
        TaskProjection task = new TaskProjection(
            taskId,
            instanceId,
            tenantId,
            "engine-task-timeline",
            "managerApproval",
            "Manager approval",
            assigneeId,
            TaskStatus.COMPLETED,
            2,
            NOW.plusSeconds(1),
            NOW.plusSeconds(10),
            NOW.plusSeconds(10)
        );
        projectionStore.createInstance(instance, List.of(task));
    }

    private static UUID identifier(int sequence) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(sequence));
    }
}
