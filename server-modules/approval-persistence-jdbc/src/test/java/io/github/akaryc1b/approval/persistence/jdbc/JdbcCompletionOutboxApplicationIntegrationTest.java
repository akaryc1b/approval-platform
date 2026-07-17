package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.engine.ApprovalEngine;
import io.github.akaryc1b.approval.integration.jdbc.JdbcOutboxRepository;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class JdbcCompletionOutboxApplicationIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-18T02:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_completion_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private PurchasePaymentApplicationService service;

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
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        FakeApprovalEngine engine = new FakeApprovalEngine();
        service = new PurchasePaymentApplicationService(
            engine,
            new ApprovalDslCompiler(),
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                new JdbcTransactionManager(dataSource),
                clock
            ),
            new JdbcApprovalProjectionStore(dataSource, objectMapper),
            new JdbcAuditEventSink(dataSource, objectMapper),
            (context, rules) -> {
                throw new IllegalStateException("rule resolver is not used by this compatibility test");
            },
            new JdbcApprovalBusinessEventOutbox(
                new JdbcOutboxRepository(dataSource, objectMapper),
                UUID::randomUUID
            ),
            clock,
            UUID::randomUUID
        );
    }

    @Test
    void onlyFinalTaskCreatesOneReplaySafeCompletionEvent() {
        service.publish(new PurchasePaymentApplicationService.PublishCommand(
            context("publisher", "publish-request", "publish-key")
        ));
        var started = service.start(new PurchasePaymentApplicationService.StartCommand(
            context("100", "start-request", "start-key"),
            "PO-CALLBACK-1",
            new BigDecimal("5000.00"),
            "Supplier A",
            "PURCHASE-1",
            List.of("attachment-1"),
            new AssigneeSnapshot(
                "manager-1",
                "finance-reviewer",
                List.of("finance-a"),
                Map.of("connectorKey", "generic-rest")
            )
        ));
        assertEquals(0, outboxCount());

        var managerResult = service.approve(new PurchasePaymentApplicationService.ApproveCommand(
            context("manager-1", "manager-request", "manager-key"),
            started.activeTasks().getFirst().taskId(),
            "approved"
        ));
        assertEquals(1, managerResult.activeTasks().size());
        assertEquals(0, outboxCount());

        RequestContext financeContext = context(
            "finance-a",
            "finance-request",
            "finance-key"
        );
        var completed = service.approve(new PurchasePaymentApplicationService.ApproveCommand(
            financeContext,
            managerResult.activeTasks().getFirst().taskId(),
            "approved"
        ));
        var replayed = service.approve(new PurchasePaymentApplicationService.ApproveCommand(
            financeContext,
            managerResult.activeTasks().getFirst().taskId(),
            "approved"
        ));

        assertEquals(completed, replayed);
        assertEquals(0, completed.activeTasks().size());
        assertEquals(1, outboxCount());
    }

    private int outboxCount() {
        return jdbc.queryForObject("select count(*) from ap_outbox", Integer.class);
    }

    private static RequestContext context(
        String operatorId,
        String requestId,
        String idempotencyKey
    ) {
        return new RequestContext(
            "tenant-a",
            operatorId,
            requestId,
            idempotencyKey,
            "trace-1"
        );
    }

    private static final class FakeApprovalEngine implements ApprovalEngine {

        private final AtomicInteger deployments = new AtomicInteger();
        private final AtomicInteger starts = new AtomicInteger();
        private final Map<String, List<TaskSnapshot>> tasks = new HashMap<>();

        @Override
        public DeploymentResult deploy(DeployCommand command) {
            int version = deployments.incrementAndGet();
            return new DeploymentResult(
                "deployment-" + version,
                command.definitionKey() + ':' + version,
                command.definitionKey(),
                version
            );
        }

        @Override
        public StartResult start(StartCommand command) {
            String instanceId = "engine-" + starts.incrementAndGet();
            tasks.put(instanceId, new ArrayList<>(List.of(new TaskSnapshot(
                instanceId + "-manager",
                instanceId,
                "managerApproval",
                "Manager approval",
                command.variables().get("managerAssignee").toString(),
                NOW
            ))));
            return new StartResult(instanceId);
        }

        @Override
        public List<TaskSnapshot> findActiveTasks(TaskQuery query) {
            return List.copyOf(tasks.getOrDefault(query.processInstanceId(), List.of()));
        }

        @Override
        public TaskResult complete(CompleteTaskCommand command) {
            for (Map.Entry<String, List<TaskSnapshot>> entry : tasks.entrySet()) {
                TaskSnapshot matched = entry.getValue().stream()
                    .filter(task -> task.taskId().equals(command.taskId()))
                    .findFirst()
                    .orElse(null);
                if (matched == null) {
                    continue;
                }
                entry.getValue().remove(matched);
                if ("managerApproval".equals(matched.taskDefinitionKey())) {
                    entry.getValue().add(new TaskSnapshot(
                        entry.getKey() + "-finance",
                        entry.getKey(),
                        "financeCountersign",
                        "Finance countersign",
                        "finance-a",
                        NOW.plusSeconds(1)
                    ));
                }
                return new TaskResult(command.taskId(), "COMPLETED");
            }
            throw new EngineOperationException("TASK_NOT_FOUND", "task not found");
        }
    }
}
