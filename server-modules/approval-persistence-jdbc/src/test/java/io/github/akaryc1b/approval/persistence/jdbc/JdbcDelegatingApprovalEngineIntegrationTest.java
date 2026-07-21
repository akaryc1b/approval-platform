package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.DelegatingApprovalEngine;
import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore.DelegationRule;
import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore.DelegationScope;
import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore.DelegationStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalTaskDelegationAssignmentStore.AssignmentStatus;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import io.github.akaryc1b.approval.engine.ApprovalEngine;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcDelegatingApprovalEngineIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-20T05:00:00Z");
    private static final String TENANT_ID = "tenant-a";
    private static final String ENGINE_INSTANCE_ID = "engine-instance-delegation-1";
    private static final String ENGINE_TASK_ID = "engine-task-delegation-1";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_delegating_engine_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private JdbcApprovalDelegationStore rules;
    private JdbcApprovalTaskDelegationAssignmentStore assignments;
    private FakeApprovalEngine rawEngine;
    private DelegatingApprovalEngine engine;

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
                ap_task_delegation_assignment,
                ap_delegation_rule,
                ap_audit_event,
                ap_approval_task,
                ap_approval_instance,
                ap_definition_version,
                ap_command_idempotency
            cascade
            """);
        rules = new JdbcApprovalDelegationStore(dataSource);
        assignments = new JdbcApprovalTaskDelegationAssignmentStore(dataSource);
        rawEngine = new FakeApprovalEngine();
        engine = new DelegatingApprovalEngine(
            rawEngine,
            rules,
            assignments,
            new JdbcAuditEventSink(
                dataSource,
                new ObjectMapper().findAndRegisterModules()
            ),
            Clock.fixed(NOW, ZoneOffset.UTC),
            UUID::randomUUID
        );
    }

    @Test
    void appliesEffectiveDelegationOnceAndCompletesImmutableResponsibilityEvidence() {
        DelegationRule rule = saveRule(
            "manager-1",
            "delegate-1",
            DelegationScope.DEFINITION,
            PurchasePaymentTemplate.DEFINITION_KEY
        );
        rawEngine.tasks.add(task(
            ENGINE_TASK_ID,
            ENGINE_INSTANCE_ID,
            "managerApproval",
            "manager-1"
        ));
        engine.start(new ApprovalEngine.StartCommand(
            TENANT_ID,
            PurchasePaymentTemplate.DEFINITION_KEY,
            "BUSINESS-DELEGATION-1",
            "initiator-1",
            Map.of()
        ));

        List<ApprovalEngine.TaskSnapshot> delegated = engine.findActiveTasks(query());
        List<ApprovalEngine.TaskSnapshot> replayed = engine.findActiveTasks(query());

        assertEquals("delegate-1", delegated.getFirst().assigneeId());
        assertEquals(delegated, replayed);
        assertEquals(1, rawEngine.transfers.get());
        var assignment = assignments.findByEngineTask(TENANT_ID, ENGINE_TASK_ID)
            .orElseThrow();
        assertEquals("manager-1", assignment.principalAssigneeId());
        assertEquals("delegate-1", assignment.delegateAssigneeId());
        assertEquals(rule.ruleId(), assignment.delegationRuleId());
        assertEquals(AssignmentStatus.ACTIVE, assignment.status());
        assertEquals(1, countAudit("TASK_DELEGATED"));

        engine.complete(new ApprovalEngine.CompleteTaskCommand(
            TENANT_ID,
            ENGINE_TASK_ID,
            "delegate-1",
            Map.of("decision", "APPROVED")
        ));

        var completed = assignments.findByEngineTask(TENANT_ID, ENGINE_TASK_ID)
            .orElseThrow();
        assertEquals(AssignmentStatus.COMPLETED, completed.status());
        assertEquals("delegate-1", completed.completedBy());
        assertEquals(NOW, completed.completedAt());
    }

    @Test
    void manualTransferSupersedesAutomaticDelegationEvidence() {
        saveRule("manager-1", "delegate-1", DelegationScope.ALL, null);
        rawEngine.tasks.add(task(
            ENGINE_TASK_ID,
            ENGINE_INSTANCE_ID,
            "managerApproval",
            "manager-1"
        ));
        engine.start(new ApprovalEngine.StartCommand(
            TENANT_ID,
            PurchasePaymentTemplate.DEFINITION_KEY,
            "BUSINESS-DELEGATION-2",
            "initiator-1",
            Map.of()
        ));
        engine.findActiveTasks(query());

        ApprovalEngine.TaskSnapshot transferred = engine.transfer(
            new ApprovalEngine.TransferTaskCommand(
                TENANT_ID,
                ENGINE_TASK_ID,
                "delegate-1",
                "finance-reviewer"
            )
        );

        assertEquals("finance-reviewer", transferred.assigneeId());
        var superseded = assignments.findByEngineTask(TENANT_ID, ENGINE_TASK_ID)
            .orElseThrow();
        assertEquals(AssignmentStatus.SUPERSEDED, superseded.status());
        assertEquals("finance-reviewer", superseded.supersededAssigneeId());
    }

    @Test
    void resolvesDefinitionFromPlatformProjectionAfterRestartAndSkipsRevisionTasks() {
        saveRule(
            "manager-1",
            "delegate-1",
            DelegationScope.DEFINITION,
            PurchasePaymentTemplate.DEFINITION_KEY
        );
        saveInstanceProjection();
        rawEngine.tasks.add(task(
            ENGINE_TASK_ID,
            ENGINE_INSTANCE_ID,
            "managerApproval",
            "manager-1"
        ));
        DelegatingApprovalEngine restarted = new DelegatingApprovalEngine(
            rawEngine,
            rules,
            assignments,
            new JdbcAuditEventSink(
                dataSource,
                new ObjectMapper().findAndRegisterModules()
            ),
            Clock.fixed(NOW, ZoneOffset.UTC),
            UUID::randomUUID
        );

        assertEquals(
            "delegate-1",
            restarted.findActiveTasks(query()).getFirst().assigneeId()
        );

        rawEngine.tasks.clear();
        rawEngine.tasks.add(task(
            "engine-revision-task",
            ENGINE_INSTANCE_ID,
            PurchasePaymentTemplate.REVISION_TASK_KEY,
            "initiator-1"
        ));
        List<ApprovalEngine.TaskSnapshot> revision = restarted.findActiveTasks(query());
        assertEquals("initiator-1", revision.getFirst().assigneeId());
        assertTrue(assignments.findByEngineTask(TENANT_ID, "engine-revision-task").isEmpty());
    }

    private DelegationRule saveRule(
        String principalId,
        String delegateId,
        DelegationScope scope,
        String definitionKey
    ) {
        return rules.create(new DelegationRule(
            UUID.randomUUID(),
            TENANT_ID,
            principalId,
            delegateId,
            scope,
            definitionKey,
            NOW.minusSeconds(60),
            NOW.plusSeconds(86_400),
            DelegationStatus.ACTIVE,
            "temporary proxy",
            principalId,
            NOW.minusSeconds(120),
            null,
            null,
            null,
            1
        ));
    }

    private void saveInstanceProjection() {
        JdbcApprovalProjectionStore projections = new JdbcApprovalProjectionStore(
            dataSource,
            new ObjectMapper().findAndRegisterModules()
        );
        projections.saveDefinition(new PublishedDefinition(
            TENANT_ID,
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
        projections.createInstance(new InstanceProjection(
            UUID.randomUUID(),
            TENANT_ID,
            "BUSINESS-DELEGATION-RESTART",
            ENGINE_INSTANCE_ID,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            ApprovalDslCompiler.COMPILER_VERSION,
            "a".repeat(64),
            "initiator-1",
            new BigDecimal("1000.00"),
            "Supplier A",
            "PO-1",
            List.of("attachment-1"),
            new AssigneeSnapshot(
                "manager-1",
                "finance-reviewer",
                List.of("finance-a", "finance-b"),
                Map.of()
            ),
            "b".repeat(64),
            InstanceStatus.RUNNING,
            1,
            NOW,
            NOW
        ), List.of());
    }

    private long countAudit(String action) {
        Long count = jdbc.queryForObject(
            "select count(*) from ap_audit_event where action = ?",
            Long.class,
            action
        );
        return count == null ? 0 : count;
    }

    private ApprovalEngine.TaskQuery query() {
        return new ApprovalEngine.TaskQuery(TENANT_ID, ENGINE_INSTANCE_ID, null);
    }

    private ApprovalEngine.TaskSnapshot task(
        String taskId,
        String instanceId,
        String taskDefinitionKey,
        String assigneeId
    ) {
        return new ApprovalEngine.TaskSnapshot(
            taskId,
            instanceId,
            taskDefinitionKey,
            taskDefinitionKey,
            assigneeId,
            NOW
        );
    }

    private static final class FakeApprovalEngine implements ApprovalEngine {

        private final List<TaskSnapshot> tasks = new ArrayList<>();
        private final AtomicInteger transfers = new AtomicInteger();

        @Override
        public DeploymentResult deploy(DeployCommand command) {
            return new DeploymentResult("deployment", "definition", command.definitionKey(), 1);
        }

        @Override
        public StartResult start(StartCommand command) {
            return new StartResult(ENGINE_INSTANCE_ID);
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
            TaskSnapshot current = find(command.taskId());
            if (!command.operatorId().equals(current.assigneeId())) {
                throw new EngineOperationException(
                    "TASK_ASSIGNEE_MISMATCH",
                    "operator does not own the task"
                );
            }
            tasks.remove(current);
            return new TaskResult(command.taskId(), "COMPLETED");
        }

        @Override
        public void terminate(TerminateInstanceCommand command) {
            tasks.removeIf(task -> task.processInstanceId().equals(command.processInstanceId()));
        }

        @Override
        public TaskSnapshot transfer(TransferTaskCommand command) {
            TaskSnapshot current = find(command.taskId());
            if (!command.currentAssigneeId().equals(current.assigneeId())) {
                throw new EngineOperationException(
                    "TASK_ASSIGNEE_MISMATCH",
                    "current assignee does not own the task"
                );
            }
            TaskSnapshot transferred = new TaskSnapshot(
                current.taskId(),
                current.processInstanceId(),
                current.taskDefinitionKey(),
                current.name(),
                command.targetAssigneeId(),
                current.createdAt()
            );
            tasks.set(tasks.indexOf(current), transferred);
            transfers.incrementAndGet();
            return transferred;
        }

        @Override
        public void retrieve(RetrieveTaskCommand command) {
            tasks.removeIf(task -> task.taskId().equals(command.currentTaskId()));
        }

        private TaskSnapshot find(String taskId) {
            return tasks.stream()
                .filter(task -> task.taskId().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new EngineOperationException(
                    "TASK_NOT_FOUND",
                    "task was not found"
                ));
        }
    }
}
