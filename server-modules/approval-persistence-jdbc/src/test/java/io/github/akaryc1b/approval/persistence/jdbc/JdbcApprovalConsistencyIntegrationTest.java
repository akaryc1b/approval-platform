package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalConsistencyService;
import io.github.akaryc1b.approval.application.ApprovalConsistencyService.RunCommand;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.CheckStatus;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.CheckType;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.ConsistencyCheck;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.Severity;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.UserIdentitySnapshot;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalConsistencyIntegrationTest {

    private static final Instant START = Instant.parse("2026-07-20T15:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_consistency_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private JdbcApprovalProjectionStore projections;
    private ApprovalConsistencyService service;

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
                ap_approval_attachment,
                ap_approval_comment,
                ap_task_collaboration_participant,
                ap_task_collaboration_policy,
                ap_task_handover_assignment,
                ap_principal_handover,
                ap_task_delegation_assignment,
                ap_delegation_rule,
                ap_audit_event,
                ap_audit_chain_state,
                ap_approval_task,
                ap_approval_instance,
                ap_definition_version,
                ap_command_idempotency
            cascade
            """);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        projections = new JdbcApprovalProjectionStore(dataSource, objectMapper);
        JdbcApprovalConsistencyStore store = new JdbcApprovalConsistencyStore(
            dataSource,
            objectMapper,
            transactionManager
        );
        JdbcIdempotencyGuard idempotency = new JdbcIdempotencyGuard(
            dataSource,
            objectMapper,
            transactionManager,
            clock
        );
        JdbcAuditEventSink audit = new JdbcAuditEventSink(
            dataSource,
            objectMapper,
            transactionManager
        );
        service = new ApprovalConsistencyService(
            idempotency,
            store,
            audit,
            clock,
            UUID::randomUUID
        );
    }

    @Test
    void cleanTenantIsIdempotentAuditedAndDetectOnly() {
        InstanceTask fixture = createInstance("tenant-a", InstanceStatus.RUNNING, TaskStatus.PENDING);
        RunCommand command = new RunCommand(context(
            "tenant-a",
            "operator-1",
            "clean-check",
            "clean-check-key"
        ));

        var first = service.run(command);
        var replay = service.run(command);

        assertEquals(first, replay);
        assertEquals(CheckStatus.COMPLETED, first.status());
        assertEquals(0, first.findingCount());
        assertEquals("PENDING", jdbc.queryForObject(
            "select status from ap_approval_task where tenant_id = ? and task_id = ?",
            String.class,
            "tenant-a",
            fixture.taskId()
        ));
        assertEquals(1, count(
            "select count(*) from ap_audit_event where tenant_id = ? and action = 'CONSISTENCY_CHECK_EXECUTED'",
            "tenant-a"
        ));
        assertEquals("true", jdbc.queryForObject(
            "select attributes_json ->> 'detectOnly' from ap_audit_event where tenant_id = ? and action = 'CONSISTENCY_CHECK_EXECUTED'",
            String.class,
            "tenant-a"
        ));
    }

    @Test
    void detectsInstanceTaskStateAndKeepsTenantsIsolated() {
        InstanceTask tenantA = createInstance(
            "tenant-a",
            InstanceStatus.RUNNING,
            TaskStatus.PENDING
        );
        createInstance("tenant-b", InstanceStatus.RUNNING, TaskStatus.PENDING);
        jdbc.update(
            "update ap_approval_task set status = 'COMPLETED', completed_at = ?, updated_at = ?, version = version + 1 where tenant_id = ? and task_id = ?",
            offset(START),
            offset(START),
            "tenant-a",
            tenantA.taskId()
        );

        var tenantACheck = run("tenant-a", "instance-check-a");
        var tenantAFindings = service.findFindings(
            "tenant-a",
            tenantACheck.checkId(),
            CheckType.INSTANCE_TASK_STATE,
            null,
            20,
            0
        );
        var tenantBCheck = run("tenant-b", "instance-check-b");

        assertEquals(1, tenantAFindings.total());
        assertEquals("RUNNING_WITHOUT_ACTIVE_TASK",
            tenantAFindings.items().getFirst().details().get("issueCode"));
        assertEquals(0, tenantBCheck.findingCount());
    }

    @Test
    void detectsDelegationHandoverAndUnreachableCollaboration() {
        InstanceTask fixture = createInstance(
            "tenant-a",
            InstanceStatus.RUNNING,
            TaskStatus.PENDING
        );
        insertDelegationMismatch(fixture);
        insertHandoverMismatch(fixture);
        insertUnreachableVote(fixture);

        var check = run("tenant-a", "responsibility-check");
        var findings = service.findFindings(
            "tenant-a",
            check.checkId(),
            null,
            null,
            100,
            0
        );
        Set<CheckType> types = findings.items().stream()
            .map(item -> item.checkType())
            .collect(java.util.stream.Collectors.toSet());

        assertTrue(types.contains(CheckType.DELEGATION_EVIDENCE));
        assertTrue(types.contains(CheckType.HANDOVER_EVIDENCE));
        assertTrue(types.contains(CheckType.COLLABORATION_POLICY));
        assertTrue(findings.items().stream().anyMatch(
            item -> item.severity() == Severity.CRITICAL
                && "UNREACHABLE_VOTE_THRESHOLD".equals(item.details().get("issueCode"))
        ));
    }

    @Test
    void detectsNotificationDeliveryEvidenceWithoutChangingQueues() {
        createInstance("tenant-a", InstanceStatus.RUNNING, TaskStatus.PENDING);
        UUID delivered = insertNotification(
            "DELIVERED",
            "CONNECTOR",
            1,
            5,
            START,
            null
        );
        UUID expired = insertNotification(
            "PROCESSING",
            "CONNECTOR",
            0,
            5,
            null,
            START.minusSeconds(60)
        );

        var check = run("tenant-a", "notification-check");
        var findings = service.findFindings(
            "tenant-a",
            check.checkId(),
            CheckType.NOTIFICATION_DELIVERY,
            null,
            100,
            0
        );

        assertEquals(2, findings.total());
        assertTrue(findings.items().stream().anyMatch(item ->
            delivered.toString().equals(item.aggregateId())
                && "DELIVERED_WITHOUT_SUCCESSFUL_ATTEMPT".equals(
                    item.details().get("issueCode")
                )
        ));
        assertTrue(findings.items().stream().anyMatch(item ->
            expired.toString().equals(item.aggregateId())
                && item.severity() == Severity.WARNING
        ));
        assertEquals("DELIVERED", notificationStatus(delivered));
        assertEquals("PROCESSING", notificationStatus(expired));
    }

    @Test
    void detectsCommentRevisionAttachmentAndAuditEvidence() {
        InstanceTask fixture = createInstance(
            "tenant-a",
            InstanceStatus.RUNNING,
            TaskStatus.PENDING
        );
        UUID missingAttachment = UUID.randomUUID();
        UUID governedComment = insertComment(
            fixture.instanceId(),
            2,
            List.of(missingAttachment)
        );
        insertRevision(governedComment, 1, "CREATE", List.of());
        insertRevision(governedComment, 2, "EDIT", List.of(missingAttachment));
        UUID mismatchedComment = insertComment(fixture.instanceId(), 2, List.of());
        insertRevision(mismatchedComment, 1, "CREATE", List.of());

        var check = run("tenant-a", "comment-check");
        var findings = service.findFindings(
            "tenant-a",
            check.checkId(),
            null,
            null,
            100,
            0
        );
        Set<CheckType> types = findings.items().stream()
            .map(item -> item.checkType())
            .collect(java.util.stream.Collectors.toSet());

        assertTrue(types.contains(CheckType.COMMENT_REVISION));
        assertTrue(types.contains(CheckType.ATTACHMENT_REFERENCE));
        assertTrue(types.contains(CheckType.AUDIT_BUSINESS_EVIDENCE));
        assertTrue(findings.items().stream().anyMatch(item ->
            governedComment.toString().equals(item.aggregateId())
                && "COMMENT_REVISION_AUDIT_MISSING".equals(
                    item.details().get("issueCode")
                )
        ));
        assertEquals(2, jdbc.queryForObject(
            "select current_revision from ap_approval_comment where tenant_id = ? and comment_id = ?",
            Integer.class,
            "tenant-a",
            mismatchedComment
        ));
    }

    @Test
    void supportsCheckAndFindingFiltersWithPagination() {
        InstanceTask fixture = createInstance(
            "tenant-a",
            InstanceStatus.RUNNING,
            TaskStatus.PENDING
        );
        insertDelegationMismatch(fixture);
        insertHandoverMismatch(fixture);
        var first = run("tenant-a", "filter-check-1");
        var second = run("tenant-a", "filter-check-2");

        var history = service.findChecks("tenant-a", CheckStatus.COMPLETED, 1, 0);
        var delegation = service.findFindings(
            "tenant-a",
            second.checkId(),
            CheckType.DELEGATION_EVIDENCE,
            Severity.ERROR,
            1,
            0
        );

        assertEquals(2, history.total());
        assertTrue(history.hasMore());
        assertEquals(second.checkId(), history.items().getFirst().checkId());
        assertEquals(1, delegation.total());
        assertFalse(delegation.hasMore());
        assertEquals(CheckType.DELEGATION_EVIDENCE,
            delegation.items().getFirst().checkType());
        assertTrue(first.findingCount() >= 2);
    }

    private ConsistencyCheck run(String tenantId, String requestId) {
        return service.run(new RunCommand(context(
            tenantId,
            "operator-1",
            requestId,
            requestId + "-key"
        )));
    }

    private InstanceTask createInstance(
        String tenantId,
        InstanceStatus instanceStatus,
        TaskStatus taskStatus
    ) {
        if (projections.findDefinition(
            tenantId,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION
        ).isEmpty()) {
            projections.saveDefinition(new PublishedDefinition(
                tenantId,
                PurchasePaymentTemplate.DEFINITION_KEY,
                PurchasePaymentTemplate.PROCESS_VERSION,
                PurchasePaymentTemplate.DEFINITION_KEY,
                PurchasePaymentTemplate.FORM_VERSION,
                ApprovalDslCompiler.COMPILER_VERSION,
                "a".repeat(64),
                "deployment-consistency-" + tenantId,
                "engine-definition-consistency-" + tenantId,
                1,
                "publisher",
                START
            ));
        }
        UUID instanceId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Map<String, UserIdentitySnapshot> identities = Map.of(
            "initiator-1", identity("initiator-1", "申请人"),
            "manager-1", identity("manager-1", "部门负责人")
        );
        projections.createInstance(new InstanceProjection(
            instanceId,
            tenantId,
            "CONSISTENCY-" + instanceId,
            "engine-instance-" + instanceId,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            ApprovalDslCompiler.COMPILER_VERSION,
            "a".repeat(64),
            "initiator-1",
            new BigDecimal("1000.00"),
            "一致性测试供应商",
            "PO-CONSISTENCY",
            List.of(),
            new AssigneeSnapshot(
                "manager-1",
                "manager-1",
                List.of("manager-1"),
                Map.of("connectorKey", "test"),
                identities
            ),
            "b".repeat(64),
            instanceStatus,
            1,
            START,
            START
        ), List.of(new TaskProjection(
            taskId,
            instanceId,
            tenantId,
            "engine-task-" + taskId,
            "managerApproval",
            "Manager approval",
            "manager-1",
            taskStatus,
            1,
            START,
            START,
            taskStatus == TaskStatus.COMPLETED ? START : null
        )));
        return new InstanceTask(instanceId, taskId, "engine-task-" + taskId);
    }

    private void insertDelegationMismatch(InstanceTask fixture) {
        UUID ruleId = UUID.randomUUID();
        jdbc.update("""
            insert into ap_delegation_rule (
                tenant_id, rule_id, principal_id, delegate_id, scope,
                definition_key, valid_from, valid_until, status, reason,
                created_by, created_at, revoked_by, revoked_at, revoke_reason, version
            ) values (?, ?, ?, ?, 'ALL', null, ?, ?, 'ACTIVE', ?, ?, ?, null, null, null, 1)
            """,
            "tenant-a",
            ruleId,
            "manager-1",
            "delegate-1",
            offset(START.minusSeconds(3600)),
            offset(START.plusSeconds(3600)),
            "temporary delegation",
            "manager-1",
            offset(START)
        );
        jdbc.update("""
            insert into ap_task_delegation_assignment (
                assignment_id, tenant_id, engine_task_id, engine_instance_id,
                definition_key, task_definition_key, principal_assignee_id,
                delegate_assignee_id, delegation_rule_id, delegation_scope,
                status, assigned_at, completed_by, completed_at,
                superseded_assignee_id, superseded_at, canceled_at, version
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ALL', 'ACTIVE', ?, null, null, null, null, null, 1)
            """,
            UUID.randomUUID(),
            "tenant-a",
            fixture.engineTaskId(),
            "engine-instance-" + fixture.instanceId(),
            PurchasePaymentTemplate.DEFINITION_KEY,
            "managerApproval",
            "manager-1",
            "delegate-1",
            ruleId,
            offset(START)
        );
    }

    private void insertHandoverMismatch(InstanceTask fixture) {
        UUID handoverId = UUID.randomUUID();
        jdbc.update("""
            insert into ap_principal_handover (
                handover_id, tenant_id, connector_key,
                principal_id, principal_source, principal_object_type,
                principal_external_value, successor_id, successor_source,
                successor_object_type, successor_external_value,
                reason, status, created_by, created_at,
                revoked_by, revoked_at, revoke_reason, version
            ) values (?, ?, 'test', ?, 'CONNECTOR', 'USER', ?, ?, 'CONNECTOR', 'USER', ?, ?, 'ACTIVE', ?, ?, null, null, null, 1)
            """,
            handoverId,
            "tenant-a",
            "manager-1",
            "external-manager",
            "successor-1",
            "external-successor",
            "employee departure",
            "operator-1",
            offset(START)
        );
        jdbc.update("""
            insert into ap_task_handover_assignment (
                assignment_id, tenant_id, engine_task_id, engine_instance_id,
                definition_key, task_definition_key, principal_assignee_id,
                successor_assignee_id, handover_id, status, assigned_at,
                completed_by, completed_at, superseded_assignee_id,
                superseded_at, canceled_at, version
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, null, null, null, null, null, 1)
            """,
            UUID.randomUUID(),
            "tenant-a",
            fixture.engineTaskId(),
            "engine-instance-" + fixture.instanceId(),
            PurchasePaymentTemplate.DEFINITION_KEY,
            "managerApproval",
            "manager-1",
            "successor-1",
            handoverId,
            offset(START)
        );
    }

    private void insertUnreachableVote(InstanceTask fixture) {
        UUID policyId = UUID.randomUUID();
        jdbc.update("""
            insert into ap_task_collaboration_policy (
                policy_id, tenant_id, task_id, instance_id,
                engine_task_id, engine_instance_id, definition_key,
                task_definition_key, task_name, owner_assignee_id,
                collaboration_mode, status, reason, created_by, created_at,
                terminal_by, terminal_at, terminal_reason, version,
                approval_threshold, approval_weight_threshold
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'VOTE', 'ACTIVE', ?, ?, ?, null, null, null, 1, 2, null)
            """,
            policyId,
            "tenant-a",
            fixture.taskId(),
            fixture.instanceId(),
            fixture.engineTaskId(),
            "engine-instance-" + fixture.instanceId(),
            PurchasePaymentTemplate.DEFINITION_KEY,
            "managerApproval",
            "Manager approval",
            "manager-1",
            "vote test",
            "operator-1",
            offset(START)
        );
        jdbc.update("""
            insert into ap_task_collaboration_participant (
                participant_id, tenant_id, policy_id, participant_user_id,
                identity_source, identity_object_type, identity_external_value,
                status, added_by, added_at, decision_comment, decided_at,
                removed_by, removed_at, removal_reason, canceled_at,
                version, participant_weight
            ) values (?, ?, ?, ?, 'CONNECTOR', 'USER', ?, 'PENDING', ?, ?, null, null, null, null, null, null, 1, 1)
            """,
            UUID.randomUUID(),
            "tenant-a",
            policyId,
            "participant-1",
            "external-participant",
            "operator-1",
            offset(START)
        );
    }

    private UUID insertNotification(
        String status,
        String channel,
        int attemptCount,
        int maxAttempts,
        Instant deliveredAt,
        Instant lockedUntil
    ) {
        UUID intentId = UUID.randomUUID();
        jdbc.update("""
            insert into ap_notification_intent (
                intent_id, tenant_id, event_type, channel,
                recipient_id, sender_id, instance_id, task_id,
                aggregate_type, aggregate_id, template_key, template_version,
                title, body, metadata_json, business_event_key, urgent,
                status, attempt_count, max_attempts, next_attempt_at,
                delivered_at, read_at, last_error_code, last_error_message,
                locked_by, locked_until, created_at, updated_at, version
            ) values (
                ?, 'tenant-a', 'TASK_ASSIGNED', ?,
                'manager-1', 'system', null, null,
                'APPROVAL_TASK', ?, 'task-assigned', 1,
                'Task assigned', 'Task assigned body', '{}'::jsonb, ?, false,
                ?, ?, ?, ?, ?, null, null, null,
                ?, ?, ?, ?, 1
            )
            """,
            intentId,
            channel,
            intentId.toString(),
            "consistency-notification-" + intentId,
            status,
            attemptCount,
            maxAttempts,
            offset(START),
            deliveredAt == null ? null : offset(deliveredAt),
            lockedUntil == null ? null : "worker-1",
            lockedUntil == null ? null : offset(lockedUntil),
            offset(START.minusSeconds(120)),
            offset(START.minusSeconds(60))
        );
        return intentId;
    }

    private UUID insertComment(
        UUID instanceId,
        int currentRevision,
        List<UUID> attachmentIds
    ) {
        UUID commentId = UUID.randomUUID();
        jdbc.update("""
            insert into ap_approval_comment (
                comment_id, tenant_id, instance_id, parent_comment_id,
                author_id, body, mention_ids_json, attachment_ids_json,
                status, visibility, current_revision, created_at, updated_at,
                deleted_at, deleted_by, delete_reason, version
            ) values (?, 'tenant-a', ?, null, 'manager-1', ?, '[]'::jsonb,
                cast(? as jsonb), 'ACTIVE', 'PARTICIPANTS', ?, ?, ?, null, null, null, 1)
            """,
            commentId,
            instanceId,
            "comment body",
            json(attachmentIds),
            currentRevision,
            offset(START),
            offset(START)
        );
        return commentId;
    }

    private void insertRevision(
        UUID commentId,
        int revisionNumber,
        String revisionType,
        List<UUID> attachmentIds
    ) {
        jdbc.update("""
            insert into ap_approval_comment_revision (
                tenant_id, comment_id, revision_number, revision_type,
                body, mention_ids_json, attachment_ids_json, visibility,
                operator_id, reason, occurred_at
            ) values ('tenant-a', ?, ?, ?, ?, '[]'::jsonb, cast(? as jsonb),
                'PARTICIPANTS', 'manager-1', ?, ?)
            """,
            commentId,
            revisionNumber,
            revisionType,
            "revision body " + revisionNumber,
            json(attachmentIds),
            revisionType.equals("DELETE") ? "delete reason" : null,
            offset(START.plusSeconds(revisionNumber))
        );
    }

    private String notificationStatus(UUID intentId) {
        return jdbc.queryForObject(
            "select status from ap_notification_intent where tenant_id = ? and intent_id = ?",
            String.class,
            "tenant-a",
            intentId
        );
    }

    private int count(String sql, Object... arguments) {
        Integer count = jdbc.queryForObject(sql, Integer.class, arguments);
        return count == null ? 0 : count;
    }

    private static String json(List<UUID> values) {
        return values.stream()
            .map(value -> "\"" + value + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static UserIdentitySnapshot identity(String id, String displayName) {
        return new UserIdentitySnapshot(
            id,
            id,
            displayName,
            null,
            null,
            List.of(),
            Set.of(),
            Set.of(),
            Map.of()
        );
    }

    private static RequestContext context(
        String tenantId,
        String operatorId,
        String requestId,
        String idempotencyKey
    ) {
        return new RequestContext(
            tenantId,
            operatorId,
            requestId,
            idempotencyKey,
            "trace-" + requestId
        );
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private record InstanceTask(UUID instanceId, UUID taskId, String engineTaskId) {
    }
}
