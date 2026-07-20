package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalHandoverService;
import io.github.akaryc1b.approval.application.DelegatingApprovalEngine;
import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore;
import io.github.akaryc1b.approval.application.port.ApprovalHandoverStore;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityCandidate;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityLookup;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityReference;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentitySearch;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalHandoverIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-20T04:30:00Z");
    private static final IdentityReference PRINCIPAL = new IdentityReference(
        "generic",
        "user",
        "departing-user"
    );
    private static final IdentityReference SUCCESSOR = new IdentityReference(
        "generic",
        "user",
        "successor-user"
    );

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_handover_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private JdbcApprovalProjectionStore projections;
    private JdbcApprovalHandoverStore handovers;
    private JdbcApprovalDelegationStore delegations;
    private JdbcApprovalTaskDelegationAssignmentStore delegationAssignments;
    private FakeApprovalEngine engine;
    private ApprovalHandoverService service;

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
                ap_task_handover_assignment,
                ap_principal_handover,
                ap_task_delegation_assignment,
                ap_delegation_rule,
                ap_approval_task,
                ap_approval_instance,
                ap_audit_event,
                ap_command_idempotency
            cascade
            """);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        projections = new JdbcApprovalProjectionStore(dataSource, objectMapper);
        handovers = new JdbcApprovalHandoverStore(dataSource);
        delegations = new JdbcApprovalDelegationStore(dataSource);
        delegationAssignments = new JdbcApprovalTaskDelegationAssignmentStore(dataSource);
        engine = new FakeApprovalEngine();
        service = new ApprovalHandoverService(
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                new JdbcTransactionManager(dataSource),
                clock
            ),
            new StubIdentityDirectory(),
            handovers,
            projections,
            engine,
            new JdbcAuditEventSink(dataSource, objectMapper),
            clock,
            UUID::randomUUID
        );
    }

    @Test
    void createTransfersExistingTasksAndReplaysIdempotently() {
        TaskProjection pending = createPendingTask(
            "departing-user",
            "managerApproval",
            "engine-task-existing",
            "engine-instance-existing"
        );
        var command = createCommand("handover-existing", "handover-existing-key");

        var created = service.create(command);
        var replayed = service.create(command);

        assertEquals(created, replayed);
        assertEquals(1, created.transferredTaskCount());
        assertEquals(List.of(pending.taskId()), created.transferredTaskIds());
        assertEquals(
            "successor-user",
            projections.findTask("tenant-a", pending.taskId()).orElseThrow().assigneeId()
        );
        assertEquals("successor-user", engine.task("engine-task-existing").assigneeId());
        assertEquals(1, engine.transferCount());
        var evidence = handovers.findAssignmentByEngineTask(
            "tenant-a",
            "engine-task-existing"
        ).orElseThrow();
        assertEquals("departing-user", evidence.principalAssigneeId());
        assertEquals("successor-user", evidence.successorAssigneeId());
        assertEquals(ApprovalHandoverStore.AssignmentStatus.ACTIVE, evidence.status());
        assertEquals(1, countAudit("EMPLOYEE_HANDOVER_CREATED"));
        assertEquals(1, countAudit("TASK_HANDOVER_ASSIGNED"));
    }

    @Test
    void exactIdentityAndActiveSuccessorAreRequired() {
        assertThrows(
            ApprovalIdentityDirectory.IdentityResolutionException.class,
            () -> service.create(new ApprovalHandoverService.CreateHandoverCommand(
                context("identity-missing", "identity-missing-key"),
                "generic-rest",
                PRINCIPAL,
                new IdentityReference("generic", "user", "missing-user"),
                "departure"
            ))
        );

        assertTrue(handovers.findByPrincipal(
            "tenant-a",
            "departing-user",
            true
        ).isEmpty());
    }

    @Test
    void futureRevisionTasksAreAssignedAndManualTransferSupersedesEvidence() {
        service.create(createCommand("handover-future", "handover-future-key"));
        createInstanceWithoutTasks("engine-instance-future");
        engine.addTask(new ApprovalEngine.TaskSnapshot(
            "engine-task-future",
            "engine-instance-future",
            PurchasePaymentTemplate.REVISION_TASK_KEY,
            "Initiator revision",
            "departing-user",
            NOW
        ));
        DelegatingApprovalEngine decorated = new DelegatingApprovalEngine(
            engine,
            delegations,
            delegationAssignments,
            handovers,
            event -> {
            },
            Clock.fixed(NOW, ZoneOffset.UTC),
            UUID::randomUUID
        );

        List<ApprovalEngine.TaskSnapshot> resolved = decorated.findActiveTasks(
            new ApprovalEngine.TaskQuery(
                "tenant-a",
                "engine-instance-future",
                null
            )
        );

        assertEquals("successor-user", resolved.getFirst().assigneeId());
        var evidence = handovers.findAssignmentByEngineTask(
            "tenant-a",
            "engine-task-future"
        ).orElseThrow();
        assertEquals(ApprovalHandoverStore.AssignmentStatus.ACTIVE, evidence.status());

        decorated.transfer(new ApprovalEngine.TransferTaskCommand(
            "tenant-a",
            "engine-task-future",
            "successor-user",
            "manual-target"
        ));

        var superseded = handovers.findAssignmentByEngineTask(
            "tenant-a",
            "engine-task-future"
        ).orElseThrow();
        assertEquals(ApprovalHandoverStore.AssignmentStatus.SUPERSEDED, superseded.status());
        assertEquals("manual-target", superseded.supersededAssigneeId());
        assertFalse(superseded.supersededAt().isAfter(NOW));
    }

    private TaskProjection createPendingTask(
        String assigneeId,
        String taskDefinitionKey,
        String engineTaskId,
        String engineInstanceId
    ) {
        UUID instanceId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskProjection task = new TaskProjection(
            taskId,
            instanceId,
            "tenant-a",
            engineTaskId,
            taskDefinitionKey,
            "Approval task",
            assigneeId,
            TaskStatus.PENDING,
            1,
            NOW,
            NOW,
            null
        );
        projections.createInstance(instance(instanceId, engineInstanceId), List.of(task));
        engine.addTask(new ApprovalEngine.TaskSnapshot(
            engineTaskId,
            engineInstanceId,
            taskDefinitionKey,
            "Approval task",
            assigneeId,
            NOW
        ));
        return task;
    }

    private void createInstanceWithoutTasks(String engineInstanceId) {
        projections.createInstance(
            instance(UUID.randomUUID(), engineInstanceId),
            List.of()
        );
    }

    private InstanceProjection instance(UUID instanceId, String engineInstanceId) {
        return new InstanceProjection(
            instanceId,
            "tenant-a",
            "business-" + instanceId,
            engineInstanceId,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.FORM_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            "compiler-test",
            "a".repeat(64),
            "initiator-user",
            new BigDecimal("100.00"),
            "Supplier",
            "PO-1",
            List.of("attachment-1"),
            new AssigneeSnapshot(
                "departing-user",
                "finance-reviewer",
                List.of("finance-approver"),
                Map.of("connectorKey", "generic-rest")
            ),
            "request-hash-" + instanceId,
            InstanceStatus.RUNNING,
            1,
            NOW,
            NOW
        );
    }

    private ApprovalHandoverService.CreateHandoverCommand createCommand(
        String requestId,
        String idempotencyKey
    ) {
        return new ApprovalHandoverService.CreateHandoverCommand(
            context(requestId, idempotencyKey),
            "generic-rest",
            PRINCIPAL,
            SUCCESSOR,
            "employee departure"
        );
    }

    private RequestContext context(String requestId, String idempotencyKey) {
        return new RequestContext(
            "tenant-a",
            "handover-admin",
            requestId,
            idempotencyKey,
            "trace-" + requestId
        );
    }

    private long countAudit(String action) {
        Long count = jdbc.queryForObject(
            "select count(*) from ap_audit_event where action = ?",
            Long.class,
            action
        );
        return count == null ? 0 : count;
    }

    private static IdentityCandidate candidate(
        IdentityReference reference,
        boolean active
    ) {
        return new IdentityCandidate(
            reference,
            reference.value(),
            reference.value(),
            reference.value(),
            null,
            null,
            active,
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static final class StubIdentityDirectory implements ApprovalIdentityDirectory {

        private final Map<String, IdentityCandidate> candidates = Map.of(
            PRINCIPAL.canonicalValue(),
            candidate(PRINCIPAL, false),
            SUCCESSOR.canonicalValue(),
            candidate(SUCCESSOR, true)
        );

        @Override
        public List<IdentityCandidate> search(IdentitySearch search) {
            return candidates.values().stream()
                .filter(IdentityCandidate::active)
                .toList();
        }

        @Override
        public IdentityCandidate requireUser(IdentityLookup lookup) {
            IdentityCandidate candidate = candidates.get(lookup.reference().canonicalValue());
            if (candidate == null) {
                throw new IdentityResolutionException(
                    "IDENTITY_NOT_FOUND",
                    "identity was not found",
                    false
                );
            }
            if (lookup.requireActive() && !candidate.active()) {
                throw new IdentityResolutionException(
                    "IDENTITY_INACTIVE",
                    "identity is inactive",
                    false
                );
            }
            return candidate;
        }
    }

    private static final class FakeApprovalEngine implements ApprovalEngine {

        private final Map<String, TaskSnapshot> tasks = new LinkedHashMap<>();
        private int transferCount;

        void addTask(TaskSnapshot task) {
            tasks.put(task.taskId(), task);
        }

        TaskSnapshot task(String taskId) {
            return tasks.get(taskId);
        }

        int transferCount() {
            return transferCount;
        }

        @Override
        public DeploymentResult deploy(DeployCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StartResult start(StartCommand command) {
            return new StartResult("started-instance");
        }

        @Override
        public List<TaskSnapshot> findActiveTasks(TaskQuery query) {
            return tasks.values().stream()
                .filter(task -> task.processInstanceId().equals(query.processInstanceId()))
                .filter(task -> query.assigneeId() == null
                    || query.assigneeId().equals(task.assigneeId()))
                .toList();
        }

        @Override
        public TaskResult complete(CompleteTaskCommand command) {
            tasks.remove(command.taskId());
            return new TaskResult(command.taskId(), "COMPLETED");
        }

        @Override
        public TaskSnapshot transfer(TransferTaskCommand command) {
            TaskSnapshot current = Optional.ofNullable(tasks.get(command.taskId()))
                .orElseThrow();
            if (!current.assigneeId().equals(command.currentAssigneeId())) {
                throw new IllegalStateException("current assignee changed");
            }
            TaskSnapshot transferred = new TaskSnapshot(
                current.taskId(),
                current.processInstanceId(),
                current.taskDefinitionKey(),
                current.name(),
                command.targetAssigneeId(),
                current.createdAt()
            );
            tasks.put(transferred.taskId(), transferred);
            transferCount++;
            return transferred;
        }
    }
}
