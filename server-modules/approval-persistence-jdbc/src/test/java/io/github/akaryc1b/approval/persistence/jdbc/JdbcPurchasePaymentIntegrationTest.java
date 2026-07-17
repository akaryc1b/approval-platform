package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.engine.ApprovalEngine;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcPurchasePaymentIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_projection_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private FakeApprovalEngine engine;
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
                ap_audit_event,
                ap_approval_task,
                ap_approval_instance,
                ap_definition_version,
                ap_command_idempotency
            cascade
            """);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        engine = new FakeApprovalEngine();
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
            clock,
            UUID::randomUUID
        );
    }

    @Test
    void publishAndStartAreDurablyIdempotent() {
        var publishContext = context("publisher", "publish-1", "publish-key");
        var firstPublish = service.publish(new PurchasePaymentApplicationService.PublishCommand(
            publishContext
        ));
        var replayedPublish = service.publish(new PurchasePaymentApplicationService.PublishCommand(
            publishContext
        ));

        assertEquals(firstPublish, replayedPublish);
        assertEquals(1, engine.deployments.get());
        assertEquals(1, count("ap_definition_version"));

        var startContext = context("alice", "start-1", "start-key");
        var command = startCommand(startContext, new BigDecimal("5000.00"), "PO-100");
        var firstStart = service.start(command);
        var replayedStart = service.start(command);

        assertEquals(firstStart, replayedStart);
        assertEquals(1, engine.starts.get());
        assertEquals(1, count("ap_approval_instance"));
        assertEquals(1, count("ap_approval_task"));
        assertEquals(2, count("ap_audit_event"));

        var differentPayload = startCommand(
            startContext,
            new BigDecimal("6000.00"),
            "PO-100"
        );
        assertThrows(
            IdempotencyGuard.IdempotencyConflictException.class,
            () -> service.start(differentPayload)
        );
    }

    @Test
    void businessKeyCannotBeReusedWithDifferentPayload() {
        service.publish(new PurchasePaymentApplicationService.PublishCommand(
            context("publisher", "publish-2", "publish-key-2")
        ));
        service.start(startCommand(
            context("alice", "start-2", "start-key-2"),
            new BigDecimal("5000.00"),
            "PO-200"
        ));

        var exception = assertThrows(
            ApprovalProjectionStore.ProjectionConflictException.class,
            () -> service.start(startCommand(
                context("alice", "start-3", "different-idempotency-key"),
                new BigDecimal("7000.00"),
                "PO-200"
            ))
        );

        assertTrue(exception.getMessage().contains("business key"));
        assertEquals(1, engine.starts.get());
    }

    @Test
    void concurrentApprovalAllowsOneTransitionOnly() throws Exception {
        service.publish(new PurchasePaymentApplicationService.PublishCommand(
            context("publisher", "publish-3", "publish-key-3")
        ));
        var started = service.start(startCommand(
            context("alice", "start-4", "start-key-4"),
            new BigDecimal("5000.00"),
            "PO-300"
        ));
        UUID taskId = started.activeTasks().getFirst().taskId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> approveConcurrently(
                taskId,
                context("manager-1", "approve-1", "approve-key-1"),
                ready,
                release
            ));
            Future<Object> second = executor.submit(() -> approveConcurrently(
                taskId,
                context("manager-1", "approve-2", "approve-key-2"),
                ready,
                release
            ));
            ready.await();
            release.countDown();

            Object firstResult = first.get();
            Object secondResult = second.get();
            assertNotEquals(firstResult.getClass(), secondResult.getClass());
            assertTrue(firstResult instanceof PurchasePaymentApplicationService.ApproveResult
                || secondResult instanceof PurchasePaymentApplicationService.ApproveResult);
            assertTrue(firstResult instanceof ApprovalProjectionStore.ProjectionConflictException
                || secondResult instanceof ApprovalProjectionStore.ProjectionConflictException);
        }

        assertEquals(1, engine.completions.get());
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from ap_approval_task where status = 'COMPLETED'",
            Integer.class
        ));
        assertEquals(2, jdbc.queryForObject(
            "select count(*) from ap_approval_task where status = 'PENDING'",
            Integer.class
        ));
    }

    private Object approveConcurrently(
        UUID taskId,
        RequestContext context,
        CountDownLatch ready,
        CountDownLatch release
    ) {
        ready.countDown();
        try {
            release.await();
            return service.approve(new PurchasePaymentApplicationService.ApproveCommand(
                context,
                taskId,
                "approved"
            ));
        } catch (ApprovalProjectionStore.ProjectionConflictException exception) {
            return exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private PurchasePaymentApplicationService.StartCommand startCommand(
        RequestContext context,
        BigDecimal amount,
        String businessKey
    ) {
        return new PurchasePaymentApplicationService.StartCommand(
            context,
            businessKey,
            amount,
            "Supplier A",
            "PURCHASE-ORDER-A",
            List.of("attachment-1"),
            new AssigneeSnapshot(
                "manager-1",
                "finance-reviewer",
                List.of("finance-a", "finance-b"),
                Map.of("resolvedFrom", "test")
            )
        );
    }

    private RequestContext context(
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

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static final class FakeApprovalEngine implements ApprovalEngine {

        private final AtomicInteger deployments = new AtomicInteger();
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger completions = new AtomicInteger();
        private final Map<String, InstanceState> instances = new HashMap<>();

        @Override
        public synchronized DeploymentResult deploy(DeployCommand command) {
            int version = deployments.incrementAndGet();
            return new DeploymentResult(
                "deployment-" + version,
                command.definitionKey() + ":" + version,
                command.definitionKey(),
                version
            );
        }

        @Override
        public synchronized StartResult start(StartCommand command) {
            int sequence = starts.incrementAndGet();
            String instanceId = "engine-instance-" + sequence;
            instances.put(instanceId, new InstanceState(
                command.tenantId(),
                command.variables(),
                new ArrayList<>(List.of(task(
                    instanceId,
                    "managerApproval",
                    "Manager approval",
                    Objects.toString(command.variables().get("managerAssignee")),
                    1
                )))
            ));
            return new StartResult(instanceId);
        }

        @Override
        public synchronized List<TaskSnapshot> findActiveTasks(TaskQuery query) {
            InstanceState state = instances.get(query.processInstanceId());
            if (state == null || !state.tenantId.equals(query.tenantId())) {
                return List.of();
            }
            return state.tasks.stream()
                .filter(task -> query.assigneeId() == null
                    || query.assigneeId().equals(task.assigneeId()))
                .toList();
        }

        @Override
        public synchronized TaskResult complete(CompleteTaskCommand command) {
            for (Map.Entry<String, InstanceState> entry : instances.entrySet()) {
                InstanceState state = entry.getValue();
                for (TaskSnapshot task : List.copyOf(state.tasks)) {
                    if (!task.taskId().equals(command.taskId())) {
                        continue;
                    }
                    if (!state.tenantId.equals(command.tenantId())) {
                        throw new EngineOperationException("TASK_NOT_FOUND", "task not found");
                    }
                    if (!task.assigneeId().equals(command.operatorId())) {
                        throw new EngineOperationException(
                            "TASK_NOT_ASSIGNED_TO_OPERATOR",
                            "wrong operator"
                        );
                    }
                    state.tasks.remove(task);
                    if ("managerApproval".equals(task.taskDefinitionKey())) {
                        state.tasks.add(task(
                            entry.getKey(),
                            "financeCountersign",
                            "Finance countersign",
                            "finance-a",
                            2
                        ));
                        state.tasks.add(task(
                            entry.getKey(),
                            "financeCountersign",
                            "Finance countersign",
                            "finance-b",
                            3
                        ));
                    }
                    completions.incrementAndGet();
                    return new TaskResult(command.taskId(), "COMPLETED");
                }
            }
            throw new EngineOperationException("TASK_NOT_FOUND", "task not found");
        }

        private static TaskSnapshot task(
            String instanceId,
            String definitionKey,
            String name,
            String assignee,
            int sequence
        ) {
            return new TaskSnapshot(
                instanceId + "-task-" + sequence,
                instanceId,
                definitionKey,
                name,
                assignee,
                NOW.plusSeconds(sequence)
            );
        }

        private static final class InstanceState {

            private final String tenantId;
            private final Map<String, Object> variables;
            private final List<TaskSnapshot> tasks;

            private InstanceState(
                String tenantId,
                Map<String, Object> variables,
                List<TaskSnapshot> tasks
            ) {
                this.tenantId = tenantId;
                this.variables = variables;
                this.tasks = tasks;
            }
        }
    }
}
