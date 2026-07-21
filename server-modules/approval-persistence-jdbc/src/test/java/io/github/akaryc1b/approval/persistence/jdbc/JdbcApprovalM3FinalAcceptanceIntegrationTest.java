package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalCommentService;
import io.github.akaryc1b.approval.application.ApprovalCommentService.CommentCommand;
import io.github.akaryc1b.approval.application.ApprovalCommentService.CommentOperationException;
import io.github.akaryc1b.approval.application.ApprovalCommentService.DeleteCommentCommand;
import io.github.akaryc1b.approval.application.ApprovalCommentService.EditCommentCommand;
import io.github.akaryc1b.approval.application.ApprovalConsistencyService;
import io.github.akaryc1b.approval.application.ApprovalConsistencyService.RunCommand;
import io.github.akaryc1b.approval.application.ApprovalDelegationService;
import io.github.akaryc1b.approval.application.ApprovalDelegationService.CreateDelegationCommand;
import io.github.akaryc1b.approval.application.ApprovalNotificationService;
import io.github.akaryc1b.approval.application.ApprovalTaskCollaborationService;
import io.github.akaryc1b.approval.application.ApprovalTaskCollaborationService.AddParticipantsCommand;
import io.github.akaryc1b.approval.application.ApprovalTaskCollaborationService.CreateCollaborationCommand;
import io.github.akaryc1b.approval.application.ApprovalTaskCollaborationService.DecideParticipantCommand;
import io.github.akaryc1b.approval.application.ApprovalTaskCollaborationService.ParticipantSpec;
import io.github.akaryc1b.approval.application.NotificationAwareAuditEventSink;
import io.github.akaryc1b.approval.application.port.ApprovalAuditStore.AuditIntegrityCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentVisibility;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.CheckStatus;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.CheckType;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.Severity;
import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore.DelegationScope;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityCandidate;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityLookup;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityReference;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentitySearch;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationEventType;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.UserIdentitySnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationMode;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationStatus;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.ParticipantDecision;
import io.github.akaryc1b.approval.application.port.ApprovalTaskDelegationAssignmentStore.AssignmentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalTaskDelegationAssignmentStore.DelegatedTaskAssignment;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalM3FinalAcceptanceIntegrationTest {

    private static final String TENANT_ID = "tenant-a";
    private static final Instant NOW = Instant.parse("2026-07-21T04:00:00Z");
    private static final UUID INSTANCE_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000001001"
    );
    private static final UUID TASK_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000001002"
    );

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_m3_final_acceptance_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private JdbcApprovalProjectionStore projections;
    private JdbcApprovalTaskDelegationAssignmentStore delegationAssignments;
    private JdbcApprovalTaskCollaborationStore collaborationStore;
    private JdbcAuditEventSink auditStore;
    private ApprovalDelegationService delegationService;
    private ApprovalTaskCollaborationService collaborationService;
    private ApprovalCommentService commentService;
    private ApprovalConsistencyService consistencyService;
    private AuditEventSink governedAudit;
    private Clock clock;

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
                ap_consistency_finding,
                ap_consistency_check,
                ap_notification_delivery_attempt,
                ap_notification_intent,
                ap_notification_preference,
                ap_notification_user_setting,
                ap_approval_comment_revision,
                ap_approval_comment,
                ap_approval_attachment,
                ap_approval_message,
                ap_task_collaboration_participant,
                ap_task_collaboration_policy,
                ap_task_delegation_assignment,
                ap_delegation_rule,
                ap_audit_chain_state,
                ap_audit_event,
                ap_approval_task,
                ap_approval_instance,
                ap_definition_version,
                ap_command_idempotency
            cascade
            """);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        projections = new JdbcApprovalProjectionStore(dataSource, objectMapper);
        JdbcIdempotencyGuard idempotency = new JdbcIdempotencyGuard(
            dataSource,
            objectMapper,
            transactions,
            clock
        );
        JdbcApprovalNotificationStore notificationStore = new JdbcApprovalNotificationStore(
            dataSource,
            objectMapper,
            transactions
        );
        ApprovalNotificationService notifications = new ApprovalNotificationService(
            notificationStore,
            projections,
            intent -> new io.github.akaryc1b.approval.application.port
                .ApprovalConnectorNotificationSender.DeliveryResult(
                    true,
                    false,
                    "connector:" + intent.intentId(),
                    null,
                    null
                ),
            intent -> new io.github.akaryc1b.approval.application.port
                .ApprovalEmailNotificationSender.DeliveryResult(
                    true,
                    false,
                    "email:" + intent.intentId(),
                    null,
                    null
                ),
            clock,
            UUID::randomUUID
        );
        auditStore = new JdbcAuditEventSink(dataSource, objectMapper);
        governedAudit = new NotificationAwareAuditEventSink(auditStore, notifications);
        delegationAssignments = new JdbcApprovalTaskDelegationAssignmentStore(dataSource);
        collaborationStore = new JdbcApprovalTaskCollaborationStore(dataSource);
        delegationService = new ApprovalDelegationService(
            idempotency,
            new JdbcApprovalDelegationStore(dataSource),
            governedAudit,
            clock,
            UUID::randomUUID
        );
        collaborationService = new ApprovalTaskCollaborationService(
            idempotency,
            new AcceptanceIdentityDirectory(),
            collaborationStore,
            projections,
            governedAudit,
            clock,
            UUID::randomUUID
        );
        commentService = new ApprovalCommentService(
            idempotency,
            projections,
            new JdbcApprovalMessageStore(dataSource, objectMapper),
            new JdbcApprovalCommentStore(dataSource, objectMapper),
            new JdbcApprovalAttachmentStore(dataSource),
            governedAudit,
            clock,
            UUID::randomUUID
        );
        consistencyService = new ApprovalConsistencyService(
            idempotency,
            new JdbcApprovalConsistencyStore(dataSource, objectMapper, transactions),
            governedAudit,
            clock,
            UUID::randomUUID
        );
        createInstance();
    }

    @Test
    void delegatedCollaborationPrivateCommentCreatesOneSetOfEvidence() {
        CrossDomainEvidence evidence = createCrossDomainEvidence();

        assertEquals(CollaborationStatus.ACTIVE, evidence.collaboration().status());
        assertEquals("delegate-1", evidence.comment().authorId());
        assertEquals(CommentVisibility.MENTIONED_ONLY, evidence.comment().visibility());
        assertEquals(1, count("ap_delegation_rule"));
        assertEquals(1, count("ap_task_delegation_assignment"));
        assertEquals(1, count("ap_task_collaboration_policy"));
        assertEquals(1, count("ap_task_collaboration_participant"));
        assertEquals(1, count("ap_approval_comment"));
        assertEquals(1, count("ap_approval_comment_revision"));
        assertEquals(1, notificationCount(
            "delegate-1",
            NotificationEventType.AUTOMATIC_DELEGATION
        ));
        assertEquals(1, notificationCount(
            "collab-user",
            NotificationEventType.TASK_COLLABORATION_ASSIGNED
        ));
        assertEquals(1, notificationCount(
            "collab-user",
            NotificationEventType.COMMENT_MENTION
        ));
        assertEquals(1, auditCount("DELEGATION_RULE_CREATED"));
        assertEquals(1, auditCount("TASK_DELEGATED"));
        assertEquals(1, auditCount("TASK_COLLABORATION_CREATED"));
        assertEquals(1, auditCount("INSTANCE_COMMENT_CREATED"));

        var integrity = auditStore.verify(new AuditIntegrityCriteria(
            TENANT_ID,
            NOW.minusSeconds(1),
            NOW.plusSeconds(1)
        ));
        assertTrue(integrity.valid());
        assertEquals(4, integrity.checkedCount());

        CommentOperationException crossTenant = assertThrows(
            CommentOperationException.class,
            () -> commentService.findComments(
                "tenant-b",
                "delegate-1",
                INSTANCE_ID,
                20,
                0
            )
        );
        assertEquals("APPROVAL_COMMENT_NOT_FOUND", crossTenant.code());
    }

    @Test
    void terminalTaskRejectsFurtherCommentAndCollaborationChanges() {
        CrossDomainEvidence evidence = createCrossDomainEvidence();
        jdbc.update(
            """
            update ap_approval_instance
            set status = 'COMPLETED', updated_at = ?, version = version + 1
            where tenant_id = ? and instance_id = ?
            """,
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
            TENANT_ID,
            INSTANCE_ID
        );
        jdbc.update(
            """
            update ap_approval_task
            set status = 'CANCELED', updated_at = ?, version = version + 1
            where tenant_id = ? and task_id = ?
            """,
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
            TENANT_ID,
            TASK_ID
        );

        CommentOperationException commentError = assertThrows(
            CommentOperationException.class,
            () -> commentService.edit(new EditCommentCommand(
                context("delegate-1", "terminal-comment", "terminal-comment-key"),
                INSTANCE_ID,
                evidence.comment().commentId(),
                "terminal mutation",
                List.of("collab-user"),
                List.of(),
                CommentVisibility.MENTIONED_ONLY,
                evidence.comment().version(),
                null
            ))
        );
        assertEquals("APPROVAL_COMMENT_INSTANCE_READ_ONLY", commentError.code());

        CommentOperationException deleteError = assertThrows(
            CommentOperationException.class,
            () -> commentService.delete(new DeleteCommentCommand(
                context("delegate-1", "terminal-delete", "terminal-delete-key"),
                INSTANCE_ID,
                evidence.comment().commentId(),
                evidence.comment().version(),
                "terminal deletion"
            ))
        );
        assertEquals("APPROVAL_COMMENT_INSTANCE_READ_ONLY", deleteError.code());

        CollaborationConflictException addError = assertThrows(
            CollaborationConflictException.class,
            () -> collaborationService.add(new AddParticipantsCommand(
                context("delegate-1", "terminal-add", "terminal-add-key"),
                TASK_ID,
                "test",
                List.of(new ParticipantSpec(reference("collab-two"), 1)),
                "terminal add"
            ))
        );
        assertEquals("APPROVAL_TASK_COLLABORATION_STATE_CONFLICT", addError.code());

        CollaborationConflictException decideError = assertThrows(
            CollaborationConflictException.class,
            () -> collaborationService.decide(new DecideParticipantCommand(
                context("collab-user", "terminal-decide", "terminal-decide-key"),
                evidence.collaboration().participants().getFirst().participantId(),
                ParticipantDecision.APPROVED,
                "terminal decision"
            ))
        );
        assertEquals("APPROVAL_TASK_COLLABORATION_STATE_CONFLICT", decideError.code());
        assertEquals(1, count("ap_approval_comment"));
        assertEquals(1, count("ap_approval_comment_revision"));
        assertEquals(1, count("ap_task_collaboration_participant"));
    }

    @Test
    void detectOnlyConsistencyPreservesEvidenceAndAuditDetectsTampering() {
        createCrossDomainEvidence();
        jdbc.update(
            """
            update ap_approval_instance
            set status = 'COMPLETED', updated_at = ?, version = version + 1
            where tenant_id = ? and instance_id = ?
            """,
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
            TENANT_ID,
            INSTANCE_ID
        );

        var check = consistencyService.run(new RunCommand(
            context("operator-admin", "final-consistency", "final-consistency-key")
        ));
        assertEquals(CheckStatus.COMPLETED, check.status());
        assertTrue(check.findingCount() > 0);
        var findings = consistencyService.findFindings(
            TENANT_ID,
            check.checkId(),
            CheckType.INSTANCE_TASK_STATE,
            Severity.ERROR,
            100,
            0
        );
        assertTrue(findings.total() > 0);
        assertEquals("PENDING", jdbc.queryForObject(
            "select status from ap_approval_task where tenant_id = ? and task_id = ?",
            String.class,
            TENANT_ID,
            TASK_ID
        ));

        var valid = auditStore.verify(new AuditIntegrityCriteria(
            TENANT_ID,
            NOW.minusSeconds(1),
            NOW.plusSeconds(1)
        ));
        assertTrue(valid.valid());
        jdbc.update(
            """
            update ap_audit_event
            set attributes_json = attributes_json || '{"tampered":"true"}'::jsonb
            where tenant_id = ? and tenant_sequence = 1
            """,
            TENANT_ID
        );
        var invalid = auditStore.verify(new AuditIntegrityCriteria(
            TENANT_ID,
            NOW.minusSeconds(1),
            NOW.plusSeconds(1)
        ));
        assertFalse(invalid.valid());
        assertTrue(invalid.firstInvalidSequence() != null);
        assertEquals(1, count("ap_consistency_check"));
        assertTrue(count("ap_consistency_finding") > 0);
        assertEquals(1, count("ap_task_delegation_assignment"));
        assertEquals(1, count("ap_approval_comment_revision"));
    }

    private CrossDomainEvidence createCrossDomainEvidence() {
        var rule = delegationService.create(new CreateDelegationCommand(
            context("manager-1", "delegation-rule", "delegation-rule-key"),
            "delegate-1",
            DelegationScope.DEFINITION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            NOW,
            NOW.plusSeconds(86_400),
            "final acceptance delegation"
        ));
        delegationAssignments.create(new DelegatedTaskAssignment(
            UUID.randomUUID(),
            TENANT_ID,
            engineTaskId(),
            engineInstanceId(),
            PurchasePaymentTemplate.DEFINITION_KEY,
            "managerApproval",
            "manager-1",
            "delegate-1",
            rule.ruleId(),
            DelegationScope.DEFINITION,
            AssignmentStatus.ACTIVE,
            NOW,
            null,
            null,
            null,
            null,
            null,
            1
        ));
        governedAudit.append(new AuditEvent(
            UUID.randomUUID(),
            TENANT_ID,
            "approval-platform",
            "TASK_DELEGATED",
            "APPROVAL_TASK",
            TASK_ID.toString(),
            "task-delegated-request",
            "trace-task-delegated-request",
            NOW,
            Map.of(
                "principalAssigneeId", "manager-1",
                "delegateAssigneeId", "delegate-1",
                "delegationRuleId", rule.ruleId().toString(),
                "engineTaskId", engineTaskId()
            )
        ));

        CreateCollaborationCommand collaborationCommand = new CreateCollaborationCommand(
            context("delegate-1", "collaboration-create", "collaboration-create-key"),
            TASK_ID,
            "test",
            CollaborationMode.ALL,
            null,
            null,
            List.of(new ParticipantSpec(reference("collab-user"), 1)),
            "specialist collaboration"
        );
        var collaboration = collaborationService.create(collaborationCommand);
        assertEquals(collaboration, collaborationService.create(collaborationCommand));

        CommentCommand commentCommand = new CommentCommand(
            context("delegate-1", "private-comment", "private-comment-key"),
            INSTANCE_ID,
            null,
            "delegated task private collaboration comment",
            List.of("collab-user"),
            List.of(),
            CommentVisibility.MENTIONED_ONLY
        );
        var comment = commentService.comment(commentCommand);
        assertEquals(comment, commentService.comment(commentCommand));
        return new CrossDomainEvidence(collaboration, comment);
    }

    private void createInstance() {
        projections.saveDefinition(new PublishedDefinition(
            TENANT_ID,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            ApprovalDslCompiler.COMPILER_VERSION,
            "a".repeat(64),
            "deployment-final",
            "engine-definition-final",
            1,
            "publisher",
            NOW
        ));
        Map<String, UserIdentitySnapshot> identities = Map.of(
            "initiator-1", identity("initiator-1"),
            "manager-1", identity("manager-1"),
            "delegate-1", identity("delegate-1"),
            "collab-user", identity("collab-user"),
            "collab-two", identity("collab-two"),
            "finance-reviewer", identity("finance-reviewer")
        );
        projections.createInstance(new InstanceProjection(
            INSTANCE_ID,
            TENANT_ID,
            "M3-FINAL-001",
            engineInstanceId(),
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            ApprovalDslCompiler.COMPILER_VERSION,
            "a".repeat(64),
            "initiator-1",
            new BigDecimal("1000.00"),
            "final supplier",
            "PO-FINAL-001",
            List.of(),
            new AssigneeSnapshot(
                "manager-1",
                "finance-reviewer",
                List.of(),
                Map.of("connectorKey", "test"),
                identities
            ),
            "b".repeat(64),
            InstanceStatus.RUNNING,
            1,
            NOW,
            NOW
        ), List.of(new TaskProjection(
            TASK_ID,
            INSTANCE_ID,
            TENANT_ID,
            engineTaskId(),
            "managerApproval",
            "Manager approval",
            "delegate-1",
            TaskStatus.PENDING,
            1,
            NOW,
            NOW,
            null
        )));
    }

    private int notificationCount(String recipientId, NotificationEventType eventType) {
        Integer count = jdbc.queryForObject(
            """
            select count(*)
            from ap_notification_intent
            where tenant_id = ? and recipient_id = ? and event_type = ?
            """,
            Integer.class,
            TENANT_ID,
            recipientId,
            eventType.name()
        );
        return count == null ? 0 : count;
    }

    private int auditCount(String action) {
        Integer count = jdbc.queryForObject(
            "select count(*) from ap_audit_event where tenant_id = ? and action = ?",
            Integer.class,
            TENANT_ID,
            action
        );
        return count == null ? 0 : count;
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static RequestContext context(
        String operatorId,
        String requestId,
        String idempotencyKey
    ) {
        return new RequestContext(
            TENANT_ID,
            operatorId,
            requestId,
            idempotencyKey,
            "trace-" + requestId
        );
    }

    private static UserIdentitySnapshot identity(String userId) {
        return new UserIdentitySnapshot(
            userId,
            userId,
            userId,
            null,
            null,
            List.of(),
            Set.of(),
            Set.of(),
            Map.of()
        );
    }

    private static IdentityReference reference(String userId) {
        return new IdentityReference("CONNECTOR_DIRECTORY", "USER", "ext-" + userId);
    }

    private static String engineTaskId() {
        return "engine-task-" + TASK_ID;
    }

    private static String engineInstanceId() {
        return "engine-instance-" + INSTANCE_ID;
    }

    private record CrossDomainEvidence(
        io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore
            .TaskCollaboration collaboration,
        ApprovalCommentService.CommentItem comment
    ) {
    }

    private static final class AcceptanceIdentityDirectory
        implements ApprovalIdentityDirectory {

        @Override
        public List<IdentityCandidate> search(IdentitySearch search) {
            return List.of(candidate("collab-user"), candidate("collab-two"));
        }

        @Override
        public IdentityCandidate requireUser(IdentityLookup lookup) {
            String value = lookup.reference().value();
            if (value.equals("ext-collab-user")) {
                return candidate("collab-user");
            }
            if (value.equals("ext-collab-two")) {
                return candidate("collab-two");
            }
            throw new ApprovalIdentityDirectory.IdentityResolutionException(
                "APPROVAL_IDENTITY_NOT_FOUND",
                "identity was not found",
                false
            );
        }

        private static IdentityCandidate candidate(String userId) {
            return new IdentityCandidate(
                reference(userId),
                userId,
                userId,
                userId,
                null,
                null,
                true,
                List.of(),
                List.of(),
                List.of()
            );
        }
    }
}
