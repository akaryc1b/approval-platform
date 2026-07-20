package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalTaskCollaborationService;
import io.github.akaryc1b.approval.application.ApprovalTaskCollaborationService.ParticipantSpec;
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
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalTaskCollaborationIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-20T06:00:00Z");
    private static final IdentityReference USER_ONE = identity("collaborator-one");
    private static final IdentityReference USER_TWO = identity("collaborator-two");
    private static final IdentityReference USER_THREE = identity("collaborator-three");
    private static final IdentityReference USER_FOUR = identity("collaborator-four");

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
    private JdbcTransactionManager transactionManager;
    private JdbcTemplate jdbc;

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
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
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
        transactionManager = new JdbcTransactionManager(dataSource);
        service = new ApprovalTaskCollaborationService(
            new JdbcIdempotencyGuard(
                dataSource,
                objectMapper,
                transactionManager,
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
        var policy = service.create(create(
            task,
            "all",
            CollaborationMode.ALL,
            null,
            null,
            spec(USER_ONE),
            spec(USER_TWO)
        ));

        assertParentApprovalBlocked(task);
        policy = decide(policy, "collaborator-one", ParticipantDecision.APPROVED);
        assertEquals(CollaborationStatus.ACTIVE, policy.status());
        policy = decide(policy, "collaborator-two", ParticipantDecision.APPROVED);
        assertEquals(CollaborationStatus.SATISFIED, policy.status());
        assertEquals(2, policy.progress().approvedCount());

        outcomes.set(TaskOutcome.APPROVED);
        assertEquals(TaskStatus.COMPLETING, claim(task).status());
        outcomes.clear();
    }

    @Test
    void anyRejectionLeavesPolicyActiveUntilAnotherParticipantApproves() {
        TaskProjection task = task("owner-any-active");
        var policy = service.create(create(
            task,
            "any-active",
            CollaborationMode.ANY,
            null,
            null,
            spec(USER_ONE),
            spec(USER_TWO)
        ));

        policy = decide(policy, "collaborator-one", ParticipantDecision.REJECTED);
        assertEquals(CollaborationStatus.ACTIVE, policy.status());
        assertEquals(1, policy.progress().pendingCount());
        policy = decide(policy, "collaborator-two", ParticipantDecision.APPROVED);
        assertEquals(CollaborationStatus.SATISFIED, policy.status());
    }

    @Test
    void anyRejectsOnlyAfterEveryParticipantRejects() {
        TaskProjection task = task("owner-any-rejected");
        var policy = service.create(create(
            task,
            "any-rejected",
            CollaborationMode.ANY,
            null,
            null,
            spec(USER_ONE),
            spec(USER_TWO)
        ));

        policy = decide(policy, "collaborator-one", ParticipantDecision.REJECTED);
        policy = decide(policy, "collaborator-two", ParticipantDecision.REJECTED);
        assertEquals(CollaborationStatus.REJECTED, policy.status());
        assertEquals(2, policy.progress().rejectedCount());
        assertParentApprovalBlocked(task);
    }

    @Test
    void voteSatisfiesWhenApprovalCountReachesThreshold() {
        TaskProjection task = task("owner-vote-pass");
        var policy = service.create(create(
            task,
            "vote-pass",
            CollaborationMode.VOTE,
            2,
            null,
            spec(USER_ONE),
            spec(USER_TWO),
            spec(USER_THREE)
        ));

        policy = decide(policy, "collaborator-one", ParticipantDecision.APPROVED);
        assertEquals(CollaborationStatus.ACTIVE, policy.status());
        policy = decide(policy, "collaborator-two", ParticipantDecision.APPROVED);
        assertEquals(CollaborationStatus.SATISFIED, policy.status());
        assertEquals(2, policy.progress().approvedCount());
        assertEquals(1, auditCount("TASK_COLLABORATION_THRESHOLD_REACHED"));
    }

    @Test
    void voteRejectsWhenRemainingVotesCannotReachThreshold() {
        TaskProjection task = task("owner-vote-fail");
        var policy = service.create(create(
            task,
            "vote-fail",
            CollaborationMode.VOTE,
            2,
            null,
            spec(USER_ONE),
            spec(USER_TWO),
            spec(USER_THREE)
        ));

        policy = decide(policy, "collaborator-one", ParticipantDecision.REJECTED);
        assertEquals(CollaborationStatus.ACTIVE, policy.status());
        policy = decide(policy, "collaborator-two", ParticipantDecision.REJECTED);
        assertEquals(CollaborationStatus.REJECTED, policy.status());
        assertEquals(1, policy.progress().maximumReachableApprovalCount());
        assertEquals(1, auditCount("TASK_COLLABORATION_THRESHOLD_IMPOSSIBLE"));
    }

    @Test
    void weightedSatisfiesWhenApprovedWeightReachesThreshold() {
        TaskProjection task = task("owner-weighted-pass");
        var policy = service.create(create(
            task,
            "weighted-pass",
            CollaborationMode.WEIGHTED,
            null,
            5,
            weighted(USER_ONE, 3),
            weighted(USER_TWO, 2),
            weighted(USER_THREE, 1)
        ));

        policy = decide(policy, "collaborator-one", ParticipantDecision.APPROVED);
        assertEquals(CollaborationStatus.ACTIVE, policy.status());
        policy = decide(policy, "collaborator-two", ParticipantDecision.APPROVED);
        assertEquals(CollaborationStatus.SATISFIED, policy.status());
        assertEquals(5, policy.progress().approvedWeight());
    }

    @Test
    void weightedRejectsWhenRemainingWeightCannotReachThreshold() {
        TaskProjection task = task("owner-weighted-fail");
        var policy = service.create(create(
            task,
            "weighted-fail",
            CollaborationMode.WEIGHTED,
            null,
            5,
            weighted(USER_ONE, 3),
            weighted(USER_TWO, 2),
            weighted(USER_THREE, 1)
        ));

        policy = decide(policy, "collaborator-one", ParticipantDecision.REJECTED);
        assertEquals(CollaborationStatus.REJECTED, policy.status());
        assertEquals(3, policy.progress().maximumReachableApprovalWeight());
    }

    @Test
    void participantCanBeAddedBeforeAnyDecision() {
        TaskProjection task = task("owner-add");
        var policy = service.create(create(
            task,
            "add-create",
            CollaborationMode.WEIGHTED,
            null,
            3,
            weighted(USER_ONE, 2),
            weighted(USER_TWO, 1)
        ));

        policy = service.add(new ApprovalTaskCollaborationService.AddParticipantsCommand(
            context(task.assigneeId(), "add-participant"),
            task.taskId(),
            "generic-rest",
            List.of(weighted(USER_THREE, 4)),
            "specialist review"
        ));
        assertEquals(3, policy.progress().eligibleParticipantCount());
        assertEquals(7, policy.progress().totalWeight());
        assertEquals(1, auditCount("TASK_COLLABORATOR_ADDED"));
    }

    @Test
    void participantChangesStopAfterAnyDecisionBegins() {
        TaskProjection task = task("owner-decision-started");
        var policy = service.create(create(
            task,
            "decision-started",
            CollaborationMode.VOTE,
            2,
            null,
            spec(USER_ONE),
            spec(USER_TWO),
            spec(USER_THREE)
        ));
        policy = decide(policy, "collaborator-one", ParticipantDecision.REJECTED);
        assertEquals(CollaborationStatus.ACTIVE, policy.status());

        var current = policy;
        assertThrows(
            ApprovalTaskCollaborationStore.CollaborationConflictException.class,
            () -> service.add(new ApprovalTaskCollaborationService.AddParticipantsCommand(
                context(task.assigneeId(), "late-add"),
                task.taskId(),
                "generic-rest",
                List.of(spec(USER_FOUR)),
                "too late"
            ))
        );
        assertThrows(
            ApprovalTaskCollaborationStore.CollaborationConflictException.class,
            () -> service.remove(new ApprovalTaskCollaborationService.RemoveParticipantCommand(
                context(task.assigneeId(), "late-remove"),
                participant(current, "collaborator-two").participantId(),
                "too late"
            ))
        );
    }

    @Test
    void voteParticipantCannotBeRemovedWhenThresholdWouldBecomeInvalid() {
        TaskProjection task = task("owner-vote-remove");
        var policy = service.create(create(
            task,
            "vote-remove",
            CollaborationMode.VOTE,
            2,
            null,
            spec(USER_ONE),
            spec(USER_TWO)
        ));

        var participant = participant(policy, "collaborator-two");
        assertThrows(
            ApprovalTaskCollaborationStore.CollaborationConflictException.class,
            () -> service.remove(new ApprovalTaskCollaborationService.RemoveParticipantCommand(
                context(task.assigneeId(), "vote-remove-attempt"),
                participant.participantId(),
                "scope reduced"
            ))
        );
    }

    @Test
    void weightedParticipantCannotBeRemovedWhenThresholdWouldBecomeInvalid() {
        TaskProjection task = task("owner-weight-remove");
        var policy = service.create(create(
            task,
            "weight-remove",
            CollaborationMode.WEIGHTED,
            null,
            4,
            weighted(USER_ONE, 3),
            weighted(USER_TWO, 1)
        ));

        var participant = participant(policy, "collaborator-two");
        assertThrows(
            ApprovalTaskCollaborationStore.CollaborationConflictException.class,
            () -> service.remove(new ApprovalTaskCollaborationService.RemoveParticipantCommand(
                context(task.assigneeId(), "weight-remove-attempt"),
                participant.participantId(),
                "scope reduced"
            ))
        );
    }

    @Test
    void idempotentCreateReplayReturnsTheSameResponsibilityEvidence() {
        TaskProjection task = task("owner-idempotent");
        var command = create(
            task,
            "idempotent",
            CollaborationMode.WEIGHTED,
            null,
            3,
            weighted(USER_ONE, 2),
            weighted(USER_TWO, 1)
        );

        var first = service.create(command);
        var replay = service.create(command);
        assertEquals(first.policyId(), replay.policyId());
        assertEquals(first.participants(), replay.participants());
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from ap_task_collaboration_policy",
            Integer.class
        ));
    }

    @Test
    void collaborationQueriesAndCommandsAreTenantIsolated() {
        TaskProjection task = task("owner-tenant");
        var policy = service.create(create(
            task,
            "tenant",
            CollaborationMode.ANY,
            null,
            null,
            spec(USER_ONE),
            spec(USER_TWO)
        ));

        assertThrows(
            ApprovalTaskCollaborationStore.CollaborationNotFoundException.class,
            () -> service.findByTask("tenant-b", task.assigneeId(), task.taskId())
        );
        assertThrows(
            ApprovalTaskCollaborationStore.CollaborationNotFoundException.class,
            () -> service.decide(new ApprovalTaskCollaborationService.DecideParticipantCommand(
                context("tenant-b", "collaborator-one", "cross-tenant"),
                participant(policy, "collaborator-one").participantId(),
                ParticipantDecision.APPROVED,
                "not allowed"
            ))
        );
    }

    @Test
    void onlyTheAssignedParticipantCanDecide() {
        TaskProjection task = task("owner-auth");
        var policy = service.create(create(
            task,
            "auth",
            CollaborationMode.ANY,
            null,
            null,
            spec(USER_ONE),
            spec(USER_TWO)
        ));

        var participant = participant(policy, "collaborator-one");
        assertThrows(
            ApprovalTaskCollaborationStore.CollaborationAuthorizationException.class,
            () -> service.decide(new ApprovalTaskCollaborationService.DecideParticipantCommand(
                context("intruder", "unauthorized-decision"),
                participant.participantId(),
                ParticipantDecision.APPROVED,
                "not assigned"
            ))
        );
    }

    @Test
    void currentTaskOwnerCannotBeAddedAsParticipant() {
        TaskProjection task = task("collaborator-one");
        assertThrows(
            ApprovalTaskCollaborationStore.CollaborationValidationException.class,
            () -> service.create(create(
                task,
                "owner-participant",
                CollaborationMode.ANY,
                null,
                null,
                spec(USER_ONE),
                spec(USER_TWO)
            ))
        );
    }

    @Test
    void parentCanRejectAnActiveCollaborationAndCancelItsRemainingWork() {
        TaskProjection task = task("owner-active-reject");
        service.create(create(
            task,
            "active-reject",
            CollaborationMode.VOTE,
            2,
            null,
            spec(USER_ONE),
            spec(USER_TWO),
            spec(USER_THREE)
        ));

        outcomes.set(TaskOutcome.REJECTED);
        try {
            assertEquals(TaskStatus.COMPLETING, claim(task).status());
        } finally {
            outcomes.clear();
        }
        service.completed(
            task.tenantId(),
            task.taskId(),
            task.assigneeId(),
            TaskOutcome.REJECTED,
            NOW
        );
        var canceled = collaborations.findByTask(task.tenantId(), task.taskId()).orElseThrow();
        assertEquals(CollaborationStatus.CANCELED, canceled.status());
    }

    @Test
    void activeCollaborationBlocksTransferAndRetrieve() {
        TaskProjection task = task("owner-control");
        service.create(create(
            task,
            "control",
            CollaborationMode.ALL,
            null,
            null,
            spec(USER_ONE),
            spec(USER_TWO)
        ));

        assertThrows(
            ApprovalTaskCollaborationStore.CollaborationConflictException.class,
            () -> guarded.transferPendingTask(
                task.tenantId(),
                task.taskId(),
                task.assigneeId(),
                "replacement-owner",
                NOW
            )
        );
        assertThrows(
            ApprovalTaskCollaborationStore.CollaborationConflictException.class,
            () -> guarded.claimPendingTaskForControl(task.tenantId(), task.taskId(), NOW)
        );
    }

    @Test
    void withdrawingTheProcessCancelsActiveCollaboration() {
        TaskProjection task = task("owner-withdraw");
        service.create(create(
            task,
            "withdraw",
            CollaborationMode.VOTE,
            2,
            null,
            spec(USER_ONE),
            spec(USER_TWO),
            spec(USER_THREE)
        ));

        guarded.withdrawRunningInstance(
            task.tenantId(),
            task.instanceId(),
            "initiator-user",
            NOW
        );
        var canceled = collaborations.findByTask(task.tenantId(), task.taskId()).orElseThrow();
        assertEquals(CollaborationStatus.CANCELED, canceled.status());
        assertTrue(canceled.participants().stream().allMatch(item ->
            item.status() == ParticipantStatus.CANCELED
        ));
    }

    @Test
    void concurrentLastVoteAndParentClaimShareTheSameTaskBoundary() throws Exception {
        TaskProjection task = task("owner-race");
        var policy = service.create(create(
            task,
            "race",
            CollaborationMode.VOTE,
            1,
            null,
            spec(USER_ONE)
        ));
        UUID participantId = participant(policy, "collaborator-one").participantId();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CollaborationStatus> vote = executor.submit(() -> {
                start.await();
                return service.decide(
                    new ApprovalTaskCollaborationService.DecideParticipantCommand(
                        context("collaborator-one", "race-vote"),
                        participantId,
                        ParticipantDecision.APPROVED,
                        "approved"
                    )
                ).status();
            });
            Future<String> claim = executor.submit(() -> {
                start.await();
                outcomes.set(TaskOutcome.APPROVED);
                try {
                    return new TransactionTemplate(transactionManager).execute(status -> {
                        try {
                            return guarded.claimPendingTask(
                                task.tenantId(),
                                task.taskId(),
                                task.assigneeId(),
                                NOW
                            ).status().name();
                        } catch (ApprovalTaskCollaborationStore.CollaborationConflictException ex) {
                            return "BLOCKED";
                        }
                    });
                } finally {
                    outcomes.clear();
                }
            });
            start.countDown();
            assertEquals(CollaborationStatus.SATISFIED, vote.get());
            String claimResult = claim.get();
            assertNotNull(claimResult);
            assertTrue("BLOCKED".equals(claimResult) || "COMPLETING".equals(claimResult));
            if ("COMPLETING".equals(claimResult)) {
                assertEquals(
                    CollaborationStatus.SATISFIED,
                    collaborations.findByTask(task.tenantId(), task.taskId()).orElseThrow().status()
                );
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertParentApprovalBlocked(TaskProjection task) {
        outcomes.set(TaskOutcome.APPROVED);
        try {
            assertThrows(
                ApprovalTaskCollaborationStore.CollaborationConflictException.class,
                () -> claim(task)
            );
        } finally {
            outcomes.clear();
        }
    }

    private ApprovalTaskCollaborationStore.TaskCollaboration decide(
        ApprovalTaskCollaborationStore.TaskCollaboration policy,
        String userId,
        ParticipantDecision decision
    ) {
        return service.decide(new ApprovalTaskCollaborationService.DecideParticipantCommand(
            context(userId, "decide-" + userId + "-" + UUID.randomUUID()),
            participant(policy, userId).participantId(),
            decision,
            decision.name().toLowerCase()
        ));
    }

    private ApprovalTaskCollaborationService.CreateCollaborationCommand create(
        TaskProjection task,
        String requestId,
        CollaborationMode mode,
        Integer approvalThreshold,
        Integer approvalWeightThreshold,
        ParticipantSpec... participants
    ) {
        return new ApprovalTaskCollaborationService.CreateCollaborationCommand(
            context(task.assigneeId(), "create-" + requestId),
            task.taskId(),
            "generic-rest",
            mode,
            approvalThreshold,
            approvalWeightThreshold,
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

    private int auditCount(String action) {
        return jdbc.queryForObject(
            "select count(*) from ap_audit_event where action = ?",
            Integer.class,
            action
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
        return context("tenant-a", operatorId, operationId);
    }

    private static RequestContext context(
        String tenantId,
        String operatorId,
        String operationId
    ) {
        return new RequestContext(
            tenantId,
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

    private static ParticipantSpec spec(IdentityReference reference) {
        return new ParticipantSpec(reference, 1);
    }

    private static ParticipantSpec weighted(IdentityReference reference, int weight) {
        return new ParticipantSpec(reference, weight);
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
            USER_THREE.canonicalValue(), candidate(USER_THREE),
            USER_FOUR.canonicalValue(), candidate(USER_FOUR)
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
