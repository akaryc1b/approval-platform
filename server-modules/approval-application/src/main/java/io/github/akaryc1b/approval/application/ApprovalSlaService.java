package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.CalendarSnapshot;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.DayOverride;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.WorkingInterval;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.ApprovalRequestEvidenceProvider.RequestEvidence;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.AutomaticAction;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarPage;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.EscalationTargetType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.PolicyStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.ResponsibilityChange;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.ResponsibilityChangeSource;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaDurationMode;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstanceCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstancePage;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyPage;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTargetType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTerminalReason;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.CollaborationParticipant;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.ParticipantStatus;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore.TaskCollaboration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Versioned work-calendar and immutable SLA lifecycle orchestration. */
public final class ApprovalSlaService {

    private static final Duration ZERO = Duration.ZERO;

    private final ApprovalSlaStore store;
    private final ApprovalWorkingTimeCalculator workingTime;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public ApprovalSlaService(
        ApprovalSlaStore store,
        ApprovalWorkingTimeCalculator workingTime,
        Clock clock,
        Supplier<UUID> identifiers
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.workingTime = Objects.requireNonNull(workingTime, "workingTime must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
    }

    public CalendarIdentity createCalendar(CreateCalendarCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateZone(command.timeZone());
        store.findCalendarByKey(command.tenantId(), command.calendarKey()).ifPresent(existing -> {
            throw conflict("APPROVAL_CALENDAR_VERSION_CONFLICT", "calendar key already exists");
        });
        Instant now = clock.instant();
        return store.createCalendar(new CalendarIdentity(
            identifiers.get(),
            command.tenantId(),
            command.calendarKey(),
            command.displayName(),
            command.timeZone(),
            CalendarStatus.DRAFT,
            null,
            command.createdBy(),
            now,
            now,
            1
        ));
    }

    public CalendarVersion saveCalendarVersion(SaveCalendarVersionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        CalendarIdentity identity = requireCalendar(command.tenantId(), command.calendarId());
        if (identity.version() != command.expectedCalendarIdentityVersion()) {
            throw conflict(
                "APPROVAL_CALENDAR_VERSION_CONFLICT",
                "calendar identity changed concurrently"
            );
        }
        String contentHash = calendarHash(command);
        CalendarSnapshot snapshot = CalendarSnapshot.of(
            command.calendarId(),
            command.tenantId(),
            command.calendarVersion(),
            command.timeZone(),
            command.weeklySchedule(),
            command.overrides(),
            contentHash
        );
        Instant now = clock.instant();
        CalendarVersion version = new CalendarVersion(
            command.calendarId(),
            command.tenantId(),
            command.calendarVersion(),
            command.effectiveFrom(),
            command.effectiveTo(),
            snapshot,
            CalendarStatus.DRAFT,
            false,
            null,
            null,
            now,
            now
        );
        return store.saveCalendarVersion(version, command.expectedCalendarIdentityVersion());
    }

    public CalendarValidation validateCalendar(SaveCalendarVersionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String contentHash = calendarHash(command);
        CalendarSnapshot snapshot = CalendarSnapshot.of(
            command.calendarId(),
            command.tenantId(),
            command.calendarVersion(),
            command.timeZone(),
            command.weeklySchedule(),
            command.overrides(),
            contentHash
        );
        Instant validationStart = command.effectiveFrom() == null
            ? clock.instant() : command.effectiveFrom();
        Instant firstWorkingInstant;
        try {
            firstWorkingInstant = workingTime.nextWorkingInstant(snapshot, validationStart);
        } catch (ApprovalWorkingTimeCalculator.WorkingTimeUnavailableException exception) {
            throw failure(
                "APPROVAL_WORKING_TIME_UNAVAILABLE",
                exception.getMessage(),
                false,
                exception
            );
        }
        return new CalendarValidation(
            contentHash,
            snapshot.weeklySchedule().values().stream().mapToInt(List::size).sum(),
            snapshot.overrides().size(),
            firstWorkingInstant
        );
    }

    public CalendarVersion publishCalendarVersion(PublishCalendarCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        CalendarVersion existing = requireCalendarVersion(
            command.tenantId(),
            command.calendarId(),
            command.calendarVersion()
        );
        if (existing.immutable()) {
            return existing;
        }
        return store.publishCalendarVersion(
            command.tenantId(),
            command.calendarId(),
            command.calendarVersion(),
            command.operatorId(),
            clock.instant(),
            command.expectedCalendarIdentityVersion()
        );
    }

    public CalendarIdentity activateCalendarVersion(ActivateCalendarCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        CalendarVersion version = requireCalendarVersion(
            command.tenantId(),
            command.calendarId(),
            command.calendarVersion()
        );
        if (!version.immutable()) {
            throw conflict(
                "APPROVAL_CALENDAR_VERSION_CONFLICT",
                "calendar version must be published before activation"
            );
        }
        CalendarIdentity identity = requireCalendar(command.tenantId(), command.calendarId());
        if (Objects.equals(identity.activeVersion(), command.calendarVersion())) {
            return identity;
        }
        return store.activateCalendarVersion(
            command.tenantId(),
            command.calendarId(),
            command.calendarVersion(),
            command.operatorId(),
            clock.instant(),
            command.expectedCalendarIdentityVersion()
        );
    }

    public Optional<CalendarIdentity> findCalendar(String tenantId, UUID calendarId) {
        return store.findCalendar(requireText(tenantId, "tenantId"), Objects.requireNonNull(calendarId));
    }

    public Optional<CalendarVersion> findCalendarVersion(
        String tenantId,
        UUID calendarId,
        int calendarVersion
    ) {
        return store.findCalendarVersion(
            requireText(tenantId, "tenantId"),
            Objects.requireNonNull(calendarId),
            calendarVersion
        );
    }

    public CalendarPage findCalendars(String tenantId, int limit, int offset) {
        return store.findCalendars(requireText(tenantId, "tenantId"), limit, offset);
    }

    public SlaPolicyIdentity createPolicy(CreatePolicyCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        store.findPolicyByKey(command.tenantId(), command.policyKey()).ifPresent(existing -> {
            throw conflict("APPROVAL_SLA_POLICY_VERSION_CONFLICT", "policy key already exists");
        });
        Instant now = clock.instant();
        return store.createPolicy(new SlaPolicyIdentity(
            identifiers.get(),
            command.tenantId(),
            command.policyKey(),
            command.displayName(),
            PolicyStatus.DRAFT,
            null,
            command.createdBy(),
            now,
            now,
            1
        ));
    }

    public SlaPolicyVersion savePolicyVersion(SavePolicyVersionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        SlaPolicyIdentity identity = requirePolicy(command.tenantId(), command.policyId());
        if (identity.version() != command.expectedPolicyIdentityVersion()) {
            throw conflict(
                "APPROVAL_SLA_POLICY_VERSION_CONFLICT",
                "SLA policy identity changed concurrently"
            );
        }
        if (command.durationMode() == SlaDurationMode.WORKING_TIME) {
            CalendarVersion calendar = requireCalendarVersion(
                command.tenantId(),
                Objects.requireNonNull(command.calendarId()),
                Objects.requireNonNull(command.calendarVersion())
            );
            if (!calendar.immutable()) {
                throw conflict(
                    "APPROVAL_SLA_POLICY_VERSION_CONFLICT",
                    "working-time policy requires a published calendar version"
                );
            }
        }
        String contentHash = policyHash(command);
        Instant now = clock.instant();
        SlaPolicyVersion version = new SlaPolicyVersion(
            command.policyId(),
            command.tenantId(),
            command.policyVersion(),
            command.definitionKey(),
            command.releaseVersion(),
            command.taskDefinitionKey(),
            command.targetType(),
            command.durationMode(),
            command.duration(),
            command.calendarId(),
            command.calendarVersion(),
            command.firstReminderOffset(),
            command.repeatReminderInterval(),
            command.maximumReminderCount(),
            command.overdueOffset(),
            command.escalationTargetType(),
            command.escalationTarget(),
            command.automaticAction(),
            command.naturalTimePauses(),
            contentHash,
            PolicyStatus.DRAFT,
            false,
            null,
            null,
            now,
            now
        );
        return store.savePolicyVersion(version, command.expectedPolicyIdentityVersion());
    }

    public PolicyValidation validatePolicy(SavePolicyVersionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        SlaPolicyIdentity identity = requirePolicy(command.tenantId(), command.policyId());
        if (identity.version() != command.expectedPolicyIdentityVersion()) {
            throw conflict(
                "APPROVAL_SLA_POLICY_VERSION_CONFLICT",
                "SLA policy identity changed concurrently"
            );
        }
        String contentHash = policyHash(command);
        Instant now = clock.instant();
        new SlaPolicyVersion(
            command.policyId(),
            command.tenantId(),
            command.policyVersion(),
            command.definitionKey(),
            command.releaseVersion(),
            command.taskDefinitionKey(),
            command.targetType(),
            command.durationMode(),
            command.duration(),
            command.calendarId(),
            command.calendarVersion(),
            command.firstReminderOffset(),
            command.repeatReminderInterval(),
            command.maximumReminderCount(),
            command.overdueOffset(),
            command.escalationTargetType(),
            command.escalationTarget(),
            command.automaticAction(),
            command.naturalTimePauses(),
            contentHash,
            PolicyStatus.DRAFT,
            false,
            null,
            null,
            now,
            now
        );
        if (command.durationMode() == SlaDurationMode.WORKING_TIME) {
            CalendarVersion calendar = requireCalendarVersion(
                command.tenantId(),
                Objects.requireNonNull(command.calendarId()),
                Objects.requireNonNull(command.calendarVersion())
            );
            if (!calendar.immutable()) {
                throw conflict(
                    "APPROVAL_SLA_POLICY_VERSION_CONFLICT",
                    "working-time policy requires a published calendar version"
                );
            }
            try {
                workingTime.addWorkingDuration(
                    calendar.snapshot(),
                    now,
                    command.duration()
                );
            } catch (RuntimeException exception) {
                throw failure(
                    "APPROVAL_SLA_CALCULATION_FAILED",
                    "SLA policy cannot be calculated with the selected calendar",
                    false,
                    exception
                );
            }
        }
        return new PolicyValidation(
            contentHash,
            command.durationMode(),
            command.maximumReminderCount(),
            command.automaticAction() == null ? AutomaticAction.NONE : command.automaticAction()
        );
    }

    public SlaPolicyVersion publishPolicyVersion(PublishPolicyCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        SlaPolicyVersion existing = requirePolicyVersion(
            command.tenantId(),
            command.policyId(),
            command.policyVersion()
        );
        if (existing.immutable()) {
            return existing;
        }
        return store.publishPolicyVersion(
            command.tenantId(),
            command.policyId(),
            command.policyVersion(),
            command.operatorId(),
            clock.instant(),
            command.expectedPolicyIdentityVersion()
        );
    }

    public SlaPolicyIdentity activatePolicyVersion(ActivatePolicyCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        SlaPolicyVersion version = requirePolicyVersion(
            command.tenantId(),
            command.policyId(),
            command.policyVersion()
        );
        if (!version.immutable()) {
            throw conflict(
                "APPROVAL_SLA_POLICY_ACTIVATION_CONFLICT",
                "SLA policy version must be published before activation"
            );
        }
        SlaPolicyIdentity identity = requirePolicy(command.tenantId(), command.policyId());
        if (Objects.equals(identity.activeVersion(), command.policyVersion())) {
            return identity;
        }
        return store.activatePolicyVersion(
            command.tenantId(),
            command.policyId(),
            command.policyVersion(),
            command.operatorId(),
            clock.instant(),
            command.expectedPolicyIdentityVersion()
        );
    }

    public Optional<SlaPolicyIdentity> findPolicy(String tenantId, UUID policyId) {
        return store.findPolicy(requireText(tenantId, "tenantId"), Objects.requireNonNull(policyId));
    }

    public Optional<SlaPolicyVersion> findPolicyVersion(
        String tenantId,
        UUID policyId,
        int policyVersion
    ) {
        return store.findPolicyVersion(
            requireText(tenantId, "tenantId"),
            Objects.requireNonNull(policyId),
            policyVersion
        );
    }

    public SlaPolicyPage findPolicies(String tenantId, int limit, int offset) {
        return store.findPolicies(requireText(tenantId, "tenantId"), limit, offset);
    }

    public Optional<SlaInstance> findInstance(String tenantId, UUID slaInstanceId) {
        return store.findInstance(
            requireText(tenantId, "tenantId"),
            Objects.requireNonNull(slaInstanceId)
        );
    }

    public Optional<ParticipantSlaView> findParticipantTaskSla(String tenantId, UUID taskId) {
        String trustedTenantId = requireText(tenantId, "tenantId");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Instant observedAt = clock.instant();
        return store.findActiveTaskInstance(trustedTenantId, taskId)
            .map(instance -> participantView(instance, observedAt));
    }

    public SlaInstancePage findInstances(SlaInstanceCriteria criteria) {
        return store.findInstances(Objects.requireNonNull(criteria, "criteria must not be null"));
    }

    public SlaInstance pause(PauseCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        SlaInstance instance = requireSlaInstance(command.tenantId(), command.slaInstanceId());
        if (instance.status() == SlaStatus.TERMINAL) {
            throw conflict(
                "APPROVAL_SLA_INSTANCE_STATE_CONFLICT",
                "terminal SLA cannot be paused"
            );
        }
        if (instance.status() == SlaStatus.PAUSED) {
            return instance;
        }
        return store.pause(
            command.tenantId(),
            command.slaInstanceId(),
            command.expectedVersion(),
            clock.instant(),
            command.reason()
        );
    }

    public SlaInstance resume(ResumeCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        SlaInstance instance = requireSlaInstance(command.tenantId(), command.slaInstanceId());
        if (instance.status() == SlaStatus.TERMINAL) {
            throw conflict(
                "APPROVAL_SLA_INSTANCE_STATE_CONFLICT",
                "terminal SLA cannot be resumed"
            );
        }
        if (instance.status() == SlaStatus.ACTIVE) {
            return instance;
        }
        Instant resumedAt = clock.instant();
        SlaPolicyVersion policy = requirePolicyVersion(
            instance.tenantId(),
            instance.policyId(),
            instance.policyVersion()
        );
        Instant dueAt = resumedDueAt(instance, policy, resumedAt);
        Instant nextReminderAt = nextReminderAt(policy, instance.startedAt(), dueAt, resumedAt);
        Instant overdueAt = dueAt.plus(orZero(policy.overdueOffset()));
        Duration pausedDuration = Duration.between(instance.pausedAt(), resumedAt);
        Duration accumulated = instance.accumulatedPausedDuration().plus(pausedDuration);
        return store.resume(
            command.tenantId(),
            command.slaInstanceId(),
            command.expectedVersion(),
            dueAt,
            nextReminderAt,
            overdueAt,
            accumulated,
            resumedAt
        );
    }

    public void synchronizeNewInstance(
        InstanceProjection instance,
        List<TaskProjection> tasks,
        RequestEvidence evidence
    ) {
        Objects.requireNonNull(instance, "instance must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        List<SlaInstance> requested = new ArrayList<>();
        if (store.findActiveProcessInstance(instance.tenantId(), instance.instanceId()).isEmpty()) {
            effectivePolicy(
                instance,
                null,
                SlaTargetType.PROCESS
            ).map(policy -> newInstance(
                instance,
                null,
                null,
                instance.initiatorId(),
                SlaTargetType.PROCESS,
                policy,
                instance.createdAt(),
                evidence
            )).ifPresent(requested::add);
        }
        requested.addAll(newTaskInstances(instance, tasks, evidence));
        store.createInstances(requested);
    }

    public void synchronizeTaskChange(
        InstanceProjection instance,
        UUID changedTaskId,
        List<TaskProjection> activeTasks,
        InstanceStatus instanceStatus,
        boolean changedTaskCanceled,
        RequestEvidence evidence
    ) {
        Objects.requireNonNull(instance, "instance must not be null");
        Instant now = clock.instant();
        if (instanceStatus == InstanceStatus.COMPLETED) {
            store.terminalApprovalInstance(
                instance.tenantId(),
                instance.instanceId(),
                SlaTerminalReason.INSTANCE_COMPLETED,
                now
            );
            return;
        }
        if (instanceStatus == InstanceStatus.REJECTED) {
            store.terminalApprovalInstance(
                instance.tenantId(),
                instance.instanceId(),
                SlaTerminalReason.INSTANCE_REJECTED,
                now
            );
            return;
        }
        store.terminalTask(
            instance.tenantId(),
            changedTaskId,
            changedTaskCanceled ? SlaTerminalReason.TASK_CANCELED : SlaTerminalReason.TASK_COMPLETED,
            now
        );
        Set<UUID> activeTaskIds = new LinkedHashSet<>();
        for (TaskProjection task : activeTasks == null ? List.<TaskProjection>of() : activeTasks) {
            if (task.status() == TaskStatus.PENDING) {
                activeTaskIds.add(task.taskId());
            }
        }
        for (SlaInstance current : store.findActiveByApprovalInstance(
            instance.tenantId(),
            instance.instanceId()
        )) {
            if (current.targetType() != SlaTargetType.PROCESS
                && current.taskId() != null
                && !activeTaskIds.contains(current.taskId())) {
                store.terminalTask(
                    instance.tenantId(),
                    current.taskId(),
                    SlaTerminalReason.TASK_CANCELED,
                    now
                );
            }
        }
        store.createInstances(newTaskInstances(instance, activeTasks, evidence));
    }

    public void terminalWithdrawnInstance(InstanceProjection instance) {
        Objects.requireNonNull(instance, "instance must not be null");
        store.terminalApprovalInstance(
            instance.tenantId(),
            instance.instanceId(),
            SlaTerminalReason.INSTANCE_WITHDRAWN,
            clock.instant()
        );
    }

    public void transferTaskResponsibility(
        String tenantId,
        UUID taskId,
        String previousUserId,
        String newUserId,
        ResponsibilityChangeSource source,
        String reason,
        RequestEvidence evidence
    ) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        store.findActiveTaskInstance(tenantId, taskId).ifPresent(instance -> {
            if (instance.responsibleUserId().equals(newUserId)) {
                return;
            }
            store.changeResponsibility(new ResponsibilityChange(
                identifiers.get(),
                instance.slaInstanceId(),
                tenantId,
                previousUserId,
                newUserId,
                source,
                reason,
                evidence.actorId(),
                clock.instant(),
                evidence.requestId(),
                evidence.traceId()
            ), instance.version());
        });
    }

    public void synchronizeCollaboration(TaskCollaboration collaboration, RequestEvidence evidence) {
        Objects.requireNonNull(collaboration, "collaboration must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        if (collaboration.status() != io.github.akaryc1b.approval.application.port
            .ApprovalTaskCollaborationStore.CollaborationStatus.ACTIVE) {
            store.terminalCollaborationParticipantsByTask(
                collaboration.tenantId(),
                collaboration.taskId(),
                SlaTerminalReason.COLLABORATION_CANCELED,
                clock.instant()
            );
            return;
        }
        List<SlaInstance> requested = new ArrayList<>();
        Set<UUID> pending = new LinkedHashSet<>();
        for (CollaborationParticipant participant : collaboration.participants()) {
            if (participant.status() != ParticipantStatus.PENDING) {
                SlaTerminalReason reason = participant.status() == ParticipantStatus.REMOVED
                    ? SlaTerminalReason.COLLABORATION_REMOVED
                    : participant.status() == ParticipantStatus.CANCELED
                        ? SlaTerminalReason.COLLABORATION_CANCELED
                        : SlaTerminalReason.COLLABORATION_DECIDED;
                store.terminalCollaborationParticipant(
                    collaboration.tenantId(),
                    participant.participantId(),
                    reason,
                    clock.instant()
                );
                continue;
            }
            pending.add(participant.participantId());
            if (store.findActiveCollaborationInstance(
                collaboration.tenantId(),
                participant.participantId()
            ).isPresent()) {
                continue;
            }
            effectiveCollaborationPolicy(collaboration).map(policy -> newCollaborationInstance(
                collaboration,
                participant,
                policy,
                evidence
            )).ifPresent(requested::add);
        }
        for (SlaInstance current : store.findActiveByApprovalInstance(
            collaboration.tenantId(),
            collaboration.instanceId()
        )) {
            if (current.targetType() == SlaTargetType.COLLABORATION_PARTICIPANT
                && current.taskId().equals(collaboration.taskId())
                && !pending.contains(current.collaborationParticipantId())) {
                store.terminalCollaborationParticipant(
                    collaboration.tenantId(),
                    current.collaborationParticipantId(),
                    SlaTerminalReason.COLLABORATION_REMOVED,
                    clock.instant()
                );
            }
        }
        store.createInstances(requested);
    }

    private List<SlaInstance> newTaskInstances(
        InstanceProjection instance,
        List<TaskProjection> tasks,
        RequestEvidence evidence
    ) {
        List<SlaInstance> requested = new ArrayList<>();
        for (TaskProjection task : tasks == null ? List.<TaskProjection>of() : tasks) {
            if (task.status() != TaskStatus.PENDING
                || store.findActiveTaskInstance(instance.tenantId(), task.taskId()).isPresent()) {
                continue;
            }
            effectivePolicy(instance, task.taskDefinitionKey(), SlaTargetType.TASK)
                .map(policy -> newInstance(
                    instance,
                    task,
                    null,
                    task.assigneeId(),
                    SlaTargetType.TASK,
                    policy,
                    task.createdAt(),
                    evidence
                )).ifPresent(requested::add);
        }
        return requested;
    }

    private Optional<SlaPolicyVersion> effectivePolicy(
        InstanceProjection instance,
        String taskDefinitionKey,
        SlaTargetType targetType
    ) {
        Optional<SlaPolicyVersion> exact = store.findEffectivePolicy(
            instance.tenantId(),
            instance.definitionKey(),
            instance.releaseVersion(),
            taskDefinitionKey,
            targetType
        );
        if (exact.isPresent() || targetType == SlaTargetType.PROCESS) {
            return exact;
        }
        return store.findEffectivePolicy(
            instance.tenantId(),
            instance.definitionKey(),
            instance.releaseVersion(),
            null,
            SlaTargetType.PROCESS
        );
    }

    private Optional<SlaPolicyVersion> effectiveCollaborationPolicy(
        TaskCollaboration collaboration
    ) {
        Optional<SlaPolicyVersion> exact = store.findEffectivePolicy(
            collaboration.tenantId(),
            collaboration.definitionKey(),
            null,
            collaboration.taskDefinitionKey(),
            SlaTargetType.COLLABORATION_PARTICIPANT
        );
        if (exact.isPresent()) {
            return exact;
        }
        Optional<SlaPolicyVersion> task = store.findEffectivePolicy(
            collaboration.tenantId(),
            collaboration.definitionKey(),
            null,
            collaboration.taskDefinitionKey(),
            SlaTargetType.TASK
        );
        return task.isPresent() ? task : store.findEffectivePolicy(
            collaboration.tenantId(),
            collaboration.definitionKey(),
            null,
            null,
            SlaTargetType.PROCESS
        );
    }

    private SlaInstance newInstance(
        InstanceProjection instance,
        TaskProjection task,
        UUID collaborationParticipantId,
        String responsibleUserId,
        SlaTargetType targetType,
        SlaPolicyVersion policy,
        Instant startedAt,
        RequestEvidence evidence
    ) {
        TimePlan plan = timePlan(policy, startedAt);
        return new SlaInstance(
            identifiers.get(),
            instance.tenantId(),
            instance.instanceId(),
            task == null ? null : task.taskId(),
            collaborationParticipantId,
            instance.definitionKey(),
            task == null ? null : task.taskDefinitionKey(),
            targetType,
            policy.policyId(),
            policy.policyVersion(),
            policy.calendarId(),
            policy.calendarVersion(),
            plan.timeZone(),
            responsibleUserId,
            responsibleUserId,
            startedAt,
            plan.dueAt(),
            plan.nextReminderAt(),
            plan.overdueAt(),
            null,
            null,
            ZERO,
            null,
            null,
            SlaStatus.ACTIVE,
            0,
            evidence.requestId(),
            evidence.traceId(),
            1,
            startedAt,
            startedAt
        );
    }

    private SlaInstance newCollaborationInstance(
        TaskCollaboration collaboration,
        CollaborationParticipant participant,
        SlaPolicyVersion policy,
        RequestEvidence evidence
    ) {
        TimePlan plan = timePlan(policy, participant.addedAt());
        return new SlaInstance(
            identifiers.get(),
            collaboration.tenantId(),
            collaboration.instanceId(),
            collaboration.taskId(),
            participant.participantId(),
            collaboration.definitionKey(),
            collaboration.taskDefinitionKey(),
            SlaTargetType.COLLABORATION_PARTICIPANT,
            policy.policyId(),
            policy.policyVersion(),
            policy.calendarId(),
            policy.calendarVersion(),
            plan.timeZone(),
            participant.participantUserId(),
            participant.participantUserId(),
            participant.addedAt(),
            plan.dueAt(),
            plan.nextReminderAt(),
            plan.overdueAt(),
            null,
            null,
            ZERO,
            null,
            null,
            SlaStatus.ACTIVE,
            0,
            evidence.requestId(),
            evidence.traceId(),
            1,
            participant.addedAt(),
            participant.addedAt()
        );
    }

    private static ParticipantSlaView participantView(SlaInstance instance, Instant observedAt) {
        ParticipantTimingStatus timingStatus;
        if (instance.status() == SlaStatus.PAUSED) {
            timingStatus = ParticipantTimingStatus.PAUSED;
        } else if (!observedAt.isBefore(instance.overdueAt())) {
            timingStatus = ParticipantTimingStatus.OVERDUE;
        } else if (!observedAt.isBefore(instance.dueAt())) {
            timingStatus = ParticipantTimingStatus.DUE;
        } else if (instance.nextReminderAt() != null
            && !observedAt.isBefore(instance.nextReminderAt())) {
            timingStatus = ParticipantTimingStatus.UPCOMING;
        } else {
            timingStatus = ParticipantTimingStatus.ACTIVE;
        }
        long remainingMillis = observedAt.isBefore(instance.dueAt())
            ? Duration.between(observedAt, instance.dueAt()).toMillis() : 0;
        return new ParticipantSlaView(
            instance.slaInstanceId(),
            instance.taskId(),
            instance.status(),
            timingStatus,
            remainingMillis,
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

    private TimePlan timePlan(SlaPolicyVersion policy, Instant startedAt) {
        try {
            Instant dueAt;
            String timeZone;
            if (policy.durationMode() == SlaDurationMode.NATURAL_TIME) {
                dueAt = startedAt.plus(policy.duration());
                timeZone = "UTC";
            } else {
                CalendarVersion calendar = requireCalendarVersion(
                    policy.tenantId(),
                    policy.calendarId(),
                    policy.calendarVersion()
                );
                dueAt = workingTime.addWorkingDuration(
                    calendar.snapshot(),
                    startedAt,
                    policy.duration()
                );
                timeZone = calendar.snapshot().zoneId().getId();
            }
            return new TimePlan(
                dueAt,
                nextReminderAt(policy, startedAt, dueAt, startedAt),
                dueAt.plus(orZero(policy.overdueOffset())),
                timeZone
            );
        } catch (RuntimeException exception) {
            if (exception instanceof ApprovalSlaException approvalSlaException) {
                throw approvalSlaException;
            }
            throw failure(
                "APPROVAL_SLA_CALCULATION_FAILED",
                "SLA due time could not be calculated",
                false,
                exception
            );
        }
    }

    private Instant resumedDueAt(
        SlaInstance instance,
        SlaPolicyVersion policy,
        Instant resumedAt
    ) {
        if (policy.durationMode() == SlaDurationMode.NATURAL_TIME) {
            if (!policy.naturalTimePauses()) {
                return instance.dueAt();
            }
            Duration remaining = instance.dueAt().isAfter(instance.pausedAt())
                ? Duration.between(instance.pausedAt(), instance.dueAt()) : ZERO;
            return resumedAt.plus(remaining);
        }
        CalendarVersion calendar = requireCalendarVersion(
            instance.tenantId(),
            instance.calendarId(),
            instance.calendarVersion()
        );
        Duration remaining = instance.dueAt().isAfter(instance.pausedAt())
            ? workingTime.workingDurationBetween(
                calendar.snapshot(),
                instance.pausedAt(),
                instance.dueAt()
            ) : ZERO;
        return workingTime.addWorkingDuration(calendar.snapshot(), resumedAt, remaining);
    }

    private static Instant nextReminderAt(
        SlaPolicyVersion policy,
        Instant startedAt,
        Instant dueAt,
        Instant lowerBound
    ) {
        if (policy.maximumReminderCount() < 1 || policy.firstReminderOffset() == null) {
            return null;
        }
        Instant reminder = dueAt.minus(policy.firstReminderOffset());
        if (reminder.isBefore(startedAt)) {
            reminder = startedAt;
        }
        return reminder.isBefore(lowerBound) ? lowerBound : reminder;
    }

    private CalendarIdentity requireCalendar(String tenantId, UUID calendarId) {
        return store.findCalendar(tenantId, calendarId).orElseThrow(() -> failure(
            "APPROVAL_CALENDAR_NOT_FOUND",
            "work calendar was not found",
            false,
            null
        ));
    }

    private CalendarVersion requireCalendarVersion(
        String tenantId,
        UUID calendarId,
        int calendarVersion
    ) {
        return store.findCalendarVersion(tenantId, calendarId, calendarVersion)
            .orElseThrow(() -> failure(
                "APPROVAL_CALENDAR_NOT_FOUND",
                "work calendar version was not found",
                false,
                null
            ));
    }

    private SlaPolicyIdentity requirePolicy(String tenantId, UUID policyId) {
        return store.findPolicy(tenantId, policyId).orElseThrow(() -> failure(
            "APPROVAL_SLA_POLICY_NOT_FOUND",
            "SLA policy was not found",
            false,
            null
        ));
    }

    private SlaPolicyVersion requirePolicyVersion(
        String tenantId,
        UUID policyId,
        int policyVersion
    ) {
        return store.findPolicyVersion(tenantId, policyId, policyVersion)
            .orElseThrow(() -> failure(
                "APPROVAL_SLA_POLICY_NOT_FOUND",
                "SLA policy version was not found",
                false,
                null
            ));
    }

    private SlaInstance requireSlaInstance(String tenantId, UUID slaInstanceId) {
        return store.findInstance(tenantId, slaInstanceId).orElseThrow(() -> failure(
            "APPROVAL_SLA_INSTANCE_NOT_FOUND",
            "SLA instance was not found",
            false,
            null
        ));
    }

    private static void validateZone(String zoneId) {
        try {
            ZoneId.of(requireText(zoneId, "timeZone"));
        } catch (java.time.DateTimeException exception) {
            throw failure(
                "APPROVAL_TIME_ZONE_INVALID",
                "timeZone must be a valid IANA zone",
                false,
                exception
            );
        }
    }

    private static String calendarHash(SaveCalendarVersionCommand command) {
        validateZone(command.timeZone());
        List<String> values = new ArrayList<>();
        values.add(command.timeZone());
        values.add(String.valueOf(command.effectiveFrom()));
        values.add(String.valueOf(command.effectiveTo()));
        command.weeklySchedule().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> entry.getValue().stream()
                .sorted(Comparator.comparing(WorkingInterval::start))
                .forEach(interval -> values.add(
                    "W:" + entry.getKey().getValue() + ':' + interval.start() + '-' + interval.end()
                )));
        command.overrides().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                values.add("O:" + entry.getKey() + ':' + entry.getValue().working());
                entry.getValue().intervals().forEach(interval -> values.add(
                    "I:" + interval.start() + '-' + interval.end()
                ));
            });
        return hash(values);
    }

    private static String policyHash(SavePolicyVersionCommand command) {
        return hash(List.of(
            command.definitionKey(),
            String.valueOf(command.releaseVersion()),
            String.valueOf(command.taskDefinitionKey()),
            command.targetType().name(),
            command.durationMode().name(),
            command.duration().toString(),
            String.valueOf(command.calendarId()),
            String.valueOf(command.calendarVersion()),
            String.valueOf(command.firstReminderOffset()),
            String.valueOf(command.repeatReminderInterval()),
            Integer.toString(command.maximumReminderCount()),
            String.valueOf(command.overdueOffset()),
            String.valueOf(command.escalationTargetType()),
            String.valueOf(command.escalationTarget()),
            String.valueOf(command.automaticAction()),
            Boolean.toString(command.naturalTimePauses())
        ));
    }

    private static String hash(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Duration orZero(Duration value) {
        return value == null ? ZERO : value;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static ApprovalSlaException conflict(String code, String message) {
        return failure(code, message, false, null);
    }

    private static ApprovalSlaException failure(
        String code,
        String message,
        boolean retryable,
        Throwable cause
    ) {
        return cause == null
            ? new ApprovalSlaException(code, message, retryable)
            : new ApprovalSlaException(code, message, retryable, cause);
    }

    private record TimePlan(
        Instant dueAt,
        Instant nextReminderAt,
        Instant overdueAt,
        String timeZone
    ) {
    }

    public record CreateCalendarCommand(
        String tenantId,
        String calendarKey,
        String displayName,
        String timeZone,
        String createdBy
    ) {
        public CreateCalendarCommand {
            tenantId = requireText(tenantId, "tenantId");
            calendarKey = requireText(calendarKey, "calendarKey");
            displayName = requireText(displayName, "displayName");
            timeZone = requireText(timeZone, "timeZone");
            createdBy = requireText(createdBy, "createdBy");
        }
    }

    public record SaveCalendarVersionCommand(
        String tenantId,
        UUID calendarId,
        int calendarVersion,
        String timeZone,
        Instant effectiveFrom,
        Instant effectiveTo,
        Map<DayOfWeek, List<WorkingInterval>> weeklySchedule,
        Map<LocalDate, DayOverride> overrides,
        long expectedCalendarIdentityVersion
    ) {
        public SaveCalendarVersionCommand {
            tenantId = requireText(tenantId, "tenantId");
            calendarId = Objects.requireNonNull(calendarId, "calendarId must not be null");
            if (calendarVersion < 1 || expectedCalendarIdentityVersion < 1) {
                throw new IllegalArgumentException("calendar versions must be positive");
            }
            timeZone = requireText(timeZone, "timeZone");
            weeklySchedule = copySchedule(weeklySchedule);
            overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
            if (effectiveFrom != null && effectiveTo != null && !effectiveFrom.isBefore(effectiveTo)) {
                throw new IllegalArgumentException("effectiveFrom must be before effectiveTo");
            }
        }
    }

    public record PublishCalendarCommand(
        String tenantId,
        UUID calendarId,
        int calendarVersion,
        String operatorId,
        long expectedCalendarIdentityVersion
    ) {
    }

    public record ActivateCalendarCommand(
        String tenantId,
        UUID calendarId,
        int calendarVersion,
        String operatorId,
        long expectedCalendarIdentityVersion
    ) {
    }

    public record CalendarValidation(
        String contentHash,
        int weeklyIntervalCount,
        int overrideCount,
        Instant firstWorkingInstant
    ) {
    }

    public record CreatePolicyCommand(
        String tenantId,
        String policyKey,
        String displayName,
        String createdBy
    ) {
    }

    public record SavePolicyVersionCommand(
        String tenantId,
        UUID policyId,
        int policyVersion,
        String definitionKey,
        Integer releaseVersion,
        String taskDefinitionKey,
        SlaTargetType targetType,
        SlaDurationMode durationMode,
        Duration duration,
        UUID calendarId,
        Integer calendarVersion,
        Duration firstReminderOffset,
        Duration repeatReminderInterval,
        int maximumReminderCount,
        Duration overdueOffset,
        EscalationTargetType escalationTargetType,
        String escalationTarget,
        AutomaticAction automaticAction,
        boolean naturalTimePauses,
        long expectedPolicyIdentityVersion
    ) {
    }

    public record PublishPolicyCommand(
        String tenantId,
        UUID policyId,
        int policyVersion,
        String operatorId,
        long expectedPolicyIdentityVersion
    ) {
    }

    public record ActivatePolicyCommand(
        String tenantId,
        UUID policyId,
        int policyVersion,
        String operatorId,
        long expectedPolicyIdentityVersion
    ) {
    }

    public record PolicyValidation(
        String contentHash,
        SlaDurationMode durationMode,
        int maximumReminderCount,
        AutomaticAction automaticAction
    ) {
    }

    public record ParticipantSlaView(
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

    public enum ParticipantTimingStatus {
        ACTIVE,
        UPCOMING,
        DUE,
        OVERDUE,
        PAUSED
    }

    public record PauseCommand(
        String tenantId,
        UUID slaInstanceId,
        long expectedVersion,
        String reason
    ) {
    }

    public record ResumeCommand(String tenantId, UUID slaInstanceId, long expectedVersion) {
    }

    public static final class ApprovalSlaException extends RuntimeException {
        private final String code;
        private final boolean retryable;

        public ApprovalSlaException(String code, String message, boolean retryable) {
            super(message);
            this.code = requireText(code, "code");
            this.retryable = retryable;
        }

        public ApprovalSlaException(
            String code,
            String message,
            boolean retryable,
            Throwable cause
        ) {
            super(message, cause);
            this.code = requireText(code, "code");
            this.retryable = retryable;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private static Map<DayOfWeek, List<WorkingInterval>> copySchedule(
        Map<DayOfWeek, List<WorkingInterval>> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<DayOfWeek, List<WorkingInterval>> copy = new LinkedHashMap<>();
        source.forEach((day, intervals) -> copy.put(day, List.copyOf(intervals)));
        return Map.copyOf(copy);
    }
}
