package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.PurchasePaymentCollaborationService;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcPurchasePaymentCollaborationIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-18T04:00:00Z");
    private static final UUID INSTANCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID MANAGER_TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID FINANCE_TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000402");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_collaboration_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private JdbcApprovalProjectionStore projections;
    private FakeApprovalEngine engine;
    private PurchasePaymentCollaborationService collaboration;

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
        projections = new JdbcApprovalProjectionStore(dataSource, objectMapper);
        engine = new FakeApprovalEngine();
        collaboration = new PurchasePaymentCollaborationService(
            engine,
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                new JdbcTransactionManager(dataSource),
                clock
            ),
            projections,
            new JdbcAuditEventSink(dataSource, objectMapper),
            clock,
            UUID::randomUUID
        );
        saveDefinition();
    }

    @Test
    void withdrawIsDurablyIdempotentAndCancelsActiveTasks() {
        createRunningInstance(List.of(pendingManagerTask()));
        engine.tasks.add(engineTask(
            "engine-manager-task-1",
            "managerApproval",
            "manager-1",
            NOW
        ));
        var command = new PurchasePaymentCollaborationService.WithdrawCommand(
            context("initiator-1", "withdraw-request", "withdraw-key"),
            INSTANCE_ID,
            "采购计划取消"
        );

        var withdrawn = collaboration.withdraw(command);
        var replayed = collaboration.withdraw(command);

        assertEquals(withdrawn, replayed);
        assertEquals(1, engine.terminations.get());
        assertEquals(InstanceStatus.WITHDRAWN, projections.findInstance("tenant-a", INSTANCE_ID)
            .orElseThrow()
            .status());
        assertEquals(TaskStatus.CANCELED, projections.findTask("tenant-a", MANAGER_TASK_ID)
            .orElseThrow()
            .status());
        assertEquals(1, countAudit("INSTANCE_WITHDRAWN"));
    }

    @Test
    void transferChangesTheProjectionAndEngineOnceAndPreservesTheReason() {
        createRunningInstance(List.of(pendingManagerTask()));
        engine.tasks.add(engineTask(
            "engine-manager-task-1",
            "managerApproval",
            "manager-1",
            NOW
        ));
        var command = new PurchasePaymentCollaborationService.TransferCommand(
            context("manager-1", "transfer-request", "transfer-key"),
            MANAGER_TASK_ID,
            "finance-reviewer",
            "负责人出差，转交财务负责人处理"
        );

        var transferred = collaboration.transfer(command);
        var replayed = collaboration.transfer(command);

        assertEquals(transferred, replayed);
        assertEquals(1, engine.transfers.get());
        assertEquals("finance-reviewer", projections.findTask("tenant-a", MANAGER_TASK_ID)
            .orElseThrow()
            .assigneeId());
        assertEquals("finance-reviewer", engine.tasks.getFirst().assigneeId());
        assertEquals(1, countAudit("TASK_TRANSFERRED"));
        assertEquals("负责人出差，转交财务负责人处理", jdbc.queryForObject(
            "select attributes_json ->> 'comment' from ap_audit_event where action = 'TASK_TRANSFERRED'",
            String.class
        ));

        assertThrows(
            ApprovalProjectionStore.ProjectionConflictException.class,
            () -> collaboration.transfer(new PurchasePaymentCollaborationService.TransferCommand(
                context("finance-reviewer", "unknown-target", "unknown-target-key"),
                MANAGER_TASK_ID,
                "unknown-user",
                "invalid target"
            ))
        );
        assertEquals("finance-reviewer", projections.findTask("tenant-a", MANAGER_TASK_ID)
            .orElseThrow()
            .assigneeId());
    }

    @Test
    void retrieveRestoresTheLatestLinearTaskAndCancelsTheDownstreamProjection() {
        TaskProjection completedManager = new TaskProjection(
            MANAGER_TASK_ID,
            INSTANCE_ID,
            "tenant-a",
            "engine-manager-task-1",
            "managerApproval",
            "Manager approval",
            "manager-1",
            TaskStatus.COMPLETED,
            2,
            NOW,
            NOW.plusSeconds(1),
            NOW.plusSeconds(1)
        );
        TaskProjection pendingFinance = new TaskProjection(
            FINANCE_TASK_ID,
            INSTANCE_ID,
            "tenant-a",
            "engine-finance-task-1",
            "financeReview",
            "Finance review",
            "finance-reviewer",
            TaskStatus.PENDING,
            1,
            NOW.plusSeconds(2),
            NOW.plusSeconds(2),
            null
        );
        createRunningInstance(List.of(completedManager, pendingFinance));
        engine.tasks.add(engineTask(
            "engine-finance-task-1",
            "financeReview",
            "finance-reviewer",
            NOW.plusSeconds(2)
        ));
        var command = new PurchasePaymentCollaborationService.RetrieveCommand(
            context("manager-1", "retrieve-request", "retrieve-key"),
            MANAGER_TASK_ID,
            "补充审批意见"
        );

        var retrieved = collaboration.retrieve(command);
        var replayed = collaboration.retrieve(command);

        assertEquals(retrieved, replayed);
        assertEquals(1, engine.retrieves.get());
        assertEquals(TaskStatus.CANCELED, projections.findTask("tenant-a", FINANCE_TASK_ID)
            .orElseThrow()
            .status());
        List<TaskProjection> pending = projections.findTasks("tenant-a", INSTANCE_ID).stream()
            .filter(task -> task.status() == TaskStatus.PENDING)
            .toList();
        assertEquals(1, pending.size());
        assertEquals("managerApproval", pending.getFirst().taskDefinitionKey());
        assertEquals("manager-1", pending.getFirst().assigneeId());
        assertEquals(1, countAudit("TASK_RETRIEVED"));
    }

    @Test
    void retrieveRejectsParallelDownstreamTasksBeforeClaimingThem() {
        TaskProjection completedManager = new TaskProjection(
            MANAGER_TASK_ID,
            INSTANCE_ID,
            "tenant-a",
            "engine-manager-task-1",
            "managerApproval",
            "Manager approval",
            "manager-1",
            TaskStatus.COMPLETED,
            2,
            NOW,
            NOW.plusSeconds(1),
            NOW.plusSeconds(1)
        );
        TaskProjection financeA = pendingTask(
            FINANCE_TASK_ID,
            "engine-finance-a",
            "financeCountersign",
            "finance-a",
            NOW.plusSeconds(2)
        );
        TaskProjection financeB = pendingTask(
            UUID.fromString("00000000-0000-0000-0000-000000000403"),
            "engine-finance-b",
            "financeCountersign",
            "finance-b",
            NOW.plusSeconds(2)
        );
        createRunningInstance(List.of(completedManager, financeA, financeB));

        var exception = assertThrows(
            ApprovalProjectionStore.ProjectionConflictException.class,
            () -> collaboration.retrieve(new PurchasePaymentCollaborationService.RetrieveCommand(
                context("manager-1", "retrieve-parallel", "retrieve-parallel-key"),
                MANAGER_TASK_ID,
                null
            ))
        );

        assertTrue(exception.getMessage().contains("exactly one"));
        assertEquals(TaskStatus.PENDING, projections.findTask("tenant-a", FINANCE_TASK_ID)
            .orElseThrow()
            .status());
        assertEquals(0, engine.retrieves.get());
    }

    private void saveDefinition() {
        projections.saveDefinition(new PublishedDefinition(
            "tenant-a",
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            ApprovalDslCompiler.COMPILER_VERSION,
            "a".repeat(64),
            "deployment-1",
            "engine-definition-1",
            1,
            "publisher",
            NOW
        ));
    }

    private void createRunningInstance(List<TaskProjection> tasks) {
        projections.createInstance(new InstanceProjection(
            INSTANCE_ID,
            "tenant-a",
            "PO-COLLABORATION-001",
            "engine-instance-1",
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            ApprovalDslCompiler.COMPILER_VERSION,
            "a".repeat(64),
            "initiator-1",
            new BigDecimal("25000.00"),
            "Supplier A",
            "PURCHASE-ORDER-A",
            List.of("attachment-1"),
            new AssigneeSnapshot(
                "manager-1",
                "finance-reviewer",
                List.of("finance-a", "finance-b"),
                Map.of("connectorKey", "test")
            ),
            "b".repeat(64),
            InstanceStatus.RUNNING,
            1,
            NOW,
            NOW
        ), tasks);
    }

    private TaskProjection pendingManagerTask() {
        return pendingTask(
            MANAGER_TASK_ID,
            "engine-manager-task-1",
            "managerApproval",
            "manager-1",
            NOW
        );
    }

    private TaskProjection pendingTask(
        UUID taskId,
        String engineTaskId,
        String taskDefinitionKey,
        String assigneeId,
        Instant createdAt
    ) {
        return new TaskProjection(
            taskId,
            INSTANCE_ID,
            "tenant-a",
            engineTaskId,
            taskDefinitionKey,
            taskDefinitionKey,
            assigneeId,
            TaskStatus.PENDING,
            1,
            createdAt,
            createdAt,
            null
        );
    }

    private ApprovalEngine.TaskSnapshot engineTask(
        String engineTaskId,
        String definitionKey,
        String assigneeId,
        Instant createdAt
    ) {
        return new ApprovalEngine.TaskSnapshot(
            engineTaskId,
            "engine-instance-1",
            definitionKey,
            definitionKey,
            assigneeId,
            createdAt
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

    private int countAudit(String action) {
        return jdbc.queryForObject(
            "select count(*) from ap_audit_event where action = ?",
            Integer.class,
            action
        );
    }

    private static final class FakeApprovalEngine implements ApprovalEngine {

        private final AtomicInteger terminations = new AtomicInteger();
        private final AtomicInteger transfers = new AtomicInteger();
        private final AtomicInteger retrieves = new AtomicInteger();
        private final AtomicInteger sequence = new AtomicInteger(20);
        private final List<TaskSnapshot> tasks = new ArrayList<>();

        @Override
        public DeploymentResult deploy(DeployCommand command) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public StartResult start(StartCommand command) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public List<TaskSnapshot> findActiveTasks(TaskQuery query) {
            return tasks.stream()
                .filter(task -> task.processInstanceId().equals(query.processInstanceId()))
                .filter(task -> query.assigneeId() == null
                    || query.assigneeId().equals(task.assigneeId()))
                .toList();
        }

        @Override
        public TaskResult complete(CompleteTaskCommand command) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public void terminate(TerminateInstanceCommand command) {
            tasks.removeIf(task -> task.processInstanceId().equals(command.processInstanceId()));
            terminations.incrementAndGet();
        }

        @Override
        public TaskSnapshot transfer(TransferTaskCommand command) {
            TaskSnapshot task = tasks.stream()
                .filter(candidate -> candidate.taskId().equals(command.taskId()))
                .findFirst()
                .orElseThrow(() -> new EngineOperationException("TASK_NOT_FOUND", "task not found"));
            if (!Objects.equals(task.assigneeId(), command.currentAssigneeId())) {
                throw new EngineOperationException("TASK_NOT_ASSIGNED_TO_OPERATOR", "wrong operator");
            }
            tasks.remove(task);
            TaskSnapshot transferred = new TaskSnapshot(
                task.taskId(),
                task.processInstanceId(),
                task.taskDefinitionKey(),
                task.name(),
                command.targetAssigneeId(),
                task.createdAt()
            );
            tasks.add(transferred);
            transfers.incrementAndGet();
            return transferred;
        }

        @Override
        public void retrieve(RetrieveTaskCommand command) {
            TaskSnapshot current = tasks.stream()
                .filter(task -> task.taskId().equals(command.currentTaskId()))
                .findFirst()
                .orElseThrow(() -> new EngineOperationException("TASK_NOT_FOUND", "task not found"));
            tasks.remove(current);
            int next = sequence.incrementAndGet();
            String assignee = "managerApproval".equals(command.targetTaskDefinitionKey())
                ? "manager-1"
                : "finance-reviewer";
            tasks.add(new TaskSnapshot(
                "engine-retrieved-task-" + next,
                command.processInstanceId(),
                command.targetTaskDefinitionKey(),
                command.targetTaskDefinitionKey(),
                assignee,
                NOW.plusSeconds(next)
            ));
            retrieves.incrementAndGet();
        }
    }
}
