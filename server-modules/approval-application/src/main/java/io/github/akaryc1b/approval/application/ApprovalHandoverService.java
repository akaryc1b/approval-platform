package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalHandoverStore;
import io.github.akaryc1b.approval.application.port.ApprovalHandoverStore.AssignmentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalHandoverStore.HandoverStatus;
import io.github.akaryc1b.approval.application.port.ApprovalHandoverStore.HandoverTaskAssignment;
import io.github.akaryc1b.approval.application.port.ApprovalHandoverStore.PendingTask;
import io.github.akaryc1b.approval.application.port.ApprovalHandoverStore.PrincipalHandover;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityCandidate;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityLookup;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory.IdentityReference;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.engine.ApprovalEngine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Administrative employee-departure handover with durable task responsibility evidence.
 */
public final class ApprovalHandoverService {

    private static final String CREATE_OPERATION = "approval.handover.create.v1";
    private static final String REVOKE_OPERATION = "approval.handover.revoke.v1";

    private final IdempotencyGuard idempotencyGuard;
    private final ApprovalIdentityDirectory identities;
    private final ApprovalHandoverStore handovers;
    private final ApprovalProjectionStore projections;
    private final ApprovalEngine engine;
    private final AuditEventSink auditEvents;
    private final Clock clock;
    private final Supplier<UUID> identifierGenerator;

    public ApprovalHandoverService(
        IdempotencyGuard idempotencyGuard,
        ApprovalIdentityDirectory identities,
        ApprovalHandoverStore handovers,
        ApprovalProjectionStore projections,
        ApprovalEngine engine,
        AuditEventSink auditEvents,
        Clock clock,
        Supplier<UUID> identifierGenerator
    ) {
        this.idempotencyGuard = Objects.requireNonNull(
            idempotencyGuard,
            "idempotencyGuard must not be null"
        );
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.handovers = Objects.requireNonNull(handovers, "handovers must not be null");
        this.projections = Objects.requireNonNull(projections, "projections must not be null");
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.auditEvents = Objects.requireNonNull(
            auditEvents,
            "auditEvents must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifierGenerator = Objects.requireNonNull(
            identifierGenerator,
            "identifierGenerator must not be null"
        );
    }

    public CreateHandoverResult create(CreateHandoverCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String connectorKey = requireText(command.connectorKey(), "connectorKey");
        String reason = requireText(command.reason(), "reason");
        IdentityReference principalReference = Objects.requireNonNull(
            command.principalIdentity(),
            "principalIdentity must not be null"
        );
        IdentityReference successorReference = Objects.requireNonNull(
            command.successorIdentity(),
            "successorIdentity must not be null"
        );
        String requestHash = hashValues(
            connectorKey,
            principalReference.canonicalValue(),
            successorReference.canonicalValue(),
            reason
        );
        return idempotencyGuard.execute(
            command.context(),
            CREATE_OPERATION,
            requestHash,
            CreateHandoverResult.class,
            () -> executeCreate(
                command.context(),
                connectorKey,
                principalReference,
                successorReference,
                reason
            )
        );
    }

    public PrincipalHandover revoke(RevokeHandoverCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String reason = requireText(command.reason(), "reason");
        String requestHash = hashValues(command.handoverId().toString(), reason);
        return idempotencyGuard.execute(
            command.context(),
            REVOKE_OPERATION,
            requestHash,
            PrincipalHandover.class,
            () -> executeRevoke(command, reason)
        );
    }

    public List<PrincipalHandover> findByPrincipal(
        String tenantId,
        String principalId,
        boolean includeRevoked
    ) {
        return handovers.findByPrincipal(
            requireText(tenantId, "tenantId"),
            requireText(principalId, "principalId"),
            includeRevoked
        );
    }

    private CreateHandoverResult executeCreate(
        RequestContext context,
        String connectorKey,
        IdentityReference principalReference,
        IdentityReference successorReference,
        String reason
    ) {
        IdentityCandidate principal = identities.requireUser(new IdentityLookup(
            context.tenantId(),
            connectorKey,
            context.requestId(),
            context.traceId(),
            principalReference,
            false
        ));
        IdentityCandidate successor = identities.requireUser(new IdentityLookup(
            context.tenantId(),
            connectorKey,
            context.requestId(),
            context.traceId(),
            successorReference,
            true
        ));
        if (principal.userId().equals(successor.userId())) {
            throw new IllegalArgumentException(
                "successor identity must differ from the departing principal"
            );
        }

        Instant now = clock.instant();
        handovers.lockPrincipal(context.tenantId(), principal.userId());
        PrincipalHandover created = handovers.create(new PrincipalHandover(
            identifierGenerator.get(),
            context.tenantId(),
            connectorKey,
            principal.userId(),
            principal.reference(),
            successor.userId(),
            successor.reference(),
            reason,
            HandoverStatus.ACTIVE,
            context.operatorId(),
            now,
            null,
            null,
            null,
            1
        ));

        List<UUID> transferredTaskIds = new ArrayList<>();
        for (PendingTask task : handovers.findPendingTasksByPrincipal(
            context.tenantId(),
            principal.userId()
        )) {
            transferPendingTask(context, created, task, now);
            transferredTaskIds.add(task.taskId());
        }

        appendAudit(
            context,
            "EMPLOYEE_HANDOVER_CREATED",
            "APPROVAL_HANDOVER",
            created.handoverId().toString(),
            Map.of(
                "principalId", created.principalId(),
                "principalIdentity", created.principalIdentity().canonicalValue(),
                "successorId", created.successorId(),
                "successorIdentity", created.successorIdentity().canonicalValue(),
                "connectorKey", created.connectorKey(),
                "transferredTaskCount", Integer.toString(transferredTaskIds.size())
            ),
            now
        );
        return new CreateHandoverResult(
            created,
            List.copyOf(transferredTaskIds),
            transferredTaskIds.size()
        );
    }

    private void transferPendingTask(
        RequestContext context,
        PrincipalHandover handover,
        PendingTask task,
        Instant changedAt
    ) {
        handovers.lockEngineTask(context.tenantId(), task.engineTaskId());
        if (handovers.findAssignmentByEngineTask(
            context.tenantId(),
            task.engineTaskId()
        ).isPresent()) {
            throw new ApprovalHandoverStore.HandoverConflictException(
                "pending task already contains handover responsibility evidence"
            );
        }
        projections.transferPendingTask(
            context.tenantId(),
            task.taskId(),
            task.assigneeId(),
            handover.successorId(),
            changedAt
        );
        ApprovalEngine.TaskSnapshot transferred = engine.transfer(
            new ApprovalEngine.TransferTaskCommand(
                context.tenantId(),
                task.engineTaskId(),
                task.assigneeId(),
                handover.successorId()
            )
        );
        if (!handover.successorId().equals(transferred.assigneeId())) {
            throw new ApprovalProjectionStore.ProjectionConflictException(
                "engine task handover did not preserve the successor assignee"
            );
        }
        HandoverTaskAssignment assignment = handovers.createAssignment(
            new HandoverTaskAssignment(
                identifierGenerator.get(),
                context.tenantId(),
                task.engineTaskId(),
                task.engineInstanceId(),
                task.definitionKey(),
                task.taskDefinitionKey(),
                handover.principalId(),
                handover.successorId(),
                handover.handoverId(),
                AssignmentStatus.ACTIVE,
                changedAt,
                null,
                null,
                null,
                null,
                null,
                1
            )
        );
        appendAudit(
            context,
            "TASK_HANDOVER_ASSIGNED",
            "APPROVAL_TASK",
            task.taskId().toString(),
            Map.of(
                "definitionKey", assignment.definitionKey(),
                "taskDefinitionKey", assignment.taskDefinitionKey(),
                "principalAssigneeId", assignment.principalAssigneeId(),
                "successorAssigneeId", assignment.successorAssigneeId(),
                "handoverId", assignment.handoverId().toString(),
                "previousEffectiveAssigneeId", task.assigneeId()
            ),
            changedAt
        );
    }

    private PrincipalHandover executeRevoke(
        RevokeHandoverCommand command,
        String reason
    ) {
        Instant now = clock.instant();
        PrincipalHandover revoked = handovers.revoke(
            command.context().tenantId(),
            command.handoverId(),
            command.context().operatorId(),
            reason,
            now
        );
        appendAudit(
            command.context(),
            "EMPLOYEE_HANDOVER_REVOKED",
            "APPROVAL_HANDOVER",
            revoked.handoverId().toString(),
            Map.of(
                "principalId", revoked.principalId(),
                "successorId", revoked.successorId(),
                "connectorKey", revoked.connectorKey()
            ),
            now
        );
        return revoked;
    }

    private void appendAudit(
        RequestContext context,
        String action,
        String aggregateType,
        String aggregateId,
        Map<String, String> attributes,
        Instant occurredAt
    ) {
        auditEvents.append(new AuditEvent(
            identifierGenerator.get(),
            context.tenantId(),
            context.operatorId(),
            action,
            aggregateType,
            aggregateId,
            context.requestId(),
            context.traceId(),
            occurredAt,
            attributes
        ));
    }

    private static String hashValues(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                String normalized = value == null ? "" : value;
                byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    public record CreateHandoverCommand(
        RequestContext context,
        String connectorKey,
        IdentityReference principalIdentity,
        IdentityReference successorIdentity,
        String reason
    ) {
        public CreateHandoverCommand {
            context = Objects.requireNonNull(context, "context must not be null");
        }
    }

    public record CreateHandoverResult(
        PrincipalHandover handover,
        List<UUID> transferredTaskIds,
        int transferredTaskCount
    ) {
        public CreateHandoverResult {
            handover = Objects.requireNonNull(handover, "handover must not be null");
            transferredTaskIds = transferredTaskIds == null
                ? List.of()
                : List.copyOf(transferredTaskIds);
            if (transferredTaskCount != transferredTaskIds.size()) {
                throw new IllegalArgumentException(
                    "transferredTaskCount must match transferredTaskIds"
                );
            }
        }
    }

    public record RevokeHandoverCommand(
        RequestContext context,
        UUID handoverId,
        String reason
    ) {
        public RevokeHandoverCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            handoverId = Objects.requireNonNull(handoverId, "handoverId must not be null");
        }
    }
}
