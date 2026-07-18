package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.PurchasePaymentTaskActionService;
import io.github.akaryc1b.approval.application.port.ApprovalBusinessEventOutbox;
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
class JdbcPurchasePaymentTaskActionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-18T03:00:00Z");
    private static final UUID INSTANCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID MANAGER_TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_task_action_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private JdbcApprovalProjectionStore projections;
    private FakeApprovalEngine engine;
    private PurchasePaymentTaskActionService actions;

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
        actions = new PurchasePaymentTaskActionService(
            engine,
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                new JdbcTransactionManager(dataSource),
                clock
            ),
            projections,
            new JdbcAuditEventSink(dataSource, objectMapper),
            ApprovalBusinessEventOutbox.noOp(),
            clock,
            UUID::randomUUID
        );
        createRunningInstance();
    }

    @Test
    void rejectAndResubmitAreDurableIdempotentAndAudited() {
        var rejectCommand = new PurchasePaymentTaskActionService.TaskActionCommand(
            context("manager-1", "reject-request", "reject-key"),
            MANAGER_TASK_ID,
            "补充发票后重新提交"
        );

        var rejected = actions.reject(rejectCommand);
        var replayedReject = actions.reject(rejectCommand);

        assertEquals(rejected, replayedReject);
        assertEquals(1, engine.completions.get());
        assertEquals(PurchasePaymentTemplate.REVISION_TASK_KEY, rejected.activeTasks().getFirst().taskDefinitionKey());
        assertEquals("initiator-1", rejected.activeTasks().getFirst().assigneeId());
        assertEquals(1, countTasks(TaskStatus.COMPLETED));
        assertEquals(1, countTasks(TaskStatus.PENDING));
        assertEquals(1, countAudit("TASK_REJECTED"));
        assertEquals("补充发票后重新提交", jdbc.queryForObject(
            "select attributes_json ->> 'comment' from ap_audit_event where action = 'TASK_REJECTED'",
            String.class
        ));

        UUID revisionTaskId = rejected.activeTasks().getFirst().taskId();
        var resubmitCommand = new PurchasePaymentTaskActionService.TaskActionCommand(
            context("initiator-1", "resubmit-request", "resubmit-key"),
            revisionTaskId,
            "材料已补充"
        );

        var resubmitted = actions.resubmit(resubmitCommand);
        var replayedResubmit = actions.resubmit(resubmitCommand);

        assertEquals(resubmitted, replayedResubmit);
        assertEquals(2, engine.completions.get());
        assertEquals("managerApproval", resubmitted.activeTasks().getFirst().taskDefinitionKey());
        assertEquals("manager-1", resubmitted.activeTasks().getFirst().assigneeId());
        assertEquals(2, countTasks(TaskStatus.COMPLETED));
        assertEquals(1, countTasks(TaskStatus.PENDING));
        assertEquals(1, countAudit("TASK_RESUBMITTED"));
    }

    @Test
    void revisionTaskCannotBeApprovedAndFailedActionRollsBackClaim() {
        var rejected = actions.reject(new PurchasePaymentTaskActionService.TaskActionCommand(
            context("manager-1", "reject-request-2", "reject-key-2"),
            MANAGER_TASK_ID,
            "请修改"
        ));
        UUID revisionTaskId = rejected.activeTasks().getFirst().taskId();

        var exception = assertThrows(
            ApprovalProjectionStore.ProjectionConflictException.class,
            () -> actions.approve(new PurchasePaymentTaskActionService.TaskActionCommand(
                context("initiator-1", "approve-revision", "approve-revision-key"),
                revisionTaskId,
                "incorrect action"
            ))
        );

        assertTrue(exception.getMessage().contains("must be resubmitted"));
        TaskProjection revision = projections.findTasks("tenant-a", INSTANCE_ID).stream()
            .filter(task -> task.taskId().equals(revisionTaskId))
            .findFirst()
            .orElseThrow();
        assertEquals(TaskStatus.PENDING, revision.status());
        assertEquals(1, engine.completions.get());
    }

    @Test
    void rejectionRequiresAReasonBeforeClaimingTheTask() {
        assertThrows(
            IllegalArgumentException.class,
            () -> actions.reject(new PurchasePaymentTaskActionService.TaskActionCommand(
                context("manager-1", "reject-empty", "reject-empty-key"),
                MANAGER_TASK_ID,
                " "
            ))
        );

        TaskProjection manager = projections.findTasks("tenant-a", INSTANCE_ID).getFirst();
        assertEquals(TaskStatus.PENDING, manager.status());
        assertEquals(0, engine.completions.get());
    }

    private void createRunningInstance() {
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
        InstanceProjection instance = new InstanceProjection(
            INSTANCE_ID,
            "tenant-a",
            "PO-ACTION-001",
            "engine-instance-1",
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            ApprovalDslCompiler.COMPILER_VERSION,
            "a".repeat(64),
            "initiator-1",
            new BigDecimal("5000.00"),
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
        );
        TaskProjection managerTask = new TaskProjection(
            MANAGER_TASK_ID,
            INSTANCE_ID,
            "tenant-a",
            "engine-manager-task-1",
            "managerApproval",
            "Manager approval",
            "manager-1",
            TaskStatus.PENDING,
            1,
            NOW,
            NOW,
            null
        );
        projections.createInstance(instance, List.of(managerTask));
        engine.tasks.add(new ApprovalEngine.TaskSnapshot(
            "engine-manager-task-1",
            "engine-instance-1",
            "managerApproval",
            "Manager approval",
            "manager-1",
            NOW
        ));
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

    private int countTasks(TaskStatus status) {
        return jdbc.queryForObject(
            "select count(*) from ap_approval_task where status = ?",
            Integer.class,
            status.name()
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

        private final AtomicInteger completions = new AtomicInteger();
        private final AtomicInteger sequence = new AtomicInteger(10);
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
            TaskSnapshot task = tasks.stream()
                .filter(candidate -> candidate.taskId().equals(command.taskId()))
                .findFirst()
                .orElseThrow(() -> new EngineOperationException("TASK_NOT_FOUND", "task not found"));
            if (!Objects.equals(task.assigneeId(), command.operatorId())) {
                throw new EngineOperationException(
                    "TASK_NOT_ASSIGNED_TO_OPERATOR",
                    "wrong operator"
                );
            }
            tasks.remove(task);
            String decision = Objects.toString(
                command.variables().get(ApprovalDslCompiler.DECISION_VARIABLE)
            );
            int next = sequence.incrementAndGet();
            if ("REJECTED".equals(decision)) {
                tasks.add(new TaskSnapshot(
                    "engine-revision-task-" + next,
                    task.processInstanceId(),
                    PurchasePaymentTemplate.REVISION_TASK_KEY,
                    "Initiator revision",
                    "initiator-1",
                    NOW.plusSeconds(next)
                ));
            } else if ("RESUBMITTED".equals(decision)) {
                tasks.add(new TaskSnapshot(
                    "engine-manager-task-" + next,
                    task.processInstanceId(),
                    "managerApproval",
                    "Manager approval",
                    "manager-1",
                    NOW.plusSeconds(next)
                ));
            }
            completions.incrementAndGet();
            return new TaskResult(command.taskId(), "COMPLETED");
        }
    }
}
