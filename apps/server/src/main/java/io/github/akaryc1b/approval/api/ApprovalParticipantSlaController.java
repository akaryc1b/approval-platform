package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.port.ApprovalParticipantSlaQuery;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaNotFoundException;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaStatus;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/approval/tasks")
public class ApprovalParticipantSlaController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";

    private final ApprovalParticipantSlaQuery query;
    private final Clock clock;

    public ApprovalParticipantSlaController(
        ApprovalParticipantSlaQuery query,
        Clock approvalClock
    ) {
        this.query = query;
        this.clock = approvalClock;
    }

    @GetMapping("/{taskId}/sla")
    public ParticipantTaskSlaResponse findTaskSla(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID taskId
    ) {
        SlaInstance instance = query.findVisibleTaskSla(tenantId, taskId, operatorId)
            .orElseThrow(() -> new SlaNotFoundException(
                "APPROVAL_SLA_INSTANCE_NOT_FOUND",
                "approval SLA was not found"
            ));
        Instant observedAt = clock.instant();
        return new ParticipantTaskSlaResponse(
            instance.slaInstanceId(),
            instance.taskId(),
            instance.status(),
            timingStatus(instance, observedAt),
            remainingMillis(instance, observedAt),
            instance.dueAt(),
            instance.nextReminderAt(),
            instance.overdueAt(),
            instance.timeZone(),
            instance.responsibleUserId(),
            instance.originalResponsibleUserId(),
            !instance.responsibleUserId().equals(instance.originalResponsibleUserId()),
            observedAt
        );
    }

    private static ParticipantTimingStatus timingStatus(
        SlaInstance instance,
        Instant observedAt
    ) {
        if (instance.status() == SlaStatus.PAUSED) {
            return ParticipantTimingStatus.PAUSED;
        }
        if (!observedAt.isBefore(instance.overdueAt())) {
            return ParticipantTimingStatus.OVERDUE;
        }
        if (!observedAt.isBefore(instance.dueAt())) {
            return ParticipantTimingStatus.DUE;
        }
        if (instance.nextReminderAt() != null
            && !observedAt.isBefore(instance.nextReminderAt())) {
            return ParticipantTimingStatus.UPCOMING;
        }
        return ParticipantTimingStatus.ACTIVE;
    }

    private static long remainingMillis(SlaInstance instance, Instant observedAt) {
        return observedAt.isBefore(instance.dueAt())
            ? Duration.between(observedAt, instance.dueAt()).toMillis()
            : 0L;
    }

    public enum ParticipantTimingStatus {
        ACTIVE,
        UPCOMING,
        DUE,
        OVERDUE,
        PAUSED
    }

    public record ParticipantTaskSlaResponse(
        UUID slaInstanceId,
        UUID taskId,
        SlaStatus status,
        ParticipantTimingStatus timingStatus,
        long remainingMillis,
        Instant dueAt,
        Instant nextReminderAt,
        Instant overdueAt,
        String timeZone,
        String responsibleUserId,
        String originalResponsibleUserId,
        boolean responsibilityChanged,
        Instant observedAt
    ) {
    }
}
