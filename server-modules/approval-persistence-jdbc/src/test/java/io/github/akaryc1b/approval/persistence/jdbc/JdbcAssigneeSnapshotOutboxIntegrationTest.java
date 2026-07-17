package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.UserIdentitySnapshot;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.integration.jdbc.JdbcOutboxRepository;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcAssigneeSnapshotOutboxIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-18T01:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_snapshot_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private JdbcApprovalProjectionStore store;

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
                ap_outbox,
                ap_audit_event,
                ap_approval_task,
                ap_approval_instance,
                ap_definition_version,
                ap_command_idempotency
            cascade
            """);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        store = new JdbcApprovalProjectionStore(dataSource, objectMapper);
        store.saveDefinition(definition());
    }

    @Test
    void richIdentitySnapshotSurvivesJsonbRoundTrip() {
        InstanceProjection instance = instance(richSnapshot(), "PO-SNAPSHOT-1");

        store.createInstance(instance, List.of());
        InstanceProjection restored = store.findInstance("tenant-a", instance.instanceId())
            .orElseThrow();

        assertEquals(instance.assigneeSnapshot(), restored.assigneeSnapshot());
        assertEquals(
            "Finance A",
            restored.assigneeSnapshot()
                .identities()
                .get("ruoyi5:user:400")
                .displayName()
        );
        assertEquals(
            Set.of("finance"),
            restored.assigneeSnapshot()
                .identities()
                .get("ruoyi5:user:400")
                .positionCodes()
        );
    }

    @Test
    void oldSnapshotJsonWithoutIdentitiesRemainsReadable() {
        InstanceProjection instance = instance(richSnapshot(), "PO-SNAPSHOT-2");
        store.createInstance(instance, List.of());
        jdbc.update(
            """
            update ap_approval_instance
            set assignee_snapshot_json = cast(? as jsonb)
            where instance_id = ?
            """,
            """
            {
              "managerAssignee":"200",
              "financeReviewer":"300",
              "financeApprovers":["400"],
              "attributes":{"connectorKey":"generic-rest"}
            }
            """,
            instance.instanceId()
        );

        AssigneeSnapshot restored = store.findInstance("tenant-a", instance.instanceId())
            .orElseThrow()
            .assigneeSnapshot();

        assertEquals("200", restored.managerAssignee());
        assertTrue(restored.identities().isEmpty());
    }

    @Test
    void completedEventIsDeduplicatedByTenantConnectorAndInstance() {
        InstanceProjection instance = instance(richSnapshot(), "PO-SNAPSHOT-3");
        store.createInstance(instance, List.of());
        AtomicLong sequence = new AtomicLong();
        var outbox = new JdbcApprovalBusinessEventOutbox(
            new JdbcOutboxRepository(dataSource, objectMapper),
            () -> new UUID(0, sequence.incrementAndGet())
        );
        RequestContext context = new RequestContext(
            "tenant-a",
            "finance-b",
            "request-completed",
            "approve-completed",
            "trace-completed"
        );

        outbox.enqueueCompleted(context, "generic-rest", instance, NOW);
        outbox.enqueueCompleted(context, "generic-rest", instance, NOW);

        assertEquals(1, jdbc.queryForObject(
            "select count(*) from ap_outbox",
            Integer.class
        ));
        String eventJson = jdbc.queryForObject(
            "select event_json::text from ap_outbox",
            String.class
        );
        assertTrue(eventJson.contains("purchase-payment.completed.v1"));
        assertTrue(eventJson.contains(instance.contentHash()));
        assertTrue(eventJson.contains("PO-SNAPSHOT-3"));
        assertTrue(eventJson.contains("Finance A"));
    }

    private static PublishedDefinition definition() {
        return new PublishedDefinition(
            "tenant-a",
            "purchase-payment",
            1,
            "purchase-payment",
            1,
            "1.0.0",
            "a".repeat(64),
            "deployment-1",
            "purchase-payment:1",
            1,
            "publisher",
            NOW
        );
    }

    private static InstanceProjection instance(AssigneeSnapshot snapshot, String businessKey) {
        return new InstanceProjection(
            UUID.randomUUID(),
            "tenant-a",
            businessKey,
            "engine-" + businessKey,
            "purchase-payment",
            1,
            "purchase-payment",
            1,
            "1.0.0",
            "a".repeat(64),
            "100",
            new BigDecimal("25000.00"),
            "Supplier A",
            "PURCHASE-1",
            List.of("attachment-1"),
            snapshot,
            "b".repeat(64),
            InstanceStatus.COMPLETED,
            4,
            NOW,
            NOW
        );
    }

    private static AssigneeSnapshot richSnapshot() {
        UserIdentitySnapshot initiator = identity("100", "Alice");
        UserIdentitySnapshot manager = identity("200", "Manager");
        UserIdentitySnapshot reviewer = identity("300", "Reviewer");
        UserIdentitySnapshot finance = identity("400", "Finance A");
        return new AssigneeSnapshot(
            "200",
            "300",
            List.of("400"),
            Map.of(
                "connectorKey", "generic-rest",
                "initiatorExternalId", "ruoyi5:user:100"
            ),
            Map.of(
                initiator.externalId(), initiator,
                manager.externalId(), manager,
                reviewer.externalId(), reviewer,
                finance.externalId(), finance
            )
        );
    }

    private static UserIdentitySnapshot identity(String value, String displayName) {
        return new UserIdentitySnapshot(
            "ruoyi5:user:" + value,
            displayName.toLowerCase().replace(" ", "-"),
            displayName,
            value + "@example.com",
            "+1000" + value,
            List.of("ruoyi5:department:10"),
            Set.of("employee"),
            Set.of("finance"),
            Map.of("source", "test")
        );
    }
}
