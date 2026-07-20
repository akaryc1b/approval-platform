package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalTaskCollaborationService;
import io.github.akaryc1b.approval.application.ApprovalTaskOutcomeContext;
import io.github.akaryc1b.approval.application.CollaborationAwareApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityCandidate;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityLookup;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityReference;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentitySearch;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationMode;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationStatus;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.ParticipantDecision;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.ParticipantStatus;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCompletionGuard.TaskOutcome;
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
class JdbcApprovalTaskCollaborationIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-20T06:00:00Z");
    private static final IdentityReference USER_ONE = identity("collaborator-one");
    private static final IdentityReference USER_TWO = identity("collaborator-two");
    private static final IdentityReference USER_THREE = identity("collaborator-three");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_task_collaboration_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcApprovalProjectionStore projections;
    private CollaborationAwareApprovalProjectionStore guarded;
    private JdbcApprovalTaskCollaborationStore collaborations;
    private ApprovalTaskCollaborationService service;
    private ApprovalTaskOutcomeContext outcomes;

    @BeforeAll
    static void migrateDatabase() {
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .load().migrate();
    }

    @BeforeEach
    void setUp() {
        new JdbcTemplate(dataSource).execute("""
            truncate table ap_task_collaboration_participant,
                ap_task_collaboration_policy, ap_approval_task,
                ap_approval_instance, ap_definition_version,
                ap_audit_event, ap_command_idempotency cascade
            """);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        projections = new JdbcApprovalProjectionStore(dataSource, objectMapper);
        collaborations = new JdbcApprovalTaskCollaborationStore(dataSource);
        outcomes = new ApprovalTaskOutcomeContext();
        guarded = new CollaborationAwareApprovalProjectionStore(
            projections,
            collaborations,
            outcomes
        );
        service = new ApprovalTaskCollaborationService(
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                new JdbcTransactionManager(dataSource),
                clock
            ),
            new StubIdentityDirectory(),
            collaborations,
            guarded,
            new JdbcAuditEventSink(dataSource, objectMapper),
            clock,
            UUID::randomUUID
        );
        projections.saveDefinition(definition());
    }

    @Test
    void allModeBlocksParentUntilEveryParticipantApproves() {
        TaskProjection task = task("owner-all");
        var policy = service.create(create(task, "all", CollaborationMode.ALL, USER_ONE, USER_TWO));

        outcomes.set(TaskOutcome.APPROVED);
        assertThrows(
            ApprovalTaskCollaborationStore.CollaborationConflictException.class,
            () -> claim(task)
        );
        outcomes.clear();

        policy = decide(policy, "collaborator-one", ParticipantDecision.APPROVED);
        assertEquals(CollaborationStatus.ACTIVE, policy.status());
        policy = decide(policy, "collaborator-two", ParticipantDecision.APPROVED);
        assertEquals(CollaborationStatus.SATISFIED, policy.status());

        outcomes.set(TaskOutcome.APPROVED);
        assertEquals(TaskStatus.COMPLETING, claim(task).status());
        outcomes.clear();
    }

    @Test
    void anyApprovalCancelsRemainingParticipantsAndRemoveSignStopsAfterDecision() {
        TaskProjection task = task("owner-any");
        var policy = service.create(create(
            task,
            "any",
            CollaborationMode.ANY,
            USER_ONE,
            USER_TWO,
            USER_THREE
        ));
        var removed = participant(policy, "collaborator-three");
        policy = service.remove(new ApprovalTaskCollaborationService.RemoveParticipantCommand(
            context("owner-any", "remove"),
            removed.participantId(),
            "not required"
        ));
        assertEquals(
            ParticipantStatus.REMOVED,
            participant(policy, "collaborator-three").status()
        );

        policy = decide(policy, "collaborator-one", ParticipantDecision.APPROVED);
        assertEquals(CollaborationStatus.SATISFIED, policy.status());
        assertEquals(
            ParticipantStatus.CANCELED,
            participant(policy, "collaborator-two").status()
        );
        assertTrue(service.findPending("tenant-a", "collaborator-two", 20).isEmpty());
        var finalPolicy = policy;
        assertThrows(
            ApprovalTaskCollaborationStore.CollaborationConflictException.class,
            () -> service.remove(new ApprovalTaskCollaborationService.RemoveParticipantCommand(
                context("owner-any", "late-remove"),
                participant(finalPolicy, "collaborator-two").participantId(),
                "too late"
            ))
        );
    }

    @Test
    void participantRejectionForcesParentTaskRejection() {
        TaskProjection task = task("owner-reject");
        var policy = service.create(create(
            task,
            "reject",
            CollaborationMode.ALL,
            USER_ONE,
            USER_TWO
        ));
        policy = decide(policy, "collaborator-one", ParticipantDecision.REJECTED);
        assertEquals(CollaborationStatus.REJECTED, policy.status());

        outcomes.set(TaskOutcome.APPROVED);
        assertThrows(
            ApprovalTaskCollaborationStore.CollaborationConflictException.class,
            () -> claim(task)
        );
        outcomes.clear();

        outcomes.set(TaskOutcome.REJECTED);
        assertEquals(TaskStatus.COMPLETING, claim(task).status());
        outcomes.clear();
    }

    private ApprovalTaskCollaborationStore.TaskCollaboration decide(
        ApprovalTaskCollaborationStore.TaskCollaboration policy,
        String userId,
        ParticipantDecision decision
    ) {
        return service.decide(new ApprovalTaskCollaborationService.DecideParticipantCommand(
            context(userId, "decide-" + userId),
            participant(policy, userId).participantId(),
            decision,
            decision.name().toLowerCase()
        ));
    }

    private ApprovalTaskCollaborationService.CreateCollaborationCommand create(
        TaskProjection task,
        String requestId,
        CollaborationMode mode,
        IdentityReference... participants
    ) {
        return new ApprovalTaskCollaborationService.CreateCollaborationCommand(
            context(task.assigneeId(), "create-" + requestId),
            task.taskId(),
            "generic-rest",
            mode,
            List.of(participants),
            "additional review required"
        );
    }

    private TaskProjection claim(TaskProjection task) {
        return guarded.claimPendingTask(
            task.tenantId(),
            task.taskId(),
            task.assigneeId(),
            NOW
        );
    }

    private TaskProjection task(String owner) {
        UUID instanceId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskProjection task = new TaskProjection(
            taskId,
            instanceId,
            "tenant-a",
            "engine-task-" + taskId,
            "managerApproval",
            "Manager approval",
            owner,
            TaskStatus.PENDING,
            1,
            NOW,
            NOW,
            null
        );
        projections.createInstance(instance(instanceId, owner), List.of(task));
        return task;
    }

    private InstanceProjection instance(UUID instanceId, String owner) {
        return new InstanceProjection(
            instanceId,
            "tenant-a",
            "business-" + instanceId,
            "engine-instance-" + instanceId,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            "compiler-test",
            "a".repeat(64),
            "initiator-user",
            new BigDecimal("100.00"),
            "Supplier",
            "PO-1",
            List.of("attachment-1"),
            new AssigneeSnapshot(
                owner,
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

    private static PublishedDefinition definition() {
        return new PublishedDefinition(
            "tenant-a",
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            "compiler-test",
            "a".repeat(64),
            "deployment-test",
            "definition-test",
            1,
            "publisher",
            NOW
        );
    }

    private static RequestContext context(String operatorId, String operationId) {
        return new RequestContext(
            "tenant-a",
            operatorId,
            operationId,
            operationId + "-key",
            "trace-" + operationId
        );
    }

    private static ApprovalTaskCollaborationStore.CollaborationParticipant participant(
        ApprovalTaskCollaborationStore.TaskCollaboration policy,
        String userId
    ) {
        return policy.participants().stream()
            .filter(item -> item.participantUserId().equals(userId))
            .findFirst()
            .orElseThrow();
    }

    private static IdentityReference identity(String value) {
        return new IdentityReference("generic", "user", value);
    }

    private static IdentityCandidate candidate(IdentityReference reference) {
        return new IdentityCandidate(
            reference,
            reference.value(),
            reference.value(),
            reference.value(),
            null,
            null,
            true,
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static final class StubIdentityDirectory implements ApprovalIdentityDirectory {

        private final Map<String, IdentityCandidate> candidates = Map.of(
            USER_ONE.canonicalValue(), candidate(USER_ONE),
            USER_TWO.canonicalValue(), candidate(USER_TWO),
            USER_THREE.canonicalValue(), candidate(USER_THREE)
        );

        @Override
        public List<IdentityCandidate> search(IdentitySearch search) {
            return candidates.values().stream()
                .filter(item -> !search.activeOnly() || item.active())
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
            return candidate;
        }
    }
}
